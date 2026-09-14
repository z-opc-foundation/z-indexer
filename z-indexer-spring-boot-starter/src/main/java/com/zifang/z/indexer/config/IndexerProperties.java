package com.zifang.z.indexer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 索引服务配置（Nacos 可覆盖）。
 */
@ConfigurationProperties(prefix = "future-team.indexer")
public class IndexerProperties {

    /** 服务总开关（false 时轮询停止、查询接口仍可用已有索引） */
    private boolean enabled = true;

    /**
     * repos 根目录：其下有 repos/（git 工作副本）、indexer/（索引产物）、bin/（内置工具链）。
     * <p>默认 JVM tmpdir（容器 /data 不可写）；生产由 application.yaml 显式配置。
     */
    private String reposRoot = System.getProperty("java.io.tmpdir", "/tmp") + "/future-team-indexer";

    /** 轮询间隔（ms），默认 10 分钟 */
    private long pollIntervalMs = 600000L;

    /** 启动后首次轮询延迟（ms），默认 1 分钟（等 config-center 就绪） */
    private long pollInitialDelayMs = 60000L;

    /** 单仓库构建超时（分钟） */
    private int buildTimeoutMinutes = 30;

    /**
     * 双轨兜底开关：java 仓 mvn 编译失败时降级 JavaParser 静态索引（同 schema，免编译）。
     * 默认开；需要纯编译语义时置 false。
     */
    private boolean staticFallbackEnabled = true;

    /**
     * mvn 子进程 JVM 参数（MAVEN_OPTS），默认堆 1g：容器 limit 4g，服务 JVM 按容器 50%，
     * 堆 2g 会触发 cgroup OOM kill（exit=137）。升容器 limit 后可调回。
     * 并发构建≥2 时建议降到 -Xmx512m（内存预算见 buildConcurrency）。
     */
    private String mavenOpts = "-Xmx1g -XX:MaxMetaspaceSize=256m";

    /**
     * 单实例并发构建数（分布式拉起：多实例各持 NAS 锁拆仓，仓内再并发）。
     * 容器 limit 4g 内存预算：服务 JVM 约 2.4g + 每构建进程约 0.8g，
     * 2 并发即极限；升 limit 或多实例分摊后可加大。
     */
    private int buildConcurrency = 2;

    /** mvn 命令（容器内可为绝对路径） */
    private String mvnCmd = "";

    /** git 命令（容器内可为绝对路径） */
    private String gitCmd = "";

    /**
     * node 可执行路径（前端仓索引）：空 = 内置 linux-x64 发行版（启动释放到 bin/node-dist/node）；
     * 本地 E2E 配本机 node（如 /Users/xx/.nvm/versions/node/v20/bin/node）。
     */
    private String nodeCmd = "";

    /** 查询端：内存常驻仓库数上限（LRU） */
    private int lruMaxRepos = 6;

    /**
     * 巨仓列表（逗号分隔 repoCode）：这些仓构建前查询端 LRU 让路（驱逐冷仓到低水位）。
     * 巨仓 mvn 堆与常驻叠加会顶破容器 limit（0902 inpatient 实证 pod cgroup 重启）。
     */
    private String bigRepoCodes = "";

    /** 巨仓构建让路后的 LRU 低水位（驱逐冷仓到剩 N 个） */
    private int buildYieldRepos = 4;

    /** 启动预热：SUCCESS 仓按索引行数降序取前 N 个预加载（0=关闭）。RepoIndex 字符串池化后
     * 单大仓 ≈ 25-60MB（未池化时 150-440MB/仓，12 仓实测 GC 风暴），预热 4 个 ≈ 150MB。 */
    private int warmupRepos = 6;

    /** 调用链默认展开深度 */
    private int defaultDepth = 2;

    /** 调用链/搜索单次返回节点上限 */
    private int maxResultNodes = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getReposRoot() {
        return reposRoot;
    }

    public void setReposRoot(String reposRoot) {
        this.reposRoot = reposRoot;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getPollInitialDelayMs() {
        return pollInitialDelayMs;
    }

    public void setPollInitialDelayMs(long pollInitialDelayMs) {
        this.pollInitialDelayMs = pollInitialDelayMs;
    }

    public int getBuildTimeoutMinutes() {
        return buildTimeoutMinutes;
    }

    public void setBuildTimeoutMinutes(int buildTimeoutMinutes) {
        this.buildTimeoutMinutes = buildTimeoutMinutes;
    }

    public boolean isStaticFallbackEnabled() {
        return staticFallbackEnabled;
    }

    public void setStaticFallbackEnabled(boolean staticFallbackEnabled) {
        this.staticFallbackEnabled = staticFallbackEnabled;
    }

    public String getMavenOpts() {
        return mavenOpts;
    }

    public void setMavenOpts(String mavenOpts) {
        this.mavenOpts = mavenOpts;
    }

    public int getBuildConcurrency() {
        return buildConcurrency;
    }

    public void setBuildConcurrency(int buildConcurrency) {
        this.buildConcurrency = buildConcurrency;
    }

    public String getMvnCmd() {
        return mvnCmd;
    }

    public void setMvnCmd(String mvnCmd) {
        this.mvnCmd = mvnCmd;
    }

    public String getGitCmd() {
        return gitCmd;
    }

    public void setGitCmd(String gitCmd) {
        this.gitCmd = gitCmd;
    }

    public String getNodeCmd() {
        return nodeCmd;
    }

    public void setNodeCmd(String nodeCmd) {
        this.nodeCmd = nodeCmd;
    }

    public int getLruMaxRepos() {
        return lruMaxRepos;
    }

    public void setLruMaxRepos(int lruMaxRepos) {
        this.lruMaxRepos = lruMaxRepos;
    }

    public int getWarmupRepos() {
        return warmupRepos;
    }

    public void setWarmupRepos(int warmupRepos) {
        this.warmupRepos = warmupRepos;
    }

    public String getBigRepoCodes() {
        return bigRepoCodes;
    }

    public void setBigRepoCodes(String bigRepoCodes) {
        this.bigRepoCodes = bigRepoCodes;
    }

    public int getBuildYieldRepos() {
        return buildYieldRepos;
    }

    public void setBuildYieldRepos(int buildYieldRepos) {
        this.buildYieldRepos = buildYieldRepos;
    }

    public int getDefaultDepth() {
        return defaultDepth;
    }

    public void setDefaultDepth(int defaultDepth) {
        this.defaultDepth = defaultDepth;
    }

    public int getMaxResultNodes() {
        return maxResultNodes;
    }

    public void setMaxResultNodes(int maxResultNodes) {
        this.maxResultNodes = maxResultNodes;
    }
}
