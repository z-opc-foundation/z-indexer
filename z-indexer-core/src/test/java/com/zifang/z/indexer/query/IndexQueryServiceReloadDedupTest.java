package com.zifang.z.indexer.query;

import com.zifang.z.indexer.IndexerConfig;
import com.zifang.z.indexer.registry.RegistryStore;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link IndexQueryService#markReloadScheduled} 的并发去重不变量。
 *
 * <p>{@code loadedFileMillis} 是普通 HashMap，被 {@code put} / {@code shrinkTo} /
 * {@code invalidate} 在 {@code lruLock} 下改；而热重建去重闸原先跑在请求线程上、
 * 且「检查 + 记账」都在锁外，于是与那三处并发访问同一张表。
 *
 * <p>可观测的后果是去重漏判：同一 millis 重复排多次热重建，
 * 而每次 {@code RepoIndex.load} 都要把整个索引读进内存。
 */
public class IndexQueryServiceReloadDedupTest {

    private File dir;
    private IndexQueryService svc;

    @BeforeMethod
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("z-indexer-query").toFile();
        IndexerConfig cfg = new IndexerConfig();
        cfg.setReposRoot(dir.getAbsolutePath());
        cfg.setLruMaxRepos(4);
        svc = new IndexQueryService(cfg, new RegistryStore(new File(dir, "indexer")));
    }

    /** 同一 repoCode + 同一 millis，只有第一次拿到调度权。 */
    @Test
    public void onlyFirstCallerWinsForSameMillis() {
        long millis = 1_700_000_000_000L;
        Assert.assertTrue(svc.markReloadScheduled("z-util", millis), "首次应赢得调度权");
        Assert.assertFalse(svc.markReloadScheduled("z-util", millis), "重复调用应被去重");
        Assert.assertFalse(svc.markReloadScheduled("z-util", millis));
    }

    /** 索引文件变了（millis 变化）就该再排一次，否则改了代码索引永远不刷新。 */
    @Test
    public void newMillisSchedulesAgain() {
        long m1 = 1_700_000_000_000L;
        Assert.assertTrue(svc.markReloadScheduled("z-util", m1));
        Assert.assertTrue(svc.markReloadScheduled("z-util", m1 + 1),
                "millis 变化说明索引已重建，必须能再次排热重建");
        Assert.assertFalse(svc.markReloadScheduled("z-util", m1 + 1));
    }

    @Test
    public void differentReposAreIndependent() {
        long millis = 1_700_000_000_000L;
        Assert.assertTrue(svc.markReloadScheduled("z-util", millis));
        Assert.assertTrue(svc.markReloadScheduled("z-trade", millis),
                "不同仓库的去重状态互不影响");
    }

    /**
     * 核心闸：N 个请求线程同时对同一 (repoCode, millis) 竞争，
     * 拿到调度权的必须<b>恰好一个</b>。
     * <p>
     * 无锁实现下「get → 判断 → put」三步之间存在窗口，多个线程会同时通过判断；
     * 且 HashMap 并发 put 还有结构损坏风险。
     */
    @Test
    public void concurrentCallersElectExactlyOne() throws Exception {
        final int threads = 16;
        final int rounds = 400;
        for (int r = 0; r < rounds; r++) {
            final long millis = 1_700_000_000_000L + r;
            final AtomicInteger winners = new AtomicInteger();
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);
            List<Thread> ts = new ArrayList<Thread>();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            start.await();
                            if (svc.markReloadScheduled("z-util", millis)) {
                                winners.incrementAndGet();
                            }
                        } catch (InterruptedException ignore) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                ts.add(t);
            }
            start.countDown();
            Assert.assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS),
                    "第 " + r + " 轮线程未在 30s 内结束（HashMap 并发损坏会表现为卡死）");
            for (Thread t : ts) {
                t.join();
            }
            Assert.assertEquals(winners.get(), 1,
                    "第 " + r + " 轮：应恰好一个线程赢得调度权，实际 " + winners.get());
        }
    }

    /** invalidate 之后应能重新排 —— 构建完成走的就是 invalidate。 */
    @Test
    public void invalidateAllowsRescheduling() {
        long millis = 1_700_000_000_000L;
        Assert.assertTrue(svc.markReloadScheduled("z-util", millis));
        Assert.assertFalse(svc.markReloadScheduled("z-util", millis));
        svc.invalidate("z-util");
        Assert.assertTrue(svc.markReloadScheduled("z-util", millis),
                "invalidate 清掉记账后应能再次调度");
    }
}
