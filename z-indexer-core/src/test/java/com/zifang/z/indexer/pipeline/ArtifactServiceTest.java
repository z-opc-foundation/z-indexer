package com.zifang.z.indexer.pipeline;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonObject;
import com.zifang.z.indexer.IndexerConfig;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * master 纯内容制品打包（{@link ArtifactService}）。
 * <p>钉四件易 silently 破的事：
 * 1) 非源码子树（.git/target/node_modules/build/dist/.idea/.vscode）必须整棵剔除——
 *    漏一个就把依赖巨树塞进制品，下游 cp 反而更慢；
 * 2) 悬空符号链接不得毁整包（{@code Files.copy} 对断链抛 NoSuchFileException，
 *    整包直接失败，而断链在释放过工具链的工作区里是常态）；
 * 3) 首个条目固定是 {@link ArtifactService#META_ENTRY}（下游据此判完整性）；
 * 4) 产物原子落定：tmp 不残留，zip/meta 成对存在且 meta 的 files/bytes 与实际条目数一致
 *    （计数若把被排除的文件也算进去，下游按 files 校验会永远对不上）。
 */
public class ArtifactServiceTest {

    @Test
    public void packsPureContentAndSkipsSymlinks() throws Exception {
        File root = Files.createTempDirectory("artifact-pack").toFile();
        try {
            String repoCode = "demo-service";
            File repoDir = new File(new File(root, "repos"), repoCode);
            Assert.assertTrue(repoDir.mkdirs(), "repo dir");
            // ArtifactService 以 .git 存在为"这是个工作副本"的判据，缺它直接拒打包
            Assert.assertTrue(new File(repoDir, ".git").mkdir());
            write(new File(repoDir, ".git/config"), "gitdir-noise");
            write(new File(repoDir, "target/app.jar"), "build-artifact-noise");
            write(new File(repoDir, "node_modules/pkg/index.js"), "dependency-noise");
            write(new File(repoDir, "build/out.class"), "build-noise");
            Assert.assertTrue(new File(repoDir, "src").mkdir());
            write(new File(repoDir, "src/Main.java"), "public class Main {}\n");
            write(new File(repoDir, "README.md"), "# demo\n");

            // 断链：指向不存在的路径，必须被跳过而不是炸整包
            // （文件系统不支持软链时这一条腿不跑，用 symlinked 记账，末尾断言只在真造过链子时才算数）
            boolean symlinked;
            try {
                Files.createSymbolicLink(new File(repoDir, "dangling-link").toPath(),
                        new File(repoDir, "does-not-exist").toPath());
                symlinked = true;
            } catch (java.io.IOException | UnsupportedOperationException fsNoSymlink) {
                System.out.println("[ArtifactServiceTest] symlink leg skipped: " + fsNoSymlink);
                symlinked = false;
            }

            IndexerConfig cfg = new IndexerConfig();
            cfg.setReposRoot(root.getAbsolutePath());
            ArtifactService svc = new ArtifactService(cfg);
            svc.buildArtifact(repoCode, "cafebabedeadbeef");

            File zip = svc.artifactFile(repoCode);
            File meta = svc.metaFile(repoCode);
            Assert.assertTrue(zip.isFile(), "zip 未生成: " + zip);
            Assert.assertTrue(meta.isFile(), "meta 未生成: " + meta);

            List<String> names = entryNames(zip);
            Assert.assertEquals(names.get(0), ArtifactService.META_ENTRY,
                    "首条目必须是元数据条目（下游按它判完整性）: " + names);
            Assert.assertTrue(names.contains("src/Main.java"), "源码没进包: " + names);
            Assert.assertTrue(names.contains("README.md"), "根文件没进包: " + names);
            for (String forbidden : new String[]{".git", "target", "node_modules", "build"}) {
                for (String n : names) {
                    Assert.assertFalse(n.equals(forbidden) || n.startsWith(forbidden + "/"),
                            "非源码子树漏进制品: " + n + " in " + names);
                }
            }
            if (symlinked) {
                Assert.assertFalse(names.contains("dangling-link"), "断链没被跳过: " + names);
            }

            JsonObject m = JsonUtil.parseObject(
                    new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
            Assert.assertEquals(m.getString("repoCode"), repoCode);
            Assert.assertEquals(m.getString("commit"), "cafebabedeadbeef");
            // zip 里除 META_ENTRY 外就是全部计入的源码文件
            // （取数值先判空：getInt/getLong 返回包装类型，null 上 .longValue() 是 NPE 不是断言失败）
            Long filesField = m.getLong("files");
            Assert.assertNotNull(filesField, "meta.files 不是数字: " + m);
            Assert.assertEquals(filesField.longValue(), (long) names.size() - 1,
                    "meta.files 与实际条目数不符，下游按 files 校验必挂: " + names);
            Long bytesField = m.getLong("bytes");
            Assert.assertNotNull(bytesField, "meta.bytes 不是数字: " + m);
            Assert.assertEquals(bytesField.longValue(),
                    new File(repoDir, "src/Main.java").length() + new File(repoDir, "README.md").length());

            // tmp 不残留（原子改名失败会留在 artifacts 目录里堆垃圾）
            File[] leftovers = svc.artifactsDir().listFiles();
            Assert.assertNotNull(leftovers);
            for (File f : leftovers) {
                Assert.assertFalse(f.getName().contains(".tmp-"), "tmp 残留: " + f.getName());
            }
        } finally {
            delete(root);
        }
    }

    private static List<String> entryNames(File zip) throws Exception {
        List<String> names = new ArrayList<String>();
        ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip.toPath()));
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                names.add(e.getName());
            }
        } finally {
            zin.close();
        }
        return names;
    }

    private static void write(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                delete(k);
            }
        }
        f.delete();
    }
}
