package com.zifang.z.indexer.config;

import com.zifang.z.indexer.IndexerConfig;
import com.zifang.z.indexer.pipeline.IndexBuildService;
import com.zifang.z.indexer.query.IndexQueryService;
import com.zifang.z.indexer.registry.BuildLockManager;
import com.zifang.z.indexer.registry.IndexRepo;
import com.zifang.z.indexer.registry.RegistryStore;
import com.zifang.z.indexer.schedule.IndexSyncRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 索引服务装配：core 组件（注册表/构建/查询/调度）以 @Bean 显式接线，
 * 调度由本类内 @Scheduled 方法驱动（fixedDelay 配置化）。
 * <p>
 * 本类由 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 注册（2026-09-29 补：此前 starter 里**没有任何注册文件**，装配全靠 z-indexer-server 那个
 * {@code @SpringBootApplication} 顺手扫包 ⇒ "引入即生效"对仓库外的消费者从来是不成立的，
 * 而 starter 恰恰是发给外面用的那一面）。
 * <p>
 * {@code @ComponentScan} 带出 REST 面（查询/注册/监控三个 controller）—— 服务是给人调的，
 * 只装 service bean 不给端点等于没装配。形状照 z-mist 的
 * {@code MistWebAutoConfiguration}（同为"供宿主 embed"的 starter：@ComponentScan controllers
 * + @EnableScheduling）。controller 不受 {@code z.indexer.enabled} 影响：那格的口径是
 * "false 时轮询停止、查询接口仍可用已有索引"（见 {@link IndexerProperties#isEnabled()}），
 * 开关只掐上面那个 @Scheduled。
 */
@Configuration
@EnableConfigurationProperties(IndexerProperties.class)
@ComponentScan(basePackages = "com.zifang.z.indexer.controller")
@EnableScheduling
public class IndexerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IndexerAutoConfiguration.class);

    private final AtomicBoolean pollerStarted = new AtomicBoolean(false);

    @javax.annotation.Resource
    private IndexerProperties indexerProperties;

    /**
     * 惰性解析：配置类字段直接注入 IndexSyncRunner 会形成循环依赖
     * （registryStore @Bean 实例化配置类 → 字段 indexSyncRunner →
     * indexSyncRunner @Bean 参数 registryStore 正在创建中）。
     */
    @Autowired
    private ObjectProvider<IndexSyncRunner> indexSyncRunnerProvider;

    @Bean
    public IndexerConfig indexerCoreConfig(IndexerProperties properties) {
        IndexerConfig config = new IndexerConfig();
        config.setReposRoot(properties.getReposRoot());
        config.setBuildTimeoutMinutes(properties.getBuildTimeoutMinutes());
        config.setStaticFallbackEnabled(properties.isStaticFallbackEnabled());
        config.setMavenOpts(properties.getMavenOpts());
        config.setBuildConcurrency(properties.getBuildConcurrency());
        config.setMvnCmd(properties.getMvnCmd());
        config.setGitCmd(properties.getGitCmd());
        config.setNodeCmd(properties.getNodeCmd());
        config.setLruMaxRepos(properties.getLruMaxRepos());
        config.setWarmupRepos(properties.getWarmupRepos());
        config.setBigRepoCodes(properties.getBigRepoCodes());
        config.setBuildYieldRepos(properties.getBuildYieldRepos());
        config.setDefaultDepth(properties.getDefaultDepth());
        config.setMaxResultNodes(properties.getMaxResultNodes());
        return config;
    }

    /**
     * 分布式构建锁（NAS locks/ 目录，多实例同仓互斥）。
     * stale 阈值 = 构建超时×2 + 10min 缓冲：实例宕机留死锁时其他实例可抢占。
     */
    @Bean
    public BuildLockManager buildLockManager(IndexerConfig config) {
        long staleMs = (config.getBuildTimeoutMinutes() * 2L + 10) * 60_000L;
        return new BuildLockManager(new File(config.getReposRoot(), "indexer"), staleMs);
    }

    @Bean
    public RegistryStore registryStore(IndexerConfig config) {
        return new RegistryStore(new File(config.getReposRoot(), "indexer"));
    }

    @Bean
    public IndexBuildService indexBuildService(IndexerConfig config, RegistryStore registryStore,
                                               BuildLockManager lockManager, IndexQueryService queryService) {
        IndexBuildService service = new IndexBuildService(config, registryStore);
        service.setInstanceTag(lockManager.getInstanceId());
        service.setQueryService(queryService);
        return service;
    }

    @Bean
    public IndexQueryService indexQueryService(IndexerConfig config, RegistryStore registryStore) {
        return new IndexQueryService(config, registryStore);
    }

    /** 构建完成 → 成功即异步预热新索引（免首查冷加载）；失败清缓存 */
    @Bean
    public ApplicationRunner queryCacheInvalidator(final IndexBuildService buildService,
                                                    final IndexQueryService queryService) {
        return new ApplicationRunner() {
            @Override
            public void run(org.springframework.boot.ApplicationArguments args) {
                buildService.setBuildListener(new IndexBuildService.BuildListener() {
                    @Override
                    public void onBuildFinished(String repoCode, boolean success) {
                        if (success) {
                            queryService.preloadAsync(repoCode);
                        } else {
                            queryService.invalidate(repoCode);
                        }
                    }
                });
            }
        };
    }

    /**
     * 启动预热：后台线程串行预加载 SUCCESS 仓中索引行数前 warmupRepos 个
     * （大仓优先常驻，避免白天首查冷加载 30-60s）。错峰 60s 启动，
     * 避开首轮 poller 的构建 IO。大仓索引内存约 150-400MB/仓，默认 6 个。
     */
    @Bean
    public ApplicationRunner queryWarmupRunner(final IndexQueryService queryService,
                                               final RegistryStore registryStore,
                                               final IndexerConfig config) {
        return new ApplicationRunner() {
            @Override
            public void run(org.springframework.boot.ApplicationArguments args) {
                if (config.getWarmupRepos() <= 0) {
                    return;
                }
                Thread warmup = new Thread(new Runnable() {
                    public void run() {
                        try {
                            Thread.sleep(60_000L);
                        } catch (InterruptedException e) {
                            return;
                        }
                        try {
                            java.util.List<Map<String, Object>> picks = new java.util.ArrayList<Map<String, Object>>();
                            for (IndexRepo repo : registryStore.listRepos()) {
                                if ("SUCCESS".equals(repo.getLastBuildStatus())
                                        && jsonlExists(config, repo.getRepoCode())) {
                                    picks.add(wrap(repo));
                                }
                            }
                            picks.sort((a, b) -> Long.compare(longOf(b.get("lines")), longOf(a.get("lines"))));
                            int n = Math.min(config.getWarmupRepos(), picks.size());
                            for (int i = 0; i < n; i++) {
                                String repoCode = String.valueOf(picks.get(i).get("repoCode"));
                                queryService.preloadSync(repoCode);
                                log.info("[Warmup] preloaded {}/{} {}", i + 1, n, repoCode);
                            }
                        } catch (Exception e) {
                            log.warn("[Warmup] aborted: {}", e.getMessage());
                        }
                    }
                }, "index-query-warmup");
                warmup.setDaemon(true);
                warmup.start();
            }
        };
    }

    private static boolean jsonlExists(IndexerConfig config, String repoCode) {
        return new File(new File(config.getReposRoot(), "indexer"), repoCode + ".jsonl").exists();
    }

    private static Map<String, Object> wrap(IndexRepo repo) {
        Map<String, Object> m = new java.util.HashMap<String, Object>();
        m.put("repoCode", repo.getRepoCode());
        m.put("lines", repo.getLastIndexLines());
        return m;
    }

    private static long longOf(Object o) {
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }

    @Bean
    public IndexSyncRunner indexSyncRunner(RegistryStore registryStore, IndexBuildService buildService,
                                            BuildLockManager lockManager, IndexerConfig config) {
        return new IndexSyncRunner(registryStore, buildService, lockManager, config);
    }

    /** 启动时建 reposRoot 并释放 code-indexer.jar 到 ~/.m2、解压内置 maven、部署 tools.jar（均幂等）。 */
    @Bean
    @Order(20)
    public ApplicationRunner codeIndexerJarInstaller(final IndexBuildService buildService,
                                                     final IndexerConfig cfg) {
        return new ApplicationRunner() {
            @Override
            public void run(org.springframework.boot.ApplicationArguments args) {
                File root = new File(cfg.getReposRoot());
                if (!root.exists() && !root.mkdirs()) {
                    log.warn("[IndexerAutoConfig] cannot create reposRoot {}", cfg.getReposRoot());
                }
                buildService.ensureCodeIndexerJar();
                buildService.ensureMavenDist();
                buildService.ensureToolsJar();
                // 前端工具链预热（有前端仓才真正消耗磁盘；失败不影响 java 链路）
                try {
                    buildService.ensureFrontendToolchain();
                } catch (Exception ignore) {
                    // 状态已记入 environmentReport
                }
            }
        };
    }

    /** 定时轮询（fixedDelay 保证上一轮跑完再计时，构建慢时自动顺延）。 */
    @Scheduled(initialDelayString = "${z.indexer.poll-initial-delay-ms:60000}",
            fixedDelayString = "${z.indexer.poll-interval-ms:600000}")
    public void scheduledPoll() {
        IndexerProperties props = indexerProperties;
        if (props == null || !props.isEnabled()) {
            return;
        }
        IndexSyncRunner runner = indexSyncRunnerProvider == null ? null : indexSyncRunnerProvider.getIfAvailable();
        if (runner == null) {
            return;
        }
        if (pollerStarted.compareAndSet(false, true)) {
            log.info("[IndexerSchedule] poller started (interval={}ms)", props.getPollIntervalMs());
        }
        runner.pollOnce();
    }
}
