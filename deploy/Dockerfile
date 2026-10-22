# ============================================================
# z-indexer —— 运行镜像（独立部署的代码索引服务）
#
# 【为什么现在才有这个文件（2026-10-22）】
#   本仓此前**没有 Dockerfile**：线上那份 `zopc/z-indexer:local` 是当初在节点上手工构建
#   后喂进 containerd 的，仓里没留下可复现的构建定义。
#   卡 TASK-20261022-039 要按新源码重建镜像 —— 新源码里才有**管理面入站鉴权**
#   （`z.indexer.auth.require-token=true` + `Z_INDEXER_TOKEN`），
#   而现役镜像里没有这段代码（实测线上 `/actuator/env` 里没有 z.indexer.token 属性、
#   `/index/admin/repos` 匿名可读 200）⇒ 不重建就永远开不了鉴权。
#
# 【基底】
#   Java 8（与本仓父链一致；现役容器 PID 1 实测也是 java 8）。
#   ⚠ 集群两台节点都是 amd64，而开发机常是 arm64 ⇒ 构建必须显式 `--platform linux/amd64`。
#   ⚠ 基底默认走 Docker Hub（本机到 Docker Hub 不通）⇒ 构建时用
#     `--build-arg BASE_IMAGE=<可用镜像源>/library/eclipse-temurin:8-jre-jammy` 覆盖。
#
# 【JAVA_OPTS】
#   现役容器 PID 1 实测：`java -Xms512m -Xmx2g -XX:MaxMetaspaceSize=512m -jar /app/app.jar`
#   ⇒ JAVA_OPTS 是**生效**的（ENTRYPOINT 展开它）。
#   ⇒ 这里必须复刻这个展开语义；写成 `ENTRYPOINT ["java","-jar","app.jar"]` 会让
#     JAVA_OPTS 变成摆设、堆大小掉回 JVM 默认值（本域容器 limit 4g，默认堆会偏小）。
#
# 【鉴权相关】
#   管理面开启鉴权后**缺令牌 ⇒ 启动失败**（fail closed）：
#   `z.indexer.auth.require-token=true` 但 `z.indexer.token` 为空 ⇒ 进程拒绝启动。
#   ⇒ **K8s Secret `z-indexer-auth`（键 `token`）必须先建好**，否则 Pod 起不来。
#   ⇒ 令牌只从环境变量 `Z_INDEXER_TOKEN` 注入（Secret 注入），**不进镜像、不进仓**。
# ============================================================

ARG BASE_IMAGE=eclipse-temurin:8-jre-jammy
FROM ${BASE_IMAGE}

LABEL maintainer="zifang"

# 时区：基底默认 UTC，钉成 Asia/Shanghai（与现役一致）
ENV TZ=Asia/Shanghai
RUN ln -sf /usr/share/zoneinfo/${TZ} /etc/localtime && echo ${TZ} > /etc/timezone

WORKDIR /app
# 可执行 boot jar（z-indexer-server 声明了 spring-boot-maven-plugin ⇒ repackage 后可直接 java -jar）
COPY z-indexer-server/target/z-indexer-server-1.0.0.jar app.jar

ENV SERVER_PORT=8080
EXPOSE 8080

# JAVA_OPTS 由 Deployment 注入（现役是 -Xms512m -Xmx2g -XX:MaxMetaspaceSize=512m）
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar"]
