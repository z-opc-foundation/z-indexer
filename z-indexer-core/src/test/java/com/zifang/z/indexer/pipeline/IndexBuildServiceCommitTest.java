package com.zifang.z.indexer.pipeline;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * {@link IndexBuildService#forceCommit(File)} —— rename 前的 NFS COMMIT。
 *
 * <p>reposRoot 挂在 ReadWriteMany NAS 上，写 tmp → rename 的形状下，
 * rename 可能先于数据落 server，server 端看到 0 字节文件。本仓已在
 * {@code ArtifactService} 实测踩到并修掉，索引产物此前漏了同一道 COMMIT。
 *
 * <p>fsync 本身在本地文件系统上没有可观测副作用，因此这里能钉住的是
 * <b>「COMMIT 的实现方式不许把文件改坏」</b>这一条：
 * 追加模式一去，{@code new FileOutputStream(f)} 会把刚写好的索引截断成 0 字节。
 */
public class IndexBuildServiceCommitTest {

    @Test
    public void commitPreservesContent() throws Exception {
        File dir = Files.createTempDirectory("z-indexer-commit").toFile();
        try {
            File f = new File(dir, "index.jsonl");
            String content = "{\"t\":\"def\"}\n{\"t\":\"call\"}\n";
            write(f, content);

            Assert.assertTrue(IndexBuildService.forceCommit(f), "正常文件应 COMMIT 成功");
            Assert.assertEquals(f.length(), content.getBytes(StandardCharsets.UTF_8).length,
                    "COMMIT 后字节数必须不变");
            Assert.assertEquals(read(f), content, "COMMIT 不得改动文件内容");
        } finally {
            deleteTree(dir);
        }
    }

    /** 最关键的一条：绝不能截断。append 标志一去，本测试立刻红。 */
    @Test
    public void commitDoesNotTruncateLargeFile() throws Exception {
        File dir = Files.createTempDirectory("z-indexer-commit").toFile();
        try {
            File f = new File(dir, "big.jsonl");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20000; i++) {
                sb.append("{\"t\":\"call\",\"line\":").append(i).append("}\n");
            }
            String content = sb.toString();
            write(f, content);
            long before = f.length();
            Assert.assertTrue(before > 100_000, "样本应足够大，实际 " + before);

            IndexBuildService.forceCommit(f);

            Assert.assertEquals(f.length(), before, "COMMIT 后字节数不得变化（被截断即为回归）");
            Assert.assertTrue(read(f).endsWith("}\n"), "尾部内容应完好");
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void commitOnMissingFileReportsFailureInsteadOfThrowing() {
        File missing = new File(
                new File(System.getProperty("java.io.tmpdir")), "z-indexer-no-such-" + System.nanoTime());
        Assert.assertFalse(IndexBuildService.forceCommit(missing),
                "文件不存在应返回 false 交由调用方兜底，而不是抛异常打断构建");
        Assert.assertFalse(missing.exists(), "COMMIT 失败不得凭空创建文件");
    }

    /** 回归：rename 后字节数校验依赖「rename 不改变字节数」这一前提。 */
    @Test
    public void renamePreservesByteCount() throws Exception {
        File dir = Files.createTempDirectory("z-indexer-commit").toFile();
        try {
            File tmp = new File(dir, ".tmp-x.jsonl");
            File target = new File(dir, "x.jsonl");
            String content = "line1\nline2\nline3\n";
            write(tmp, content);
            long expect = tmp.length();

            Assert.assertTrue(tmp.renameTo(target));
            Assert.assertEquals(target.length(), expect,
                    "rename 不得改变字节数，否则 rename 后校验会误报");
            Assert.assertEquals(read(target), content);
        } finally {
            deleteTree(dir);
        }
    }

    // ------------------------------------------------------------ helpers

    private static void write(File f, String content) throws Exception {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    private static String read(File f) throws Exception {
        byte[] buf = Files.readAllBytes(f.toPath());
        return new String(buf, StandardCharsets.UTF_8);
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
