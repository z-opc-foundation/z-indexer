package com.zifang.z.indexer.pipeline;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * pom 注入器语义钉值：注入必带 annotationProcessorPaths + -A 参数；
 * 仓库本 pom 的 &lt;proc&gt;none&lt;/proc&gt;（build/plugins 与 pluginManagement 两处）
 * 必须被剥掉——javac 的 -proc:none 优先于显式 -processorpath，不剥则处理器整体静默不跑
 * （自索引自证：构建 SUCCESS、零告警、零产物）。重复 patch 幂等且 -A 刷新。
 */
public class PomIndexerPatcherTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>demo</groupId><artifactId>demo</artifactId><version>1.0</version>\n";

    @Test
    public void injectsIntoPlainPom() throws Exception {
        File dir = tempDir("patcher-plain");
        File pom = write(dir, HEAD + "</project>\n");
        String out = new PomIndexerPatcher(dir, new File(dir, "indexer/demo.compiled.jsonl")).patch(pom);
        Assert.assertTrue(out.contains("added"), out);
        String xml = read(pom);
        Assert.assertTrue(xml.contains("<annotationProcessorPaths"), xml);
        Assert.assertTrue(xml.contains("<artifactId>code-indexer</artifactId>"), xml);
        Assert.assertTrue(xml.contains("-AindexOut="), xml);
        Assert.assertTrue(xml.contains("-AindexRoot="), xml);
        Assert.assertFalse(xml.contains("<proc>"), "plain pom must not grow a proc element: " + xml);
    }

    @Test
    public void stripsProcNoneInBuildPlugins() throws Exception {
        File dir = tempDir("patcher-plugins-proc");
        File pom = write(dir, HEAD
                + "  <build><plugins><plugin>\n"
                + "    <groupId>org.apache.maven.plugins</groupId>\n"
                + "    <artifactId>maven-compiler-plugin</artifactId>\n"
                + "    <configuration><proc>none</proc></configuration>\n"
                + "  </plugin></plugins></build>\n"
                + "</project>\n");
        new PomIndexerPatcher(dir, new File(dir, "indexer/demo.compiled.jsonl")).patch(pom);
        String xml = read(pom);
        Assert.assertFalse(xml.contains("<proc>none</proc>"), "proc:none must be stripped: " + xml);
        Assert.assertTrue(xml.contains("<annotationProcessorPaths"), xml);
    }

    @Test
    public void stripsProcNoneInPluginManagement() throws Exception {
        File dir = tempDir("patcher-pm-proc");
        File pom = write(dir, HEAD
                + "  <build><pluginManagement><plugins><plugin>\n"
                + "    <groupId>org.apache.maven.plugins</groupId>\n"
                + "    <artifactId>maven-compiler-plugin</artifactId>\n"
                + "    <version>3.11.0</version>\n"
                + "    <configuration><proc>none</proc></configuration>\n"
                + "  </plugin></plugins></pluginManagement></build>\n"
                + "</project>\n");
        new PomIndexerPatcher(dir, new File(dir, "indexer/demo.compiled.jsonl")).patch(pom);
        String xml = read(pom);
        Assert.assertFalse(xml.contains("<proc>none</proc>"), "managed proc:none must be stripped: " + xml);
        Assert.assertTrue(xml.contains("<annotationProcessorPaths"), xml);
    }

    @Test
    public void rePatchIsIdempotentAndRefreshesArgs() throws Exception {
        File dir = tempDir("patcher-idem");
        File pom = write(dir, HEAD
                + "  <build><plugins><plugin>\n"
                + "    <groupId>org.apache.maven.plugins</groupId>\n"
                + "    <artifactId>maven-compiler-plugin</artifactId>\n"
                + "    <configuration><proc>none</proc></configuration>\n"
                + "  </plugin></plugins></build>\n"
                + "</project>\n");
        PomIndexerPatcher patcher = new PomIndexerPatcher(dir, new File(dir, "indexer/demo.compiled.jsonl"));
        patcher.patch(pom);
        String out = patcher.patch(pom);
        Assert.assertTrue(out.contains("existed"), out);
        String xml = read(pom);
        Assert.assertEquals(count(xml, "<artifactId>code-indexer</artifactId>"), 1, xml);
        Assert.assertEquals(count(xml, "-AindexRoot="), 1, xml);
        Assert.assertFalse(xml.contains("<proc>none</proc>"), xml);

        File out2 = new File(dir, "indexer/demo2.compiled.jsonl");
        new PomIndexerPatcher(dir, out2).patch(pom);
        xml = read(pom);
        Assert.assertTrue(xml.contains("-AindexOut=" + out2.getAbsolutePath()), xml);
        Assert.assertEquals(count(xml, "-AindexOut="), 1, "stale -AindexOut must be replaced: " + xml);
    }

    private static int count(String s, String needle) {
        int n = 0;
        int at = 0;
        while ((at = s.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    private static File tempDir(String prefix) throws Exception {
        File f = File.createTempFile(prefix, "");
        Assert.assertTrue(f.delete());
        Assert.assertTrue(f.mkdirs());
        return f;
    }

    private static File write(File dir, String content) throws Exception {
        File pom = new File(dir, "pom.xml");
        OutputStream os = new FileOutputStream(pom);
        try {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            os.close();
        }
        return pom;
    }

    private static String read(File f) throws Exception {
        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(all, StandardCharsets.UTF_8);
    }
}
