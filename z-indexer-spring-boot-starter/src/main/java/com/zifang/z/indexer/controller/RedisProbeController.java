package com.zifang.z.indexer.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 连通性探针（0902 z-boot-cache-starter 接入验证）。
 * <p>配置走 spring.redis.*（CacheEnvironmentPostProcessor 翻译成 spring.redis.*，
 * lettuce 连接池 + redisson 默认启用）。本端点 SET→GET→DEL 一个带 TTL 的测试键，
 * 一次 curl 看清链路是否通、由哪个实现承接（多副本共享/边数据外置的前置验证）。
 */
@RestController
@RequestMapping("/index/redis")
public class RedisProbeController {

    private static final String PROBE_KEY = "ft-indexer:redis-probe";

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 连通性 + 读写往返探针（幂等，测试键 TTL 60s 自动过期）。
     */
    @RequestMapping(value = "/probe", method = RequestMethod.GET)
    public Map<String, Object> probe() {
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        if (stringRedisTemplate == null) {
            resp.put("ok", false);
            resp.put("error", "StringRedisTemplate 未装配（z-boot-cache-starter 未生效或配置缺失）");
            return resp;
        }
        resp.put("templateClass", stringRedisTemplate.getClass().getName());
        try {
            Object factory = stringRedisTemplate.getConnectionFactory();
            resp.put("connectionFactoryClass", factory == null ? null : factory.getClass().getName());
        } catch (Throwable ignore) {
            // redisson 某些版本 getConnectionFactory 行为差异，不影响读写判定
        }

        String value = "probe-" + UUID.randomUUID();
        try {
            long t0 = System.currentTimeMillis();
            stringRedisTemplate.opsForValue().set(PROBE_KEY, value, 60, TimeUnit.SECONDS);
            long setMs = System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            String got = stringRedisTemplate.opsForValue().get(PROBE_KEY);
            long getMs = System.currentTimeMillis() - t0;

            stringRedisTemplate.delete(PROBE_KEY);

            boolean match = value.equals(got);
            resp.put("ok", match);
            resp.put("setMs", setMs);
            resp.put("getMs", getMs);
            resp.put("valueMatch", match);
            if (!match) {
                resp.put("got", got);
                resp.put("error", "SET/GET 往返值不一致");
            }
        } catch (Exception e) {
            resp.put("ok", false);
            resp.put("error", String.valueOf(e.getMessage()));
            resp.put("errorClass", e.getClass().getName());
        }
        return resp;
    }
}
