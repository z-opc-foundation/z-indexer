package com.zifang.z.indexer.registry;

/**
 * 单次构建历史记录。
 * <p>追加写入 {@code repos/indexer/build-log.jsonl}（审计留全量），内存保留最近 N 条供监控查询。
 */
public class BuildLogEntry {

    /** INIT（注册后首次）/ SCHEDULE（轮询发现变更）/ MANUAL（手动触发） */
    private String triggerType;

    private String repoCode;

    private String resolvedRef;

    private String commitBefore;

    private String commitAfter;

    /** SUCCESS / FAILED */
    private String status;

    /** 产出链路：COMPILED（编译期）/ STATIC（静态兜底），仅 SUCCESS 有意义 */
    private String indexType;

    private long durationMs;

    private long indexLines;

    private String errorMsg;

    private long startedAt;

    private long finishedAt;

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getRepoCode() {
        return repoCode;
    }

    public void setRepoCode(String repoCode) {
        this.repoCode = repoCode;
    }

    public String getResolvedRef() {
        return resolvedRef;
    }

    public void setResolvedRef(String resolvedRef) {
        this.resolvedRef = resolvedRef;
    }

    public String getCommitBefore() {
        return commitBefore;
    }

    public void setCommitBefore(String commitBefore) {
        this.commitBefore = commitBefore;
    }

    public String getCommitAfter() {
        return commitAfter;
    }

    public void setCommitAfter(String commitAfter) {
        this.commitAfter = commitAfter;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public long getIndexLines() {
        return indexLines;
    }

    public void setIndexLines(long indexLines) {
        this.indexLines = indexLines;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getIndexType() {
        return indexType;
    }

    public void setIndexType(String indexType) {
        this.indexType = indexType;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }
}
