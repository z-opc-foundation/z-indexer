package com.zifang.z.indexer.registry;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.net.InetAddress;

/**
 * 分布式构建锁：NAS 共享目录 {@code locks/<repoCode>.lock} 文件协调多实例。
 * <p>
 * 多实例 indexer 挂载同一 NAS（ReadWriteMany），同一仓库同一时刻只允许一台机器构建。
 * 抢锁即原子创建锁文件（{@code File.createNewFile()} 在 NFS 上为原子操作）；
 * 锁内容记录持有实例与时间，供监控和 stale 判定。
 * <p>
 * 实例宕机可能留下死锁：锁文件 mtime 超过 staleMs 视为可抢占
 * （默认构建超时 ×2 + 10 分钟缓冲，覆盖 clone+compile 最长路径）。
 */
public class BuildLockManager {

    private static final Logger log = LoggerFactory.getLogger(BuildLockManager.class);

    private final File locksDir;

    private final String instanceId;

    private final long staleMs;

    public BuildLockManager(File indexerDir, long staleMs) {
        this.locksDir = new File(indexerDir, "locks");
        this.locksDir.mkdirs();
        this.staleMs = staleMs;
        this.instanceId = resolveInstanceId();
    }

    /** 实例标识：hostname:pid（日志与锁文件可观测）。 */
    private static String resolveInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + ":" + ProcessHandlePidHolder.PID;
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 尝试抢锁：锁不存在则原子创建并写入持有者信息；
     * 锁存在但已 stale（超时）则删除重建（处理实例宕机留下的死锁）。
     *
     * @return true=本实例获得构建权
     */
    public boolean tryAcquire(String repoCode) {
        File lock = lockFile(repoCode);
        try {
            if (lock.createNewFile()) {
                writeHolder(lock);
                return true;
            }
            // 已有锁：stale 检测（mtime 超时视为持有实例已死）
            long age = System.currentTimeMillis() - lock.lastModified();
            if (age > staleMs) {
                log.warn("[BuildLock] repo={} lock stale ({}ms > {}ms, holder={}), takeover",
                        repoCode, age, staleMs, readHolder(lock));
                if (lock.delete() && lock.createNewFile()) {
                    writeHolder(lock);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // NFS 异常按抢锁失败处理（宁可慢不可重复构建）
            log.warn("[BuildLock] repo={} acquire error: {}", repoCode, e.getMessage());
            return false;
        }
    }

    /** 释放锁（仅当持有者仍是本实例，防止删掉他人新抢的锁）。 */
    public void release(String repoCode) {
        File lock = lockFile(repoCode);
        try {
            if (!lock.exists()) {
                return;
            }
            JsonObject holder = readHolder(lock);
            if (holder != null && instanceId.equals(holder.getString("instance"))) {
                lock.delete();
            } else if (holder == null) {
                // 内容损坏的锁直接清（保守：非本实例内容完整则不删）
                lock.delete();
            }
        } catch (Exception e) {
            log.warn("[BuildLock] repo={} release error: {}", repoCode, e.getMessage());
        }
    }

    /** 当前锁持有实例（监控用；无锁返回 null）。 */
    public String lockHolder(String repoCode) {
        File lock = lockFile(repoCode);
        if (!lock.exists()) {
            return null;
        }
        JsonObject holder = readHolder(lock);
        return holder == null ? "?" : holder.getString("instance");
    }

    /**
     * 锁不存在或已 stale：视为可构建。
     * <p>BUILDING 状态但锁已不在（实例宕机残留）时重新触发重建的依据；
     * 隐含语义：无锁 ≠ 无人构建（创建锁前的窗口），仅用于 BUILDING 恢复判定，
     * 不能单独作为抢锁依据。
     */
    public boolean isStaleOrFree(String repoCode) {
        File lock = lockFile(repoCode);
        if (!lock.exists()) {
            return true;
        }
        return System.currentTimeMillis() - lock.lastModified() > staleMs;
    }

    private File lockFile(String repoCode) {
        return new File(locksDir, repoCode + ".lock");
    }

    private void writeHolder(File lock) throws Exception {
        JsonObject o = new JsonObject();
        o.put("instance", instanceId);
        o.put("acquiredAt", System.currentTimeMillis());
        FileWriter w = new FileWriter(lock);
        try {
            w.write(o.toString());
        } finally {
            w.close();
        }
    }

    private JsonObject readHolder(File lock) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(lock),
                            java.nio.charset.StandardCharsets.UTF_8));
            try {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[256];
                int n;
                while ((n = reader.read(buf)) >= 0) {
                    sb.append(buf, 0, n);
                }
                return JsonUtil.parseObject(sb.toString());
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Java 8 兼容的 pid 获取（ProcessHandle 是 9+）。 */
    private static final class ProcessHandlePidHolder {
        static final long PID = resolvePid();

        static long resolvePid() {
            try {
                String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
                return Long.parseLong(name.split("@")[0]);
            } catch (Exception e) {
                return -1L;
            }
        }
    }
}
