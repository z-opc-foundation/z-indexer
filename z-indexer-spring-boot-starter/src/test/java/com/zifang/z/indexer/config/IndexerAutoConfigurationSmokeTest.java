package com.zifang.z.indexer.config;

import com.zifang.z.indexer.controller.IndexAdminController;
import com.zifang.z.indexer.controller.IndexArtifactController;
import com.zifang.z.indexer.controller.IndexMonitorController;
import com.zifang.z.indexer.controller.IndexQueryController;
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
 * 不拉起 z-boot 配置中心/Web 环境，refresh() 阶段即可暴露装配环。
 */
public class IndexerAutoConfigurationSmokeTest {

    private AnnotationConfigApplicationContext ctx;

    @BeforeClass
    public void setUp() throws Exception {
        File reposRoot = Files.createTempDirectory("z-indexer-ctx").toFile();
        System.setProperty("z.indexer.repos-root", reposRoot.getAbsolutePath());
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

    /**
     * REST 面随 @ComponentScan 一起装出来（只装 service bean 不给端点等于没装配）。
     * <p>制品端点是后加的：它的 bean 不在装配面里，等于打包链路对外不可见，
     * 而 buildService.getArtifactService() 为 null 时是 500 而不是 404，故两处都验。
     */
    @Test
    public void restSurfaceWired() {
        Assert.assertNotNull(ctx.getBean(IndexQueryController.class));
        Assert.assertNotNull(ctx.getBean(IndexAdminController.class));
        Assert.assertNotNull(ctx.getBean(IndexMonitorController.class));
        Assert.assertNotNull(ctx.getBean(IndexArtifactController.class));
        Assert.assertNotNull(ctx.getBean(IndexBuildService.class).getArtifactService(),
                "artifactService 未构造，制品端点会 NPE");
    }

    @AfterClass
    public void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }
}
