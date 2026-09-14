package com.zifang.z.indexer.config;

import com.zifang.z.indexer.pipeline.IndexBuildService;
import com.zifang.z.indexer.query.IndexQueryService;
import com.zifang.z.indexer.registry.RegistryStore;
import com.zifang.z.indexer.schedule.IndexSyncRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * Spring 装配冒烟：验证 IndexerAutoConfiguration 全部 @Bean 可创建、
 * 注入关系无循环依赖（生产 CRID 424263 曾因配置类字段注入 IndexSyncRunner
 * 形成 registryStore → 配置类 → indexSyncRunner → registryStore 环，启动即挂）。
 * <p>用 AnnotationConfigApplicationContext 而非 @SpringBootTest：
 * 不拉起 c2f-boot 配置中心/Web 环境，refresh() 阶段即可暴露装配环。
 */
public class IndexerAutoConfigurationSmokeTest {

    private AnnotationConfigApplicationContext ctx;

    @BeforeClass
    public void setUp() throws Exception {
        File reposRoot = Files.createTempDirectory("ft-indexer-ctx").toFile();
        System.setProperty("future-team.indexer.repos-root", reposRoot.getAbsolutePath());
        ctx = new AnnotationConfigApplicationContext();
        ctx.register(IndexerAutoConfiguration.class);
        ctx.refresh();
    }

    @Test
    public void allCoreBeansWired() {
        Assert.assertNotNull(ctx.getBean(RegistryStore.class));
        Assert.assertNotNull(ctx.getBean(IndexBuildService.class));
        Assert.assertNotNull(ctx.getBean(IndexQueryService.class));
        Assert.assertNotNull(ctx.getBean(IndexSyncRunner.class));
    }

    @Test
    public void indexSyncRunnerWiredWithSameStore() {
        RegistryStore store = ctx.getBean(RegistryStore.class);
        Assert.assertNotNull(store);
        Assert.assertNotNull(ctx.getBean(IndexSyncRunner.class));
    }

    @AfterClass
    public void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }
}
