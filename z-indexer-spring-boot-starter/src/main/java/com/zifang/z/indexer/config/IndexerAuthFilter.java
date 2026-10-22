package com.zifang.z.indexer.config;

import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 索引服务管理面的入站鉴权闸门。
 *
 * <p><b>补的是什么洞</b>：管理面 {@code /index/admin/**} 此前**零鉴权** ——
 * 它含 {@code POST/DELETE/PUT /index/admin/repos}（注册/删除仓库）、
 * {@code POST /index/admin/repos/{repoCode}/rebuild}（强制重建索引）、
 * {@code POST /index/admin/repos/{repoCode}/upload}（上传索引制品）、
 * {@code POST /index/admin/poll}（触发全量轮询）。
 * ⇒ 能建仓、能重建、能上传制品 —— 这三条都改运行时状态，**不是只读面**。
 *
 * <p><b>头的名字沿用仓内既有契约</b>：前端 {@code z-indexer-component} 早就按
 * 「{@code X-Indexer-Token} 头，开启后所有 endpoint 必带」写好了注入逻辑
 * （{@code localStorage['zindexer_admin_token']}）——
 * <b>后端一直没实现这道校验</b>，前端那套注入因此一直是空转。
 * ⇒ 本类把后端补上，前端**不用改一行**。
 *
 * <p>同时接受 {@code Authorization: Bearer <token>}：给脚本/巡检用
 * （标准做法，别的仓的巡检探针都按这个口径发）。
 *
 * <p><b>常数时间比较</b>（{@link MessageDigest#isEqual}）：按字节短路比较会留时序侧信道。
 */
public class IndexerAuthFilter extends OncePerRequestFilter {

    /** 管理令牌头（前端既有契约）。 */
    public static final String TOKEN_HEADER = "X-Indexer-Token";

    /** 挑战的 realm。 */
    public static final String REALM = "z-indexer";

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] token;

    private final List<String> excludedPaths;

    /**
     * @param token         管理令牌；<b>null = 不校验</b>（放行一切）。
     *                      传 null 之前调用方应当已经 warn 过「鉴权没开」——
     *                      一个安静的「不校验」比一个响亮的失败危险得多。
     * @param excludedPaths 免鉴权的路径前缀。
     */
    public IndexerAuthFilter(String token, List<String> excludedPaths) {
        if (token != null && token.trim().isEmpty()) {
            throw new IllegalArgumentException("令牌必填");
        }
        this.token = token == null ? null : token.trim().getBytes(StandardCharsets.UTF_8);
        this.excludedPaths = new ArrayList<String>();
        if (excludedPaths != null) {
            for (String p : excludedPaths) {
                if (p != null && !p.trim().isEmpty()) {
                    this.excludedPaths.add(p.trim());
                }
            }
        }
    }

    /** 是否真的在校验（false = 放行一切）。 */
    public boolean isEnforced() {
        return token != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (isExcluded(path)) {
            chain.doFilter(request, response);
            return;
        }
        String presented = tokenOf(request);
        if (presented != null && MessageDigest.isEqual(
                token, presented.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        unauthorized(response);
    }

    private boolean isExcluded(String path) {
        if (path == null) {
            return false;
        }
        for (String p : excludedPaths) {
            if (path.equals(p) || path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 取令牌：先看仓内契约头 {@code X-Indexer-Token}，再看标准 bearer 头。 */
    static String tokenOf(HttpServletRequest request) {
        String v = trim(request.getHeader(TOKEN_HEADER));
        if (v != null) {
            return v;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null) {
            return null;
        }
        String a = auth.trim();
        if (a.length() <= BEARER_PREFIX.length()
                || !a.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String t = a.substring(BEARER_PREFIX.length()).trim();
        return t.isEmpty() ? null : t;
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"" + REALM + "\"");
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        // ⚠ **不回显收到的头**：回显等于把凭据写进日志与响应体。
        response.getWriter().write(
                "{\"ok\":false,\"error\":\"unauthorized\","
                        + "\"message\":\"需要 " + TOKEN_HEADER + " 头或 Authorization: Bearer <token>\"}");
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
