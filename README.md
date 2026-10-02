# z-indexer

> 代码索引中心 —— 仓库注册表 + 定时轮询重建 + 符号/调用链查询 + 构建监控

一人公司基座里"让 agent 读得懂整个代码组织"的那一层：把一批 Git 仓库登记进来，服务自己 clone、
检测远端变更、重建索引（Java 走 javac 编译期注解处理器，编译失败免编译静态解析兜底；前端仓走内置
Node + Babel），产物落成 JSONL 倒排索引常驻内存，对外只给四类 HTTP 能力——注册管理、符号/调用链查询、
构建监控、产物上传。零数据库：注册表与构建状态都是 NAS 上的文件，多实例靠锁文件协调。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-indexer`（`git@github.com:z-opc-foundation/z-indexer.git`，分支 `main`） |
| **Maven 坐标** | `io.github.yuku123:z-indexer:${revision}`（根 POM `<packaging>pom</packaging>`） |
| **当前版本** | `<revision>` = `1.0.0`（CI-friendly versions + flatten-maven-plugin，`flattenMode=oss`） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘；其自身 parent 为 `z-boot-dependencies:1.0.20`，实测中央发布 pom） |
| **Maven Central** | **已发布 1.0.0**（2026-09-30 现测 repo1 均 200）：`z-indexer:1.0.0`（pom）、`z-indexer-core:1.0.0`（pom+jar）、`z-indexer-spring-boot-starter:1.0.0`（pom+jar）；`1.0.1` 全部 404；`z-indexer-server` 目录整体 404（设计上永不发布，见下） |
| **模块数** | 3：`z-indexer-core` / `z-indexer-spring-boot-starter` / `z-indexer-server` |
| **默认端口** | 本仓 `application.yaml` **未设** `server.port`（实测无该键），端口由部署侧下发；仓内唯一显式端口配置是测试 main 里的 `server.port=0`（随机口） |
| **context-path** | 由 `bootstrap.yaml` 的 `z-opc.application.name=z-indexer-indexer` 决定（该文件注释：不设则 context-path 为 `/unknown`，网关路由 404） |
| **运行口径** | Java 8（父链 `java.version=1.8`、`maven.compiler.source/target=8`）· Spring Boot 2.7.18（父链 `spring-boot.version`） |
| **许可证声明** | 根 POM `<licenses>` 声明 MIT License；**仓库根无 `LICENSE` 文件**（实测），故本 README 不设 License 章节 |
| **最近更新** | 2026-09-30（仓内最后一次提交 2026-09-29 `build(pom): 消费入口抬到 z-boot-parent:1.0.21`） |

---

## 🎯 能力清单（逐条对应到代码）

| 能力 | 实现位置 | 说明 |
|------|----------|------|
| 仓库注册表（零 DB） | `z-indexer-core` `registry/RegistryStore`、`registry/IndexRepo` | `indexer/registry.json` 原子替换写 + 每仓 `indexer/status/<repo>.json` + `build-log.jsonl` 全量审计；`refMode` 支持 `BRANCH` / `TAG_PATTERN` / `TAG_LATEST`；`language` 支持 `java` / `frontend`（留空自动探测 pom.xml / package.json） |
| 远端变更检测与同步 | `pipeline/GitOps` | 纯 JGit 实现（`ls-remote` 零成本变更检测、clone、fetch+checkout+reset+clean），生产纯 JRE 容器无 `git` 命令也能跑；私有仓凭证经 gitUrl 的 userinfo 段注入（由注册表字段传入，本 README 不示例） |
| 双轨索引构建 | `pipeline/IndexBuildService`（1018 行主编排）、`pipeline/PomIndexerPatcher`、`pipeline/StaticJavaIndexer`（2435 行） | 轮 1 免编译静态全量主索引（JavaParser，`indexType=STATIC`，秒级）；轮 2 编译附带对照产物 `<repo>.compiled.jsonl`（pom 注入 `com.zifang:code-indexer:1.0` 注解处理器 + mvn），轮 2 失败不影响主索引 SUCCESS；`static-fallback-enabled=false` 回退纯编译模式 |
| 内置工具链自释放 | `IndexBuildService.ensureCodeIndexerJar/ensureMavenDist/ensureToolsJar/ensureFrontendToolchain` | 启动时把 `z-indexer-server/src/main/resources/bin/` 里的 maven 发行版、`tools.jar`、node 发行版、babel-parser、`frontend-indexer.cjs` 幂等释放到 `repos-root/bin/` |
| 前端仓索引 | 同上 + `resources/bin/frontend-indexer.cjs` | Node 脚本扫描 TS/JS，输出与 Java 链路同 schema 的 def/call 行 |
| 定时轮询重建 | `schedule/IndexSyncRunner` + starter `config/IndexerAutoConfiguration#scheduledPoll` | `fixedDelay` 语义（上一轮跑完再计时）；`BUILDING` 跳过、`FAILED` 每轮重试、`NEVER` 首建 |
| 分布式多实例互斥 | `registry/BuildLockManager` | NAS `locks/<repoCode>.lock` 原子创建抢锁；stale 阈值 = 构建超时×2 + 10 分钟，实例宕机留死锁可被抢占；多实例天然把待建集合拆分并行 |
| 符号 / 调用链查询 | `query/IndexQueryService`、`query/RepoIndex` | 内存倒排（符号表 def + 入边 callee→callers + 出边 caller→callees）；LRU 懒加载、meta/mtime 变化后台热重建（重建期间旧索引继续服务）；冷仓首查立即返回 loading 提示并异步加载；加载期字符串复用池（实测 dtc 440MB→~60MB） |
| 巨仓构建让路 | `IndexQueryService#shrinkTo` + `IndexerConfig.bigRepoCodes/buildYieldRepos` | 巨仓构建前把冷仓驱逐到低水位，避免预热常驻 + mvn 堆叠加顶破容器内存 |
| 启动预热 | `IndexerAutoConfiguration#queryWarmupRunner` | SUCCESS 仓按索引行数降序预加载前 `warmup-repos` 个，错峰 60s 避开首轮 poller IO |
| 索引产物上传（本地生产模式） | `controller/IndexAdminController#uploadIndex/uploadPart/uploadFinish` | 容器内跑不动的仓（如前端）在本地生成 JSONL 后上传：`commit` 与远端 HEAD `ls-remote` 校验、`sha256` 端到端校验、按 `Content-Length` 硬截断、tmp 名带 UUID 隔离网关 retry 并发流；上限 256MB；支持分片 |
| master 制品打包 | `pipeline/ArtifactService` + `controller/IndexArtifactController` | 每次 SUCCESS 后异步把工作区打成 `artifacts/<repoCode>.zip`（纯内容，无目录前缀），首个条目是 `_artifact_meta.json`（commit / files / bytes）；剔除 `.git,target,node_modules,build,dist,.idea,.vscode`，跳过符号链接；单线程 daemon 队列串行打包，先写 `.tmp-*` 再 `fd.sync()` + `ATOMIC_MOVE`（NFS 上 rename 后 0 字节的坑）。源码在容器里被删的仓，下游可直接拉制品而不必挂整卷 |
| 监控与健康 | `controller/IndexMonitorController` | 状态分布 / 索引新鲜度 / 常驻仓库 / 最近轮询耗时 / 构建历史 / 巡检健康 |
| Redis 连通探针 | `controller/RedisProbeController` | `SET→GET→DEL` 带 TTL 测试键，验证 `z-boot-cache-starter` 链路 |
| 引入即生效（starter） | `z-indexer-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 2026-09-29 补齐：此前 starter 无任何注册文件，装配全靠 server 的 `@SpringBootApplication` 扫包，"引入即用"对仓外消费者不成立 |

---

## 🏗️ 项目结构

```
z-indexer/
├── pom.xml                              # 根聚合 POM：z-boot-parent:1.0.21 + <revision>1.0.0 + central profile
├── _doc/                                # 文档收口目录（实测：当前为空目录，0 个文件）
├── _frontend/
│   └── README.md                        # 前端索引器说明（交付副本在 resources/bin/，本目录不被构建读取）
├── z-indexer-core/                      # 领域核心，不依赖 Spring（纯 POJO IndexerConfig）
│   └── src/main/java/com/zifang/z/indexer/
│       ├── IndexerConfig.java           # 核心配置 POJO
│       ├── registry/                    # RegistryStore / IndexRepo / BuildLogEntry / BuildLockManager
│       ├── pipeline/                    # GitOps / IndexBuildService / PomIndexerPatcher / StaticJavaIndexer / ArtifactService
│       ├── query/                       # IndexQueryService / RepoIndex
│       └── schedule/                    # IndexSyncRunner
├── z-indexer-spring-boot-starter/       # 可被引用的库：自动装配 + 5 个 REST Controller（发 Central）
│   └── src/main/java/com/zifang/z/indexer/
│       ├── config/                      # IndexerAutoConfiguration / IndexerProperties
│       └── controller/                  # IndexAdmin / IndexQuery / IndexMonitor / IndexArtifact / RedisProbe
└── z-indexer-server/                    # 可执行进程（fat jar，永不发 Central）
    └── src/main/
        ├── java/com/zifang/z/indexer/IndexerApplication.java   # @SpringBootApplication + @EnableScheduling
        └── resources/{application.yaml, bootstrap.yaml, bin/}  # bin/ 实测 59MB 内置工具链
```

发布边界（实测）：`z-indexer-server/pom.xml` 设 `maven.deploy.skip=true`，根 POM `central` profile 的
`central-publishing-maven-plugin` 另配 `<excludeArtifacts>z-indexer-server</excludeArtifacts>` ——
注释记录了原因：central-publishing 0.7.0 **不看** `maven.deploy.skip`（z-msg 1.1.0 实测照样把 example 打进
bundle），`excludeArtifacts` 才是真正生效的那道。`z-indexer-core` 与 starter 无 skip，随 `central` profile 发布。

---

## 🔧 技术栈（全部取自 pom 实测）

| 层级 | 技术 / 版本 |
|------|-------------|
| 语言 / 运行时 | Java 8（父链下发 `maven.compiler.source/target=8`；JDK 9+ 上本仓 `java8-floor-on-jdk9plus` profile 强制 `release=8`） |
| 框架 | Spring Boot 2.7.18（版本由 `z-boot-parent:1.0.21` 属性 `spring-boot.version` 供） |
| Git 客户端 | `org.eclipse.jgit:org.eclipse.jgit` **5.13.3.202401111512-r**（刻意不回地板的 5.13.4：JGit 6.x 基线 Java 11，5.13.4 那份 jar Build-Jdk-Spec=17，JDK 8 运行期 `NoSuchMethodError`） |
| 静态解析 | `com.github.javaparser:javaparser-core` **3.26.4**（高于地板 3.23.1，回地板等于换实现） |
| 日志 | `slf4j-api` **2.0.13**（不回 1.7.36：运行期绑定是 `log4j-slf4j2-impl`，属 SLF4J 2.x provider） |
| JSON | `io.github.yuku123:z-util-parser-json`（已从 fastjson 迁移，提交 `e05395f`） |
| 兄弟件 | `z-boot-config-starter` / `z-boot-gw-starter` / `z-boot-cache-starter`（聚合 starter，一行 import） |
| 缓存客户端 | `spring-boot-starter-data-redis`（lettuce 连接池 + Redisson 默认） |
| 测试 | TestNG **7.5.1**（7.6 起基线 Java 11；父链不供该格） |
| 构建 | Maven · `flatten-maven-plugin`（`oss` 模式，剥 parent 链写面值）· `maven-deploy-plugin` 3.1.4（父链不管这件） |
| z-util 版本 | 由父链的 `z-boot-fleet` 表供（实测 `z-boot-parent:1.0.21` 引 `z-boot-fleet:1.0.1`，表内 `z-util.version=1.0.14`、`z-indexer.version=1.0.0`） |

本仓 `pom.xml` 只保留 **5 格刻意与父链不同值/父链不供** 的属性：`slf4j.version`、`jgit.version`、
`javaparser.version`、`testng.version`、`maven-deploy-plugin.version`；其余第三方一律不在这钉版本。

---

## ⚙️ 配置

配置前缀 `z.indexer.*`（`IndexerProperties`，`@EnableConfigurationProperties` 装配，Nacos/配置中心可覆盖）。
下表"仓内值"＝ `z-indexer-server/src/main/resources/application.yaml` 实测覆盖值，"代码默认"＝ `IndexerProperties` 字段默认值。

| 配置键 | 代码默认 | 仓内值 | 用途 |
|--------|----------|--------|------|
| `z.indexer.enabled` | `true` | `true` | 总开关：只掐 `@Scheduled` 轮询，REST 端点仍可查已有索引 |
| `z.indexer.repos-root` | `${java.io.tmpdir}/z-indexer` | `/var/lib/z-indexer` | 根目录，下设 `repos/`（工作副本）、`indexer/`（产物+注册表+锁）、`bin/`（释放的工具链）、`artifacts/`（纯内容制品）。**值不带 `/repos` 后缀** —— `reposDir()` 自己拼 `repos/`，写成 `.../repos` 会落出 `repos/repos/` |
| `z.indexer.poll-interval-ms` | `600000` | `600000` | 轮询间隔 |
| `z.indexer.poll-initial-delay-ms` | `60000` | `60000` | 启动后首轮延迟 |
| `z.indexer.build-timeout-minutes` | `30` | `45` | 单仓构建超时 |
| `z.indexer.static-fallback-enabled` | `true` | —（未覆盖） | JavaParser 兜底开关 |
| `z.indexer.maven-opts` | `-Xmx1g -XX:MaxMetaspaceSize=256m` | `-Xmx1024m -XX:MaxMetaspaceSize=256m` | mvn 子进程 JVM 参数 |
| `z.indexer.build-concurrency` | `2` | `1` | 单实例并发构建数 |
| `z.indexer.mvn-cmd` / `git-cmd` / `node-cmd` | `""` | `node-cmd: ""` | 留空＝自足模式（内置发行版 / JGit）；本机 E2E 可指本地命令 |
| `z.indexer.lru-max-repos` | `6` | `18` | 查询端常驻仓库上限 |
| `z.indexer.warmup-repos` | `6` | `12` | 启动预热仓数（0＝关） |
| `z.indexer.big-repo-codes` | `""` | `demo-big-repo-1,demo-big-repo-2` | 巨仓名单（构建前让路） |
| `z.indexer.build-yield-repos` | `4` | `4` | 让路后 LRU 低水位 |
| `z.indexer.default-depth` | `2` | `2` | 调用链默认展开深度 |
| `z.indexer.max-result-nodes` | `200` | `200` | 单次返回节点上限 |

**轮询周期口径（2026-10-02 修）**：`IndexerAutoConfiguration#scheduledPoll` 的占位符此前写成
`${z-indexer.indexer.poll-*}`，而 `IndexerProperties` 绑的是 `z.indexer.poll-*` —— 两个前缀不是同一格，
`application.yaml` 里配的 `poll-interval-ms` 永远到不了调度器，真实周期被钉死在占位符默认值
（60s 延迟 / 600s 间隔）上，改配置不生效且毫无征兆。现占位符已统一为 `z.indexer.poll-*`，
即改 yaml/配置中心里的 `z.indexer.poll-interval-ms` 就真的改到轮询周期。
（仓内该值恰与默认同数，所以修前修后首轮节奏一样 —— 差别在"能不能被配走"。）

环境变量（`application.yaml` 实测只有这 4 个 `${}` 引用，全在 `spring.redis.*` 下；**只写变量名，值必须经环境注入**）：

| 环境变量 | 对应键 | 默认 |
|----------|--------|------|
| `REDIS_HOST` | `spring.redis.host` | `localhost` |
| `REDIS_PORT` | `spring.redis.port` | `6379` |
| `REDIS_DB` | `spring.redis.database` | `0` |
| `REDIS_PASSWORD` | `spring.redis.password` | 空 |

Actuator 暴露：`management.endpoints.web.exposure.include=health,info,metrics,env`，`show-details=always`；
日志级别 `logging.level.com.zifang.z.indexer=INFO`。`bootstrap.yaml` 另有 `z-opc.application.name=z-indexer-indexer`
与 `z-opc.extension.datasource.route.enable=false`。本仓**没有** `application-<profile>.yml` 变体（实测 find 只有
`application.yaml` + `bootstrap.yaml`）。

---

## 🚀 快速开始

### 编译

```bash
mvn clean install -DskipTests
```

若报解析不到 parent，先确认能拉到 `io.github.yuku123:z-boot-parent:1.0.21`（在 repo1，不在磁盘）。

### 起进程（fat jar）

```bash
java -jar z-indexer-server/target/z-indexer-server-1.0.0.jar
```

主类 `com.zifang.z.indexer.IndexerApplication`（`z-indexer-server/pom.xml` 的 `start-class`），
`spring-boot-maven-plugin` 在 `package` 阶段 repackage；少了 repackage 产出的普通 jar 没有 `Main-Class`。

起得来的前置条件（实测注释与代码）：

- **Redis 可达**：`spring.redis.*` 直配的是 Redisson 原生前缀，默认值指向 `localhost:6379`，
  连不上会直接阻止启动（`application.yaml` 注释记录）。本地要么起一个 Redis，要么用 `REDIS_HOST`/`REDIS_PORT` 指过去。
- **可写数据目录**：`z.indexer.repos-root` 默认落 `java.io.tmpdir/z-indexer`；生产显式给
  `/var/lib/z-indexer`（跨部署持久的卷；值不带 `/repos` 后缀，见配置表那格）。
- **端口**：仓内未钉 `server.port`，按部署侧下发值访问；`z-opc.application.name` 决定 context-path。

### 本机想跑真实构建链路

```bash
# 指本机命令，绕开内置 linux-x64 发行版
java -jar z-indexer-server/target/z-indexer-server-1.0.0.jar \
  --z.indexer.mvn-cmd=mvn --z.indexer.git-cmd=git --z.indexer.node-cmd=node \
  --z.indexer.repos-root=/tmp/z-indexer-root
```

---

## 🔌 API

四个 Controller 全在 `z-indexer-spring-boot-starter/.../controller/`，统一返回 `{"ok": true/false, ...}`；
下表为**类级前缀 + 方法级路径**的实测拼接，完整 URL 还要带上 context-path。

| 方法与路径 | 说明 |
|------------|------|
| `GET /index/admin/repos` | 仓库列表（含构建状态） |
| `POST /index/admin/repos` | 注册/更新：`repoCode, repoName, gitUrl, refMode, refValue, enabled, remark` |
| `DELETE /index/admin/repos/{repoCode}` | 注销 |
| `PUT /index/admin/repos/{repoCode}/enabled` | 启停（body `enabled`） |
| `POST /index/admin/repos/{repoCode}/rebuild` | 手动重建（MANUAL，无变更也强建）；`?skipCompile=true` 走预热快路：只跑轮 1 静态索引 + 打制品，跳过轮 2 mvn 编译对照（单仓从分钟级压到秒级） |
| `GET /index/admin/repos/{repoCode}/build-log?tailLines=300` | 构建日志尾部，`tailLines` 限 `[1,2000]` |
| `POST /index/admin/poll` | 立即触发一轮全量轮询（后台线程） |
| `POST /index/admin/repos/{repoCode}/upload?commit=&sha256=` | 单次上传 JSONL 产物（需 `Content-Length`，≤256MB，`ls-remote` 校 commit） |
| `POST /index/admin/repos/{repoCode}/upload/part?uploadId=&offset=&sha256=` | 分片上传（乱序安全，逐片校验） |
| `POST /index/admin/repos/{repoCode}/upload/finish?uploadId=&commit=&sha256=` | 分片收尾：整体 sha + commit 校验后原子替换 |
| `GET /index/repo/artifact?repoCode=` | 下载 master 纯内容制品 zip（`artifacts/<repoCode>.zip`，响应头 `X-Artifact-Commit` 带打包时的 commit）；404＝制品未生成（仓从未 SUCCESS / 仍在打包），消费方回退整卷 cp。`repoCode` 走 `[A-Za-z0-9._-]{1,120}` 白名单，不合规直接 400 |
| `GET /index/query/repos` | 可查询仓库 + 索引版本 |
| `POST /index/query/symbol` | 符号搜索：`{repoCode, query, limit?, includeAccessors?}` |
| `POST /index/query/callers` | 谁调用了它：`{repoCode, symbol, depth?, includeAccessors?}` |
| `POST /index/query/callees` | 它调用了谁：入参同上 |
| `GET /index/monitor/overview` | 状态分布 / 索引行数合计 / 常驻仓库 / 最近轮询四项指标 |
| `GET /index/monitor/builds?repoCode=&limit=50` | 构建历史，`limit` 收敛到 `[1,500]` |
| `GET /index/monitor/health` | 巡检健康：有 enabled 仓 `FAILED` 或轮询超 3 个间隔未跑即不健康 |
| `GET /index/redis/probe` | Redis SET→GET→DEL 往返探针（测试键 TTL 60s） |

冷仓首查会返回 loading 提示而非阻塞等待（`IndexQueryService` 异步预热，稍后重试即热）。

---

## 🧪 测试

```bash
mvn test                                     # TestNG，surefire 版本走父链 2.22.2
mvn test -Dtest=IndexerE2eTest -Dz.indexer.e2e=true   # 端到端（默认跳过）
```

实测的测试资产（2026-10-02 本机 `mvn -B -o clean test`：**core 8 个 / starter 3 个，0 failures 0 errors，3 skipped**）：

- `z-indexer-core`（6 类 8 法）：
  - `IndexerE2eTest`（3 个 `@Test`：`fullPipeline` / `dualTrackFallback` / `frontendPipeline`，真实 git +
    真实 mvn；默认 `SkipException` 跳过 → 上面那 3 个 skipped 就是它们，需 `-Dz.indexer.e2e=true` 且本机 mvn/git 可用）
  - `RepoIndexTest#loadDedupsRepeatedStringsAndKeepsSemantics`（`assertSame` 锁死字符串复用，防无声回退）
  - `RegistryStoreStatusStampTest#stampShortCircuitAndExternalChange`（status 文件 mtime+size 短路戳语义，含多实例外部改写可见性）
  - `RegistryStoreJsonRoundTripTest#persistWritesParseableJsonAndReloadsExact`（registry.json 落盘必须仍可解析：
    直塞 POJO 会写出 `IndexRepo@15db9742` 碎片，注册表读回来就是空，属"写成功但数据坏了"的静默故障）
  - `ArtifactServiceTest#packsPureContentAndSkipsSymlinks`（制品 zip 纯内容 + 剔除名单 + 首条目是 meta、
    `meta.files/bytes` 与实际条目逐字节相符、`.tmp-*` 不残留）
  - `StaticJavaIndexerTest#symContractAndJoin`（轮 1 静态索引的 def/call 产出与符号拼接口径）
- `z-indexer-spring-boot-starter`：`IndexerAutoConfigurationSmokeTest`（3 个 `@Test`，用
  `AnnotationConfigApplicationContext` 在 `refresh()` 阶段暴露装配环，不拉 Web/配置中心；
  `restSurfaceWired` 额外守 REST 面与 `getArtifactService()`，因为制品 bean 缺失时是 500 而不是 404）。
- 轮 2 编译产物与轮 1 的逐行对照（replay）没有自动化测试：它必须拿一个真实业务仓跑 `mvn`，
  在开源/无外网环境下必然红，故只由 `IndexerE2eTest#dualTrackFallback` 在显式开关下覆盖"轮 2 失败不影响主索引 SUCCESS"这条不变式。
- `RedisStarterSmokeMain` 是**手工排障 main**（非 `@Test`，不进 CI），参数全部走
  `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DB` 环境变量（缺省 localhost），源码里不含任何真实凭据；
  生产链路请一律用上面的环境变量注入方式，不要把凭据写进源码或 yml。
- `z-indexer-server` 无测试代码。

---

## 📦 打包与交付物

实测（2026-09-29 本地产物）：`z-indexer-core-1.0.0.jar` ≈ 117 KB、
`z-indexer-spring-boot-starter.jar`（`finalName` 钉死，无版本号后缀）≈ 36 KB、
`z-indexer-server-1.0.0.jar`（fat jar）≈ 114 MB，其中 `src/main/resources/bin/` 实测 59 MB：
`node-bin.zip` 35,089,148B + `tools.jar` 18,362,894B + `apache-maven-3.8.8-bin.zip` 8,376,105B +
`babel-parser.zip` 435,363B + `code-indexer.jar` 8,087B + `frontend-indexer.cjs` 16,131B + `settings.xml`。

进程模块的 `application.yaml` 注释记录了扩容/内存口径：容器 limit 4g、mvn 堆 1024m、`build-concurrency: 1`，
多副本靠 NAS 锁拆仓。**本仓不含任何容器/编排资产**（实测 `find` 对 `Dockerfile*`、`docker-compose*`、
`Makefile`、`deploy/`、`k8s/`、`*.sh` 均 0 命中，也没有 `.github/` CI 配置）——交付方式就是上面这枚 fat jar，
镜像与编排定义在仓外。

---

## 文档目录

`_doc/` 目录**存在但为空**：实测 `find _doc -mindepth 1` 返回 0 条、`git ls-files _doc` 亦为空，
即 002_项目文档收口规范要求的 `_doc/{001_arch,002_deploy,003_script,004_skill}` 四类标准文档槽
**目前全部缺失**（架构总览、模块结构、接口清单、DB schema、部署 SQL、运维脚本、AI skill 均尚无文件），
不存在可链接的真实文件，故本节不列链接以免留下断链。

仓内当前唯一的文档文件是前端侧的说明：

- [`_frontend/README.md`](_frontend/README.md) — 前端工程索引器 `frontend-indexer.cjs` 的设计说明，
  并记录本目录只是编辑用暂存位（不被构建读取）：运行时实际用的副本打包在
  `z-indexer-server/src/main/resources/bin/frontend-indexer.cjs`（测试用副本在 `z-indexer-core/src/test/resources/bin/`），
  改动须同步这两份，否则线上仍跑旧版。

补文档时按规范落 `_doc/001_arch/` 等子目录，并回到本节加链接。
