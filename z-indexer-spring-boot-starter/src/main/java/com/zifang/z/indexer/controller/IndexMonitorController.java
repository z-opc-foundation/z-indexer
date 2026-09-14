package com.zifang.z.indexer.controller;

import com.zifang.z.indexer.pipeline.IndexBuildService;
import com.zifang.z.indexer.query.IndexQueryService;
import com.zifang.z.indexer.registry.BuildLogEntry;
import com.zifang.z.indexer.registry.IndexRepo;
import com.zifang.z.indexer.registry.RegistryStore;
import com.zifang.z.indexer.schedule.IndexSyncRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引服务监控 REST API（给 future-team 巡检 / 监控消费）。
 * <p>统一返回 {@code {"ok": true/false, ...}}。
 */
@RestController
@RequestMapping("/index/monitor")
public class IndexMonitorController {

    @Autowired
    private RegistryStore registryStore;

    @Autowired
    private IndexSyncRunner syncRunner;

    @Autowired
    private IndexQueryService queryService;

    @Autowired
    private IndexBuildService buildService;

    /**
     * 聚合概览：仓库状态分布 / 最近构建 / 索引新鲜度 / 内存常驻仓库。
     */
    @RequestMapping(value = "/overview", method = RequestMethod.GET)
    public Map<String, Object> overview() {
        Map<String, Object> resp = ok();
        List<IndexRepo> repos = registryStore.listRepos();
        Map<String, Integer> statusCount = new LinkedHashMap<String, Integer>();
        int enabled = 0;
        long totalLines = 0;
        List<Map<String, Object>> repoSummaries = new ArrayList<Map<String, Object>>();
        long now = System.currentTimeMillis();
        for (IndexRepo repo : repos) {
            if (repo.isEnabled()) {
                enabled++;
            }
            String st = repo.getLastBuildStatus() == null ? "NEVER" : repo.getLastBuildStatus();
            Integer c = statusCount.get(st);
            statusCount.put(st, c == null ? 1 : c + 1);
            totalLines += repo.getLastIndexLines();
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("repoCode", repo.getRepoCode());
            m.put("enabled", repo.isEnabled());
            m.put("status", st);
            m.put("ref", repo.getLastIndexedRef());
            m.put("commit", repo.getLastIndexedCommit());
            m.put("lines", repo.getLastIndexLines());
            m.put("buildTime", repo.getLastBuildTime());
            m.put("indexAgeMinutes", repo.getLastBuildTime() > 0 ? (now - repo.getLastBuildTime()) / 60000L : -1);
            m.put("error", repo.getLastErrorMsg());
            repoSummaries.add(m);
        }
        resp.put("repoTotal", repos.size());
        resp.put("repoEnabled", enabled);
        resp.put("statusCount", statusCount);
        resp.put("totalIndexLines", totalLines);
        resp.put("residentRepos", queryService.residentRepos());
        resp.put("lastPollAt", syncRunner.getLastPollAtMillis());
        resp.put("lastPollDurationMs", syncRunner.getLastPollDurationMs());
        resp.put("lastPollRepoCount", syncRunner.getLastPollRepoCount());
        resp.put("lastPollTriggeredCount", syncRunner.getLastPollTriggeredCount());
        resp.put("recentBuilds", recentBuildsInternal(null, 10));
        resp.put("repos", repoSummaries);
        return resp;
    }

    /**
     * 构建历史。
     * <p>?repoCode=&limit=
     */
    @RequestMapping(value = "/builds", method = RequestMethod.GET)
    public Map<String, Object> builds(@RequestParam(value = "repoCode", required = false) String repoCode,
                                      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        Map<String, Object> resp = ok();
        resp.put("builds", recentBuildsInternal(repoCode, Math.min(Math.max(limit, 1), 500)));
        return resp;
    }

    /**
     * 巡检健康：有一个仓库 FAILED 或轮询长期未跑即不健康。
     */
    @RequestMapping(value = "/health", method = RequestMethod.GET)
    public Map<String, Object> health() {
        List<String> failedRepos = new ArrayList<String>();
        long now = System.currentTimeMillis();
        // 轮询超过 3 个间隔没跑过 → 调度异常
        long lastPollAt = syncRunner.getLastPollAtMillis();
        boolean pollerAlive = lastPollAt <= 0 || (now - lastPollAt) < 3 * 600000L;
        for (IndexRepo repo : registryStore.listRepos()) {
            if (repo.isEnabled() && "FAILED".equals(repo.getLastBuildStatus())) {
                failedRepos.add(repo.getRepoCode());
            }
        }
        boolean healthy = failedRepos.isEmpty() && pollerAlive;
        Map<String, Object> resp = ok();
        resp.put("healthy", healthy);
        resp.put("pollerAlive", pollerAlive);
        resp.put("failedRepos", failedRepos);
        resp.put("lastPollAt", lastPollAt);
        // 容器环境自检（生产容器无 git/mvn/javac，首次定位“三无”环境用）
        resp.put("env", buildService.environmentReport());
        return resp;
    }

    private List<Map<String, Object>> recentBuildsInternal(String repoCode, int limit) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (BuildLogEntry e : registryStore.recentBuilds(repoCode, limit)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("repoCode", e.getRepoCode());
            m.put("triggerType", e.getTriggerType());
            m.put("ref", e.getResolvedRef());
            m.put("commitBefore", e.getCommitBefore());
            m.put("commitAfter", e.getCommitAfter());
            m.put("status", e.getStatus());
            m.put("durationMs", e.getDurationMs());
            m.put("indexLines", e.getIndexLines());
            m.put("error", e.getErrorMsg());
            m.put("startedAt", e.getStartedAt());
            m.put("finishedAt", e.getFinishedAt());
            result.add(m);
        }
        return result;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        return m;
    }
}
