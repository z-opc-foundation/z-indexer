package com.zifang.z.indexer.registry;

import com.zifang.util.json.JsonUtil;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * registry.json 落盘/读回往返。
 * <p>钉的是 JsonArray 的取值口径：往数组里塞裸 POJO 时序列化器直接落 toString，
 * 产物是 {@code "repos": [com.zifang.z.indexer.registry.IndexRepo@15db9742]} 这种碎片，
 * 下一次启动 load() 解析即抛，整个注册表读不回来（写侧不报错，只有读侧炸，故必须在此断言原文）。
 * <p>同时钉住字符串不被美化器改写：gitUrl 必带冒号（ssh 形式 {@code git@host:org/repo.git}），
 * 任何"给冒号后补空格"的 pretty 实现都会让它变成 {@code git@host: org/repo.git}，
 * 而 clone 用的就是这个值——所以断言写的是整串字面量，不是长度/非空。
 */
public class RegistryStoreJsonRoundTripTest {

    private static final String SSH_URL = "git@github.com:acme/demo-service.git";

    private static IndexRepo repo(String code, String gitUrl) {
        IndexRepo r = new IndexRepo();
        r.setRepoCode(code);
        r.setRepoName("中文名-" + code);
        r.setGitUrl(gitUrl);
        r.setRefMode(IndexRepo.REF_MODE_BRANCH);
        r.setRefValue("master");
        r.setEnabled(true);
        r.setRemark("owner: platform-team");
        return r;
    }

    @Test
    public void persistWritesParseableJsonAndReloadsExact() throws Exception {
        File dir = Files.createTempDirectory("reg-roundtrip").toFile();
        File registryFile = new File(dir, "registry.json");
        try {
            RegistryStore store = new RegistryStore(dir);
            store.upsert(repo("repo-a", SSH_URL));
            store.upsert(repo("repo-b", "https://github.com/acme/other.git"));
            store.persistRegistry();

            String raw = new String(Files.readAllBytes(registryFile.toPath()), StandardCharsets.UTF_8);

            // 1) 反空转锚点：POJO 落 toString 的碎片形态一旦出现即为回归
            Assert.assertFalse(raw.contains("IndexRepo@"),
                    "registry.json 里混进了 POJO toString 碎片，落盘已不可解析: " + raw);
            Assert.assertTrue(raw.contains("\"" + SSH_URL + "\""),
                    "gitUrl 原文没落对（冒号被改写/整串丢失），clone 必挂: " + raw);
            Assert.assertTrue(raw.contains("\"repoName\":\"中文名-repo-a\"")
                            || raw.contains("\"repoName\": \"中文名-repo-a\""),
                    "非 ASCII 字段未原样落盘: " + raw);

            // 2) 原文可被自家解析器吃下（写读同一实现也要过，否则等于自证）
            Assert.assertNotNull(JsonUtil.parseObject(raw), "registry.json 不是合法 JSON");

            // 3) 新实例从盘上读回，逐字段比对注册面字段
            //    （upsert 只搬注册面：lastIndexedCommit 之类构建态走 status/ 文件，
            //     由 RegistryStoreStatusStampTest 覆盖，此处不得当作注册字段断言）
            RegistryStore reopened = new RegistryStore(dir);
            Assert.assertEquals(reopened.listRepos().size(), 2);
            IndexRepo a = reopened.find("repo-a");
            Assert.assertNotNull(a, "repo-a 读不回来");
            Assert.assertEquals(a.getGitUrl(), SSH_URL);
            Assert.assertEquals(a.getRefMode(), IndexRepo.REF_MODE_BRANCH);
            Assert.assertEquals(a.getRefValue(), "master");
            Assert.assertEquals(a.getRepoName(), "中文名-repo-a");
            Assert.assertEquals(a.getRemark(), "owner: platform-team");
            Assert.assertTrue(a.isEnabled());
            Assert.assertEquals(reopened.find("repo-b").getGitUrl(), "https://github.com/acme/other.git");
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
