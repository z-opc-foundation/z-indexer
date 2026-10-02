package com.zifang.z.indexer.schedule;

import com.zifang.z.indexer.IndexerConfig;
import com.zifang.z.indexer.pipeline.GitOps;
import com.zifang.z.indexer.pipeline.IndexBuildService;
import com.zifang.z.indexer.registry.BuildLockManager;
import com.zifang.z.indexer.registry.IndexRepo;
import com.zifang.z.indexer.registry.RegistryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 轮询调度：由 starter 层 @Scheduled 驱动 {@link #pollOnce()}。
 * <p>
 * 分布式多实例（共用同一 NAS）：pollOnce 先做变更检测，再通过
 * {@link BuildLockManager}（locks/&lt;repoCode&gt;.lock 原子创建）逐仓抢锁——
 * 抢到的仓库在本机并发构建（{@code buildConcurrency} 线程），没抢到的说明
 * 其它实例正在构建，本轮跳过。构建结束（无论成败）释放锁。
 * <p>
 * 单实例语义不变（锁必抢到）；多实例天然把 needBuild 集合拆分给各机器并行，
 * 每台机器独立容器内存预算，单仓构建互斥由 NAS 锁保证。
 */
public class IndexSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexSyncRunner.class);

    private final RegistryStore registryStore;
    private final IndexBuildService buildService;
    private final BuildLockManager lockManager;
    private final ExecutorService buildPool;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最近一轮轮询的耗时与触发情况（监控用） */
    private volatile long lastPollAtMillis;
    private volatile long lastPollDurationMs;
    private volatile int lastPollRepoCount;
    private volatile int lastPollTriggeredCount;

    public IndexSyncRunner(RegistryStore registryStore, IndexBuildService buildService,
                           BuildLockManager lockManager, IndexerConfig config) {
        this.registryStore = registryStore;
        this.buildService = buildService;
        this.lockManager = lockManager;
        int concurrency = Math.max(1, config.getBuildConcurrency());
        this.buildPool = Executors.newFixedThreadPool(concurrency, new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "index-build-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * 一轮轮询：变更检测 → 逐仓抢锁（跨实例互斥）→ 抢到的并发重建。
     * 注册后从未构建（NEVER）的仓库也会被触发（首次 INIT）；
     * BUILDING（其它实例构建中）跳过；FAILED 每轮重试。
     */
    public void pollOnce() {
        if (!running.compareAndSet(false, true)) {
            log.debug("[IndexSync] previous poll still running, skip");
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            List<IndexRepo> repos = new ArrayList<IndexRepo>(registryStore.listRepos());
            int triggered = 0;
            final List<IndexRepo> claimed = new ArrayList<IndexRepo>();
            for (IndexRepo repo : repos) {
                if (!repo.isEnabled()) {
                    continue;
                }
                try {
                    boolean needBuild;
                    String status = repo.getLastBuildStatus();
                    if ("NEVER".equals(status)) {
                        needBuild = true;
                    } else if ("BUILDING".equals(status)) {
                        // 锁新鲜（其它实例正在构建）：跳过；
                        // 锁不在/stale（实例宕机残留的 BUILDING）：重新触发重建
                        needBuild = lockManager.isStaleOrFree(repo.getRepoCode());
                    } else if ("SUCCESS".equals(status)) {
                        GitOps.RemoteRef remote = buildService.getGitOps()
                                .lsRemoteRef(repo.getGitUrl(), repo.getRefMode(), repo.getRefValue());
                        if (remote == null) {
                            needBuild = false;
                        } else if (!remote.getSha().equals(repo.getLastIndexedCommit())) {
                            needBuild = true;
                        } else {
                            needBuild = false;
                            // 同 commit 不同 ref（如发版 tag 打在已索引 commit 上）：仅刷新 ref 记录不重建
                            String resolved = GitOps.normalizeRefName(remote.getRef());
                            String expect = repo.isBranchMode() ? repo.getRefValue() : resolved;
                            if (!expect.equals(repo.getLastIndexedRef())) {
                                repo.setLastIndexedRef(expect);
                                registryStore.persistStatus(repo);
                                log.info("[IndexSync] repo={} ref moved to {} at same commit {} (no rebuild)",
                                        repo.getRepoCode(), expect, remote.getSha());
                            }
                        }
                    } else {
                        // 上次失败：每轮重试（失败原因可能是瞬时的：镜像仓抖动 / tag 未就绪）
                        needBuild = true;
                    }
                    if (needBuild) {
                        if (lockManager.tryAcquire(repo.getRepoCode())) {
                            claimed.add(repo);
                            triggered++;
                        } else {
                            log.debug("[IndexSync] repo={} locked by other instance, skip this round",
                                    repo.getRepoCode());
                        }
                    }
                } catch (Exception e) {
                    log.error("[IndexSync] repo {} poll failed: {}", repo.getRepoCode(), e.getMessage());
                }
            }
            if (!claimed.isEmpty()) {
                runClaimed(claimed);
            }
            lastPollAtMillis = System.currentTimeMillis();
            lastPollDurationMs = lastPollAtMillis - t0;
            lastPollRepoCount = repos.size();
            lastPollTriggeredCount = triggered;
            if (triggered > 0) {
                log.info("[IndexSync] poll done: repos={}, claimed={}, cost={}ms, instance={}",
                        repos.size(), triggered, lastPollDurationMs, lockManager.getInstanceId());
            }
        } finally {
            running.set(false);
        }
    }

    /** 本机并发构建抢到的仓库（每仓构建结束释放锁，异常也不留死锁）。 */
    private void runClaimed(final List<IndexRepo> claimed) {
        List<Future<?>> futures = new ArrayList<Future<?>>(claimed.size());
        for (final IndexRepo repo : claimed) {
            futures.add(buildPool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean first = "NEVER".equals(repo.getLastBuildStatus());
                        buildService.buildOne(repo, first ? "INIT" : "SCHEDULE");
                    } catch (Exception e) {
                        log.error("[IndexSync] repo {} build crashed: {}", repo.getRepoCode(), e.getMessage());
                    } finally {
                        lockManager.release(repo.getRepoCode());
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.error("[IndexSync] build future failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 手动触发单仓库重建（Admin 接口用）。
     * <p>锁被其它实例持有时返回 false（该实例正在构建同仓）。
     */
    public boolean rebuildOne(String repoCode) {
        return rebuildOne(repoCode, false);
    }

    /** 手动重建（Admin 用）：skipCompile=true 跳过 mvn 编译轮，仅静态索引+打制品（预热用）。 */
    public boolean rebuildOne(String repoCode, boolean skipCompile) {
        IndexRepo repo = registryStore.find(repoCode);
        if (repo == null) {
            return false;
        }
        if (!lockManager.tryAcquire(repoCode)) {
            log.warn("[IndexSync] repo={} locked by instance {}, manual rebuild rejected",
                    repoCode, lockManager.lockHolder(repoCode));
            return false;
        }
        try {
            return buildService.buildOne(repo, "MANUAL", skipCompile);
        } finally {
            lockManager.release(repoCode);
        }
    }

    public long getLastPollAtMillis() {
        return lastPollAtMillis;
    }

    public long getLastPollDurationMs() {
        return lastPollDurationMs;
    }

    public int getLastPollRepoCount() {
        return lastPollRepoCount;
    }

    public int getLastPollTriggeredCount() {
        return lastPollTriggeredCount;
    }

    /** 当前实例标识（监控透出）。 */
    public String getInstanceId() {
        return lockManager.getInstanceId();
    }
}
