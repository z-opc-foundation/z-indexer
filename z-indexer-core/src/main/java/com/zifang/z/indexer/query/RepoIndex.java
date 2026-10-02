package com.zifang.z.indexer.query;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单仓库内存倒排索引。
 * <p>加载 JSONL 产物构建：符号表（def）、入边表（callee → 谁调它）、出边表（caller → 调了谁）。
 * 全部引用不可变结构，热重建时整体原子替换，查询线程无需加锁。
 */
public class RepoIndex {

    private static final Logger log = LoggerFactory.getLogger(RepoIndex.class);

    public static class Def {
        public final String kind;
        public final String sym;
        public final String file;
        public final int line;

        public Def(String kind, String sym, String file, int line) {
            this.kind = kind;
            this.sym = sym;
            this.file = file;
            this.line = line;
        }
    }

    public static class Call {
        public final String from;
        public final String to;
        public final String file;
        public final int line;

        public Call(String from, String to, String file, int line) {
            this.from = from;
            this.to = to;
            this.file = file;
            this.line = line;
        }
    }

    /**
     * 加载期字符串复用池：jsonl 每行独立解析，边表 from/to/file 与符号表 sym/kind 高度重复
     * （同一 FQN 在每条边里都是新拷贝，Java 8 无 compact strings，120 字符 ≈300B，
     * 某个 53 万边的大仓不规范化 ≈440MB——预热 6 大仓 OOM 的实测根因；
     * 复用后仅保留唯一串，同仓降至 ~60MB，7-8 倍）。池本身加载完即可 GC。
     */
    private static final class StringPool {
        private final HashMap<String, String> map = new HashMap<String, String>();

        String canon(String s) {
            if (s == null) {
                return null;
            }
            String c = map.get(s);
            if (c == null) {
                map.put(s, s);
                c = s;
            }
            return c;
        }
    }

    private final String repoCode;
    private final long loadedAtMillis;
    private final long indexFileMillis;
    private final String indexedCommit;
    private final String indexedRef;
    /** 产出链路：COMPILED（编译期）/ STATIC（免编译静态）；旧 meta 无此字段视为 COMPILED */
    private final String indexType;
    private final long totalLines;

    private final Map<String, List<Def>> symbolDefs;
    private final List<String> symbolList;
    private final Map<String, List<Call>> incoming;
    private final Map<String, List<Call>> outgoing;

    private RepoIndex(String repoCode, long indexFileMillis, String indexedCommit, String indexedRef,
                      String indexType, long totalLines, Map<String, List<Def>> symbolDefs, List<String> symbolList,
                      Map<String, List<Call>> incoming, Map<String, List<Call>> outgoing) {
        this.repoCode = repoCode;
        this.loadedAtMillis = System.currentTimeMillis();
        this.indexFileMillis = indexFileMillis;
        this.indexedCommit = indexedCommit;
        this.indexedRef = indexedRef;
        this.indexType = indexType;
        this.totalLines = totalLines;
        this.symbolDefs = symbolDefs;
        this.symbolList = symbolList;
        this.incoming = incoming;
        this.outgoing = outgoing;
    }

    /** 从 jsonl + meta 构建内存索引（不抛异常，产出日志）。 */
    public static RepoIndex load(String repoCode, File jsonlFile, File metaFile) {
        Map<String, List<Def>> symbolDefs = new HashMap<String, List<Def>>();
        List<String> symbolList = new ArrayList<String>(16384);
        Map<String, List<Call>> incoming = new HashMap<String, List<Call>>(65536);
        Map<String, List<Call>> outgoing = new HashMap<String, List<Call>>(65536);

        String indexedCommit = null;
        String indexedRef = null;
        String indexType = "COMPILED";
        long indexFileMillis = jsonlFile.exists() ? jsonlFile.lastModified() : 0L;
        if (metaFile.exists()) {
            try {
                String meta = readAll(metaFile);
                JsonObject obj = JsonUtil.parseObject(meta);
                indexedCommit = obj.getString("commit");
                indexedRef = obj.getString("ref");
                String t = obj.getString("indexType");
                if (t != null && !t.isEmpty()) {
                    indexType = t;
                }
            } catch (Exception ignore) {
                // meta 损坏不阻塞查询
            }
        }

        long lines = 0;
        long t0 = System.currentTimeMillis();
        StringPool pool = new StringPool();
        if (jsonlFile.exists()) {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(jsonlFile), StandardCharsets.UTF_8), 1 << 16);
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines++;
                        if (line.isEmpty()) {
                            continue;
                        }
                        JsonObject obj;
                        try {
                            obj = JsonUtil.parseObject(line);
                        } catch (Exception ignore) {
                            continue;
                        }
                        String t = obj.getString("t");
                        if ("def".equals(t)) {
                            String sym = pool.canon(obj.getString("sym"));
                            Integer lineVal = obj.getInt("line");
                            Def def = new Def(pool.canon(obj.getString("kind")), sym,
                                    pool.canon(obj.getString("file")), lineVal == null ? 0 : lineVal);
                            List<Def> defs = symbolDefs.get(sym);
                            if (defs == null) {
                                defs = new ArrayList<Def>(1);
                                symbolDefs.put(sym, defs);
                                symbolList.add(sym);
                            }
                            defs.add(def);
                        } else if ("call".equals(t)) {
                            String from = pool.canon(obj.getString("from"));
                            String to = pool.canon(obj.getString("to"));
                            Integer lineVal = obj.getInt("line");
                            Call call = new Call(from, to,
                                    pool.canon(obj.getString("file")), lineVal == null ? 0 : lineVal);
                            List<Call> in = incoming.get(to);
                            if (in == null) {
                                in = new ArrayList<Call>(4);
                                incoming.put(to, in);
                            }
                            in.add(call);
                            if (from != null && !"?".equals(from)) {
                                List<Call> out = outgoing.get(from);
                                if (out == null) {
                                    out = new ArrayList<Call>(8);
                                    outgoing.put(from, out);
                                }
                                out.add(call);
                            }
                        }
                    }
                } finally {
                    reader.close();
                }
            } catch (Exception e) {
                log.warn("[RepoIndex] failed loading {}: {}", jsonlFile.getAbsolutePath(), e.getMessage());
            }
        }
        log.info("[RepoIndex] repo={} loaded: defs={}, calls(in)={}, lines={}, cost={}ms",
                repoCode, symbolDefs.size(), incoming.size(), lines, System.currentTimeMillis() - t0);
        return new RepoIndex(repoCode, indexFileMillis, indexedCommit, indexedRef, indexType, lines,
                symbolDefs, symbolList, incoming, outgoing);
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

    public String getRepoCode() {
        return repoCode;
    }

    public long getLoadedAtMillis() {
        return loadedAtMillis;
    }

    public long getIndexFileMillis() {
        return indexFileMillis;
    }

    public String getIndexedCommit() {
        return indexedCommit;
    }

    public String getIndexedRef() {
        return indexedRef;
    }

    public String getIndexType() {
        return indexType;
    }

    public long getTotalLines() {
        return totalLines;
    }

    public int getSymbolCount() {
        return symbolDefs.size();
    }

    public long getCallCount() {
        long n = 0;
        for (List<Call> calls : incoming.values()) {
            n += calls.size();
        }
        return n;
    }

    /** 符号搜索（包含匹配，大小写不敏感）。 */
    public List<Def> searchSymbols(String query, int limit, boolean includeAccessors) {
        String q = query == null ? "" : query.toLowerCase();
        List<Def> result = new ArrayList<Def>();
        // 调用边 to 指向的符号（如接口方法）可能没有 def 记录（外部依赖），也允许命中符号表补 def
        int symbolListSize = symbolList.size();
        for (int i = 0; i < symbolListSize && result.size() < limit; i++) {
            String sym = symbolList.get(i);
            if (q.isEmpty() || sym.toLowerCase().contains(q)) {
                for (Def def : symbolDefs.get(sym)) {
                    if (!includeAccessors && isAccessor(def)) {
                        continue;
                    }
                    result.add(def);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    /** 命中判定（调用链入口模糊匹配：包含即可）。 */
    public List<Def> resolveSymbols(String query, boolean includeAccessors) {
        return searchSymbols(query, 50, includeAccessors);
    }

    public List<Call> incomingCalls(String sym) {
        List<Call> calls = incoming.get(sym);
        return calls == null ? new ArrayList<Call>(0) : calls;
    }

    public List<Call> outgoingCalls(String sym) {
        List<Call> calls = outgoing.get(sym);
        return calls == null ? new ArrayList<Call>(0) : calls;
    }

    public boolean hasSymbol(String sym) {
        return symbolDefs.containsKey(sym);
    }

    /** 索引符号是否为噪音（Lombok/IDE 生成的 getter/setter 等）。 */
    public static boolean isAccessor(Def def) {
        if (!"method".equals(def.kind)) {
            return false;
        }
        String name = simpleName(def.sym);
        if (name.startsWith("<init>") || name.equals("toString") || name.equals("hashCode")
                || name.equals("equals") || name.equals("canEqual")) {
            return true;
        }
        if ((name.startsWith("get") || name.startsWith("set") || name.startsWith("is"))
                && name.length() > 3) {
            // getter/setter/isXxx：无参或单参命名特征不再细判，统一视为访问器
            return true;
        }
        return false;
    }

    /** 提取 FQN#method(...) 中的方法名。 */
    public static String simpleName(String sym) {
        if (sym == null) {
            return "";
        }
        int hash = sym.indexOf('#');
        if (hash < 0) {
            int dot = sym.lastIndexOf('.');
            return dot < 0 ? sym : sym.substring(dot + 1);
        }
        String rest = sym.substring(hash + 1);
        int paren = rest.indexOf('(');
        return paren < 0 ? rest : rest.substring(0, paren);
    }

    /** 提取 FQN#method(...) 中的 FQN。 */
    public static String ownerType(String sym) {
        if (sym == null) {
            return "";
        }
        int hash = sym.indexOf('#');
        return hash < 0 ? sym : sym.substring(0, hash);
    }
}
