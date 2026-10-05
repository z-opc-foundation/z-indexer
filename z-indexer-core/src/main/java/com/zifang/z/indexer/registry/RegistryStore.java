package com.zifang.z.indexer.registry;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册表 + 构建历史的文件存储。
 * <p>注册表：{@code repos/indexer/registry.json}（原子替换写，静态配置真源）。
 * 构建状态：{@code repos/indexer/status/<repoCode>.json}（每仓一文件，构建机独占写）。
 * 构建历史：{@code repos/indexer/build-log.jsonl}（追加写，全量审计），
 * 内存 ring 保留最近 {@link #RING_SIZE} 条供监控查询。
 * <p>零 DB 依赖；多实例共用同一 NAS 时：同一仓库构建状态由构建实例写入
 * 单仓 status 文件（无覆盖冲突），persistRegistry 写前先 refresh 合并磁盘最新状态，
 * 保证不覆盖他机刚写入的状态。
 */
public class RegistryStore {

    private static final Logger log = LoggerFactory.getLogger(RegistryStore.class);

    private static final int RING_SIZE = 500;

    private final File registryFile;

    private final File statusDir;

    private final File buildLogFile;

    private final Object lock = new Object();

    private final Map<String, IndexRepo> repos = new LinkedHashMap<String, IndexRepo>();

    private final Deque<BuildLogEntry> recentBuilds = new ArrayDeque<BuildLogEntry>(RING_SIZE);

    /** status 文件 mtime+size 短路戳（repoCode → stamp）。所有访问均在 lock 内；
     *  未变化文件跳过重读+解析——百仓级 /repos 从 1.4-4s 降到 <100ms，实时性不变
     *  （stat 每次都做，文件一变下一次请求立即感知）。 */
    private final Map<String, Long> statusStamps = new HashMap<String, Long>();

    public RegistryStore(File indexerDir) {
        this.registryFile = new File(indexerDir, "registry.json");
        this.statusDir = new File(indexerDir, "status");
        this.statusDir.mkdirs();
        this.buildLogFile = new File(indexerDir, "build-log.jsonl");
        load();
        loadStatuses();
    }

    /** repoCode 允许的字符集（同时用于文件名与目录名，故不含路径分隔符）。 */
    static final String REPO_CODE_PATTERN = "[A-Za-z0-9._-]+";

    /**
     * 校验 repoCode 可安全用作文件名 / 目录名。
     * <p>
     * 字符集之外<b>必须</b>显式排除 {@code "."} 与 {@code ".."}：二者完全落在
     * {@link #REPO_CODE_PATTERN} 内，但 {@code new File(reposDir, "..")} 规范化后
     * 就是 reposRoot 本身（实测 escaped），工作副本目录会被指到 indexer 数据根之外 ——
     * 而 clone 目标、indexer/*.jsonl、build-logs/*.log、locks/*.lock、status/*.json
     * 等十余处路径都用 repoCode 直接拼。
     *
     * @throws IllegalArgumentException 非法时
     */
    static void validateRepoCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("repoCode required");
        }
        if (!code.matches(REPO_CODE_PATTERN)) {
            throw new IllegalArgumentException("repoCode only allow [A-Za-z0-9._-]: " + code);
        }
        if (".".equals(code) || "..".equals(code)) {
            throw new IllegalArgumentException(
                    "repoCode must not be a relative path segment (. or ..): " + code);
        }
    }

    private void load() {
        try {
            if (registryFile.exists()) {
                String content = readAll(registryFile);
                JsonObject root = JsonUtil.parseObject(content);
                JsonArray arr = root.getJsonArray("repos");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        IndexRepo repo = JsonUtil.fromJson(arr.get(i).toString(), IndexRepo.class);
                        if (repo == null || repo.getRepoCode() == null) {
                            continue;
                        }
                        try {
                            validateRepoCode(repo.getRepoCode());
                        } catch (IllegalArgumentException e) {
                            // registry.json 是可手工编辑的静态配置真源，非法条目跳过并告警：
                            // 不能让一条坏数据把整个 indexer 拖到启动失败，也不能让它
                            // 带着越界的 repoCode 进内存表被轮询器当真执行。
                            // 跳过后下次 persistRegistry 会把该条目从磁盘抹掉（自愈）。
                            log.warn("[RegistryStore] skip illegal repo entry: {}", e.getMessage());
                            continue;
                        }
                        repos.put(repo.getRepoCode(), repo);
                    }
                }
                log.info("[RegistryStore] loaded {} repos from {}", repos.size(), registryFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("[RegistryStore] failed to load registry, start empty: {}", e.getMessage());
        }
        try {
            if (buildLogFile.exists()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(buildLogFile), StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) {
                            continue;
                        }
                        try {
                            BuildLogEntry entry = JsonUtil.fromJson(line, BuildLogEntry.class);
                            if (entry != null) {
                                offerRing(entry);
                            }
                        } catch (Exception ignore) {
                            // 损坏行跳过
                        }
                    }
                } finally {
                    reader.close();
                }
                log.info("[RegistryStore] loaded {} recent builds from {}", recentBuilds.size(),
                        buildLogFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("[RegistryStore] failed to load build log: {}", e.getMessage());
        }
    }

    private static String readAll(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    public List<IndexRepo> listRepos() {
        synchronized (lock) {
            refreshStatusesLocked();
            return new ArrayList<IndexRepo>(repos.values());
        }
    }

    public IndexRepo find(String repoCode) {
        synchronized (lock) {
            return repos.get(repoCode);
        }
    }

    /** 新增或更新注册条目（更新时保留构建状态字段）。 */
    public IndexRepo upsert(IndexRepo input) {
        synchronized (lock) {
            String code = input.getRepoCode();
            validateRepoCode(code);
            if (input.getGitUrl() == null || input.getGitUrl().trim().isEmpty()) {
                throw new IllegalArgumentException("gitUrl required");
            }
            long now = System.currentTimeMillis();
            IndexRepo merged = repos.get(code);
            if (merged == null) {
                merged = new IndexRepo();
                merged.setRepoCode(code);
                merged.setCreatedAt(now);
            }
            merged.setRepoName(input.getRepoName() == null ? code : input.getRepoName());
            merged.setGitUrl(input.getGitUrl().trim());
            merged.setRefMode(input.getRefMode() == null ? IndexRepo.REF_MODE_BRANCH : input.getRefMode());
            merged.setRefValue(input.getRefValue() == null ? "master" : input.getRefValue());
            merged.setLanguage(input.getLanguage() == null ? "" : input.getLanguage());
            merged.setEnabled(input.isEnabled());
            if (input.getRemark() != null) {
                merged.setRemark(input.getRemark());
            }
            merged.setUpdatedAt(now);
            repos.put(code, merged);
            persistRegistry();
            return merged;
        }
    }

    public boolean remove(String repoCode) {
        synchronized (lock) {
            IndexRepo removed = repos.remove(repoCode);
            statusStamps.remove(repoCode);
            if (removed != null) {
                persistRegistry();
                return true;
            }
            return false;
        }
    }

    /** 构建完成后回写状态（引用即为注册表内对象，仅负责落盘）。 */
    public void persistRegistry() {
        synchronized (lock) {
            try {
                // 写前先合并磁盘上其它实例写入的状态（多实例不覆盖他人刚写的构建结果）
                refreshStatusesLocked();
                File parent = registryFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                File tmp = new File(registryFile.getParentFile(), registryFile.getName() + ".tmp");
                JsonObject root = new JsonObject();
                root.put("schemaVersion", 1);
                JsonArray arr = new JsonArray();
                for (IndexRepo r : repos.values()) {
                    // POJO 必须先转 JsonObject：JsonArray 对未知类型直接 toString，
                    // 落盘得到 "IndexRepo@15db9742" 这类碎片，registry.json 再也读不回来
                    arr.add(JsonUtil.parseObject(JsonUtil.toJson(r)));
                }
                root.put("repos", arr);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8));
                try {
                    writer.write(JsonUtil.toJson(root));
                } finally {
                    writer.close();
                }
                if (!tmp.renameTo(registryFile)) {
                    registryFile.delete();
                    tmp.renameTo(registryFile);
                }
            } catch (Exception e) {
                log.error("[RegistryStore] failed to persist registry: {}", e.getMessage());
            }
        }
    }

    // ──── 分布式状态外置：status/<repoCode>.json 每仓一文件 ────

    /**
     * 单仓构建状态落盘（tmp+rename 原子替换）。
     * <p>多实例下每仓状态文件只由构建它的实例写入，无覆盖冲突；
     * registry.json 中的状态字段仅为本地快照，磁盘真源以 status/ 为准。
     */
    public void persistStatus(IndexRepo repo) {
        synchronized (lock) {
            try {
                File target = new File(statusDir, repo.getRepoCode() + ".json");
                File tmp = new File(statusDir, repo.getRepoCode() + ".json.tmp");
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8));
                try {
                    writer.write(JsonUtil.toJson(repo));
                } finally {
                    writer.close();
                }
                if (!tmp.renameTo(target)) {
                    target.delete();
                    tmp.renameTo(target);
                }
                // 写方同步记账，本实例下次 /repos 不再重读该文件
                statusStamps.put(repo.getRepoCode(), statusStampOf(target));
            } catch (Exception e) {
                log.warn("[RegistryStore] failed to persist status for {}: {}",
                        repo.getRepoCode(), e.getMessage());
            }
        }
    }

    /** 从 status/ 目录合并其它实例写入的构建状态到内存表（需持有 lock）。 */
    private void refreshStatusesLocked() {
        File[] files = statusDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".json")) {
                continue;
            }
            String code = name.substring(0, name.length() - 5);
            IndexRepo local = repos.get(code);
            if (local == null) {
                continue; // 静态真源在 registry.json；无注册条目的孤儿状态忽略
            }
            // mtime+size 未变即跳过重读（写方 persistStatus 同步记账；
            // 他机写入 mtime 必变，下一次请求立即感知，秒级精度内无陈旧窗口）
            Long stamp = statusStampOf(f);
            Long known = statusStamps.get(code);
            if (known != null && known.equals(stamp)) {
                continue;
            }
            try {
                IndexRepo snap = JsonUtil.fromJson(readAll(f), IndexRepo.class);
                if (snap != null) {
                    copyBuildStatus(snap, local);
                    statusStamps.put(code, stamp);
                }
            } catch (Exception ignore) {
                // 损坏的状态文件跳过，不影响其它仓
            }
        }
    }

    /** status 文件短路戳：mtime ^ (length << 20)，mtime 秒级精度由 size 互补。 */
    private static Long statusStampOf(File f) {
        long m = f.lastModified();
        long len = f.length();
        return m ^ (len << 20);
    }

    /** 启动时与 load() 配套：把磁盘状态合并进内存表。 */
    private void loadStatuses() {
        synchronized (lock) {
            refreshStatusesLocked();
        }
    }

    private static void copyBuildStatus(IndexRepo from, IndexRepo to) {
        to.setLastBuildStatus(from.getLastBuildStatus());
        to.setLastIndexedCommit(from.getLastIndexedCommit());
        to.setLastIndexedRef(from.getLastIndexedRef());
        to.setLastIndexLines(from.getLastIndexLines());
        to.setLastBuildDurationMs(from.getLastBuildDurationMs());
        to.setLastBuildTime(from.getLastBuildTime());
        to.setLastErrorMsg(from.getLastErrorMsg());
        to.setBuiltBy(from.getBuiltBy());
    }

    private void offerRing(BuildLogEntry entry) {
        if (recentBuilds.size() >= RING_SIZE) {
            recentBuilds.pollFirst();
        }
        recentBuilds.addLast(entry);
    }

    /** 追加构建历史（jsonl 全量 + 内存 ring）。 */
    public void appendBuildLog(BuildLogEntry entry) {
        synchronized (lock) {
            offerRing(entry);
            try {
                File parent = buildLogFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(buildLogFile, true), StandardCharsets.UTF_8));
                try {
                    writer.write(JsonUtil.toJson(entry));
                    writer.write("\n");
                } finally {
                    writer.close();
                }
            } catch (Exception e) {
                log.warn("[RegistryStore] failed to append build log: {}", e.getMessage());
            }
        }
    }

    /** 最近构建（新的在前），可按仓库过滤。 */
    public List<BuildLogEntry> recentBuilds(String repoCode, int limit) {
        synchronized (lock) {
            List<BuildLogEntry> result = new ArrayList<BuildLogEntry>();
            List<BuildLogEntry> snapshot = new ArrayList<BuildLogEntry>(recentBuilds);
            for (int i = snapshot.size() - 1; i >= 0 && result.size() < limit; i--) {
                BuildLogEntry e = snapshot.get(i);
                if (repoCode == null || repoCode.isEmpty() || repoCode.equals(e.getRepoCode())) {
                    result.add(e);
                }
            }
            return result;
        }
    }
}
