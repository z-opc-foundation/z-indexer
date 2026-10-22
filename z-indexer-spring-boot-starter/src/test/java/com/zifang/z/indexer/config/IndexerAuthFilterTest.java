package com.zifang.z.indexer.config;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Arrays;

/**
 * 索引服务管理面的入站鉴权闸门。
 *
 * <p>要抓的是三件容易犯的错：
 * <ol>
 *   <li><b>只挡写端点</b> ⇒ 仓列表/构建日志仍可匿名读 ⇒ 那不是鉴权；</li>
 *   <li><b>头名对不上</b> ⇒ 前端注入的是 {@code X-Indexer-Token}，
 *       后端若只认 {@code Authorization} ⇒ 前端直接 401，功能当场瘫；</li>
 *   <li><b>回显凭据</b> ⇒ 401 响应体里不能出现客户端递来的头。</li>
 * </ol>
 */
public class IndexerAuthFilterTest {

    private static final String TOKEN = "test-indexer-token";

    private IndexerAuthFilter filter;

    @BeforeMethod
    public void setUp() {
        filter = new IndexerAuthFilter(TOKEN, Arrays.asList("/index/monitor/health"));
    }

    private MockHttpServletResponse doGet(String path, String headerName, String headerValue)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        if (headerName != null) {
            req.addHeader(headerName, headerValue);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    @Test
    public void 没有令牌一律401() throws Exception {
        Assert.assertEquals(doGet("/index/admin/repos", null, null).getStatus(), 401);
    }

    @Test
    public void 令牌错也401() throws Exception {
        Assert.assertEquals(
                doGet("/index/admin/repos", IndexerAuthFilter.TOKEN_HEADER, "wrong").getStatus(), 401);
    }

    @Test
    public void 仓内契约头XIndexerToken放行() throws Exception {
        Assert.assertEquals(
                doGet("/index/admin/repos", IndexerAuthFilter.TOKEN_HEADER, TOKEN).getStatus(), 200);
    }

    @Test
    public void 标准bearer头也放行() throws Exception {
        Assert.assertEquals(
                doGet("/index/admin/repos", "Authorization", "Bearer " + TOKEN).getStatus(), 200);
    }

    @Test
    public void 写端点同样被挡() throws Exception {
        // ⚠ 建仓/删仓/重建/上传 —— 每一条都改运行时状态
        Assert.assertEquals(doGet("/index/admin/repos", null, null).getStatus(), 401);
        Assert.assertEquals(doGet("/index/admin/poll", null, null).getStatus(), 401);
        Assert.assertEquals(doGet("/index/admin/repos/abc/rebuild", null, null).getStatus(), 401);
        Assert.assertEquals(doGet("/index/admin/repos/abc/upload", null, null).getStatus(), 401);
    }

    @Test
    public void 查询面也被挡() throws Exception {
        // 只挡写端点 = 半拉子鉴权：仓清单与构建日志仍是公开的
        Assert.assertEquals(doGet("/index/query/repos", null, null).getStatus(), 401);
        Assert.assertEquals(doGet("/index/monitor/overview", null, null).getStatus(), 401);
    }

    @Test
    public void 豁免路径放行() throws Exception {
        Assert.assertEquals(doGet("/index/monitor/health", null, null).getStatus(), 200);
    }

    @Test
    public void 挑战头与响应体不泄露凭据() throws Exception {
        MockHttpServletResponse resp =
                doGet("/index/admin/repos", IndexerAuthFilter.TOKEN_HEADER, "leaked-secret");
        Assert.assertTrue(resp.getHeader("WWW-Authenticate").contains("Bearer realm=\"z-indexer\""));
        Assert.assertFalse(resp.getContentAsString().contains("leaked-secret"));
    }

    @Test
    public void 未配令牌时过滤器放行但自身标记为未生效() {
        IndexerAuthFilter permissive = new IndexerAuthFilter(null, null);
        Assert.assertFalse(permissive.isEnforced());
    }
}
