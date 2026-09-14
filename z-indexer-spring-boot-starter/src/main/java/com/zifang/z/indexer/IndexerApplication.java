package com.zifang.z.indexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 代码索引独立部署入口。
 * <p>
 * 作为独立 Spring Boot 服务运行，提供仓库注册 / 索引查询 / 监控 REST API。
 * 部署方式与 future-team-msg / knowledge 一致，通过 c2f-boot 配置中心管理端口和 context-path。
 */
@SpringBootApplication
@EnableScheduling
public class IndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndexerApplication.class, args);
    }
}
