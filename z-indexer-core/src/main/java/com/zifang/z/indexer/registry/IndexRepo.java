package com.zifang.z.indexer.registry;

/**
 * 仓库注册表条目。
 * <p>持久化于 {@code repos/indexer/registry.json}，重启即恢复。
 */
public class IndexRepo {

    /** ref 解析模式：固定分支 */
    public static final String REF_MODE_BRANCH = "BRANCH";
    /** ref 解析模式：通配匹配最新 tag（主推，与 matrix 发版节奏联动） */
    public static final String REF_MODE_TAG_PATTERN = "TAG_PATTERN";
    /** ref 解析模式：取字典序最大的 tag */
    public static final String REF_MODE_TAG_LATEST = "TAG_LATEST";

    /** 仓库唯一标识（目录名），如 hbos-medication */
    private String repoCode;

    /** 显示名 */
    private String repoName;

    /** git 地址（http 可带凭证，或容器内预配 ssh key） */
    private String gitUrl;

    /** BRANCH / TAG_PATTERN / TAG_LATEST */
    private String refMode = REF_MODE_BRANCH;

    /** BRANCH: 分支名；TAG_PATTERN: 通配表达式，如 release_hbos-medication_version_* */
    private String refValue = "master";

    /** 构建类型（预留，当前仅 MAVEN） */
    private String buildType = "MAVEN";

    /**
     * 工程语言：java（maven 编译链路）| frontend（node 索引器链路）。
     * 留空自动探测：有 pom.xml → java；有 package.json 无 pom.xml → frontend。
     */
    private String language = "";

    /** 是否启用轮询 */
    private boolean enabled = true;

    /** 最近一次成功索引的 commit SHA */
    private String lastIndexedCommit;

    /** 最近一次成功索引时解析到的 ref（分支名或 tag 名） */
    private String lastIndexedRef;

    /** NEVER / BUILDING / SUCCESS / FAILED */
    private String lastBuildStatus = "NEVER";

    /** 最近一次构建耗时（ms） */
    private long lastBuildDurationMs;

    /** 最近成功索引行数 */
    private long lastIndexLines;

    /**
     * 最近成功索引的产出链路：COMPILED（mvn+processor 编译期，精度上限）/
     * STATIC（免编译静态解析，编译失败兜底或前端仓）。空 = 历史数据（视为 COMPILED）。
     */
    private String lastIndexType = "";

    /** 最近一次构建完成时间（epoch ms） */
    private long lastBuildTime;

    /** 最近一次构建错误信息（截断 2048） */
    private String lastErrorMsg;

    /**
     * 编译附带对照轮状态（双轮模式，仅 java 仓）：SUCCESS / FAILED / SKIPPED（轮询时上次
     * 编译失败省时跳过）。null = 前端仓或历史数据。对照产物 &lt;repoCode&gt;.compiled.jsonl
     * 供静态索引质量校准（回放 diff），不影响主索引成败。
     */
    private String lastCompiledStatus;

    /** 编译附带索引行数（&lt;repoCode&gt;.compiled.jsonl） */
    private long lastCompiledLines;

    /** 编译附带失败摘要（截断 2000） */
    private String lastCompiledError;

    /** 最近一次构建的实例（分布式多实例下哪台机器构建的，hostname:pid） */
    private String builtBy;

    private String remark;

    private long createdAt;

    private long updatedAt;

    public boolean isBranchMode() {
        return REF_MODE_BRANCH.equalsIgnoreCase(refMode);
    }

    public String getRepoCode() {
        return repoCode;
    }

    public void setRepoCode(String repoCode) {
        this.repoCode = repoCode;
    }

    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }

    public String getGitUrl() {
        return gitUrl;
    }

    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    public String getRefMode() {
        return refMode;
    }

    public void setRefMode(String refMode) {
        this.refMode = refMode;
    }

    public String getRefValue() {
        return refValue;
    }

    public void setRefValue(String refValue) {
        this.refValue = refValue;
    }

    public String getBuildType() {
        return buildType;
    }

    public void setBuildType(String buildType) {
        this.buildType = buildType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLastIndexedCommit() {
        return lastIndexedCommit;
    }

    public void setLastIndexedCommit(String lastIndexedCommit) {
        this.lastIndexedCommit = lastIndexedCommit;
    }

    public String getLastIndexedRef() {
        return lastIndexedRef;
    }

    public void setLastIndexedRef(String lastIndexedRef) {
        this.lastIndexedRef = lastIndexedRef;
    }

    public String getLastBuildStatus() {
        return lastBuildStatus;
    }

    public void setLastBuildStatus(String lastBuildStatus) {
        this.lastBuildStatus = lastBuildStatus;
    }

    public long getLastBuildDurationMs() {
        return lastBuildDurationMs;
    }

    public void setLastBuildDurationMs(long lastBuildDurationMs) {
        this.lastBuildDurationMs = lastBuildDurationMs;
    }

    public long getLastIndexLines() {
        return lastIndexLines;
    }

    public void setLastIndexLines(long lastIndexLines) {
        this.lastIndexLines = lastIndexLines;
    }

    public String getLastIndexType() {
        return lastIndexType;
    }

    public void setLastIndexType(String lastIndexType) {
        this.lastIndexType = lastIndexType;
    }

    public long getLastBuildTime() {
        return lastBuildTime;
    }

    public void setLastBuildTime(long lastBuildTime) {
        this.lastBuildTime = lastBuildTime;
    }

    public String getLastErrorMsg() {
        return lastErrorMsg;
    }

    public void setLastErrorMsg(String lastErrorMsg) {
        this.lastErrorMsg = lastErrorMsg;
    }

    public String getLastCompiledStatus() {
        return lastCompiledStatus;
    }

    public void setLastCompiledStatus(String lastCompiledStatus) {
        this.lastCompiledStatus = lastCompiledStatus;
    }

    public long getLastCompiledLines() {
        return lastCompiledLines;
    }

    public void setLastCompiledLines(long lastCompiledLines) {
        this.lastCompiledLines = lastCompiledLines;
    }

    public String getLastCompiledError() {
        return lastCompiledError;
    }

    public void setLastCompiledError(String lastCompiledError) {
        this.lastCompiledError = lastCompiledError;
    }

    public String getBuiltBy() {
        return builtBy;
    }

    public void setBuiltBy(String builtBy) {
        this.builtBy = builtBy;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
