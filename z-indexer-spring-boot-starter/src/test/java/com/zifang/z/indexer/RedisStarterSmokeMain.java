package com.zifang.z.indexer;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * z-boot-cache-starter 本地冒烟（0902 排障用，不进 CI）：
 * 完整 @SpringBootApplication 启动（与生产 IndexerApplication 同路径），
 * indexer 组件用本地临时目录兜底，spring.redis.* 与生产 yaml 完全一致。
 * <p>背景：456a20e5 部署后新 pod CrashLoop（Unavailable，4 分钟重启 4 次），
 * 本地凭据直连 OK（AUTH/PING/SELECT 79），需定位是装配问题还是容器网络问题。
 * <p>注意：本地会报 "access cache config pull fail: cache config content is empty"——
 * 配置中心本地不可达所致（ERROR 但不中断启动），生产环境配置中心可达。
 */
@SpringBootApplication
public class RedisStarterSmokeMain {

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(RedisStarterSmokeMain.class)
                .properties(
                        "server.port=0",
                        "spring.application.name=redis-smoke",
                        // indexer 组件本地兜底：临时目录 + 首轮 poller 拉远避免干扰
                        "z.indexer.repos-root=/tmp/idx-smoke-root",
                        "z.indexer.poll-initial-delay-ms=999999999",
                        // 直配 spring.redis.*（RedissonAutoConfiguration 原生前缀，不依赖配置中心）
                        "spring.redis.database=79",
                        "spring.redis.host=r-bp1b4vmp5do59nohtw.redis.rds.aliyuncs.com",
                        "spring.redis.password=YC3p#JTuC6m7",
                        "spring.redis.port=6379",
                        "spring.redis.lettuce.pool.max-active=20",
                        "spring.redis.lettuce.pool.max-idle=10",
                        "spring.redis.lettuce.pool.max-wait=-1ms",
                        "spring.redis.lettuce.pool.min-idle=2")
                .run();

        try {
            System.out.println("[SMOKE] context UP");
            System.out.println("[SMOKE] redis 相关 bean:");
            for (String n : ctx.getBeanDefinitionNames()) {
                String ln = n.toLowerCase();
                if (ln.contains("redis") || ln.contains("lettuce") || ln.contains("redisson")) {
                    System.out.println("  bean: " + n + " -> " + ctx.getBean(n).getClass().getName());
                }
            }
            StringRedisTemplate t = ctx.getBean(StringRedisTemplate.class);
            String key = "indexer:smoke:local";
            t.opsForValue().set(key, "ok", 60, TimeUnit.SECONDS);
            String v = t.opsForValue().get(key);
            System.out.println("[SMOKE] SET/GET 往返: " + v + "  => " + ("ok".equals(v) ? "链路通 PASS" : "值不符 FAIL"));
        } finally {
            ctx.close();
        }
    }
}
