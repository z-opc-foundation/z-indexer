# z-indexer 部署清单（deploy/k8s）

本目录是 **z-indexer** 在家庭 k3s 集群（节点 `zifang001` / `zifang002`）的部署清单。

这批 Deployment 当初是手工 `kubectl apply` 上去的，仓里查不到「它是怎么部署的」——
集群无法从代码复现，发版只能靠记忆和手记。本目录把**线上实况**（`kubectl get deploy,svc,pvc -o yaml` 的真实导出）固化回代码。

> ⚠ 本目录**只产出清单，不代跑**。`kubectl apply` 会改动生产集群，需要人工确认后执行。

## 0. 清单内容

| 文件 | 对象 | 线上对应 |
|---|---|---|
| `00-namespace.yaml` | Namespace `z-indexer` | 已存在 |
| `01-service.yaml` | Service `z-indexer-server`（NodePort 31892 → 8080） | 已存在 |
| `02-deployment.yaml` | Deployment `z-indexer-server`（镜像 `zopc/z-indexer:local`，replicas 1） | 已存在 |
| `03-pvc.yaml` | PVC `z-indexer-data`（20Gi / RWO / local-path） | 已存在 |
| `README.md` | 本文件 | — |

线上实况要点（与基线导出逐条对齐）：

- Deployment 名 `z-indexer-server`，容器名 `z-indexer`，容器端口 `8080`（端口名 `http`）
- Service `z-indexer-server`，`type: NodePort`，`port 8080 → targetPort http`，`nodePort 31892`
- 镜像 `zopc/z-indexer:local`，`imagePullPolicy: Never`
- env：`JAVA_OPTS=-Xms512m -Xmx2g -XX:MaxMetaspaceSize=512m` + `REDIS_HOST` / `REDIS_PORT`
- resources：requests `500m` / `3Gi`，limits `4` CPU / `4Gi`
- PVC `z-indexer-data`（20Gi，RWO，local-path，Filesystem）挂在 `/var/lib/z-indexer`
- 本域**有一个鉴权 Secret**（`z-indexer-auth`，见 §3.0；基线导出时还没有）

⚠ **跨域依赖**：本域连的是 `redis.z-cache.svc.cluster.local` ⇒ **ns `z-cache` 里的 redis**。
z-cache 域不在本仓（见 `z-cache/deploy/k8s/`），它没起来本域就起不来（连不上 redis）。

## 1. 怎么构建镜像

### 现状（如实说明）

**本仓没有 Dockerfile。** 全仓 grep 无 `Dockerfile` / `*.Dockerfile`（`z-indexer/deploy/` 目录是本任务新建的）。
⇒ **「把源码变成 `zopc/z-indexer:local`」这一步目前不在仓内**，
线上那份镜像是当初在节点上手工构建后喂进 containerd 的，仓里没有留下可复现的构建定义。

线上镜像口径：裸 tag `zopc/z-indexer:local`，只存在于两个节点的 containerd，不在任何 registry。

### 建议命令（可执行；Dockerfile 需先补进本仓）

```bash
# 1) 打 boot jar —— 启动类是 z-indexer-server 里的 IndexerApplication
#    （本仓 z-indexer-server 声明了 spring-boot-maven-plugin ⇒ 产出的是可执行 boot jar）
cd z-indexer
mvn -pl z-indexer-server -am clean package -DskipTests
# 产物：z-indexer-server/target/z-indexer-server-1.0.0.jar

# 2) 本仓当前没有 Dockerfile，建议新增 deploy/Dockerfile（Java 8 口径与本仓
#    maven.compiler.source/target=8 一致；参考 z-task/Dockerfile 的写法）：
#
#    FROM eclipse-temurin:8-jre
#    WORKDIR /app
#    COPY z-indexer-server/target/z-indexer-server-1.0.0.jar app.jar
#    ENV SERVER_PORT=8080
#    EXPOSE 8080
#    ENTRYPOINT ["java", "-jar", "app.jar"]

# 3) 构建（tag 必须与 02-deployment.yaml 里的 image: 逐字一致）
docker build -t zopc/z-indexer:local -f deploy/Dockerfile .
```

## 2. 怎么喂 containerd

铁律三条：

1. **裸 tag，禁 Nexus 前缀**——本集群 `imagePullPolicy: Never`，k3s 不会去 registry 拉，
   它按 `image:` 的字面引用去节点本地 content store 找。喂进去的 tag 带前缀
   （如 `nexus.internal/zopc/z-indexer:local`）而清单里写的是裸 tag（或反过来）⇒ **ErrImageNeverPull**。
2. **必须用 `cat` 管道喂 `k3s ctr images import -`**——`ctr images import` 从 **stdin** 读，
   直接把文件路径当参数会报 `unrecognized image format`。
3. **两个节点都要喂**——两个节点都是工作节点，Pod 可能落在任一节点；只喂一个会出现
   `ErrImageNeverPull` + Pod 反复重建。

```bash
# 1) 在开发机上导出镜像（build 可在开发机做，不必上节点）
docker save zopc/z-indexer:local > /tmp/z-indexer-local.tar
scp /tmp/z-indexer-local.tar zifang@192.168.31.231:/tmp/     # zifang001
scp /tmp/z-indexer-local.tar zifang@192.168.31.136:/tmp/     # zifang002

# 2) 喂 zifang001（192.168.31.231）——该节点 sudo 需要口令，会交互提示
ssh zifang@192.168.31.231 'cat /tmp/z-indexer-local.tar | sudo k3s ctr images import -'

# 3) 喂 zifang002（192.168.31.136）——该节点 sudo -n 可免密
ssh zifang@192.168.31.136 'cat /tmp/z-indexer-local.tar | sudo -n k3s ctr images import -'

# 4) 核验（两个节点都要看到，且 tag 必须与清单一致）
ssh zifang@192.168.31.231 'sudo k3s ctr images ls | grep z-indexer'
ssh zifang@192.168.31.136 'sudo -n k3s ctr images ls | grep z-indexer'
```

一步到位的写法（不落临时文件，效果等价）：

```bash
docker save zopc/z-indexer:local | ssh zifang@192.168.31.231 'cat - | sudo k3s ctr images import -'
docker save zopc/z-indexer:local | ssh zifang@192.168.31.136 'cat - | sudo -n k3s ctr images import -'
```

## 3. 怎么 apply

### 3.0 ⚠ 前置：先建鉴权 Secret（顺序是硬前提）

卡 `TASK-20261022-017` 第 2 项给本域的管理面加了入站鉴权，**fail closed**：

| Secret | 键 | 保护什么 | 缺了会怎样 |
|---|---|---|---|
| `z-indexer-auth` | `token` | `/index/**` 整个命名空间：建仓/删仓/强制重建/上传制品/触发轮询 **+ 查询面/监控面** | 进程起不来 |

**值不进仓**：从密钥库取（或本地一次性生成）后直接灌进集群。

```bash
# ⚠ 会改动生产集群（写 secret）⇒ 需要人工确认后执行；本仓只产出清单，不代跑。
# 值从环境变量/密钥库取，**不要**写进命令历史或仓库：
kubectl -n z-indexer create secret generic z-indexer-auth \
  --from-literal=token="$Z_INDEXER_TOKEN" \
  --dry-run=client -o yaml | kubectl apply -f -
```

**顺序铁律**：Secret → 镜像喂进节点 → apply Deployment。
（Secret 必须在 Deployment 之前：鉴权 fail closed，Secret 没建就 apply ⇒ Pod CrashLoopBackOff。）

⚠ **前端不用改**：`z-indexer-component` 早就按「`X-Indexer-Token`，开启后所有 endpoint 必带」
写好了注入（`localStorage['zindexer_admin_token']`），后端这次把校验补上，两边对上。
⇒ **发版后前端要在浏览器里填一次 token**（控制台管理面需要），否则它会拿到 401。

### 3.1 apply

```bash
# ⚠ 会改动生产集群，需要人工确认后再执行；本仓只产出清单，不代跑。
kubectl apply -f deploy/k8s/
```

执行顺序与前置条件（apply 前置检查）：

1. **namespace 先在**：`00-namespace.yaml` 已包含在目录里，`kubectl apply -f deploy/k8s/`
   会按文件名顺序（00 → 01 → 02 → 03）依次 apply，namespace 自动先建。
2. **PVC 的顺序无所谓，但别删**：`03-pvc.yaml` 对已有 PVC 是幂等更新（不改容量、不重绑、不清数据）。
   ⚠ **绝不要 `kubectl delete pvc z-indexer-data -n z-indexer`**——local-path 上删 PVC 会连带删 PV 里的数据，
   索引重建不回来。
3. **Secret 先在**：见 §3.0（`z-indexer-auth`）。
4. **镜像先在**：见 §2。两个节点都喂完再 apply，否则 Pod 起不来。
5. **依赖域先在**：ns `z-cache` 的 redis 要在跑（`REDIS_HOST=redis.z-cache.svc.cluster.local`）。

apply 后自检：

```bash
kubectl -n z-indexer get deploy,svc,pvc,pods
kubectl -n z-indexer describe deploy z-indexer-server | tail -30
# 鉴权是否真的生效（匿名读 ⇒ 必须 401）
kubectl -n z-indexer exec deploy/z-indexer-server -- \
  wget -q -S -O- http://127.0.0.1:8080/index/admin/repos 2>&1 | head -3
```

## 4. 怎么回滚

```bash
# 看历史版本
kubectl -n z-indexer rollout history deploy/z-indexer-server

# 回滚到上一个版本
kubectl -n z-indexer rollout undo deploy/z-indexer-server

# 回滚到指定版本
kubectl -n z-indexer rollout undo deploy/z-indexer-server --to-revision=<N>

# 看回滚是否完成
kubectl -n z-indexer rollout status deploy/z-indexer-server
```

⚠ **PVC 数据不会自动回滚**（重要）：`rollout undo` 只回滚 Deployment 的 Pod 模板
（镜像 + env + 资源配置），**不碰 PVC `z-indexer-data`**。
⇒ 索引数据在 `/var/lib/z-indexer` 里**原样保留**，旧版本镜像若数据结构/格式与新版本不兼容，
会出现「旧代码读新索引」的错乱，需要人工处理（清索引重建 or 手工迁移）。
⇒ 同理，回滚**不会**回滚数据库 schema / 数据。

⚠ **回滚的前提是节点上有旧镜像**：回滚只改 Deployment 里的 `image:` 字段指向的旧 tag，
该 tag 必须仍在这两个节点的 containerd 里（`k3s ctr images ls` 能看到）。旧镜像被清掉就回滚不动。

⚠ **Pod 只能落在 zifang001**（PVC 的 PV 已绑该节点，见 §0 注释）。回滚时若 zifang001 不可用，
Pod 会一直 Pending（不是回滚失败，是没地方放）。

## 5. 本清单与线上的差异（抄写时发现）

| 项 | 线上 | 本清单 | 原因 |
|---|---|---|---|
| 探针 | 无 | `httpGet /actuator/health`（readiness + liveness） | 本域 1 副本在跑且该端点实测 200 ⇒ 用实测可用的端点；线上没有探针 ⇒ 属于新增可观测性，不改行为 |
| labels | 资源上只有 `app.kubernetes.io/name` | 额外加 `app.kubernetes.io/part-of` | 仅加标签，不影响 selector 匹配；`apply` 是幂等合并 |
| nodeSelector | 无 | 无（保持） | PVC 的 PV 已绑 zifang001，靠 PV 亲和性约束；显式加 nodeSelector 属于改 spec，需另行评估 |

## 6. 待办（不在本任务范围内）

- 本仓**缺 Dockerfile**，镜像构建不可复现（§1 已给出建议内容，需人工补进仓并提交）。
- `REDIS_HOST` 指向跨 ns 依赖（z-cache 域），z-cache 的清单在 `z-cache/deploy/k8s/`，
  两域一起上线时注意先后顺序。
