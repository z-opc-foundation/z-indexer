package com.zifang.z.indexer.pipeline;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonObject;
import com.zifang.z.indexer.IndexerConfig;
import com.zifang.z.indexer.query.IndexQueryService;
import com.zifang.z.indexer.registry.BuildLogEntry;
import com.zifang.z.indexer.registry.GitUrlMasker;
import com.zifang.z.indexer.registry.IndexRepo;
import com.zifang.z.indexer.registry.RegistryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 构建管线编排：远端变更检测 → 同步工作副本 → 双轮索引（静态全量主索引 + 编译附带对照）
 * → JSONL 原子替换 → meta.json → 注册表/构建日志回写。
 * <p>双轮模式：轮1 静态全量（JavaParser 免编译秒级，indexType=STATIC，全量仓
 * 一律可索引）；轮2 编译附带（pom 注入+mvn，成功才写 &lt;repo&gt;.compiled.jsonl 对照产物，
 * 失败不影响主索引 SUCCESS）。staticFallbackEnabled=false 时回退纯编译模式（kill-switch）。
 * <p>串行调用（由 IndexSyncRunner 单线程驱动），方法自身保证单仓库状态闭环。
 */
public class IndexBuildService {

    private static final Logger log = LoggerFactory.getLogger(IndexBuildService.class);

    /** code-indexer jar 在 starter 打包资源中的位置 */
    public static final String INDEXER_JAR_RESOURCE = "/bin/code-indexer.jar";

    /** 内置 maven 发行版 zip 在 starter 打包资源中的位置（生产容器无 mvn 命令） */
    public static final String MAVEN_DIST_RESOURCE = "/bin/apache-maven-3.8.8-bin.zip";

    /** 内置 maven 解压后的顶层目录名 */
    private static final String MAVEN_DIR_NAME = "apache-maven-3.8.8";

    /** JDK8 tools.jar 在 starter 打包资源中的位置（纯 JRE 容器缺 javac，annotation processor 无法运行） */
    public static final String TOOLS_JAR_RESOURCE = "/bin/tools.jar";

    /** 前端索引器脚本在 starter 打包资源中的位置（与 Java 链路产出同 schema 的 jsonl） */
    public static final String FRONTEND_INDEXER_RESOURCE = "/bin/frontend-indexer.cjs";

    /** @babel/parser 包 zip（解压到 bin/node_modules/@babel/parser，前端索引器的唯一依赖） */
    public static final String BABEL_PARSER_RESOURCE = "/bin/babel-parser.zip";

    /** node linux-x64 二进制 zip（仅 node 单文件，解压到 bin/node-dist/node；容器无 node 命令） */
    public static final String NODE_BIN_RESOURCE = "/bin/node-bin.zip";

    /**
     * 内置 node 版本戳（须与 {@link #NODE_BIN_RESOURCE} 里那份二进制一致，改包即改戳）：
     * NAS 卷跨部署持久，仅判 exists 会沿用旧二进制。
     */
    public static final String NODE_BIN_VERSION = "v20.18.1";

    /** 最近一次 ensureToolsJar 的结果说明（空=成功），供环境自检透出 */
    private volatile String toolsJarStatus = "not-checked";

    /** 最近一次 ensureMavenDist 的结果说明（含解压失败根因），供环境自检透出 */
    private volatile String mavenDistStatus = "not-run";

    /** 最近一次前端工具链准备的结果说明，供环境自检透出 */
    private volatile String frontendToolchainStatus = "not-run";

    /** 构建完成回调（无论成败），供查询端失效缓存等。 */
    public interface BuildListener {
        void onBuildFinished(String repoCode, boolean success);
    }

    private final IndexerConfig config;
    private final RegistryStore registryStore;
    private final GitOps gitOps;
    private final ArtifactService artifactService;
    private volatile BuildListener buildListener;

    /** 查询端引用（构建让路用，装配层注入） */
    private volatile IndexQueryService queryService;

    /** 实例标识（分布式多实例下写入 status 文件的 builtBy 字段） */
    private volatile String instanceTag = "local";

    public IndexBuildService(IndexerConfig config, RegistryStore registryStore) {
        this.config = config;
        this.registryStore = registryStore;
        this.gitOps = new GitOps(config);
        this.artifactService = new ArtifactService(config);
    }

    public GitOps getGitOps() {
        return gitOps;
    }

    /** master 纯内容制品维护（构建 SUCCESS 后自动打包，HTTP 端点透出给快照链路） */
    public ArtifactService getArtifactService() {
        return artifactService;
    }

    public void setBuildListener(BuildListener buildListener) {
        this.buildListener = buildListener;
    }

    /** 装配层注入查询端（巨仓构建让路）。 */
    public void setQueryService(IndexQueryService queryService) {
        this.queryService = queryService;
    }

    /** 装配层注入实例标识（BuildLockManager.instanceId）。 */
    public void setInstanceTag(String instanceTag) {
        this.instanceTag = instanceTag;
    }

    private void fireBuildFinished(String repoCode, boolean success) {
        BuildListener l = buildListener;
        if (l != null) {
            try {
                l.onBuildFinished(repoCode, success);
            } catch (Exception e) {
                log.warn("[IndexBuild] build listener failed for {}: {}", repoCode, e.getMessage());
            }
        }
    }

    public File reposDir() {
        return new File(config.getReposRoot(), "repos");
    }

    public File indexerDir() {
        return new File(config.getReposRoot(), "indexer");
    }

    public File repoDir(String repoCode) {
        return new File(reposDir(), repoCode);
    }

    public File indexFile(String repoCode) {
        return new File(indexerDir(), repoCode + ".jsonl");
    }

    public File metaFile(String repoCode) {
        return new File(indexerDir(), repoCode + ".meta.json");
    }

    /**
     * 释放内置 maven 发行版到 reposRoot/bin/（幂等，目录存在即跳过）。
     * 返回 mvn 可执行文件路径（mvnCmd 显式配置时直接返回配置值）。
     */
    public String ensureMavenDist() {
        String cmd = config.getMvnCmd();
        if (cmd != null && !cmd.trim().isEmpty()) {
            mavenDistStatus = "explicit-cmd:" + cmd.trim();
            return cmd.trim();
        }
        File mvn = new File(new File(new File(config.getReposRoot(), "bin"), MAVEN_DIR_NAME), "bin/mvn");
        if (mvn.exists()) {
            ensureMavenSettings();
            mavenDistStatus = "dist-present:" + mvn.getAbsolutePath();
            return mvn.getAbsolutePath();
        }
        InputStream in = getClass().getResourceAsStream(MAVEN_DIST_RESOURCE);
        if (in == null) {
            mavenDistStatus = "resource-missing:" + MAVEN_DIST_RESOURCE + " (classpath=" + getClass().getResource(MAVEN_DIST_RESOURCE) + ")";
            log.warn("[IndexBuild] maven dist resource missing ({}), falling back to 'mvn'", MAVEN_DIST_RESOURCE);
            return "mvn";
        }
        try {
            File binRoot = new File(config.getReposRoot(), "bin");
            if (!binRoot.exists() && !binRoot.mkdirs()) {
                mavenDistStatus = "bin-root-mkdirs-failed:" + binRoot.getAbsolutePath();
                log.warn("[IndexBuild] cannot create {}", binRoot.getAbsolutePath());
                return "mvn";
            }
            File destDir = new File(binRoot, MAVEN_DIR_NAME);
            File marker = new File(binRoot, MAVEN_DIR_NAME + ".extracting");
            File marker2 = new File(binRoot, MAVEN_DIR_NAME + ".done");
            if (marker2.exists() && destDir.exists()) {
                mavenDistStatus = "dist-present:" + mvn.getAbsolutePath();
                return mvn.getAbsolutePath();
            }
            marker2.delete();
            writeText(marker, String.valueOf(System.currentTimeMillis()));
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in);
            try {
                java.util.zip.ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    File out = new File(binRoot, e.getName());
                    if (e.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }
                    out.getParentFile().mkdirs();
                    OutputStream fout = new FileOutputStream(out);
                    try {
                        copy(zin, fout);
                    } finally {
                        fout.close();
                    }
                    if ((e.getTime()) > 0) {
                        out.setLastModified(e.getTime());
                    }
                }
            } finally {
                zin.close();
            }
            // 类 unix 平台加执行权限
            try {
                mvn.setExecutable(true, false);
                File mvnCmdFile = new File(new File(new File(binRoot, MAVEN_DIR_NAME), "bin"), "mvn.cmd");
                if (mvnCmdFile.exists()) {
                    mvnCmdFile.setExecutable(true, false);
                }
            } catch (Exception ignore) {
                // chmod 失败不影响（进程用绝对路径执行）
            }
            marker.delete();
            writeText(marker2, "ok");
            ensureMavenSettings();
            mavenDistStatus = "dist-extracted:" + mvn.getAbsolutePath();
            log.info("[IndexBuild] maven dist extracted to {}", mvn.getAbsolutePath());
            return mvn.getAbsolutePath();
        } catch (Exception e) {
            mavenDistStatus = "extract-error: " + e;
            log.warn("[IndexBuild] maven dist extract failed, falling back to 'mvn': {}", e);
            return "mvn";
        }
    }

    /**
     * 释放/刷新内置 settings.xml 到 bin/（每次覆盖，与 jar 内资源保持一致；
     * 不覆盖已释放文件的话，镜像 snapshots 策略等修复无法随版本升级生效）。
     */
    private void ensureMavenSettings() {
        InputStream si = null;
        try {
            si = getClass().getResourceAsStream("/bin/settings.xml");
            if (si == null) {
                return;
            }
            OutputStream so = null;
            try {
                so = new FileOutputStream(new File(new File(config.getReposRoot(), "bin"), "settings.xml"));
                copy(si, so);
            } finally {
                if (so != null) {
                    so.close();
                }
            }
        } catch (Exception e) {
            log.warn("[IndexBuild] cannot refresh settings.xml: {}", e);
        } finally {
            if (si != null) {
                try {
                    si.close();
                } catch (IOException ignore) {
                    // 关闭失败无影响
                }
            }
        }
    }

    /**
     * 确保 javac 可用（幂等）：
     * <ul>
     * <li>当前运行时已带编译器（完整 JDK / JDK9+）→ 无事可做；</li>
     * <li>JRE8 → 释放内置 tools.jar 到 &lt;java.home&gt;/lib/tools.jar（JDK8 ToolProvider
     * 的标准查找路径，mvn 子进程的 maven-compiler-plugin 靠它拿到 systemJavaCompiler）；</li>
     * <li>父目录不可写 → 记录失败说明，透出到 environmentReport 供定位。</li>
     * </ul>
     */
    public void ensureToolsJar() {
        try {
            if (javax.tools.ToolProvider.getSystemJavaCompiler() != null) {
                toolsJarStatus = "system-compiler-present";
                return;
            }
            File lib = new File(System.getProperty("java.home"), "lib");
            File target = new File(lib, "tools.jar");
            if (target.exists() && target.length() > 0) {
                toolsJarStatus = "tools.jar-present:" + target.getAbsolutePath();
                return;
            }
            InputStream in = getClass().getResourceAsStream(TOOLS_JAR_RESOURCE);
            if (in == null) {
                toolsJarStatus = "resource-missing:" + TOOLS_JAR_RESOURCE;
                log.warn("[IndexBuild] tools.jar resource missing ({}), mvn compile will fail on JRE", TOOLS_JAR_RESOURCE);
                return;
            }
            if (!lib.exists() || !lib.canWrite()) {
                toolsJarStatus = "java-home-lib-not-writable:" + lib.getAbsolutePath();
                log.warn("[IndexBuild] cannot deploy tools.jar ({} not writable)", lib.getAbsolutePath());
                return;
            }
            OutputStream out = new FileOutputStream(target);
            try {
                copy(in, out);
            } finally {
                out.close();
                in.close();
            }
            toolsJarStatus = "tools.jar-deployed:" + target.getAbsolutePath();
            log.info("[IndexBuild] tools.jar deployed to {}", target.getAbsolutePath());
        } catch (Exception e) {
            toolsJarStatus = "error:" + e;
            log.warn("[IndexBuild] ensureToolsJar failed: {}", e);
        }
    }

    /** 环境自检（容器无 git/mvn/javac 的“三无”环境定位用，透出到监控接口）。 */
    public java.util.Map<String, Object> environmentReport() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("javaHome", System.getProperty("java.home"));
        m.put("osName", System.getProperty("os.name"));
        m.put("osArch", System.getProperty("os.arch"));
        m.put("systemJavacAvailable", javax.tools.ToolProvider.getSystemJavaCompiler() != null);
        m.put("toolsJarStatus", toolsJarStatus);
        m.put("jgitAvailable", true);
        try {
            m.put("mvnResolved", ensureMavenDist());
        } catch (Exception e) {
            m.put("mvnResolved", "error: " + e.getMessage());
        }
        m.put("mavenDistStatus", mavenDistStatus);
        m.put("frontendToolchainStatus", frontendToolchainStatus);
        File root = new File(config.getReposRoot());
        m.put("reposRoot", config.getReposRoot());
        m.put("reposRootUsableBytes", root.exists() ? root.getUsableSpace() : -1L);
        m.put("reposRootTotalBytes", root.exists() ? root.getTotalSpace() : -1L);
        m.put("reposRootFreeBytes", root.exists() ? root.getFreeSpace() : -1L);
        m.put("reposRootWritable", root.exists() && root.canWrite());
        return m;
    }

    /** 启动时释放 code-indexer.jar 到本地 maven 仓库（幂等，文件级拷贝，不依赖 install 插件）。 */
    public void ensureCodeIndexerJar() {
        try {
            String home = System.getProperty("user.home");
            File m2 = new File(home, ".m2/repository/" + PomIndexerPatcher.INDEXER_GROUP_ID.replace('.', '/')
                    + "/" + PomIndexerPatcher.INDEXER_ARTIFACT_ID + "/" + PomIndexerPatcher.INDEXER_VERSION);
            if (!m2.exists()) {
                m2.mkdirs();
            }
            File targetJar = new File(m2, PomIndexerPatcher.INDEXER_ARTIFACT_ID + "-"
                    + PomIndexerPatcher.INDEXER_VERSION + ".jar");
            InputStream in = getClass().getResourceAsStream(INDEXER_JAR_RESOURCE);
            if (in == null) {
                log.warn("[IndexBuild] code-indexer.jar resource missing ({}), repos depending on it will fail", INDEXER_JAR_RESOURCE);
                return;
            }
            try {
                if (targetJar.exists() && targetJar.length() == drain(in)) {
                    return; // 长度相同视为已安装
                }
            } finally {
                // drain 只读长度，需要重开流写文件
                in.close();
            }
            in = getClass().getResourceAsStream(INDEXER_JAR_RESOURCE);
            OutputStream out = new FileOutputStream(targetJar);
            try {
                copy(in, out);
            } finally {
                out.close();
                in.close();
            }
            // 最小 pom（maven 解析本地构件需要）
            File targetPom = new File(m2, PomIndexerPatcher.INDEXER_ARTIFACT_ID + "-"
                    + PomIndexerPatcher.INDEXER_VERSION + ".pom");
            writeText(targetPom, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                    + "  <modelVersion>4.0.0</modelVersion>\n"
                    + "  <groupId>" + PomIndexerPatcher.INDEXER_GROUP_ID + "</groupId>\n"
                    + "  <artifactId>" + PomIndexerPatcher.INDEXER_ARTIFACT_ID + "</artifactId>\n"
                    + "  <version>" + PomIndexerPatcher.INDEXER_VERSION + "</version>\n"
                    + "  <packaging>jar</packaging>\n"
                    + "</project>\n");
            log.info("[IndexBuild] code-indexer.jar installed to {}", targetJar.getAbsolutePath());
        } catch (Exception e) {
            log.warn("[IndexBuild] failed to install code-indexer.jar: {}", e.getMessage());
        }
    }

    /**
     * 构建入口。
     *
     * @param trigger INIT / SCHEDULE / MANUAL
     * @return 构建是否成功
     */
    public boolean buildOne(IndexRepo repo, String trigger) {
        return buildOne(repo, trigger, false);
    }

    /**
     * @param skipCompile 预热专用：跳过轮 2 mvn 编译对照（制品只依赖轮 1 STATIC 后的 master 工作区，
     *                    跳编译把单仓构建从分钟级压到秒级）。SKIPPED 保留 lastCompiledStatus 原值。
     */
    public boolean buildOne(IndexRepo repo, String trigger, boolean skipCompile) {
        long startedAt = System.currentTimeMillis();
        String repoCode = repo.getRepoCode();
        String commitBefore = repo.getLastIndexedCommit();
        BuildLogEntry entry = new BuildLogEntry();
        entry.setRepoCode(repoCode);
        entry.setTriggerType(trigger);
        entry.setCommitBefore(commitBefore);
        entry.setStartedAt(startedAt);

        log.info("[IndexBuild] start repo={} trigger={} mode={} ref={}",
                repoCode, trigger, repo.getRefMode(), repo.getRefValue());

        // 1. 解析目标 ref
        GitOps.RemoteRef remote = gitOps.lsRemoteRef(repo.getGitUrl(), repo.getRefMode(), repo.getRefValue());
        if (remote == null) {
            String detail = gitOps.getLastLsRemoteError();
            fail(repo, entry, startedAt, "ls-remote no match: mode=" + repo.getRefMode()
                    + " value=" + repo.getRefValue()
                    + (detail == null || detail.isEmpty() ? "" : "; " + detail));
            return false;
        }
        String resolvedRef = repo.isBranchMode() ? repo.getRefValue() : GitOps.normalizeRefName(remote.getRef());
        String targetSha = remote.getSha();
        entry.setResolvedRef(resolvedRef);
        entry.setCommitAfter(targetSha);

        // 2. 无变更跳过（INIT/MANUAL 强制重建）
        if (!"INIT".equals(trigger) && !"MANUAL".equals(trigger)
                && targetSha.equals(repo.getLastIndexedCommit())) {
            log.debug("[IndexBuild] repo={} unchanged at {}", repoCode, targetSha);
            return true;
        }

        repo.setLastBuildStatus("BUILDING");
        repo.setBuiltBy(instanceTag);
        registryStore.persistStatus(repo);

        // 巨仓让路：mvn 1g 堆与查询端高常驻叠加会顶破 4g limit（实证：巨仓构建中 pod
        // cgroup 重启，build-log 半行戛然而止），构建前驱逐 LRU 冷仓到低水位
        if (isBigRepo(repoCode) && "MAVEN".equalsIgnoreCase(repo.getBuildType())) {
            yieldForBuild(repoCode);
        }

        try {
            // 3. 工作副本同步（不存在则 clone）
            File dir = repoDir(repoCode);
            if (!new File(dir, ".git").exists()) {
                reposDir().mkdirs();
                GitOps.ExecResult cloneRes = gitOps.clone(repo.getGitUrl(), dir, 30);
                if (!cloneRes.isOk()) {
                    fail(repo, entry, startedAt, "git clone failed: " + cloneRes.tail(1000));
                    return false;
                }
            }
            GitOps.ExecResult sync = gitOps.syncTo(dir, resolvedRef, targetSha, repo.isBranchMode(), repo.getRefValue());
            if (!sync.isOk()) {
                fail(repo, entry, startedAt, "git sync failed: " + sync.tail(1000));
                return false;
            }

            // 4. 索引构建：按工程语言分流（java=静态主索引+编译附带对照；frontend=node 索引器）。
            // 双轮：轮1 静态全量主索引（免编译免依赖秒级，全量仓一律可索引）；轮2 编译附带
            // 对照（成功才写 <repo>.compiled.jsonl，失败不影响主索引 SUCCESS）。开关关闭时
            // 回退纯编译模式（编译成功 COMPILED，失败 FAILED——与双轨上线前行为一致）。
            File tmpIndex = new File(indexerDir(), ".tmp-" + repoCode + ".jsonl");
            if (tmpIndex.exists()) {
                tmpIndex.delete();
            }
            indexerDir().mkdirs();
            boolean javaRepo = !isFrontendRepo(repo, dir);
            String indexType;
            long lines;
            String compiledStatus = null;   // SUCCESS / FAILED / SKIPPED（前端仓 null）
            String compiledError = null;
            if (javaRepo) {
                if (config.isStaticFallbackEnabled()) {
                    // ── 轮1：静态主索引（全量，免编译免依赖）──
                    StaticJavaIndexer.Result sr = runStaticIndex(repoCode, dir, tmpIndex);
                    if (sr == null || !sr.ok()) {
                        fail(repo, entry, startedAt, "static index "
                                + (sr == null ? "error (see build log)"
                                : "produced 0 lines (" + sr.summary() + ")"));
                        return false;
                    }
                    indexType = "STATIC";
                    lines = sr.lines;
                    // ── 轮2：编译附带对照（尽力而为，编译失败不回滚主索引）──
                    String sidecar = runCompiledSidecar(repo, trigger, skipCompile, dir);
                    if (sidecar.startsWith("FAILED")) {
                        compiledStatus = "FAILED";
                        compiledError = sidecar.substring("FAILED:".length()).trim();
                    } else {
                        compiledStatus = sidecar;   // SUCCESS / SKIPPED
                    }
                    log.info("[IndexBuild] repo={} STATIC main indexed: {}; compiled sidecar: {}",
                            repoCode, sr.summary(), sidecar);
                } else {
                    // ── 纯编译模式（kill-switch 回退，无静态参与）──
                    File pom = new File(dir, "pom.xml");
                    new PomIndexerPatcher(dir, tmpIndex).patch(pom);
                    GitOps.ExecResult buildRes = runMvn(dir);
                    if (!buildRes.isOk()) {
                        restorePom(dir);
                        fail(repo, entry, startedAt, "mvn compile failed (exit=" + buildRes.getExitCode()
                                + "): " + buildRes.tail(1500));
                        return false;
                    }
                    lines = countLines(tmpIndex);
                    if (lines <= 0) {
                        restorePom(dir);
                        fail(repo, entry, startedAt, "no index lines produced (pom inject not effective?)");
                        return false;
                    }
                    indexType = "COMPILED";
                }
            } else {
                GitOps.ExecResult buildRes = runFrontendIndexer(dir, tmpIndex);
                if (!buildRes.isOk()) {
                    fail(repo, entry, startedAt, "frontend index failed (exit=" + buildRes.getExitCode()
                            + "): " + buildRes.tail(1500));
                    return false;
                }
                lines = countLines(tmpIndex);
                if (lines <= 0) {
                    fail(repo, entry, startedAt, "no index lines produced (no source files matched?)");
                    return false;
                }
                indexType = "STATIC";   // 前端索引器本质即静态
            }

            // 5. 原子替换 + meta
            File target = indexFile(repoCode);
            long expectBytes = tmpIndex.length();
            forceCommit(tmpIndex);
            if (!tmpIndex.renameTo(target)) {
                target.delete();
                if (!tmpIndex.renameTo(target)) {
                    restorePom(dir);
                    fail(repo, entry, startedAt, "atomic replace failed for " + target.getAbsolutePath());
                    return false;
                }
            }
            if (target.length() != expectBytes) {
                // rename 后 server 端字节数与写入端不符 = NFS 上数据未 COMMIT 落地，
                // 此时落盘的是一份静默损坏的索引，而 meta.lines 记的是 rename 前的行数，
                // 两者对不上且无人会发现。宁可报失败让下轮重建。
                long actual = target.length();
                target.delete();
                restorePom(dir);
                fail(repo, entry, startedAt, "index truncated after atomic replace (expected "
                        + expectBytes + " bytes, got " + actual + ") for "
                        + target.getAbsolutePath());
                return false;
            }
            long finishedAt = System.currentTimeMillis();
            JsonObject meta = new JsonObject();
            meta.put("repoCode", repoCode);
            meta.put("gitUrl", GitUrlMasker.mask(repo.getGitUrl()));
            meta.put("ref", resolvedRef);
            meta.put("commit", targetSha);
            meta.put("builtAt", finishedAt);
            meta.put("durationMs", finishedAt - startedAt);
            meta.put("lines", lines);
            meta.put("indexType", indexType);
            // 编译附带对照信息（仅 java 双轮模式；前端仓/纯编译模式无此节）
            if (compiledStatus != null) {
                JsonObject cm = new JsonObject();
                cm.put("status", compiledStatus);
                if ("SUCCESS".equals(compiledStatus)) {
                    cm.put("lines", repo.getLastCompiledLines());
                }
                if (compiledError != null) {
                    cm.put("error", compiledError.length() > 500 ? compiledError.substring(0, 500) : compiledError);
                }
                meta.put("compiled", cm);
            }
            writeText(metaFile(repoCode), JsonUtil.toJson(meta) + "\n");

            // 还原 pom 注入（编译轮在 sidecar 内已还原；此处幂等兜底；前端链路无注入无副作用）
            if (javaRepo) {
                restorePom(dir);
            }

            // 6. 注册表回写（编译附带状态独立记录，不影响主索引 SUCCESS 语义）
            repo.setLastIndexedCommit(targetSha);
            repo.setLastIndexedRef(resolvedRef);
            repo.setLastBuildStatus("SUCCESS");
            repo.setLastBuildDurationMs(finishedAt - startedAt);
            repo.setLastIndexLines(lines);
            repo.setLastIndexType(indexType);
            if (compiledStatus != null) {
                repo.setLastCompiledStatus(compiledStatus);
                if ("FAILED".equals(compiledStatus)) {
                    if (compiledError != null && compiledError.length() > 2000) {
                        compiledError = compiledError.substring(0, 2000);
                    }
                    repo.setLastCompiledError(compiledError);
                } else if ("SUCCESS".equals(compiledStatus)) {
                    repo.setLastCompiledError(null);   // 修复后清历史失败摘要
                }
                // SKIPPED：保留上次失败摘要供运维归因
            }
            repo.setLastBuildTime(finishedAt);
            repo.setLastErrorMsg(null);
            repo.setBuiltBy(instanceTag);
            repo.setUpdatedAt(finishedAt);
            registryStore.persistStatus(repo);

            entry.setStatus("SUCCESS");
            entry.setDurationMs(finishedAt - startedAt);
            entry.setIndexLines(lines);
            entry.setIndexType(indexType);
            entry.setFinishedAt(finishedAt);
            registryStore.appendBuildLog(entry);
            log.info("[IndexBuild] repo={} SUCCESS({}) ref={} commit={} lines={} in {}ms",
                    repoCode, indexType, resolvedRef, targetSha, lines, finishedAt - startedAt);
            // master 工作区即最新，顺手异步打包纯内容制品（串行队列，失败不影响构建结果）
            artifactService.submitBuild(repoCode, targetSha);
            fireBuildFinished(repoCode, true);
            return true;
        } catch (Exception e) {
            fail(repo, entry, startedAt, "unexpected: " + e);
            return false;
        }
    }

    /** 是否配置的巨仓（big-repo-codes 逗号分隔）。 */
    private boolean isBigRepo(String repoCode) {
        String big = config.getBigRepoCodes();
        if (big == null || big.trim().isEmpty()) {
            return false;
        }
        for (String c : big.split(",")) {
            if (repoCode.equals(c.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 巨仓构建让路：驱逐 LRU 冷仓到低水位（build-yield-repos），同步触发一次 System.gc()
     * 让 JVM 尽快归还 RepoIndex 垃圾占用的堆页（CMS 已提交页不主动还 OS，
     * 显式 Full GC 才有机会收缩，让路才真正体现在 RSS 上）。
     * 失败静默——让路是尽力而为，不阻断构建本身。
     */
    private void yieldForBuild(String repoCode) {
        IndexQueryService qs = queryService;
        if (qs == null) {
            return;
        }
        try {
            List<String> evicted = qs.shrinkTo(Math.max(1, config.getBuildYieldRepos()));
            if (!evicted.isEmpty()) {
                log.info("[IndexBuild] big repo {} yields: evicted {} cold repos {}",
                        repoCode, evicted.size(), evicted);
                System.gc();
            }
        } catch (Exception e) {
            log.warn("[IndexBuild] yield for {} failed (non-fatal): {}", repoCode, e.getMessage());
        }
    }

    /**
     * 双轮轮1：JavaParser 静态索引（免编译、免依赖，秒级，全量主索引）。
     * 异常不抛出（返回 null 视为失败，走 fail 路径）。
     */
    private StaticJavaIndexer.Result runStaticIndex(String repoCode, File dir, File tmpIndex) {
        try {
            return new StaticJavaIndexer().index(dir, tmpIndex);
        } catch (Exception e) {
            log.warn("[IndexBuild] repo={} static index error: {}", repoCode, String.valueOf(e));
            return null;
        }
    }

    /** 编译附带对照产物（&lt;repoCode&gt;.compiled.jsonl，供静态索引质量校准回放；查询端不加载）。 */
    public File compiledFile(String repoCode) {
        return new File(indexerDir(), repoCode + ".compiled.jsonl");
    }

    /**
     * 双轮轮2（编译附带对照）：pom 注入 + mvn → &lt;repoCode&gt;.compiled.jsonl。
     * 编译失败/空产出返回 "FAILED:摘要"，不影响主索引 SUCCESS；
     * SCHEDULE 且上次编译失败时返回 "SKIPPED"（省 mvn 数分钟，MANUAL/INIT 永远重试——
     * 业务侧修复依赖后人工 rebuild 即可补对照产物）；成功返回 "SUCCESS"。
     */
    private String runCompiledSidecar(IndexRepo repo, String trigger, boolean skipCompile, File dir) {
        if (skipCompile || ("SCHEDULE".equals(trigger) && "FAILED".equals(repo.getLastCompiledStatus()))) {
            return "SKIPPED";
        }
        String repoCode = repo.getRepoCode();
        File tmp = new File(indexerDir(), ".tmp-" + repoCode + "-compiled.jsonl");
        if (tmp.exists()) {
            tmp.delete();
        }
        try {
            File pom = new File(dir, "pom.xml");
            new PomIndexerPatcher(dir, tmp).patch(pom);
            GitOps.ExecResult res = runMvn(dir);
            if (!res.isOk()) {
                return "FAILED:mvn compile failed (exit=" + res.getExitCode() + "): " + res.tail(1200);
            }
            long n = countLines(tmp);
            if (n <= 0) {
                return "FAILED:no index lines produced (pom inject not effective?)";
            }
            File target = compiledFile(repoCode);
            long expectBytes = tmp.length();
            forceCommit(tmp);
            if (!tmp.renameTo(target)) {
                target.delete();
                if (!tmp.renameTo(target)) {
                    return "FAILED:atomic replace failed for " + target.getName();
                }
            }
            if (target.length() != expectBytes) {
                long actual = target.length();
                target.delete();
                return "FAILED:compiled index truncated after atomic replace (expected "
                        + expectBytes + " bytes, got " + actual + ")";
            }
            repo.setLastCompiledLines(n);
            return "SUCCESS";
        } catch (Exception e) {
            return "FAILED:unexpected: " + e;
        } finally {
            restorePom(dir);
            tmp.delete();
        }
    }

    /** 起子进程 mvn（batch 模式，超时由配置控制）。 */
    private GitOps.ExecResult runMvn(File dir) {
        ensureToolsJar();
        String mvnCmd = ensureMavenDist();
        List<String> cmd = new ArrayList<String>();
        cmd.add(mvnCmd);
        cmd.add("clean");
        cmd.add("compile");
        cmd.add("-B");
        // -U 强刷 SNAPSHOT/失败缓存：首次解析失败的 SNAPSHOT（含 parent pom 那种）
        // 会被 Maven 本地仓库缓存 24h 不再重试，必须 -U 才能恢复（实测不加 -U 恒 exit=1）
        cmd.add("-U");
        cmd.add("-DskipTests");
        cmd.add("-Dmaven.test.skip=true");
        // 内置 maven 时用内置 settings（默认镜像 Maven Central，见 resources/bin/settings.xml）
        File settings = new File(new File(config.getReposRoot(), "bin"), "settings.xml");
        if (mvnCmd.endsWith("/bin/mvn") && settings.exists()) {
            cmd.add("-s");
            cmd.add(settings.getAbsolutePath());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        pb.environment().put("MAVEN_OPTS", config.getMavenOpts());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        // 内置 maven 需要 JAVA_HOME（容器内 java.home 即服务自身 JDK 8）
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        return runProcess(pb);
    }
    
    /**
     * 起子进程并捕获输出：stdout/stderr 合流进内存（tail 摘要用）+ 全量落盘
     * build-logs/&lt;repoCode&gt;.log（覆盖式留最近一次）。lastErrorMsg 只存尾部，
     * 巨仓 mvn 的 [ERROR] 编译错误行常被截掉，全量日志是归因唯一可靠来源。
     * <p>读流必须在独立线程：主线程 waitFor(timeout) 才能真正超时杀进程
     * （读流在主线程会阻塞到进程退出，活进程无输出时 timeout 永不触发，
     * 巨仓僵尸进程挂 40min+ 实证）。
     */
    private GitOps.ExecResult runProcess(ProcessBuilder pb) {
        Process p = null;
        final PrintWriter buildLog = openBuildLog(pb.directory());
        try {
            p = pb.start();
            final Process proc = p;
            final StringBuilder out = new StringBuilder();
            Thread tap = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            synchronized (out) {
                                out.append(line).append('\n');
                            }
                            if (buildLog != null) {
                                buildLog.println(line);
                            }
                        }
                    } catch (IOException ignore) {
                        // 进程销毁时中断
                    }
                }
            }, "build-tap-" + pb.directory().getName());
            tap.setDaemon(true);
            tap.start();
            boolean finished = proc.waitFor(config.getBuildTimeoutMinutes(), TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                try {
                    tap.join(3000);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
                return new GitOps.ExecResult(-1, "TIMEOUT after " + config.getBuildTimeoutMinutes() + "min\n"
                        + snapshot(out));
            }
            try {
                tap.join(5000);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            return new GitOps.ExecResult(proc.exitValue(), snapshot(out));
        } catch (Exception e) {
            if (p != null) {
                p.destroyForcibly();
            }
            return new GitOps.ExecResult(-1, "EXEC_ERROR: " + e.getMessage());
        } finally {
            if (buildLog != null) {
                buildLog.close();
            }
        }
    }
    
    /** 线程安全取 StringBuilder 快照。 */
    private static String snapshot(StringBuilder sb) {
        synchronized (sb) {
            return sb.toString();
        }
    }
    
    /** 构建日志落盘句柄（失败静默降级，不影响构建本身；autoflush 保证尾部实时可查）。 */
    private PrintWriter openBuildLog(File repoDir) {
        try {
            File logs = new File(config.getReposRoot(), "build-logs");
            if (!logs.exists() && !logs.mkdirs()) {
                return null;
            }
            return new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(buildLogFile(repoDir.getName()), false), StandardCharsets.UTF_8)), true);
        } catch (IOException e) {
            log.warn("[IndexBuild] build-log open failed for {}: {}", repoDir.getName(), e.getMessage());
            return null;
        }
    }
    
    /** 构建日志路径（admin build-log 接口读取）。 */
    public File buildLogFile(String repoCode) {
        return new File(new File(config.getReposRoot(), "build-logs"), repoCode + ".log");
    }

    /** 构建失败/异常后还原 pom（reset --hard 的兜底，避免脏工作区影响下轮 pull）。 */
    private void restorePom(File dir) {
        gitOps.checkoutPom(dir);
    }

    /**
     * 是否前端工程：language 显式声明优先，否则目录探测
     * （package.json 存在且无 pom.xml——混合仓按 java 处理，maven 主构建）。 
     */
    private boolean isFrontendRepo(IndexRepo repo, File dir) {
        String lang = repo.getLanguage();
        if ("frontend".equalsIgnoreCase(lang)) {
            return true;
        }
        if ("java".equalsIgnoreCase(lang)) {
            return false;
        }
        return new File(dir, "package.json").exists() && !new File(dir, "pom.xml").exists();
    }

    /**
     * 前端工具链准备（幂等）：释放索引器脚本（每次覆盖，随版本升级）+ @babel/parser 包 + node 运行时。
     * 返回 node 可执行路径；nodeCmd 显式配置时直接用配置值（E2E 本机 node）。
     */
    public String ensureFrontendToolchain() {
        try {
            File binRoot = new File(config.getReposRoot(), "bin");
            if (!binRoot.exists() && !binRoot.mkdirs()) {
                frontendToolchainStatus = "bin-root-mkdirs-failed";
                return "node";
            }
            // 1) 索引器脚本（覆盖式，与 settings.xml 同策略）
            copyResource(FRONTEND_INDEXER_RESOURCE, new File(binRoot, "frontend-indexer.cjs"));
            // 2) @babel/parser 包（marker 幂等）
            File parserPkg = new File(binRoot, "node_modules/@babel/parser/package.json");
            if (!parserPkg.exists()) {
                unzipResource(BABEL_PARSER_RESOURCE, new File(binRoot, "node_modules/@babel/parser"));
            }
            // 3) node 运行时（显式配置优先；否则释放内置 linux-x64）
            String cmd = config.getNodeCmd();
            if (cmd != null && !cmd.trim().isEmpty()) {
                frontendToolchainStatus = "explicit-node:" + cmd.trim() + ",script-ok";
                return cmd.trim();
            }
            File nodeBin = new File(binRoot, "node-dist/node");
            File nodeMarker = new File(binRoot, "node-dist/.node-version");
            if (!nodeBin.exists() || nodeBin.length() == 0L
                    || !NODE_BIN_VERSION.equals(readSmallText(nodeMarker))) {
                // NAS 卷跨部署持久：残留旧版本二进制必须显式清除，否则仅判 exists 会沿用旧 node
                if (nodeBin.exists() && !nodeBin.delete()) {
                    throw new IllegalStateException("cannot replace stale node binary: " + nodeBin);
                }
                unzipResource(NODE_BIN_RESOURCE, new File(binRoot, "node-dist"));
                nodeBin.setExecutable(true, false);
                writeSmallText(nodeMarker, NODE_BIN_VERSION);
            }
            frontendToolchainStatus = "node-ok:" + nodeBin.getAbsolutePath() + ",script-ok";
            return nodeBin.getAbsolutePath();
        } catch (Exception e) {
            frontendToolchainStatus = "error: " + e;
            log.warn("[IndexBuild] ensureFrontendToolchain failed: {}", e.getMessage());
            return "node";
        }
    }

    /** classpath 资源 → 文件（覆盖式）。 */
    private void copyResource(String resource, File target) throws Exception {
        InputStream in = getClass().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("resource missing: " + resource);
        }
        OutputStream out = new FileOutputStream(target);
        try {
            copy(in, out);
        } finally {
            out.close();
            in.close();
        }
    }

    /** 读小文本文件（marker 用）；不存在/读失败返回 null。 */
    private static String readSmallText(File f) {
        if (!f.isFile()) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[256];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            } finally {
                in.close();
            }
            return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 写小文本文件（marker 用，覆盖式）。 */
    private static void writeSmallText(File f, String s) throws Exception {
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    /** classpath zip 资源 → 目录（zip 内容为包根，直接展开到 destDir）。 */
    private void unzipResource(String resource, File destDir) throws Exception {
        InputStream in = getClass().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("resource missing: " + resource);
        }
        destDir.mkdirs();
        java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in);
        try {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                OutputStream fout = new FileOutputStream(out);
                try {
                    copy(zin, fout);
                } finally {
                    fout.close();
                }
            }
        } finally {
            zin.close();
        }
    }

    /**
     * 跑前端索引器：node frontend-indexer.cjs --root dir --out tmp.jsonl。
     * 输出同 schema jsonl（def/call），查询端零改动。
     */
    private GitOps.ExecResult runFrontendIndexer(File dir, File tmpIndex) {
        String nodeCmd = ensureFrontendToolchain();
        List<String> cmd = new ArrayList<String>();
        cmd.add(nodeCmd);
        cmd.add(new File(new File(config.getReposRoot(), "bin"), "frontend-indexer.cjs").getAbsolutePath());
        cmd.add("--root");
        cmd.add(dir.getAbsolutePath());
        cmd.add("--out");
        cmd.add(tmpIndex.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        // @babel/parser 从释放目录 resolve；堆限 768m 与 mvn 同预算（防大仓 OOM 拖垮容器）
        pb.environment().put("NODE_PATH",
                new File(new File(config.getReposRoot(), "bin"), "node_modules").getAbsolutePath());
        pb.environment().put("NODE_OPTIONS", "--max-old-space-size=768");
        return runProcess(pb);
    }

    /** 环境自检透出前端工具链状态。 */
    public String getFrontendToolchainStatus() {
        return frontendToolchainStatus;
    }

    private void fail(IndexRepo repo, BuildLogEntry entry, long startedAt, String message) {
        long finishedAt = System.currentTimeMillis();
        if (message != null && message.length() > 2000) {
            message = message.substring(0, 2000);
        }
        repo.setLastBuildStatus("FAILED");
        repo.setLastBuildDurationMs(finishedAt - startedAt);
        repo.setLastBuildTime(finishedAt);
        repo.setLastErrorMsg(message);
        repo.setBuiltBy(instanceTag);
        repo.setUpdatedAt(finishedAt);
        registryStore.persistStatus(repo);
        entry.setStatus("FAILED");
        entry.setDurationMs(finishedAt - startedAt);
        entry.setErrorMsg(message);
        entry.setFinishedAt(finishedAt);
        registryStore.appendBuildLog(entry);
        log.error("[IndexBuild] repo={} FAILED: {}", repo.getRepoCode(), message);
        fireBuildFinished(repo.getRepoCode(), false);
    }

    /**
     * rename 前强制 NFS COMMIT。
     * <p>
     * {@code FileOutputStream.close()} 只把数据刷到 client 缓冲；reposRoot 挂在
     * ReadWriteMany NAS 上，rename 是独立的元数据操作，可能先于数据落 server，
     * 于是 {@code rename} 成功而 server 端看到 0 字节文件。
     * 这个坑本仓已在 {@code ArtifactService} 实测踩到（几十 MB 制品 rename 后
     * Content-Length=0）并用 {@code fos.getFD().sync()} 修掉；索引产物走的是
     * 同一种"写 tmp → rename"的形状，此前漏了同一道 COMMIT。
     * <p>
     * 这里以追加模式重开一次再 sync：目的是对该 inode 发 NFS COMMIT，
     * 不写入任何字节，文件内容不受影响。
     * <p>
     * 追加模式会在文件不存在时<b>创建</b>它，所以先挡一道：
     * 不存在的 / 空文件没有可 COMMIT 的数据，顺手凭空造出一个 0 字节
     * {@code .tmp-<code>.jsonl} 只会污染 indexer 目录并误导后续排障。
     *
     * @return true=已 COMMIT；false=无需或无法 COMMIT（交由调用方靠 rename 后字节数校验兜底）
     */
    static boolean forceCommit(File f) {
        if (f == null || !f.isFile() || f.length() == 0L) {
            return false;
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, true);
            fos.getFD().sync();
            return true;
        } catch (Exception e) {
            log.warn("[IndexBuild] force commit failed for {}: {}", f, e.getMessage());
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignore) {
                    // close 失败不影响已发出的 COMMIT
                }
            }
        }
    }

    private static long countLines(File file) throws IOException {
        if (!file.exists()) {
            return 0;
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            long n = 0;
            while (reader.readLine() != null) {
                n++;
            }
            return n;
        } finally {
            reader.close();
        }
    }

    private static void writeText(File file, String content) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
    }

    private static long drain(InputStream in) throws IOException {
        long n = 0;
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) {
            n += r;
        }
        return n;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
    }
}
