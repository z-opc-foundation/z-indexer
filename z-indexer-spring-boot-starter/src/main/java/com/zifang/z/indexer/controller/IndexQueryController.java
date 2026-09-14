package com.zifang.z.indexer.controller;

import com.zifang.z.indexer.query.IndexQueryService;
import com.zifang.z.indexer.query.RepoIndex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引查询 REST API（供 galaxy-proxy 工具 / agent / 排查脚本消费）。
 * <p>统一返回 {@code {"ok": true/false, ...}}。
 */
@RestController
@RequestMapping("/index/query")
public class IndexQueryController {

    @Autowired
    private IndexQueryService queryService;

    /** 可查询仓库列表 + 索引版本。 */
    @RequestMapping(value = "/repos", method = RequestMethod.GET)
    public Map<String, Object> repos() {
        Map<String, Object> resp = ok();
        resp.put("repos", queryService.listQueryableRepos());
        return resp;
    }

    /**
     * 符号搜索。
     * <p>body: {repoCode, query, limit?, includeAccessors?}
     */
    @RequestMapping(value = "/symbol", method = RequestMethod.POST)
    public Map<String, Object> symbol(@RequestBody Map<String, Object> body) {
        String repoCode = str(body.get("repoCode"));
        String query = str(body.get("query"));
        if (repoCode == null || repoCode.trim().isEmpty() || query == null || query.trim().isEmpty()) {
            return fail("repoCode and query required");
        }
        Map<String, Object> notReady = checkReady(repoCode.trim());
        if (notReady != null) {
            notReady.put("repoCode", repoCode.trim());
            notReady.put("query", query);
            return notReady;
        }
        int limit = intOf(body.get("limit"), 20);
        boolean includeAccessors = boolOf(body.get("includeAccessors"), false);
        List<RepoIndex.Def> defs = queryService.searchSymbols(repoCode.trim(), query.trim(), limit, includeAccessors);
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (RepoIndex.Def def : defs) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("kind", def.kind);
            m.put("sym", def.sym);
            m.put("type", RepoIndex.ownerType(def.sym));
            m.put("name", RepoIndex.simpleName(def.sym));
            m.put("file", def.file);
            m.put("line", def.line);
            results.add(m);
        }
        Map<String, Object> resp = ok();
        resp.put("repoCode", repoCode.trim());
        resp.put("query", query);
        resp.put("indexType", queryService.indexTypeOf(repoCode.trim()));
        resp.put("count", results.size());
        resp.put("results", results);
        return resp;
    }

    /**
     * 调用链（谁调用了它）。
     * <p>body: {repoCode, symbol, depth?, includeAccessors?}
     */
    @RequestMapping(value = "/callers", method = RequestMethod.POST)
    public Map<String, Object> callers(@RequestBody Map<String, Object> body) {
        return chain(body, true);
    }

    /**
     * 被调链（它调用了谁）。
     * <p>body: {repoCode, symbol, depth?, includeAccessors?}
     */
    @RequestMapping(value = "/callees", method = RequestMethod.POST)
    public Map<String, Object> callees(@RequestBody Map<String, Object> body) {
        return chain(body, false);
    }

    private Map<String, Object> chain(Map<String, Object> body, boolean incoming) {
        String repoCode = str(body.get("repoCode"));
        String symbol = str(body.get("symbol"));
        if (repoCode == null || repoCode.trim().isEmpty() || symbol == null || symbol.trim().isEmpty()) {
            return fail("repoCode and symbol required");
        }
        Map<String, Object> notReady = checkReady(repoCode.trim());
        if (notReady != null) {
            notReady.put("repoCode", repoCode);
            notReady.put("symbol", symbol);
            return notReady;
        }
        int depth = intOf(body.get("depth"), 0);
        boolean includeAccessors = boolOf(body.get("includeAccessors"), false);
        Map<String, Object> data = incoming
                ? queryService.callers(repoCode.trim(), symbol.trim(), depth, includeAccessors)
                : queryService.callees(repoCode.trim(), symbol.trim(), depth, includeAccessors);
        if (data.containsKey("error")) {
            Map<String, Object> resp = fail(String.valueOf(data.get("error")));
            resp.put("repoCode", repoCode);
            resp.put("symbol", symbol);
            return resp;
        }
        Map<String, Object> resp = ok();
        resp.putAll(data);
        return resp;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 查询前置状态检查（冷仓异步加载，避免同步加载阻塞请求线程 10s+ 被工具层超时拦截）：
     * LOADING → 返回 loading 响应（带重试提示）；NOT_INDEXED → 错误；null = 就绪可查。
     */
    private Map<String, Object> checkReady(String repoCode) {
        String state = queryService.acquireState(repoCode);
        if (IndexQueryService.STATE_LOADING.equals(state)) {
            Map<String, Object> resp = fail("repo index loading (cold start), retry the SAME query in ~30s");
            resp.put("loading", true);
            resp.put("retryAfterSeconds", 30);
            return resp;
        }
        if (IndexQueryService.STATE_NOT_INDEXED.equals(state)) {
            Map<String, Object> resp = fail("repo not indexed: " + repoCode);
            resp.put("notIndexed", true);
            return resp;
        }
        return null;
    }

    private static int intOf(Object o, int dft) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return dft;
        }
    }

    private static boolean boolOf(Object o, boolean dft) {
        if (o == null) {
            return dft;
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }
}
