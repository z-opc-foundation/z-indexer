package com.zifang.z.indexer.registry;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * repoCode 的路径安全闸。
 *
 * <p>repoCode 会被直接拼进十余处路径（repos/&lt;code&gt; 工作副本、indexer/&lt;code&gt;.jsonl、
 * build-logs/&lt;code&gt;.log、locks/&lt;code&gt;.lock、status/&lt;code&gt;.json、artifacts/&lt;code&gt;.zip），
 * 所以它必须是安全文件名。此前字符白名单 {@code [A-Za-z0-9._-]+} 排除了路径分隔符
 * （这点是对的），但 {@code "."} 与 {@code ".."} 完全落在白名单内，
 * 而 {@code new File(reposDir, "..")} 规范化后就是 reposRoot 本身。
 */
public class RegistryStoreRepoCodeTest {

    // ------------------------------------------------------------ validateRepoCode

    @Test
    public void dotDotIsRejected() {
        assertRejected("..", "\"..\" 会把工作副本目录指到 reposRoot 之外，必须拒绝");
    }

    @Test
    public void singleDotIsRejected() {
        assertRejected(".", "\".\" 会把工作副本目录别名到 repos/ 自身，必须拒绝");
    }

    @Test
    public void pathSeparatorsAreRejected() {
        for (String bad : new String[]{"a/b", "/etc", "a\\b", "../x", "a/../b"}) {
            assertRejected(bad, "路径分隔符应被拒绝");
        }
    }

    @Test
    public void blankAndNullAreRejected() {
        for (String bad : new String[]{null, "", "   "}) {
            assertRejected(bad, "空 code 应被拒绝");
        }
    }

    @Test
    public void punctuationOutsideCharsetIsRejected() {
        for (String bad : new String[]{"a b", "a$b", "a:b", "a*b", "a?", "a;b", "中", "a\tb"}) {
            assertRejected(bad, "字符集外字符应被拒绝");
        }
    }

    @Test
    public void ordinaryCodesAreAccepted() {
        for (String ok : new String[]{"z-util", "a.b.c", "-x", ".hidden", "A1_b-c.d",
                "...", "----", "1"}) {
            try {
                RegistryStore.validateRepoCode(ok);
            } catch (IllegalArgumentException e) {
                Assert.fail("合法 repoCode 被误拒 [" + ok + "]: " + e.getMessage());
            }
        }
    }

    /**
     * 把"为什么 '.'/'..' 危险"钉成可执行证据：字符白名单放行它们，
     * 但解析后的路径确实逃出了 {@code repos/}。
     */
    @Test
    public void dotDotReallyEscapesReposDir() throws Exception {
        File root = Files.createTempDirectory("z-indexer-repoc").toFile();
        try {
            File reposDir = new File(root, "repos");
            Assert.assertTrue(reposDir.mkdirs());

            Assert.assertTrue("..".matches(RegistryStore.REPO_CODE_PATTERN),
                    "旧白名单确实放行 \"..\"");
            File escaped = new File(reposDir, "..").getCanonicalFile();
            Assert.assertEquals(escaped, root.getCanonicalFile(),
                    "\"..\" 的工作副本目录会解析到 reposRoot 本身 —— 这就是必须拒绝它的原因");
        } finally {
            root.delete();
        }
    }

    // ------------------------------------------------------------ upsert 入口

    @Test
    public void upsertRejectsDotDot() throws Exception {
        File dir = Files.createTempDirectory("z-indexer-reg").toFile();
        try {
            RegistryStore store = new RegistryStore(dir);
            IndexRepo repo = new IndexRepo();
            repo.setRepoCode("..");
            repo.setGitUrl("https://git.example.com/a/b.git");
            try {
                store.upsert(repo);
                Assert.fail("upsert 应拒绝 repoCode [..]");
            } catch (IllegalArgumentException expected) {
                // pass
            }
            Assert.assertTrue(store.find("..") == null, "非法 code 不得进内存表");
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void upsertAcceptsOrdinaryCode() throws Exception {
        File dir = Files.createTempDirectory("z-indexer-reg").toFile();
        try {
            RegistryStore store = new RegistryStore(dir);
            IndexRepo repo = new IndexRepo();
            repo.setRepoCode("z-util");
            repo.setGitUrl("https://git.example.com/a/b.git");
            IndexRepo saved = store.upsert(repo);
            Assert.assertEquals(saved.getRepoCode(), "z-util");
            Assert.assertNotNull(store.find("z-util"));
        } finally {
            deleteTree(dir);
        }
    }

    // ------------------------------------------------------------ load() 旁路

    /**
     * registry.json 是可手工编辑的静态配置真源，走 {@code load()} 进内存。
     * 该路径<b>原先零校验</b>，比 upsert 更松 —— 一个 repoCode 为 ".." 的条目
     * 能绕过 API 直接进表，随后被后台轮询器当真执行构建。
     */
    @Test
    public void loadSkipsIllegalRepoCodes() throws Exception {
        File dir = Files.createTempDirectory("z-indexer-reg").toFile();
        try {
            String json = "{\"schemaVersion\":1,\"repos\":["
                    + entry("good-repo", "https://git.example.com/good.git") + ","
                    + entry("..", "https://git.example.com/evil.git") + ","
                    + entry(".", "https://git.example.com/evil2.git") + ","
                    + entry("a/b", "https://git.example.com/evil3.git") + ","
                    + entry("z-trd", "https://git.example.com/good2.git")
                    + "]}";
            write(new File(dir, "registry.json"), json);

            RegistryStore store = new RegistryStore(dir);
            List<IndexRepo> repos = store.listRepos();

            Assert.assertEquals(repos.size(), 2, "只应留下合法条目，实际: " + codes(repos));
            Assert.assertNotNull(store.find("good-repo"));
            Assert.assertNotNull(store.find("z-trd"));
            Assert.assertNull(store.find(".."), "\"..\" 不得从 registry.json 进内存表");
            Assert.assertNull(store.find("."));
            Assert.assertNull(store.find("a/b"));
        } finally {
            deleteTree(dir);
        }
    }

    /** 单条坏数据不能让整个 indexer 起不来：合法的必须照常加载。 */
    @Test
    public void loadDoesNotFailOnCorruptedEntry() throws Exception {
        File dir = Files.createTempDirectory("z-indexer-reg").toFile();
        try {
            String json = "{\"schemaVersion\":1,\"repos\":["
                    + entry("ok-1", "https://git.example.com/1.git") + ","
                    + "{\"repoCode\":null,\"gitUrl\":\"x\"},"
                    + "{\"noCode\":true},"
                    + entry("ok-2", "https://git.example.com/2.git")
                    + "]}";
            write(new File(dir, "registry.json"), json);

            RegistryStore store = new RegistryStore(dir);   // 不应抛异常
            Assert.assertEquals(store.listRepos().size(), 2, "两条合法条目都应加载");
        } finally {
            deleteTree(dir);
        }
    }

    // ------------------------------------------------------------ helpers

    /** TestNG 的 Assert.assertThrows 在本版本不收 lambda，手写 try/catch 以给出明确失败信息。 */
    private static void assertRejected(String code, String why) {
        try {
            RegistryStore.validateRepoCode(code);
        } catch (IllegalArgumentException expected) {
            return;
        }
        Assert.fail(why + " —— 但 repoCode [" + code + "] 被放行了");
    }

    private static String entry(String code, String url) {
        return "{\"repoCode\":\"" + code + "\",\"gitUrl\":\"" + url + "\",\"enabled\":true}";
    }

    private static String codes(List<IndexRepo> repos) {
        StringBuilder sb = new StringBuilder("[");
        for (IndexRepo r : repos) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(r.getRepoCode());
        }
        return sb.append("]").toString();
    }

    private static void write(File f, String content) throws Exception {
        try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(f),
                StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    private static void deleteTree(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        dir.delete();
    }
}
