package com.zifang.z.indexer.query;

import com.zifang.z.indexer.IndexerConfig;
import com.zifang.z.indexer.registry.IndexRepo;
import com.zifang.z.indexer.registry.RegistryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 查询服务：按仓库懒加载 RepoIndex（LRU 驱逐），构建完成后 meta/文件 mtime 变化触发后台热重建，
 * 重建期间旧索引继续服务（读不阻塞）。
 */
public class IndexQueryService {

    private static final Logger log = LoggerFactory.getLogger(IndexQueryService.class);

    private final IndexerConfig config;
    private final RegistryStore registryStore;

    /** accessOrder LRU：repoCode → RepoIndex */
    private final LinkedHashMap<String, RepoIndex> lru;

    /** 热重建单线程（避免多仓库同时 reload 抢 IO） */
    private final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor();

    /** 冷加载单线程（与热重建分开：大仓冷加载 10-60s，不能阻塞 meta 变更热重建） */
    private final ExecutorService coldLoadExecutor = Executors.newSingleThreadExecutor();

    /** 冷加载去重：正在后台加载的 repoCode（实证：同一冷仓的三个并发调用全部
     * 同步加载超 10s 被工具层超时拦截，调用方 7 分钟后回来才热；改异步后首次查询立即返回
     * loading 提示，稍后重试即热） */
    private final Set<String> loadingRepos = new HashSet<String>();

    private final Object lruLock = new Object();

    /** repoCode → 当前磁盘 mtime 快照（判断是否需要热重建） */
    private final Map<String, Long> loadedFileMillis = new HashMap<String, Long>();

    public IndexQueryService(IndexerConfig config, RegistryStore registryStore) {
        this.config = config;
        this.registryStore = registryStore;
        this.lru = new LinkedHashMap<String, RepoIndex>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RepoIndex> eldest) {
                return size() > Math.max(1, config.getLruMaxRepos());
            }
        };
    }

    /** LRU 内当前常驻仓库（监控用）。 */
    public List<String> residentRepos() {
        synchronized (lruLock) {
            return new ArrayList<String>(lru.keySet());
        }
    }

    /**
     * 构建让路：驱逐最旧冷仓直到常驻 ≤ maxRepos，返回被驱逐列表（给构建日志归因）。
     * <p>仅移除引用，正在查询的线程持有的 RepoIndex 继续用完自然回收；
     * 构建结束后不主动回填——查询触发懒加载自然回常驻。
     */
    public List<String> shrinkTo(int maxRepos) {
        List<String> evicted = new ArrayList<String>();
        synchronized (lruLock) {
            while (lru.size() > maxRepos) {
                String eldest = lru.keySet().iterator().next();
                lru.remove(eldest);
                loadedFileMillis.remove(eldest);
                evicted.add(eldest);
            }
        }
        return evicted;
    }

    /** 查询入口：确保仓库索引可用（懒加载 + mtime 检查触发后台热重建）。 */
    public RepoIndex getOrLoad(String repoCode) {
        File jsonl = jsonlFile(repoCode);
        if (!jsonl.exists()) {
            return null;
        }
        RepoIndex idx = peek(repoCode);
        long diskMillis = jsonl.lastModified();
        if (idx == null || idx.getIndexFileMillis() != diskMillis) {
            // 首次加载同步（保证查询可用）；已有旧数据则后台热重建
            if (idx == null) {
                idx = RepoIndex.load(repoCode, jsonl, metaFile(repoCode));
                put(repoCode, idx);
            } else {
                triggerReload(repoCode, diskMillis);
            }
        }
        return idx;
    }

    private RepoIndex peek(String repoCode) {
        synchronized (lruLock) {
            return lru.get(repoCode);
        }
    }

    private void put(String repoCode, RepoIndex idx) {
        synchronized (lruLock) {
            lru.put(repoCode, idx);
            loadedFileMillis.put(repoCode, idx.getIndexFileMillis());
        }
    }

    private void triggerReload(final String repoCode, final long diskMillis) {
        if (!markReloadScheduled(repoCode, diskMillis)) {
            return; // 已在重建或已完成
        }
        reloadExecutor.submit(new Runnable() {
            public void run() {
                try {
                    RepoIndex fresh = RepoIndex.load(repoCode, jsonlFile(repoCode), metaFile(repoCode));
                    put(repoCode, fresh);
                    log.info("[IndexQuery] hot reloaded repo={} commit={} lines={}",
                            repoCode, fresh.getIndexedCommit(), fresh.getTotalLines());
                } catch (Exception e) {
                    log.warn("[IndexQuery] hot reload failed repo={}: {}", repoCode, e.getMessage());
                }
            }
        });
    }

    /**
     * 热重建去重闸：同一 {@code repoCode + diskMillis} 只有第一次返回 true。
     * <p>
     * 「检查 + 记账」必须在同一个临界区里。{@code loadedFileMillis} 是普通
     * {@link HashMap}，{@link #put}/{@link #shrinkTo}/{@link #invalidate} 都在
     * {@code lruLock} 下改它，而本方法跑在<b>请求线程</b>上（{@link #getOrLoad} 的
     * 入口），若无锁则与那三处并发访问同一张表：去重会漏（同一 millis 重复排多次
     * 热重建，而 {@code RepoIndex.load} 要把整个索引读进内存），更坏的是
     * HashMap 在并发 put/扩容下结构损坏。
     * <p>
     * 包级可见以便直接测这条去重不变量。
     *
     * @return true=本次调用赢得调度权，应提交一次热重建
     */
    boolean markReloadScheduled(String repoCode, long diskMillis) {
        synchronized (lruLock) {
            Long known = loadedFileMillis.get(repoCode);
            if (known != null && known == diskMillis) {
                return false;
            }
            loadedFileMillis.put(repoCode, diskMillis);
            return true;
        }
    }

    /** 主动失效（构建完成后由调度器调用，等价于下次查询时懒检查）。 */
    public void invalidate(String repoCode) {
        synchronized (lruLock) {
            lru.remove(repoCode);
            loadedFileMillis.remove(repoCode);
        }
    }

    // ── 冷加载状态机（生产查询入口先 acquireState，避免同步加载阻塞请求线程） ──

    public static final String STATE_RESIDENT = "RESIDENT";
    public static final String STATE_LOADING = "LOADING";
    public static final String STATE_NOT_INDEXED = "NOT_INDEXED";

    /**
     * 查询前置状态检查：RESIDENT 已常驻（mtime 变化则后台热重建，旧索引继续服务）；
     * LOADING 磁盘有索引但未加载，已提交后台加载（大仓约 30-60s），调用方应提示稍后重试；
     * NOT_INDEXED 磁盘无索引文件。
     */
    public String acquireState(String repoCode) {
        File jsonl = jsonlFile(repoCode);
        if (!jsonl.exists()) {
            return STATE_NOT_INDEXED;
        }
        RepoIndex idx = peek(repoCode);
        if (idx != null) {
            long diskMillis = jsonl.lastModified();
            if (idx.getIndexFileMillis() != diskMillis) {
                triggerReload(repoCode, diskMillis);
            }
            return STATE_RESIDENT;
        }
        submitColdLoad(repoCode);
        return STATE_LOADING;
    }

    /** 构建成功后预热：后台加载新索引（走冷加载队列串行，避免与 NAS IO 争抢）。 */
    public void preloadAsync(String repoCode) {
        File jsonl = jsonlFile(repoCode);
        if (!jsonl.exists()) {
            return;
        }
        RepoIndex idx = peek(repoCode);
        if (idx != null && idx.getIndexFileMillis() == jsonl.lastModified()) {
            return; // 已是最新
        }
        submitColdLoad(repoCode);
    }

    /** 启动预热用：当前线程同步加载（warmup 线程自身串行，便于日志按序）。 */
    public void preloadSync(String repoCode) {
        File jsonl = jsonlFile(repoCode);
        if (!jsonl.exists()) {
            return;
        }
        RepoIndex idx = peek(repoCode);
        if (idx != null && idx.getIndexFileMillis() == jsonl.lastModified()) {
            return;
        }
        try {
            RepoIndex fresh = RepoIndex.load(repoCode, jsonl, metaFile(repoCode));
            put(repoCode, fresh);
            log.info("[IndexQuery] preloaded repo={} commit={} lines={}",
                    repoCode, fresh.getIndexedCommit(), fresh.getTotalLines());
        } catch (Exception e) {
            log.warn("[IndexQuery] preload failed repo={}: {}", repoCode, e.getMessage());
        }
    }

    /** 提交后台冷加载（loadingRepos 去重；失败也移除标记，下次查询可重触发）。 */
    private void submitColdLoad(final String repoCode) {
        synchronized (loadingRepos) {
            if (loadingRepos.contains(repoCode)) {
                return;
            }
            loadingRepos.add(repoCode);
        }
        coldLoadExecutor.submit(new Runnable() {
            public void run() {
                try {
                    RepoIndex fresh = RepoIndex.load(repoCode, jsonlFile(repoCode), metaFile(repoCode));
                    put(repoCode, fresh);
                    log.info("[IndexQuery] cold loaded repo={} commit={} lines={}",
                            repoCode, fresh.getIndexedCommit(), fresh.getTotalLines());
                } catch (Exception e) {
                    log.warn("[IndexQuery] cold load failed repo={}: {}", repoCode, e.getMessage());
                } finally {
                    synchronized (loadingRepos) {
                        loadingRepos.remove(repoCode);
                    }
                }
            }
        });
    }

    private File jsonlFile(String repoCode) {
        return new File(config.getReposRoot(), "indexer/" + repoCode + ".jsonl");
    }

    private File metaFile(String repoCode) {
        return new File(config.getReposRoot(), "indexer/" + repoCode + ".meta.json");
    }

    /**
     * 仓库当前索引的产出链路（COMPILED/STATIC）：读 meta 文件，轻量不触发加载。
     * 旧 meta 无字段视为 COMPILED；meta 缺失返回 null（未索引）。
     */
    public String indexTypeOf(String repoCode) {
        File meta = metaFile(repoCode);
        if (!meta.exists()) {
            return null;
        }
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(meta), java.nio.charset.StandardCharsets.UTF_8));
            try {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[2048];
                int n;
                while ((n = reader.read(buf)) >= 0) {
                    sb.append(buf, 0, n);
                }
                com.zifang.util.json.model.JsonObject obj = com.zifang.util.json.JsonUtil.parseObject(sb.toString());
                String t = obj.getString("indexType");
                return (t == null || t.isEmpty()) ? "COMPILED" : t;
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            return "COMPILED";
        }
    }

    /** 符号搜索。 */
    public List<RepoIndex.Def> searchSymbols(String repoCode, String query, int limit, boolean includeAccessors) {
        RepoIndex idx = getOrLoad(repoCode);
        if (idx == null) {
            return Collections.emptyList();
        }
        return idx.searchSymbols(query, limit <= 0 ? 20 : limit, includeAccessors);
    }

    /** 调用链（谁调用了它），BFS 深度展开。 */
    public Map<String, Object> callers(String repoCode, String symbol, int depth, boolean includeAccessors) {
        return chain(repoCode, symbol, depth, includeAccessors, true);
    }

    /** 被调链（它调用了谁），BFS 深度展开。 */
    public Map<String, Object> callees(String repoCode, String symbol, int depth, boolean includeAccessors) {
        return chain(repoCode, symbol, depth, includeAccessors, false);
    }

    private Map<String, Object> chain(String repoCode, String symbol, int depth,
                                      boolean includeAccessors, boolean incomingDirection) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        RepoIndex idx = getOrLoad(repoCode);
        if (idx == null) {
            result.put("error", "repo not indexed: " + repoCode);
            return result;
        }
        int maxDepth = depth <= 0 ? config.getDefaultDepth() : Math.min(depth, 5);
        int nodeLimit = config.getMaxResultNodes();

        // 入口解析：精确命中 or 包含匹配（取第一个精确的，否则模糊候选中第一个）
        List<RepoIndex.Def> candidates = idx.resolveSymbols(symbol, includeAccessors);
        RepoIndex.Def entry = null;
        for (RepoIndex.Def d : candidates) {
            if (d.sym.equals(symbol)) {
                entry = d;
                break;
            }
        }
        if (entry == null && !candidates.isEmpty()) {
            entry = candidates.get(0);
        }
        if (entry == null) {
            result.put("error", "symbol not found: " + symbol);
            return result;
        }

        List<Map<String, Object>> roots = new ArrayList<Map<String, Object>>();
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("sym", entry.sym);
        root.put("file", entry.file);
        root.put("line", entry.line);
        root.put("depth", 0);
        roots.add(root);
        result.put("repo", repoCode);
        result.put("indexedCommit", idx.getIndexedCommit());
        result.put("indexedRef", idx.getIndexedRef());
        result.put("indexType", idx.getIndexType());
        result.put("entry", roots);

        // BFS
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        Set<String> visited = new HashSet<String>();
        visited.add(entry.sym);
        List<String> frontier = Collections.singletonList(entry.sym);
        int totalNodes = 1;
        boolean truncated = false;
        for (int d = 1; d <= maxDepth; d++) {
            List<String> next = new ArrayList<String>();
            for (String cur : frontier) {
                List<RepoIndex.Call> calls = incomingDirection ? idx.incomingCalls(cur) : idx.outgoingCalls(cur);
                for (RepoIndex.Call call : calls) {
                    String other = incomingDirection ? call.from : call.to;
                    if (other == null || "?".equals(other)) {
                        continue;
                    }
                    Map<String, Object> edge = new LinkedHashMap<String, Object>();
                    // 底层 Call 统一 from=调用者 / to=被调者，两个方向输出保持同一语义
                    //（此前 outgoing 分支翻转过方向，导致 callees 返回“被调者→根”的反向边）
                    edge.put("from", call.from);
                    edge.put("to", call.to);
                    edge.put("file", call.file);
                    edge.put("line", call.line);
                    edge.put("depth", d);
                    edges.add(edge);
                    if (!visited.add(other)) {
                        continue;
                    }
                    totalNodes++;
                    if (totalNodes > nodeLimit) {
                        truncated = true;
                        continue;
                    }
                    next.add(other);
                }
            }
            if (next.isEmpty() || truncated) {
                break;
            }
            frontier = next;
        }
        result.put("edges", edges);
        result.put("nodeCount", totalNodes);
        result.put("truncated", truncated);
        return result;
    }

    /** 可查询仓库列表（含索引元信息）。 */
    public List<Map<String, Object>> listQueryableRepos() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (IndexRepo repo : registryStore.listRepos()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("repoCode", repo.getRepoCode());
            m.put("repoName", repo.getRepoName());
            m.put("refMode", repo.getRefMode());
            m.put("refValue", repo.getRefValue());
            m.put("lastBuildStatus", repo.getLastBuildStatus());
            m.put("lastIndexedCommit", repo.getLastIndexedCommit());
            m.put("lastIndexedRef", repo.getLastIndexedRef());
            m.put("lastIndexLines", repo.getLastIndexLines());
            m.put("lastIndexType", repo.getLastIndexType() == null || repo.getLastIndexType().isEmpty()
                    ? "COMPILED" : repo.getLastIndexType());
            m.put("lastBuildTime", repo.getLastBuildTime());
            File jsonl = jsonlFile(repo.getRepoCode());
            m.put("indexed", jsonl.exists());
            RepoIndex resident = peek(repo.getRepoCode());
            if (resident != null) {
                m.put("residentSymbols", resident.getSymbolCount());
                m.put("residentCalls", resident.getCallCount());
            }
            result.add(m);
        }
        return result;
    }
}
