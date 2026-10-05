package com.zifang.z.indexer.registry;

import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * {@link BuildLockManager} 的锁语义（本类原先零测试）。
 * <p>
 * 重点是三处最容易回归的地方：
 * <ol>
 *   <li>同一仓库同一时刻只允许一台实例抢到锁（createNewFile 的互斥性）；</li>
 *   <li>stale 锁可被抢占、<b>但只有真 stale 才可</b>（mtime 未超时的锁抢不走）；</li>
 *   <li>release 只删自己的锁 —— 删掉别人的锁会让两台实例同时构建同一仓库。</li>
 * </ol>
 */
public class BuildLockManagerTest {

    private File dir;

    @BeforeMethod
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("z-indexer-lock").toFile();
    }

    private static void cleanup(File dir) {
        if (dir == null) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                k.delete();
            }
        }
        File locks = new File(dir, "locks");
        if (locks.isDirectory()) {
            File[] lk = locks.listFiles();
            if (lk != null) {
                for (File f : lk) {
                    f.delete();
                }
            }
            locks.delete();
        }
        dir.delete();
    }

    /**
     * 手工造一个"被别的实例持有"的锁文件。
     *
     * @param mtimeMs 锁文件 mtime。stale 判定只读 {@code lastModified()}，
     *                不读内容里的 {@code acquiredAt}，故必须显式设定；
     *                否则测试会退化成"赌写盘到再检查至少过了 staleMs"。
     */
    private void writeForeignLock(String repoCode, String instance, long mtimeMs) throws Exception {
        File locks = new File(dir, "locks");
        locks.mkdirs();
        File f = new File(locks, repoCode + ".lock");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write("{\"instance\":\"" + instance + "\",\"acquiredAt\":" + mtimeMs + "}");
        }
        Assert.assertTrue(f.setLastModified(mtimeMs), "测试前置：应能设定锁文件 mtime");
    }

    @Test
    public void firstAcquireSucceeds() {
        try {
            BuildLockManager m = new BuildLockManager(dir, 60_000L);
            Assert.assertTrue(m.tryAcquire("repo-a"),
                    "无锁时第一次抢锁应成功");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void secondAcquireOfSameRepoFails() {
        // 同一目录 = 同一台机器；两个 manager 模拟两台实例共享 NAS
        try {
            BuildLockManager m1 = new BuildLockManager(dir, 60_000L);
            BuildLockManager m2 = new BuildLockManager(dir, 60_000L);

            Assert.assertTrue(m1.tryAcquire("repo-a"), "实例 1 应抢到锁");
            Assert.assertFalse(m2.tryAcquire("repo-a"),
                    "锁已被实例 1 持有，实例 2 必须抢不到（否则两台同时构建同一仓库）");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void differentReposDoNotBlockEachOther() {
        try {
            BuildLockManager m = new BuildLockManager(dir, 60_000L);
            Assert.assertTrue(m.tryAcquire("repo-a"));
            Assert.assertTrue(m.tryAcquire("repo-b"),
                    "不同仓库应各自独立加锁");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void releaseThenCanReacquire() {
        try {
            BuildLockManager m = new BuildLockManager(dir, 60_000L);
            Assert.assertTrue(m.tryAcquire("repo-a"));
            m.release("repo-a");
            Assert.assertTrue(m.tryAcquire("repo-a"),
                    "释放后应能重新抢到");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void freshLockIsNotStolen() throws Exception {
        // 反向闸：stale 判定不能过松，否则活着的实例会被别人抢走锁
        try {
            long now = System.currentTimeMillis();
            writeForeignLock("repo-a", "other-host:111", now);

            BuildLockManager m = new BuildLockManager(dir, 60_000L);
            Assert.assertFalse(m.tryAcquire("repo-a"),
                    "锁刚创建（mtime 新鲜）时不得被抢占");
            Assert.assertEquals(m.lockHolder("repo-a"), "other-host:111",
                    "锁持有者应仍是原实例");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void staleLockIsTakenOver() throws Exception {
        try {
            // mtime 显式回拨 10s，staleMs=1s ⇒ 判定必 stale，与机器负载无关
            writeForeignLock("repo-a", "dead-host:999", System.currentTimeMillis() - 10_000L);

            BuildLockManager m = new BuildLockManager(dir, 1_000L);
            Assert.assertTrue(m.tryAcquire("repo-a"),
                    "stale 锁（持有实例已宕机）应可被抢占，否则死锁到人工干预");
            Assert.assertEquals(m.lockHolder("repo-a"), m.getInstanceId(),
                    "抢占后锁内容应更新为本实例");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void releaseDoesNotDeleteOthersLock() throws Exception {
        // 核心闸：删掉别人的锁 = 两台实例同时构建
        try {
            writeForeignLock("repo-a", "other-host:111", System.currentTimeMillis());

            BuildLockManager m = new BuildLockManager(dir, 60_000L);
            // m 没抢到锁就 release 也不该动别人的锁
            Assert.assertFalse(m.tryAcquire("repo-a"));
            m.release("repo-a");

            Assert.assertTrue(new File(new File(dir, "locks"), "repo-a.lock").exists(),
                    "release 不属于本实例时不得删除锁文件");
            Assert.assertEquals(m.lockHolder("repo-a"), "other-host:111",
                    "锁持有者必须仍是 other-host");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void isStaleOrFreeReflectsLockState() throws Exception {
        try {
            BuildLockManager fresh = new BuildLockManager(dir, 60_000L);
            Assert.assertTrue(fresh.isStaleOrFree("repo-a"), "无锁时应为 true");

            Assert.assertTrue(fresh.tryAcquire("repo-a"));
            Assert.assertFalse(fresh.isStaleOrFree("repo-a"), "刚拿到的锁不应算 stale/free");

            writeForeignLock("repo-b", "x:1", System.currentTimeMillis() - 10_000L);
            BuildLockManager loose = new BuildLockManager(dir, 1_000L);
            Assert.assertTrue(loose.isStaleOrFree("repo-b"), "mtime 超期的锁应算 stale");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void releaseClearsCorruptedLock() throws Exception {
        // 内容损坏（写 holder 时崩在半路）时，release 必须把锁清掉。
        // 否则该仓库会一直卡到 staleMs 到期才有人能抢，且持锁者自己也无法解锁。
        // 注意这条分支只在 release 被调用时触发，而 release 只有持锁者会调
        // （IndexSyncRunner:158 在 tryAcquire 成功分支内、:194 在 :186 成功之后），
        // 所以不存在"非持锁者误删活锁"的路径。
        try {
            File locks = new File(dir, "locks");
            locks.mkdirs();
            File f = new File(locks, "repo-a.lock");
            Assert.assertTrue(f.createNewFile());
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write("{\"inst");   // 截断的 JSON
            }

            BuildLockManager m = new BuildLockManager(dir, 60_000L);
            m.release("repo-a");
            Assert.assertFalse(f.exists(), "内容损坏的锁应由 release 清理");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void lockHolderReturnsNullWhenNoLock() {
        try {
            BuildLockManager m = new BuildLockManager(dir, 60_000L);
            Assert.assertNull(m.lockHolder("never-locked"), "无锁时 lockHolder 应为 null");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void instanceIdIsStableAndNonEmpty() {
        try {
            BuildLockManager m1 = new BuildLockManager(dir, 60_000L);
            BuildLockManager m2 = new BuildLockManager(dir, 60_000L);
            String id = m1.getInstanceId();
            Assert.assertNotNull(id);
            Assert.assertFalse(id.isEmpty(), "实例标识不能为空");
            Assert.assertEquals(m1.getInstanceId(), m2.getInstanceId(),
                    "同机两个 manager 的实例标识应一致（同 host:pid）");
        } finally {
            cleanup(dir);
        }
    }
}
