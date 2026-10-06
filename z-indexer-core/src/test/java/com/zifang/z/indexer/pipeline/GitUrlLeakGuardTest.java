package com.zifang.z.indexer.pipeline;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源码普查：gitUrl 的<b>每个对外出口</b>都必须经过 {@code GitUrlMasker}。
 *
 * <p>为什么要有这条而不是只测行为：gitUrl 分散在 7 个出口（3 处 {@code meta.json}、
 * 1 处管理 API 响应体、3 处失败日志），它们<b>各自一份实现</b>、互不相干。
 * 逐个写行为测试只能守住当前这 7 个；新增第 8 个出口时没人会记得回来加一条。
 * 这条判据把「所有 {@code "gitUrl", X} 里的 X 必须含 mask」变成不变量。</p>
 *
 * <p><b>只剥注释、不剥字符串</b>：要找的正是 {@code "gitUrl"} 这个 key（字符串字面量本身），
 * 连字符串一起剥掉就等于把判据的核心证据抹掉了。剥字符串只用在另一处——
 * 判断 {@code gitUrl} 这个<b>变量</b>有没有被送进某个日志调用。</p>
 */
public class GitUrlLeakGuardTest {

    private static final Path SRC = Paths.get("src/main/java/com/zifang/z/indexer");

    /** {@code "gitUrl", <表达式>} —— 捕获 put/Map 的取值表达式。 */
    private static final Pattern GIT_URL_FIELD = Pattern.compile("\"gitUrl\"\\s*,\\s*([^,\\n]*)");

    // ==================================================================
    // meta.json：core 侧（IndexBuildService#buildOne）
    // ==================================================================

    @Test
    public void everyGitUrlFieldInCoreIsMasked() throws IOException {
        List<String> bare = new ArrayList<String>();
        List<String> all = new ArrayList<String>();

        for (String file : new String[]{"pipeline/IndexBuildService.java", "schedule/IndexSyncRunner.java"}) {
            Path p = SRC.resolve(file);
            Assert.assertTrue(Files.exists(p), "找不到源码：" + p + "（测试的工作目录假设是模块根）");
            String src = stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            Matcher m = GIT_URL_FIELD.matcher(src);
            while (m.find()) {
                String value = m.group(1).trim();
                all.add(file + " -> " + value);
                if (!value.contains("GitUrlMasker.mask(")) {
                    bare.add(file + " 第 " + lineOf(src, m.start()) + " 行：" + value);
                }
            }
        }

        Assert.assertTrue(bare.isEmpty(),
                "以下 gitUrl 字段未经 GitUrlMasker 直接写出，等于把 userinfo 里的凭证原样送出：\n  "
                        + String.join("\n  ", bare));

        Assert.assertFalse(all.isEmpty(),
                "一条 \"gitUrl\" 字段都没扫到 —— 判据自身失效（路径/正则该改，不是生产代码该改）");
    }

    // ==================================================================
    // 失败日志：GitOps 三处。日志的留存与访问面都比 API 更大
    // ==================================================================

    @Test
    public void gitOpsLogsNeverPrintRawGitUrl() throws IOException {
        Path p = SRC.resolve("pipeline/GitOps.java");
        Assert.assertTrue(Files.exists(p), "找不到源码：" + p);
        String src = stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));

        int masked = countOccurrences(src, "GitUrlMasker.mask(gitUrl)");
        Assert.assertEquals(masked, 3,
                "GitOps 有 3 处把 gitUrl 打进日志（ls-remote 失败 / 无匹配 / tag 无匹配），"
                        + "每一处都得单独脱敏；实际脱敏了 " + masked + " 处");

        // 逐条 log 语句判定：整条语句里出现 gitUrl 变量，就必须是被 mask 过的那个形态。
        // 不能用 `{}, gitUrl` 这种窄正则——字符串收尾引号正好夹在中间，匹配不到。
        List<String> bare = new ArrayList<String>();
        Matcher logCall = Pattern.compile("log\\.(?:warn|info|error|debug)\\s*\\(").matcher(src);
        while (logCall.find()) {
            int end = src.indexOf(");", logCall.end());
            String stmt = end < 0 ? src.substring(logCall.end()) : src.substring(logCall.end(), end);
            if (stripStrings(stmt).contains("gitUrl") && !stmt.contains("GitUrlMasker.mask(gitUrl)")) {
                bare.add(stmt.replaceAll("\\s+", " ").trim());
            }
        }

        Assert.assertTrue(bare.isEmpty(),
                "以下日志语句把 gitUrl 原文写出（凭证会进日志系统）：\n  " + String.join("\n  ", bare));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static int countOccurrences(String src, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = src.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
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

    /**
     * 把字符串字面量换成空串，只留代码骨架——用来判断 {@code gitUrl} 这个<b>变量</b>
     * 有没有被送进某个调用。
     */
    static String stripStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '"') {
                i++;
                while (i < src.length()) {
                    char d = src.charAt(i++);
                    if (d == '\\' && i < src.length()) {
                        i++;
                    } else if (d == '"') {
                        break;
                    }
                }
                out.append("\"\"");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 剥掉注释，<b>保留字符串字面量</b>（判据要找的 {@code "gitUrl"} 就在字面量里）。
     * 注释替换成等长空格，行号与列号不变，报错时能直接定位。
     */
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