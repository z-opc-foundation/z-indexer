package com.zifang.z.indexer.controller;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理端点这一侧的 gitUrl 泄露判据。
 *
 * <p>{@code IndexAdminController} 有 3 个出口把 gitUrl 写出去
 * （2 处落盘 {@code meta.json}、1 处管理 API 响应体 {@code toMap}），
 * 而 core 侧的 {@code GitUrlLeakGuardTest} 扫的是 core 模块的文件，够不到这里。
 * <b>一处漏判据就是一处能悄悄退回原样的代码</b>。</p>
 */
public class IndexAdminGitUrlLeakTest {

    private static final Path SRC = Paths.get("src/main/java/com/zifang/z/indexer/controller");
    private static final String TOKEN = "ghp_AAAABBBBCCCCDDDDEEEEFFFF";

    // ==================================================================
    // 出口一：管理 API 响应体（toMap，5 个端点的公共出口）
    // ==================================================================

    @Test
    public void toMapMasksGitUrl() throws Exception {
        IndexRepoStub stub = new IndexRepoStub("https://deploybot:" + TOKEN + "@github.com/acme/repo.git");
        Method toMap = IndexAdminController.class.getDeclaredMethod("toMap",
                com.zifang.z.indexer.registry.IndexRepo.class);
        toMap.setAccessible(true);

        Map<String, Object> out = (Map<String, Object>) toMap.invoke(null, stub.repo);

        String gitUrl = (String) out.get("gitUrl");
        Assert.assertNotNull(gitUrl, "toMap 没有 gitUrl 字段，判据本身失效");
        Assert.assertFalse(gitUrl.contains(TOKEN),
                "管理 API 响应体把仓库推送凭证原样返回，实际=" + gitUrl);
        Assert.assertEquals(gitUrl, "https://deploybot:***@github.com/acme/repo.git",
                "必须保留用户名/主机/路径，只掩密码段");
    }

    @Test
    public void toMapMasksEveryGitUrlShape() {
        // 一个出口要经得起各种 gitUrl 形态，而不是只对某一条样例脱敏
        String[][] cases = {
                {"https://u:" + TOKEN + "@h/o/r.git", "https://u:***@h/o/r.git"},
                {"http://u:" + TOKEN + "@h:8080/o/r.git", "http://u:***@h:8080/o/r.git"},
                {"ssh://git@h/o/r.git", "ssh://git@h/o/r.git"},
                {"git@h:o/r.git", "git@h:o/r.git"},
                {"https://h/o/r.git", "https://h/o/r.git"},
                {null, null},
        };
        for (String[] c : cases) {
            Map<String, Object> out = toMapOf(c[0]);
            Assert.assertEquals(out.get("gitUrl"), c[1], "形态 " + c[0] + " 的脱敏结果不对");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMapOf(String gitUrl) {
        try {
            Method toMap = IndexAdminController.class.getDeclaredMethod("toMap",
                    com.zifang.z.indexer.registry.IndexRepo.class);
            toMap.setAccessible(true);
            return (Map<String, Object>) toMap.invoke(null, new IndexRepoStub(gitUrl).repo);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================================================================
    // 出口二、三：落盘的 meta.json（uploadIndex / uploadFinish）
    // ==================================================================

    @DataProvider(name = "adminSources")
    public Object[][] adminSources() {
        return new Object[][]{{"IndexAdminController.java"}};
    }

    @Test(dataProvider = "adminSources")
    public void everyGitUrlFieldInAdminControllerIsMasked(String file) throws IOException {
        Path p = SRC.resolve(file);
        Assert.assertTrue(Files.exists(p), "找不到源码：" + p + "（测试的工作目录假设是模块根）");

        String src = stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        List<String> bare = new ArrayList<String>();
        List<String> all = new ArrayList<String>();

        Matcher m = Pattern.compile("\"gitUrl\"\\s*,\\s*([^,\\n]*)").matcher(src);
        while (m.find()) {
            String value = m.group(1).trim();
            all.add(value);
            if (!value.contains("GitUrlMasker.mask(")) {
                bare.add("第 " + lineOf(src, m.start()) + " 行：" + value);
            }
        }

        Assert.assertTrue(bare.isEmpty(),
                "以下 gitUrl 字段未经 GitUrlMasker 直接写出：\n  " + String.join("\n  ", bare));
        Assert.assertEquals(all.size(), 3,
                "IndexAdminController 有 3 个 gitUrl 出口（uploadIndex / uploadFinish 的 meta.json"
                        + " + toMap 的 API 响应体），判据配平要求正好 3 条；实际扫到 " + all.size() + " 条");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 造一个只带 gitUrl 的 IndexRepo。 */
    static final class IndexRepoStub {

        final com.zifang.z.indexer.registry.IndexRepo repo =
                new com.zifang.z.indexer.registry.IndexRepo();

        IndexRepoStub(String gitUrl) {
            repo.setRepoCode("demo");
            repo.setGitUrl(gitUrl);
        }
    }

    private static int lineOf(String src, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 剥注释，保留字符串字面量（要找的 {@code "gitUrl"} 就在字面量里）。 */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                for (int k = 0; k < 2 && i < n; k++) {
                    out.append(' ');
                    i++;
                }
            } else if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (d == '"') {
                        break;
                    }
                }
            } else if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (d == '\'') {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}