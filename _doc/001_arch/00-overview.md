# 项目总览

## 1. 项目简介

z-indexer 是组织内的**代码索引中心**：把一批 Git 仓库登记进来，服务自己 clone、检测远端变更、重建索引（Java 走 javac 编译期注解处理器，编译失败时免编译静态解析兜底；前端仓走内置 Node + Babel），产物落成 JSONL 倒排索引常驻内存，对外给四类能力 —— 仓库注册管理、符号/调用链查询、构建监控、产物上传。

**零数据库**：仓库注册表与构建状态都是 NAS 上的文件，多实例靠锁文件协调。

它由三段组成：**领域核心（`z-indexer-core`，不依赖 Spring）+ 可引用的 starter（`z-indexer-spring-boot-starter`，自动装配 + REST 面）+ 可执行进程（`z-indexer-server`，fat jar）**。

**技术栈：** Java 8 · Spring Boot 2.7.18 · JGit 5.13.3 · JavaParser 3.26.4 · SLF4J 2.0.13 · Redis（`z-boot-cache-starter`）· TestNG

## 2. 模块架构

```
z-indexer/
├── pom.xml                              # 聚合根：parent z-boot-parent:1.1.0，<revision>1.0.0</revision>，3 个 <module>，central profile
├── _frontend/
│   ├── README.md                        # 前端索引器（frontend-indexer.cjs）设计说明 + 编辑用暂存位口径
│   ├── z-indexer-component/             # 前端组件包
│   └── z-indexer-suit/                  # 前端管理台壳
├── z-indexer-core/                      # 领域核心，不依赖 Spring（IndexerConfig 是纯 POJO）
│   └── src/main/java/com/zifang/z/indexer/
│       ├── IndexerConfig.java           # 核心配置 POJO
│       ├── registry/                    # RegistryStore / IndexRepo / BuildLogEntry / BuildLockManager / GitUrlMasker
│       ├── pipeline/                    # GitOps / IndexBuildService / PomIndexerPatcher / StaticJavaIndexer / ArtifactService
│       ├── query/                       # IndexQueryService / RepoIndex
│       └── schedule/                    # IndexSyncRunner
├── z-indexer-spring-boot-starter/       # 可被引用的库：自动装配 + 5 个 REST Controller
│   ├── src/main/java/com/zifang/z/indexer/
│   │   ├── config/                      # IndexerAutoConfiguration / IndexerProperties
│   │   └── controller/                  # IndexAdmin / IndexQuery / IndexMonitor / IndexArtifact / RedisProbe
│   └── src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── z-indexer-server/                    # 可执行进程（fat jar，永不发中央）
    └── src/main/
        ├── java/com/zifang/z/indexer/IndexerApplication.java   # @SpringBootApplication + @EnableScheduling
        └── resources/
            ├── application.yaml         # z.indexer.* 配置
            ├── bootstrap.yaml           # z-opc.application.name（决定 context-path）
            └── bin/                     # 内置工具链（maven 发行版 / node 发行版 / tools.jar / babel-parser / frontend-indexer.cjs / settings.xml）
```

## 3. 核心功能

### 3.1 仓库注册表（零 DB）

`registry/RegistryStore`（400 行）把注册表写在 `indexer/registry.json`（原子替换写），每仓一份状态 `indexer/status/<repo>.json`，构建日志走 `build-log.jsonl` 全量审计。`registry/IndexRepo` 描述一个仓：`refMode` 支持 `BRANCH` / `TAG_PATTERN` / `TAG_LATEST`，`language` 支持 `java` / `frontend`（留空时按 `pom.xml` / `package.json` 自动探测）。

### 3.2 远端变更检测与同步

`pipeline/GitOps`（390 行）是**纯 JGit** 实现：`ls-remote` 做零成本变更检测，再 clone / fetch + checkout + reset + clean —— 生产纯 JRE 容器里没有 `git` 命令也能跑。私有仓凭证内嵌在 gitUrl 的 userinfo 段里。

### 3.3 双轨索引构建

`pipeline/IndexBuildService`（1146 行）主编排：

- **轮 1 · 免编译静态全量主索引**：`pipeline/StaticJavaIndexer`（2435 行）用 JavaParser 扫全仓，产出 `indexType=STATIC` 的符号与调用边，秒级。
- **轮 2 · 编译附带对照产物**：`pipeline/PomIndexerPatcher`（221 行）往目标仓的 pom 里注入 `annotationProcessorPaths` 与 `-A` 参数，再跑 `mvn`，产出 `<repo>.compiled.jsonl`。**轮 2 失败不影响主索引 SUCCESS**；`static-fallback-enabled=false` 可退回纯编译模式。

`PomIndexerPatcher` 有一条硬约束：目标仓 pom 里的 `<proc>none</proc>`（`build/plugins` 与 `pluginManagement` 两处）必须被剥掉 —— javac 的 `-proc:none` 优先于显式 processorpath，不剥则处理器整体静默不跑、构建零告警零产物。

### 3.4 内置工具链自释放

`IndexBuildService.ensureCodeIndexerJar` / `ensureMavenDist` / `ensureToolsJar` / `ensureFrontendToolchain` 在启动时把 `z-indexer-server/src/main/resources/bin/` 里的 maven 发行版、`tools.jar`、node 发行版、babel-parser、`frontend-indexer.cjs` 幂等释放到 `repos-root/bin/`。

`bin/frontend-indexer.cjs` 是 Node 脚本，扫描前端仓的 TS/JS 源码，输出与 Java 链路同 schema 的 def/call 行，因此查询端无需区分语言。

### 3.5 定时轮询与分布式互斥

- `schedule/IndexSyncRunner`（218 行）+ starter 的 `IndexerAutoConfiguration#scheduledPoll`：`fixedDelay` 语义（上一轮跑完再计时）；`BUILDING` 跳过、`FAILED` 每轮重试、`NEVER` 首建。
- `registry/BuildLockManager`（178 行）：NAS 上 `locks/<repoCode>.lock` 原子创建抢锁，stale 阈值 = 构建超时 × 2 + 10 分钟，实例宕机留下的死锁可被抢占；多实例天然把待建集合拆开并行。

### 3.6 符号与调用链查询

`query/IndexQueryService`（430 行）+ `query/RepoIndex`（340 行）：内存倒排（符号表 def + 入边 callee→callers + 出边 caller→callees）。LRU 懒加载，meta/mtime 变化后台热重建，重建期间旧索引继续服务；冷仓首查立即返回 loading 提示并异步加载。

加载期做**字符串复用池**（同值 FQN/路径共享实例）—— 这是内存问题的根治手段。

`IndexerConfig.bigRepoCodes` / `buildYieldRepos` 控制巨仓构建让路：巨仓构建前把冷仓驱逐到低水位，避免预热常驻 + mvn 堆叠加顶破容器内存。`IndexerAutoConfiguration#queryWarmupRunner` 做启动预热：SUCCESS 仓按索引行数降序预加载前 `warmup-repos` 个，错峰 60s 避开首轮 poller IO。

### 3.7 索引产物上传与制品打包

- **上传**（`IndexAdminController#uploadIndex` / `uploadPart` / `uploadFinish`）：容器内跑不动的仓（如前端）可在本地生成 JSONL 后上传 —— `commit` 与远端 HEAD `ls-remote` 校验、`sha256` 端到端校验、按 `Content-Length` 硬截断、tmp 名带 UUID 隔离网关 retry 并发流；上限 256MB，支持分片。
- **打包**（`pipeline/ArtifactService` + `IndexArtifactController`）：每次 SUCCESS 后异步把工作区打成 `artifacts/<repoCode>.zip`（纯内容，无目录前缀），首个条目是 `_artifact_meta.json`（commit / files / bytes）；剔除 `.git,target,node_modules,build,dist,.idea,.vscode`，跳过符号链接；单线程 daemon 队列串行打包，先写 `.tmp-*` 再 `fd.sync()` + `ATOMIC_MOVE`。

### 3.8 git 地址脱敏

`registry/GitUrlMasker`（57 行）只掩密码段：用户名 / 主机 / 端口 / 路径逐字保留（它们是日志的诊断价值所在）。判定不了凭证时原样返回 —— scp 形式 `git@host:org/repo.git`、`ssh://git@host/...`、匿名可读仓库都不动。脱敏只该动凭证，不该顺手规范化 URL。

### 3.9 监控与健康

`IndexMonitorController` 给状态分布、索引新鲜度、常驻仓库、最近轮询耗时、构建历史、巡检健康；`RedisProbeController` 用 `SET → GET → DEL` 带 TTL 的测试键探活 `z-boot-cache-starter` 链路。

## 4. 对外集成 / 上下游

**本仓不钉 `server.port`。** `application.yaml` 里没有 `server.port` 键，端口由部署侧下发；`bootstrap.yaml` 的 `z-opc.application.name=z-indexer-indexer` 决定 context-path（该文件注释：不设则 context-path 为 `/unknown`，网关路由 404）。

**对外 REST 面**（5 个 Controller，类级前缀如下；完整 URL 还要带 context-path）：

| Controller | 类级前缀 | 面 |
|---|---|---|
| `IndexAdminController` | `/index/admin` | 仓库列表 / 注册 / 注销 / 启停 / 手动重建 / 构建日志 / 立即轮询 / 产物上传（含分片） |
| `IndexQueryController` | `/index/query` | 可查询仓库 / 符号搜索 / 谁调用了它 / 它调用了谁 |
| `IndexMonitorController` | `/index/monitor` | 概览 / 构建历史 / 巡检健康 |
| `IndexArtifactController` | `/index` | `GET /index/repo/artifact` 下载制品 zip |
| `RedisProbeController` | `/index/redis` | Redis 往返探针 |

返回体统一是 `{"ok": true/false, ...}`。

**上游依赖（取自各模块 `pom.xml`）：**

| 依赖 | 出现在 | 作用 |
|---|---|---|
| `org.eclipse.jgit:org.eclipse.jgit` 5.13.3 | `z-indexer-core` | 纯 Java 的 Git 客户端 |
| `com.github.javaparser:javaparser-core` 3.26.4 | `z-indexer-core` | 免编译静态解析 |
| `io.github.yuku123:z-util-parser-json` | core + starter | JSON 处理 |
| `org.slf4j:slf4j-api` 2.0.13 | `z-indexer-core` | 日志门面 |
| `z-boot-config-starter` / `z-boot-gw-starter` / `z-boot-cache-starter` | starter | 配置中心 / 网关 / Redis |
| `spring-boot-starter-web` / `-actuator` / `-data-redis` | starter | REST / 健康端点 / Redis |
| `spring-boot-maven-plugin` | `z-indexer-server` | 打成 fat jar |

**下游消费方：** 引入 `z-indexer-spring-boot-starter` 即自动装配（`AutoConfiguration.imports` 注册 `IndexerAutoConfiguration`）；`z-indexer-server` 是可独立部署的 fat jar。

**发布边界：** `z-indexer-server` 永不发中央 —— 模块的 `maven.deploy.skip=true` 之外，根 POM `central` profile 另配了 `<excludeArtifacts>z-indexer-server</excludeArtifacts>`（central-publishing 0.7.0 不看 `maven.deploy.skip`）。`z-indexer-core` 与 starter 随 `central` profile 发布。

**配置键（`z.indexer.*`，来自 `application.yaml` 与 `IndexerProperties`）：** `enabled` / `repos-root`（生产 `/var/lib/z-indexer`）/ `poll-interval-ms`（600000）/ `poll-initial-delay-ms`（60000）/ `build-timeout-minutes`（45）/ `maven-opts`（`-Xmx1024m -XX:MaxMetaspaceSize=256m`）/ `build-concurrency`（1）/ `node-cmd`（空 = 用内置发行版）/ `lru-max-repos`（18）/ `warmup-repos`（12）/ `big-repo-codes` / `build-yield-repos`（4）/ `default-depth`（2）/ `max-result-nodes`（200）。Redis 走 `spring.redis.*`（`REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` / `REDIS_PASSWORD` 注入）。

## 5. 技术特点

1. **零数据库**：注册表、状态、构建日志、锁文件全是 NAS 上的文件；多实例靠 `locks/<repoCode>.lock` 原子创建抢锁拆仓，天然把待建集合拆开并行。
2. **JGit 纯 Java 实现**：生产容器是无 `git` 命令的纯 JRE 镜像，`ls-remote` + clone 全部走 JGit。
3. **双轨构建且互不拖累**：轮 1 免编译静态主索引（秒级、可回退），轮 2 编译对照产物（可失败）；轮 2 失败不影响主索引 SUCCESS。
4. **`<proc>none</proc>` 必须被剥**：javac 的 `-proc:none` 优先于显式 processorpath —— 注入器不剥它，注解处理器整体静默不跑且构建零告警零产物。
5. **内存靠字符串池化治本**：`RepoIndex` 加载期同值 FQN/路径共享实例，巨仓边数大幅下降；同时用 LRU + 巨仓构建让路 + 启动预热三条策略压常驻。
6. **git 地址只掩密码段**：用户名/主机/端口/路径逐字保留（诊断价值），scp 与匿名形态原样不动。
7. **工具链自释放进 jar**：`bin/` 里塞了 maven 发行版、node 发行版、`tools.jar`、babel-parser、`frontend-indexer.cjs`，启动时幂等释放到 `repos-root/bin/`，免去容器预装。
8. **前后端同一套索引 schema**：前端仓由 `frontend-indexer.cjs` 产出与 Java 链路同形的 def/call 行，查询端不区分语言。
9. **starter 真正「引入即生效」**：`AutoConfiguration.imports` 里注册 `IndexerAutoConfiguration`，不再依赖 server 的 `@SpringBootApplication` 扫包。

## 6. 目录结构

源码按 Java 包树组织，根包 `com.zifang.z.indexer`：

```
z-indexer/
├── z-indexer-core/src/main/java/com/zifang/z/indexer/
│   ├── IndexerConfig.java              # 核心配置 POJO（不依赖 Spring）
│   ├── registry/                       # RegistryStore(400) IndexRepo(279) BuildLockManager(178)
│   │                                   #   BuildLogEntry(131) GitUrlMasker(57)
│   ├── pipeline/                       # StaticJavaIndexer(2435) IndexBuildService(1146) GitOps(390)
│   │                                   #   PomIndexerPatcher(221) ArtifactService(197)
│   ├── query/                          # IndexQueryService(430) RepoIndex(340)
│   └── schedule/                       # IndexSyncRunner(218)
├── z-indexer-spring-boot-starter/src/main/
│   ├── java/com/zifang/z/indexer/
│   │   ├── config/                     # IndexerAutoConfiguration(249) IndexerProperties(218)
│   │   └── controller/                 # IndexAdminController(661) IndexQueryController(178)
│   │                                   #   IndexMonitorController(149) RedisProbeController(76)
│   │                                   #   IndexArtifactController(62)
│   └── resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── z-indexer-server/src/main/
│   ├── java/com/zifang/z/indexer/IndexerApplication.java
│   └── resources/{application.yaml, bootstrap.yaml, bin/}
└── _frontend/{README.md, z-indexer-component/, z-indexer-suit/}
```

规模实测：`z-indexer-core` 14 个 `.java` 共 6606 行，starter 7 个 `.java` 共 1593 行。
