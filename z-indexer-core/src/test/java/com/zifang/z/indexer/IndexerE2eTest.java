package com.zifang.z.indexer;

import com.zifang.z.indexer.pipeline.IndexBuildService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zifang.z.indexer.query.IndexQueryService;
import com.zifang.z.indexer.query.RepoIndex;
import com.zifang.z.indexer.registry.BuildLockManager;
import com.zifang.z.indexer.registry.IndexRepo;
import com.zifang.z.indexer.registry.RegistryStore;
import com.zifang.z.indexer.schedule.IndexSyncRunner;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 端到端验证（真实 git 仓库 + 真实 mvn 编译）：
 * 注册 → INIT 构建 → 查询 → 代码变更轮询重建 → TAG_PATTERN 发版联动。
 * <p>依赖本机 mvn/git 可用，processor 从 test classpath（bin/code-indexer.jar）
 * 自动释放到 ~/.m2（自足，不依赖环境预装）。
 * <p>默认跳过（Matrix 构建机不跑 E2E，避免环境抖动挂部署）：
 * 开启方式 {@code mvn test -Dtest=IndexerE2eTest -Dft.indexer.e2e=true}。
 */
public class IndexerE2eTest {

    private static final String POM = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
            + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
            + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>demo</groupId>\n"
            + "  <artifactId>demo-repo</artifactId>\n"
            + "  <version>1.0</version>\n"
            + "  <packaging>jar</packaging>\n"
            + "  <build>\n"
            + "    <plugins>\n"
            + "      <plugin>\n"
            + "        <groupId>org.apache.maven.plugins</groupId>\n"
            + "        <artifactId>maven-compiler-plugin</artifactId>\n"
            + "        <version>3.8.1</version>\n"
            + "        <configuration><source>1.8</source><target>1.8</target></configuration>\n"
            + "      </plugin>\n"
            + "    </plugins>\n"
            + "  </build>\n"
            + "</project>\n";

    private static final String CALC = "package demo;\n\n"
            + "public class PriceCalculator {\n"
            + "    public double calcPrice(double base) {\n"
            + "        return base * 1.1;\n"
            + "    }\n"
            + "}\n";

    private static final String ORDER = "package demo;\n\n"
            + "public class OrderService {\n"
            + "    public void submit(double amount) {\n"
            + "        double price = new PriceCalculator().calcPrice(amount);\n"
            + "        audit(amount);\n"
            + "    }\n\n"
            + "    private void audit(double v) {\n"
            + "        System.out.println(v);\n"
            + "    }\n"
            + "}\n";

    /** 变更后新增的类（模拟发版新增代码）。 */
    private static final String REFUND = "package demo;\n\n"
            + "public class RefundService {\n"
            + "    public void refund(double amount) {\n"
            + "        double price = new PriceCalculator().calcPrice(amount);\n"
            + "    }\n"
            + "}\n";

    @Test
    public void fullPipeline() throws Exception {
        String flag = System.getProperty("ft.indexer.e2e",
                System.getenv("FT_INDEXER_E2E") == null ? "" : System.getenv("FT_INDEXER_E2E"));
        if (!"true".equalsIgnoreCase(flag)) {
            throw new SkipException("indexer E2E disabled (enable with -Dft.indexer.e2e=true)");
        }
        if (!commandAvailable("git") || !commandAvailable("mvn")) {
            throw new SkipException("git/mvn not available on this machine");
        }
        File work = createTempDir("ft-indexer-e2e");
        File srcRepo = new File(work, "demo-repo-src");
        writeSources(srcRepo);
        git(srcRepo, "init", "-q", "-b", "master");
        git(srcRepo, "add", "-A");
        git(srcRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");

        IndexerConfig cfg = new IndexerConfig();
        cfg.setReposRoot(new File(work, "repos-root").getAbsolutePath());
        cfg.setBuildTimeoutMinutes(10);
        cfg.setMvnCmd("mvn"); // 测试用本机 mvn（生产默认空=内置发行版）

        RegistryStore store = new RegistryStore(new File(cfg.getReposRoot(), "indexer"));
        BuildLockManager locks = new BuildLockManager(new File(cfg.getReposRoot(), "indexer"), 60_000L);
        IndexBuildService build = new IndexBuildService(cfg, store);
        build.setInstanceTag(locks.getInstanceId());
        // 从 test classpath 释放 processor 到 ~/.m2（自足，干净仓库可跑）
        build.ensureCodeIndexerJar();
        IndexQueryService query = new IndexQueryService(cfg, store);
        IndexSyncRunner runner = new IndexSyncRunner(store, build, locks, cfg);

        // ── 1. 注册 + INIT 构建 ──
        IndexRepo repo = new IndexRepo();
        repo.setRepoCode("demo-repo");
        repo.setRepoName("Demo Repo");
        repo.setGitUrl(srcRepo.getAbsolutePath());
        repo.setRefMode(IndexRepo.REF_MODE_BRANCH);
        repo.setRefValue("master");
        store.upsert(repo);

        boolean initOk = build.buildOne(store.find("demo-repo"), "INIT");
        if (!initOk) {
            System.out.println("[E2E-DEBUG] INIT failed: " + store.find("demo-repo").getLastErrorMsg());
        }
        Assert.assertTrue(initOk, "INIT build should succeed");
        IndexRepo built = store.find("demo-repo");
        Assert.assertEquals(built.getLastBuildStatus(), "SUCCESS");
        Assert.assertTrue(built.getLastIndexLines() > 0, "index lines > 0");
        Assert.assertTrue(new File(cfg.getReposRoot(), "indexer/demo-repo.jsonl").exists(), "jsonl produced");
        Assert.assertTrue(new File(cfg.getReposRoot(), "indexer/demo-repo.meta.json").exists(), "meta produced");
        Assert.assertNotNull(built.getLastIndexedCommit(), "indexed commit recorded");

        // ── 2. 查询：符号搜索 + 调用链 ──
        List<RepoIndex.Def> defs = query.searchSymbols("demo-repo", "calcPrice", 10, true);
        Assert.assertFalse(defs.isEmpty(), "calcPrice should be found");
        String calcSym = defs.get(0).sym;
        Assert.assertEquals(RepoIndex.simpleName(calcSym), "calcPrice");

        Map<String, Object> callers = query.callers("demo-repo", calcSym, 2, true);
        Assert.assertNull(callers.get("error"), "callers should resolve");
        List<?> edges = (List<?>) callers.get("edges");
        Assert.assertNotNull(edges);
        boolean orderServiceFound = false;
        for (Object o : edges) {
            Map<?, ?> edge = (Map<?, ?>) o;
            if (String.valueOf(edge.get("from")).contains("OrderService")) {
                orderServiceFound = true;
                Assert.assertTrue(((Number) edge.get("line")).intValue() > 0, "call site line > 0");
            }
        }
        Assert.assertTrue(orderServiceFound, "OrderService should call calcPrice");

        // ── 3. 无变更时轮询不重建 ──
        runner.pollOnce();
        Assert.assertEquals(store.find("demo-repo").getLastBuildStatus(), "SUCCESS");
        // pollOnce 对未变化仓库跳过构建（commit 不变即无触发），状态保持 SUCCESS

        // ── 4. 代码变更 → 轮询发现 → 自动重建 → 新代码可查 ──
        Thread.sleep(1100); // 保证 mtime 变化
        writeText(new File(srcRepo, "src/main/java/demo/RefundService.java"), REFUND);
        git(srcRepo, "add", "-A");
        git(srcRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "add RefundService");
        // 构建完成回调 → 查询端缓存失效（同 starter 装配）
        build.setBuildListener(new IndexBuildService.BuildListener() {
            @Override
            public void onBuildFinished(String repoCode, boolean success) {
                query.invalidate(repoCode);
            }
        });
        runner.pollOnce();
        Assert.assertEquals(store.find("demo-repo").getLastBuildStatus(), "SUCCESS", "rebuild after change");

        List<RepoIndex.Def> refundDefs = query.searchSymbols("demo-repo", "RefundService", 10, true);
        Assert.assertFalse(refundDefs.isEmpty(), "RefundService should be indexed after rebuild");
        // 热加载窗口过后再次确认稳定可查
        Thread.sleep(1500);
        refundDefs = query.searchSymbols("demo-repo", "RefundService", 10, true);
        Assert.assertFalse(refundDefs.isEmpty(), "hot reload should expose RefundService");

        // ── 5. TAG_PATTERN 发版联动：打 tag → 注册 tag 模式 → 轮询切到 tag ──
        Thread.sleep(1100);
        git(srcRepo, "tag", "release_demo-repo_version_defaultPipeline_20260901120000");
        IndexRepo tagRepo = store.find("demo-repo");
        tagRepo.setRefMode(IndexRepo.REF_MODE_TAG_PATTERN);
        tagRepo.setRefValue("release_demo-repo_version_*");
        store.persistRegistry();
        runner.pollOnce();
        IndexRepo afterTag = store.find("demo-repo");
        Assert.assertEquals(afterTag.getLastBuildStatus(), "SUCCESS", "tag build should succeed");
        Assert.assertEquals(afterTag.getLastIndexedRef(), "release_demo-repo_version_defaultPipeline_20260901120000",
                "tag ref should be resolved and recorded");

        // ── 6. 注册表持久化：重新加载后状态仍在（重启即用） ──
        RegistryStore reloaded = new RegistryStore(new File(cfg.getReposRoot(), "indexer"));
        Assert.assertNotNull(reloaded.find("demo-repo"));
        Assert.assertEquals(reloaded.find("demo-repo").getLastIndexedCommit(), afterTag.getLastIndexedCommit());
        Assert.assertFalse(reloaded.recentBuilds(null, 100).isEmpty(), "build log persisted");

        // ── 7. 构建后工作副本 pom 干净（reset --hard 还原注入） ──
        Assert.assertTrue(build.getGitOps().isClean(new File(cfg.getReposRoot(), "repos/demo-repo")),
                "working copy should be clean after build (pom patch restored)");
    }

    /** 引用不存在构件的 pom：保证 mvn 编译链路必然失败（双轨兜底测试用）。 */
    private static final String BAD_POM = POM.replace("</project>",
            "  <dependencies>\n"
            + "    <dependency>\n"
            + "      <groupId>nonexistent.group</groupId>\n"
            + "      <artifactId>missing-artifact</artifactId>\n"
            + "      <version>9.9.9</version>\n"
            + "    </dependency>\n"
            + "  </dependencies>\n"
            + "</project>");

    /**
     * 双轮端到端：轮1 静态全量主索引（恒 STATIC，全量仓一律可索引）+ 轮2 编译附带对照
     * （&lt;repo&gt;.compiled.jsonl，失败不影响主索引 SUCCESS）。
     * <p>①好仓 → STATIC 主索引 + compiled SUCCESS；②坏 pom 仓 → 主索引仍 SUCCESS+STATIC，
     * compiled FAILED（lastErrorMsg=null，失败记 lastCompiledError）；③修复 pom → MANUAL 重建
     * → 仍 STATIC + compiled SUCCESS（编译轮重试）；④开关关闭回退纯编译模式（kill-switch）；
     * ⑤SCHEDULE 且上次编译失败 → 编译轮 SKIPPED（省 mvn 数分钟）。
     */
    @Test
    public void dualTrackFallback() throws Exception {
        String flag = System.getProperty("ft.indexer.e2e",
                System.getenv("FT_INDEXER_E2E") == null ? "" : System.getenv("FT_INDEXER_E2E"));
        if (!"true".equalsIgnoreCase(flag)) {
            throw new SkipException("indexer E2E disabled (enable with -Dft.indexer.e2e=true)");
        }
        if (!commandAvailable("git") || !commandAvailable("mvn")) {
            throw new SkipException("git/mvn not available on this machine");
        }
        File work = createTempDir("ft-indexer-dual");

        IndexerConfig cfg = new IndexerConfig();
        cfg.setReposRoot(new File(work, "repos-root").getAbsolutePath());
        cfg.setBuildTimeoutMinutes(10);
        cfg.setMvnCmd("mvn");
        Assert.assertTrue(cfg.isStaticFallbackEnabled(), "默认开启静态兜底");

        RegistryStore store = new RegistryStore(new File(cfg.getReposRoot(), "indexer"));
        BuildLockManager locks = new BuildLockManager(new File(cfg.getReposRoot(), "indexer"), 60_000L);
        IndexBuildService build = new IndexBuildService(cfg, store);
        build.setInstanceTag(locks.getInstanceId());
        build.ensureCodeIndexerJar();
        IndexQueryService query = new IndexQueryService(cfg, store);
        build.setBuildListener(new IndexBuildService.BuildListener() {
            @Override
            public void onBuildFinished(String repoCode, boolean success) {
                query.invalidate(repoCode);
            }
        });

        // ── ① 好仓：编译行就用编译的 ──
        File goodRepo = new File(work, "good-repo-src");
        writeText(new File(goodRepo, "pom.xml"), POM);
        writeText(new File(goodRepo, "src/main/java/demo/PriceCalculator.java"), CALC);
        writeText(new File(goodRepo, "src/main/java/demo/OrderService.java"), ORDER);
        git(goodRepo, "init", "-q", "-b", "master");
        git(goodRepo, "add", "-A");
        git(goodRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
        register(store, "good-repo", goodRepo);

        Assert.assertTrue(build.buildOne(store.find("good-repo"), "INIT"), "好仓 INIT 应成功");
        IndexRepo good = store.find("good-repo");
        Assert.assertEquals(good.getLastBuildStatus(), "SUCCESS");
        Assert.assertEquals(good.getLastIndexType(), "STATIC", "静态全量主索引");
        Assert.assertNull(good.getLastErrorMsg(), "主索引成功无错误信息");
        Assert.assertEquals(good.getLastCompiledStatus(), "SUCCESS", "好仓编译轮成功");
        Assert.assertTrue(good.getLastCompiledLines() > 0, "编译附带行数 > 0");
        Assert.assertTrue(new File(cfg.getReposRoot(), "indexer/good-repo.compiled.jsonl").exists(),
                "compiled.jsonl 对照产物落盘");
        Map<String, Object> goodChain = query.callers("good-repo", "demo.PriceCalculator#calcPrice(double)", 2, true);
        Assert.assertNull(goodChain.get("error"));
        Assert.assertEquals(goodChain.get("indexType"), "STATIC", "链查询透出 STATIC");
        JSONObject goodMeta = JSON.parseObject(readFile(
                new File(cfg.getReposRoot(), "indexer/good-repo.meta.json")));
        Assert.assertEquals(goodMeta.getJSONObject("compiled").getString("status"), "SUCCESS",
                "meta compiled 节透出 SUCCESS");
        Assert.assertTrue(goodMeta.getJSONObject("compiled").getLongValue("lines") > 0,
                "meta compiled 节透出行数");

        // ── ② 坏 pom 仓：编译不行 → 落到静态 ──
        File badRepo = new File(work, "bad-repo-src");
        writeText(new File(badRepo, "pom.xml"), BAD_POM);
        writeText(new File(badRepo, "src/main/java/demo/PriceCalculator.java"), CALC);
        writeText(new File(badRepo, "src/main/java/demo/OrderService.java"), ORDER);
        git(badRepo, "init", "-q", "-b", "master");
        git(badRepo, "add", "-A");
        git(badRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
        register(store, "bad-repo", badRepo);

        Assert.assertTrue(build.buildOne(store.find("bad-repo"), "INIT"),
                "双轮模式坏 pom 仓主索引应成功（编译失败不影响）");
        IndexRepo bad = store.find("bad-repo");
        Assert.assertEquals(bad.getLastBuildStatus(), "SUCCESS", "静态主索引 SUCCESS");
        Assert.assertEquals(bad.getLastIndexType(), "STATIC", "主索引恒 STATIC");
        Assert.assertNull(bad.getLastErrorMsg(), "主索引成功无错误信息（编译失败只记 compiled）");
        Assert.assertEquals(bad.getLastCompiledStatus(), "FAILED", "编译轮失败独立记录");
        Assert.assertTrue(bad.getLastCompiledError() != null && !bad.getLastCompiledError().isEmpty(),
                "编译失败摘要：" + bad.getLastCompiledError());
        Assert.assertTrue(new File(cfg.getReposRoot(), "indexer/bad-repo.jsonl").exists(), "静态 jsonl 产出");
        JSONObject badMeta = JSON.parseObject(readFile(
                new File(cfg.getReposRoot(), "indexer/bad-repo.meta.json")));
        Assert.assertEquals(badMeta.getString("indexType"), "STATIC", "meta 透出 STATIC");
        Assert.assertEquals(badMeta.getJSONObject("compiled").getString("status"), "FAILED",
                "meta compiled 节透出 FAILED");
        Assert.assertTrue(badMeta.getJSONObject("compiled").containsKey("error"),
                "meta compiled 节透出失败摘要");

        // 静态索引可查询（查询端零改动）
        List<RepoIndex.Def> defs = query.searchSymbols("bad-repo", "calcPrice", 10, true);
        Assert.assertFalse(defs.isEmpty(), "静态索引符号可查");
        Assert.assertEquals(defs.get(0).sym, "demo.PriceCalculator#calcPrice(double)");
        Map<String, Object> badChain = query.callers("bad-repo", defs.get(0).sym, 2, true);
        Assert.assertNull(badChain.get("error"), "静态索引调用链可查");
        Assert.assertEquals(badChain.get("indexType"), "STATIC", "链查询透出 STATIC");
        boolean badOrderCalls = false;
        for (Object o : (List<?>) badChain.get("edges")) {
            if (String.valueOf(((Map<?, ?>) o).get("from")).contains("OrderService")) {
                badOrderCalls = true;
            }
        }
        Assert.assertTrue(badOrderCalls, "静态索引 def/call join 生效");

        // ── ③ 修复 pom → MANUAL 重建 → 主索引仍 STATIC + 编译轮重试成功 ──
        Thread.sleep(1100);
        writeText(new File(badRepo, "pom.xml"), POM);
        git(badRepo, "add", "-A");
        git(badRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "fix pom");
        Assert.assertTrue(build.buildOne(store.find("bad-repo"), "MANUAL"), "修复后重建成功");
        IndexRepo upgraded = store.find("bad-repo");
        Assert.assertEquals(upgraded.getLastIndexType(), "STATIC", "主索引恒 STATIC（不升回 COMPILED）");
        Assert.assertEquals(upgraded.getLastCompiledStatus(), "SUCCESS", "MANUAL 重试编译轮成功");
        Assert.assertNull(upgraded.getLastCompiledError(), "编译恢复后失败摘要清空");
        Assert.assertTrue(new File(cfg.getReposRoot(), "indexer/bad-repo.compiled.jsonl").exists(),
                "compiled.jsonl 补齐");
        JSONObject upgradedMeta = JSON.parseObject(readFile(
                new File(cfg.getReposRoot(), "indexer/bad-repo.meta.json")));
        Assert.assertEquals(upgradedMeta.getString("indexType"), "STATIC");
        Assert.assertEquals(upgradedMeta.getJSONObject("compiled").getString("status"), "SUCCESS");

        // ── ④ 开关关闭：坏仓不兜底，维持 FAILED（kill-switch）──
        File noFallbackRepo = new File(work, "nofb-repo-src");
        writeText(new File(noFallbackRepo, "pom.xml"), BAD_POM);
        writeText(new File(noFallbackRepo, "src/main/java/demo/PriceCalculator.java"), CALC);
        git(noFallbackRepo, "init", "-q", "-b", "master");
        git(noFallbackRepo, "add", "-A");
        git(noFallbackRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
        register(store, "nofb-repo", noFallbackRepo);
        cfg.setStaticFallbackEnabled(false);
        try {
            Assert.assertFalse(build.buildOne(store.find("nofb-repo"), "INIT"),
                    "开关关闭时坏仓应 FAILED（纯编译模式）");
            Assert.assertEquals(store.find("nofb-repo").getLastBuildStatus(), "FAILED");
        } finally {
            cfg.setStaticFallbackEnabled(true);
        }

        // ── ⑤ SCHEDULE 且上次编译失败 → 编译轮 SKIPPED（主索引不受影响）──
        File skipRepo = new File(work, "skip-repo-src");
        writeText(new File(skipRepo, "pom.xml"), BAD_POM);
        writeText(new File(skipRepo, "src/main/java/demo/PriceCalculator.java"), CALC);
        git(skipRepo, "init", "-q", "-b", "master");
        git(skipRepo, "add", "-A");
        git(skipRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
        register(store, "skip-repo", skipRepo);
        Assert.assertTrue(build.buildOne(store.find("skip-repo"), "MANUAL"),
                "坏 pom 仓 MANUAL 主索引成功");
        Assert.assertEquals(store.find("skip-repo").getLastCompiledStatus(), "FAILED",
                "前置：编译轮失败");
        Thread.sleep(1100);
        writeText(new File(skipRepo, "pom.xml"), POM);
        git(skipRepo, "add", "-A");
        git(skipRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "fix pom");
        Assert.assertTrue(build.buildOne(store.find("skip-repo"), "SCHEDULE"),
                "SCHEDULE 有变更触发构建");
        IndexRepo skipped = store.find("skip-repo");
        Assert.assertEquals(skipped.getLastBuildStatus(), "SUCCESS", "主索引 SUCCESS");
        Assert.assertEquals(skipped.getLastIndexType(), "STATIC", "主索引 STATIC");
        Assert.assertEquals(skipped.getLastCompiledStatus(), "SKIPPED",
                "SCHEDULE 且上次编译失败 → 编译轮跳过");
    }

    private static void register(RegistryStore store, String repoCode, File srcRepo) {
        IndexRepo repo = new IndexRepo();
        repo.setRepoCode(repoCode);
        repo.setRepoName(repoCode);
        repo.setGitUrl(srcRepo.getAbsolutePath());
        repo.setRefMode(IndexRepo.REF_MODE_BRANCH);
        repo.setRefValue("master");
        store.upsert(repo);
    }

    private static String readFile(File f) throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8));
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            r.close();
        }
    }

    private static final String FE_CALC = "export function calcTotal(x: number): number {\n"
            + "  return x * 2;\n"
            + "}\n";

    private static final String FE_PAGE = "import { calcTotal } from './calc';\n\n"
            + "export default function Page(): number {\n"
            + "  return calcTotal(21);\n"
            + "}\n";

    /**
     * 前端工程全链路：package.json 无 pom.xml → 自动探测 frontend → node 索引器
     * → 同 schema jsonl → 查询端零改动可用。
     */
    @Test
    public void frontendPipeline() throws Exception {
        String flag = System.getProperty("ft.indexer.e2e",
                System.getenv("FT_INDEXER_E2E") == null ? "" : System.getenv("FT_INDEXER_E2E"));
        if (!"true".equalsIgnoreCase(flag)) {
            throw new SkipException("indexer E2E disabled (enable with -Dft.indexer.e2e=true)");
        }
        if (!commandAvailable("git")) {
            throw new SkipException("git not available on this machine");
        }
        String node = resolveNode();
        if (node == null) {
            throw new SkipException("node not available (set NODE_BIN or add to PATH)");
        }

        File work = createTempDir("ft-indexer-fe-e2e");
        File srcRepo = new File(work, "fe-repo-src");
        writeText(new File(srcRepo, "package.json"), "{\"name\":\"fe-repo\",\"version\":\"1.0.0\"}\n");
        writeText(new File(srcRepo, "src/calc.ts"), FE_CALC);
        writeText(new File(srcRepo, "src/page.tsx"), FE_PAGE);
        git(srcRepo, "init", "-q", "-b", "master");
        git(srcRepo, "add", "-A");
        git(srcRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");

        IndexerConfig cfg = new IndexerConfig();
        cfg.setReposRoot(new File(work, "repos-root").getAbsolutePath());
        cfg.setBuildTimeoutMinutes(5);
        cfg.setNodeCmd(node); // 测试用本机 node（生产默认空=内置 linux 发行版）

        RegistryStore store = new RegistryStore(new File(cfg.getReposRoot(), "indexer"));
        IndexBuildService build = new IndexBuildService(cfg, store);
        IndexQueryService query = new IndexQueryService(cfg, store);

        IndexRepo repo = new IndexRepo();
        repo.setRepoCode("fe-repo");
        repo.setRepoName("FE Repo");
        repo.setGitUrl(srcRepo.getAbsolutePath());
        repo.setRefMode(IndexRepo.REF_MODE_BRANCH);
        repo.setRefValue("master");
        store.upsert(repo);

        boolean initOk = build.buildOne(store.find("fe-repo"), "INIT");
        if (!initOk) {
            System.out.println("[E2E-FE-DEBUG] INIT failed: " + store.find("fe-repo").getLastErrorMsg());
        }
        Assert.assertTrue(initOk, "frontend INIT build should succeed");
        IndexRepo built = store.find("fe-repo");
        Assert.assertEquals(built.getLastBuildStatus(), "SUCCESS");
        Assert.assertTrue(built.getLastIndexLines() > 0, "fe index lines > 0");
        Assert.assertTrue(new File(cfg.getReposRoot(), "indexer/fe-repo.jsonl").exists(), "fe jsonl produced");

        // 查询：符号 + 调用链（查询端零改动，同 schema）
        List<RepoIndex.Def> defs = query.searchSymbols("fe-repo", "calcTotal", 10, true);
        Assert.assertFalse(defs.isEmpty(), "calcTotal should be found in fe index");
        String sym = defs.get(0).sym;
        Assert.assertEquals(RepoIndex.simpleName(sym), "calcTotal");

        Map<String, Object> callers = query.callers("fe-repo", sym, 2, true);
        Assert.assertNull(callers.get("error"), "fe callers should resolve");
        List<?> edges = (List<?>) callers.get("edges");
        boolean pageCallsCalc = false;
        for (Object o : edges) {
            Map<?, ?> edge = (Map<?, ?>) o;
            if (String.valueOf(edge.get("from")).contains("page")) {
                pageCallsCalc = true;
            }
        }
        Assert.assertTrue(pageCallsCalc, "page.tsx should call calcTotal (import resolved)");
    }

    /** node 可执行路径：NODE_BIN 环境变量 > PATH > ~/.nvm 最新版本。 */
    private static String resolveNode() {
        String env = System.getenv("NODE_BIN");
        if (env != null && new File(env).canExecute()) {
            return env;
        }
        if (commandAvailable("node")) {
            return "node";
        }
        File nvm = new File(System.getProperty("user.home"), ".nvm/versions/node");
        File[] versions = nvm.listFiles();
        if (versions != null) {
            for (File v : versions) {
                File n = new File(v, "bin/node");
                if (n.canExecute()) {
                    return n.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static void writeSources(File repoDir) throws Exception {
        writeText(new File(repoDir, "pom.xml"), POM);
        writeText(new File(repoDir, "src/main/java/demo/PriceCalculator.java"), CALC);
        writeText(new File(repoDir, "src/main/java/demo/OrderService.java"), ORDER);
    }

    private static void writeText(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static boolean commandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            boolean ok = p.waitFor() == 0;
            p.destroy();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static void git(File dir, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(prepend("git", args));
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] all = new byte[8192];
        // consume output
        int n = p.getInputStream().read(all);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args[0] + " failed: " + new String(all, 0, Math.max(n, 0)));
        }
    }

    private static String[] prepend(String head, String... rest) {
        String[] cmd = new String[rest.length + 1];
        cmd[0] = head;
        System.arraycopy(rest, 0, cmd, 1, rest.length);
        return cmd;
    }

    private static File createTempDir(String prefix) throws Exception {
        File f = File.createTempFile(prefix, "");
        Assert.assertTrue(f.delete());
        Assert.assertTrue(f.mkdirs());
        return f;
    }
}
