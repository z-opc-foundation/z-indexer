package com.zifang.z.indexer.registry;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0911 P0：status mtime+size 短路戳语义。
 * <p>1) 未变化文件 listRepos 用内存快照（短路不损实时性——stat 每次都做）；
 * 2) 写方 persistStatus 记账后，本实例读回一致；
 * 3) 外部进程改写 status 文件（多实例场景），下一次 listRepos 立即感知。
 */
public class RegistryStoreStatusStampTest {

    private static IndexRepo repo(String code, String status) {
        IndexRepo r = new IndexRepo();
        r.setRepoCode(code);
        r.setGitUrl("https://git.example.com/" + code + ".git");
        r.setRefMode(IndexRepo.REF_MODE_BRANCH);
        r.setRefValue("master");
        r.setEnabled(true);
        r.setLastBuildStatus(status);
        return r;
    }

    private static void overwrite(File f, String status) throws Exception {
        IndexRepo r = repo(f.getName().substring(0, f.getName().length() - 5), status);
        OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(com.zifang.util.json.JsonUtil.toJson(r));
        } finally {
            w.close();
        }
    }

    @Test
    public void stampShortCircuitAndExternalChange() throws Exception {
        File dir = Files.createTempDirectory("reg-stamp").toFile();
        try {
            RegistryStore store = new RegistryStore(dir);
            // 真实调用模式（IndexSyncRunner 同款）：内存表引用 = upsert/find 返回值，
            // 构建完成后 setLastBuildStatus + persistStatus 回写同一引用
            IndexRepo repoA = store.upsert(repo("repo-a", "NEVER"));
            repoA.setLastBuildStatus("SUCCESS");
            store.persistStatus(repoA);

            // 1) 写方记账后读回一致
            Assert.assertEquals(store.listRepos().get(0).getLastBuildStatus(), "SUCCESS");

            // 2) 短路：内存值与磁盘一致（用外部改写前，listRepos 不重读也不出错）
            Assert.assertEquals(store.listRepos().get(0).getLastBuildStatus(), "SUCCESS");

            // 3) 外部进程改写（模拟另一实例构建完成），下一次 listRepos 立即感知
            File statusFile = new File(dir, "status/repo-a.json");
            overwrite(statusFile, "FAILED");
            Assert.assertEquals(store.listRepos().get(0).getLastBuildStatus(), "FAILED");

            // 4) 短路期 mtime 抖动防御：mtime 未变内容未变时重复调用稳定
            Assert.assertEquals(store.listRepos().get(0).getLastBuildStatus(), "FAILED");
        } finally {
            delete(dir);
        }
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
