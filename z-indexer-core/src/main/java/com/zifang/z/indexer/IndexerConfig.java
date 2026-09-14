package com.zifang.z.indexer;

/**
 * 索引服务核心配置（由 starter 层 IndexerProperties 转换注入）。
 * <p>保持纯 POJO，core 不依赖 Spring。
 */
public class IndexerConfig {

    /** repos 根目录：其下有 repos/（git 工作副本）、indexer/（索引产物）、bin/（索引器 jar） */
    private String reposRoot;

    /** 单仓库构建超时（分钟） */
    private int buildTimeoutMinutes = 30;

    /**
     * 双轨兜底开关：java 仓 mvn 编译失败时降级 JavaParser 静态索引（同 schema）。
     * 默认开（静态索引精度略降但可查询，远好于整仓无索引）；需要纯编译语义时关。
     */
    private boolean staticFallbackEnabled = true;

    /**
     * mvn 子进程 JVM 参数（MAVEN_OPTS）。默认堆 1g：容器 limit 4g，服务 JVM 按容器 50%（约 2.4g RSS），
     * 留给构建进程约 1.6g——堆 2g 时大仓库构建会被 cgroup OOM kill（exit=137，inpatient/nis 实证）。
     * 并发构建≥2 时建议降到 -Xmx512m（内存预算见 buildConcurrency 注释）。
     */
    private String mavenOpts = "-Xmx1g -XX:MaxMetaspaceSize=256m";

    /**
     * 单轮轮询内并发构建数。默认 2（分布式拉起）：60+ 仓全量串行需 2~3 小时，并发 2 减半。
     * 内存预算（容器 limit 4g）：服务 JVM 约 2.4g（MaxRAMPercentage=50），
     * 每个构建子进程约 0.8g（堆 512m + metaspace 256m + 栈/码缓）——
     * 2 并发 × 0.8g + 2.4g ≈ 4g 极限，故并发≥2 时必须配 mavenOpts=-Xmx512m。
     * 大仓库构建若 mvn 堆 OOM（exit=1 OutOfMemoryError）可调回 1 并发 + 1g 堆。
     */
    private int buildConcurrency = 2;

    /** mvn 命令（可配绝对路径，容器内路径可能不同） */
    private String mvnCmd = "";

    /** git 命令 */
    private String gitCmd = "";

    /**
     * node 可执行路径（前端仓索引用）。空 = 内置发行版（bin/node-bin.zip 释放到 bin/node-dist/node，
     * linux-x64；与内置 maven 同模式）。本地 E2E 可配本机 node（如 /Users/xx/.nvm/.../bin/node）。
     */
    private String nodeCmd = "";

    /** 查询端内存常驻仓库数上限（LRU） */
    private int lruMaxRepos = 6;

    /**
     * 巨仓列表（逗号分隔 repoCode）：这些仓构建前查询端 LRU 让路（驱逐冷仓到低水位）。
     * 背景（0902 实证）：容器 limit 4g 内「预热 12 仓常驻 + mvn 1g 堆」叠加会 cgroup
     * OOM kill 整个 pod（inpatient 构建中 pod 重启，build-log 半行戛然而止）。
     * 让路后构建照常、常驻靠查询自然回填（agent 首查 30s loading 可接受），
     * 与常态高常驻（warmup/lru）不冲突——只在巨仓构建的几分钟内临时收缩。
     */
    private String bigRepoCodes = "";

    /** 巨仓构建让路后的 LRU 低水位（驱逐冷仓到剩 N 个） */
    private int buildYieldRepos = 4;

    /** 启动预热仓库数（SUCCESS 仓按索引行数降序取前 N 个，0=不预热） */
    private int warmupRepos = 6;

    /** 调用链默认展开深度 */
    private int defaultDepth = 2;

    /** 调用链/搜索单次返回节点上限（防爆炸） */
    private int maxResultNodes = 200;

    public String getReposRoot() {
        return reposRoot;
    }

    public void setReposRoot(String reposRoot) {
        this.reposRoot = reposRoot;
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
