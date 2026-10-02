package com.zifang.z.indexer.controller;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonObject;
import com.zifang.z.indexer.pipeline.GitOps;
import com.zifang.z.indexer.pipeline.IndexBuildService;
import com.zifang.z.indexer.query.IndexQueryService;
import com.zifang.z.indexer.registry.IndexRepo;
import com.zifang.z.indexer.registry.RegistryStore;
import com.zifang.z.indexer.schedule.IndexSyncRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 索引服务管理 REST API（注册表 CRUD + 手动重建）。
 * <p>统一返回 {@code {"ok": true/false, ...}}。
 */
@RestController
@RequestMapping("/index/admin")
public class IndexAdminController {

    private static final Logger log = LoggerFactory.getLogger(IndexAdminController.class);

    @Autowired
    private RegistryStore registryStore;

    @Autowired
    private IndexSyncRunner syncRunner;

    /** 索引产物落盘路径（indexer/&lt;repoCode&gt;.jsonl + meta）与 ls-remote 校验。 */
    @Autowired
    private IndexBuildService buildService;

    /** 上传成功后触发异步冷加载（大仓不阻塞上传响应）。 */
    @Autowired
    private IndexQueryService queryService;

    /** 仓库列表（含构建状态）。 */
    @RequestMapping(value = "/repos", method = RequestMethod.GET)
    public Map<String, Object> listRepos() {
        Map<String, Object> resp = ok();
        List<Map<String, Object>> repos = new ArrayList<Map<String, Object>>();
        for (IndexRepo repo : registryStore.listRepos()) {
            repos.add(toMap(repo));
        }
        resp.put("repos", repos);
        return resp;
    }

    /**
     * 注册/更新仓库。
     * <p>body: {repoCode, repoName, gitUrl, refMode: BRANCH|TAG_PATTERN|TAG_LATEST,
     * refValue, enabled, remark}
     */
    @RequestMapping(value = "/repos", method = RequestMethod.POST)
    public Map<String, Object> upsertRepo(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp;
        try {
            IndexRepo input = fromMap(body);
            IndexRepo saved = registryStore.upsert(input);
            resp = ok();
            resp.put("repo", toMap(saved));
            log.info("[IndexAdmin] repo upserted: {}", saved.getRepoCode());
        } catch (IllegalArgumentException e) {
            resp = fail(e.getMessage());
        } catch (Exception e) {
            resp = fail("upsert failed: " + e.getMessage());
        }
        return resp;
    }

    @RequestMapping(value = "/repos/{repoCode}", method = RequestMethod.DELETE)
    public Map<String, Object> removeRepo(@PathVariable("repoCode") String repoCode) {
        Map<String, Object> resp = ok();
        resp.put("removed", registryStore.remove(repoCode));
        return resp;
    }

    /** 启停。 */
    @RequestMapping(value = "/repos/{repoCode}/enabled", method = RequestMethod.PUT)
    public Map<String, Object> toggleEnabled(@PathVariable("repoCode") String repoCode,
                                             @RequestBody Map<String, Object> body) {
        IndexRepo repo = registryStore.find(repoCode);
        if (repo == null) {
            return fail("repo not found: " + repoCode);
        }
        Object enabled = body.get("enabled");
        repo.setEnabled(enabled == null || Boolean.parseBoolean(String.valueOf(enabled)));
        registryStore.persistRegistry();
        Map<String, Object> resp = ok();
        resp.put("repo", toMap(repo));
        return resp;
    }

    /** 手动重建（MANUAL，强制即使无变更也重建）；skipCompile=true 走预热快路（仅静态索引+打制品）。 */
    @RequestMapping(value = "/repos/{repoCode}/rebuild", method = RequestMethod.POST)
    public Map<String, Object> rebuild(@PathVariable("repoCode") String repoCode,
                                       @RequestParam(value = "skipCompile", required = false, defaultValue = "false") Boolean skipCompile) {
        IndexRepo repo = registryStore.find(repoCode);
        if (repo == null) {
            return fail("repo not found: " + repoCode);
        }
        boolean success = syncRunner.rebuildOne(repoCode, Boolean.TRUE.equals(skipCompile));
        Map<String, Object> resp = ok();
        resp.put("success", success);
        resp.put("repo", toMap(registryStore.find(repoCode)));
        return resp;
    }

    /**
     * 构建日志尾部（全量在 build-logs/&lt;repoCode&gt;.log）。lastErrorMsg 只存尾部 2000 字，
     * 巨仓 mvn 的 [ERROR] 编译错误行常被截掉，归因从这里看。
     */
    @RequestMapping(value = "/repos/{repoCode}/build-log", method = RequestMethod.GET)
    public Map<String, Object> buildLog(@PathVariable("repoCode") String repoCode,
                                       @RequestParam(value = "tailLines", defaultValue = "300") int tailLines) {
        if (tailLines < 1 || tailLines > 2000) {
            return fail("tailLines must be in [1, 2000]");
        }
        File logFile = buildService.buildLogFile(repoCode);
        if (!logFile.exists()) {
            return fail("build log not found: " + logFile.getAbsolutePath());
        }
        // 环形缓冲只留尾部 N 行（巨仓全量日志可数 MB，避免大响应）
        ArrayDeque<String> tail = new ArrayDeque<String>(tailLines);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(logFile), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() >= tailLines) {
                    tail.pollFirst();
                }
                tail.addLast(line);
            }
        } catch (Exception e) {
            return fail("read failed: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignore) {
                }
            }
        }
        Map<String, Object> resp = ok();
        resp.put("repoCode", repoCode);
        resp.put("logFile", logFile.getAbsolutePath());
        resp.put("lines", tail.size());
        resp.put("log", String.join("\n", tail));
        return resp;
    }

    /** 立即触发一轮全量轮询（不等定时）。 */
    @RequestMapping(value = "/poll", method = RequestMethod.POST)
    public Map<String, Object> pollNow() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                syncRunner.pollOnce();
            }
        }, "manual-poll").start();
        Map<String, Object> resp = ok();
        resp.put("triggered", true);
        return resp;
    }

    /** 上传产物大小上限（前端仓 jsonl 最大几十 MB，留 256MB 余量）。 */
    private static final long MAX_UPLOAD_BYTES = 256L * 1024 * 1024;

    /** 每仓一把上传锁：网关对慢请求会 retry（0902 实证），串行化并发重复请求，防交错写。 */
    private final ConcurrentHashMap<String, Object> uploadLocks = new ConcurrentHashMap<String, Object>();

    /**
     * 索引产物上传（本地生产模式）：容器内无法构建的仓（如前端，容器 glibc 跑不动 node）
     * 由外部在本地生成 jsonl 后直接上传。body 为原始 jsonl 字节流（def/call 行，同构建产物 schema）。
     * <p>?commit= 为产物对应的分支 sha，服务端 ls-remote 校验与远端 HEAD 一致
     * （不一致拒绝，让调用方重新生成），落盘后 meta/status 同步 SUCCESS，
     * lastIndexedCommit 对齐远端 → poller 视为无变更跳过，不会触发容器内重建。
     * <p>?sha256= 为产物摘要（64 位 hex，客户端 shasum -a 256 生成），端到端校验。
     * <p>产物标注 indexType=STATIC（上传即静态分析产物，与容器内双轮主索引同语义）。
     * <p>读流按 Content-Length 硬截断：0902 实证经网关转发的请求流可能永不返回 EOF
     * （read 不返回 -1），无截断会无限写盘（4 请求 15 分钟写爆 103GB NAS）；
     * 且网关 retry 造成的并发重复请求会交错写盘（同 tmp 被 truncate 两次、双流独立
     * offset 交错，落盘行数错乱+末行截断）——故 tmp 名带 UUID 隔离并发流，
     * 落盘后 sha256 比对不符即拒绝删除，total != expected（流早断）也拒绝。
     */
    @RequestMapping(value = "/repos/{repoCode}/upload", method = RequestMethod.POST)
    public Map<String, Object> uploadIndex(@PathVariable("repoCode") String repoCode,
                                            @RequestParam("commit") String commit,
                                            @RequestParam("sha256") String sha256,
                                            HttpServletRequest request) {
        long startedAt = System.currentTimeMillis();
        IndexRepo repo = registryStore.find(repoCode);
        if (repo == null) {
            return fail("repo not found: " + repoCode);
        }
        if (commit == null || !commit.matches("[0-9a-fA-F]{7,40}")) {
            return fail("invalid commit sha: " + commit);
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            return fail("invalid sha256 (64 hex chars expected): " + sha256);
        }
        long expected = request.getContentLengthLong();
        if (expected <= 0) {
            return fail("Content-Length required (got " + expected + "); use --data-binary @file");
        }
        if (expected > MAX_UPLOAD_BYTES) {
            return fail("payload too large: " + expected + " bytes, max " + MAX_UPLOAD_BYTES);
        }
        // 远端 HEAD 校验：不一致说明本地产物已过期（索引内容与 sha 对不上，且 poll 会对不上号）
        GitOps.RemoteRef remote = null;
        try {
            remote = buildService.getGitOps().lsRemoteRef(
                    repo.getGitUrl(), repo.getRefMode(), repo.getRefValue());
        } catch (Exception e) {
            log.warn("[IndexAdmin] upload ls-remote failed for {}: {}", repoCode, e.getMessage());
        }
        if (remote == null) {
            return fail("ls-remote failed for " + repoCode + ": " + buildService.getGitOps().getLastLsRemoteError()
                    + " (cannot verify commit, upload rejected)");
        }
        if (!commit.equalsIgnoreCase(remote.getSha())) {
            return fail("commit mismatch: remote " + repo.getRefValue() + " HEAD is " + remote.getSha()
                    + " but payload claims " + commit + "; regenerate locally and re-upload");
        }
        String resolvedRef = repo.isBranchMode() ? repo.getRefValue() : GitOps.normalizeRefName(remote.getRef());

        // 同仓上传互斥：网关 retry 产生的并发重复请求排队处理（内容相同，后者覆盖前者无害）
        Object lock = uploadLocks.computeIfAbsent(repoCode, k -> new Object());
        synchronized (lock) {
        // tmp 名带 UUID：0902 实证固定名会被并发 retry 的第二个请求 truncate，
        // 双流独立 offset 交错写盘（行数错乱+末行截断）；唯一名让并发流各写各的
        File tmp = new File(buildService.indexerDir(), ".upload-" + repoCode + "-" + UUID.randomUUID() + ".jsonl");
        File target = buildService.indexFile(repoCode);
        long lines = 0;
        String actualSha256;
        try {
            buildService.indexerDir().mkdirs();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            OutputStream out = new DigestOutputStream(new FileOutputStream(tmp), digest);
            long total = 0;
            try {
                byte[] buf = new byte[8192];
                int n;
                boolean tailNewline = true;
                // 读满 Content-Length 即止（流不正常 EOF 也不多读，防无限写）
                while (total < expected && (n = request.getInputStream().read(buf)) > 0) {
                    int take = (int) Math.min(n, expected - total);
                    out.write(buf, 0, take);
                    total += take;
                    for (int i = 0; i < take; i++) {
                        if (buf[i] == '\n') {
                            lines++;
                            tailNewline = true;
                        } else {
                            tailNewline = false;
                        }
                    }
                }
                if (!tailNewline && total > 0) {
                    lines++;
                }
            } finally {
                out.close();
            }
            // 流早断：读到的字节少于声明（网关截断/重试路径），拒绝防落半截产物
            if (total != expected) {
                tmp.delete();
                return fail("stream truncated: read " + total + " of " + expected
                        + " declared bytes; retry upload");
            }
            actualSha256 = hex(digest.digest());
            // 端到端摘要校验：传输路径任何形式的损坏（重复块/错位/污染）在此检出
            if (!actualSha256.equalsIgnoreCase(sha256)) {
                tmp.delete();
                return fail("sha256 mismatch: payload corrupted in transit (server got " + actualSha256
                        + ", declared " + sha256 + "); retry upload");
            }
            if (lines <= 0) {
                tmp.delete();
                return fail("empty payload (0 index lines)");
            }
            // 原子替换（与 buildOne 同策略）
            if (!tmp.renameTo(target)) {
                target.delete();
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                    return fail("atomic replace failed for " + target.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            tmp.delete();
            return fail("write payload failed: " + e.getMessage());
        }

        // meta（与 buildOne 同 schema）
        long finishedAt = System.currentTimeMillis();
        JsonObject meta = new JsonObject();
        meta.put("repoCode", repoCode);
        meta.put("gitUrl", repo.getGitUrl());
        meta.put("ref", resolvedRef);
        meta.put("commit", remote.getSha());
        meta.put("builtAt", finishedAt);
        meta.put("durationMs", finishedAt - startedAt);
        meta.put("lines", lines);
        meta.put("indexType", "STATIC");
        meta.put("sha256", actualSha256);
        writeTextFile(buildService.metaFile(repoCode), JsonUtil.toJson(meta) + "\n");

        // status 对齐 SUCCESS（lastIndexedCommit=远端 HEAD → poll 无变更跳过）
        repo.setLastIndexedCommit(remote.getSha());
        repo.setLastIndexedRef(resolvedRef);
        repo.setLastBuildStatus("SUCCESS");
        repo.setLastIndexType("STATIC");   // 上传产物即静态分析产物（与容器内主索引同语义）
        repo.setLastBuildDurationMs(finishedAt - startedAt);
        repo.setLastIndexLines(lines);
        repo.setLastBuildTime(finishedAt);
        repo.setLastErrorMsg(null);
        repo.setBuiltBy("upload");
        repo.setUpdatedAt(finishedAt);
        registryStore.persistStatus(repo);
        queryService.preloadAsync(repoCode);
        log.info("[IndexAdmin] repo={} uploaded index: commit={} lines={} bytes={} sha256={} in {}ms",
                repoCode, remote.getSha(), lines, target.length(), actualSha256, finishedAt - startedAt);

        Map<String, Object> resp = ok();
        resp.put("repo", toMap(repo));
        resp.put("lines", lines);
        resp.put("sha256", actualSha256);
        return resp;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 分片上传 part：body 为该片原始字节（≤1MB），写入 .upload-<repoCode>-<uploadId>.part 的 offset 处。
     * <p>背景：网关对 >8-12KB 的请求体会做确定性的内容重排（0902 实证 12KB 起必坏、
     * 8KB 内完好），单次大 body 直传不可用，改分片（客户端建议 8KB/片）：
     * <ul>
     * <li>同 uploadId 同 offset 重复写幂等——网关 retry 重发某片只是重写同一位置，无害；
     * <li>每片带 sha256 落盘即验，单片损坏立刻拒绝；
     * <li>乱序到达安全（seek 定位写，不 truncate 已写内容）；
     * <li>commit 与远端 HEAD 的一致性在 finish 时校验（part 阶段不 ls-remote，省 RTT）。
     * </ul>
     */
    @RequestMapping(value = "/repos/{repoCode}/upload/part", method = RequestMethod.POST)
    public Map<String, Object> uploadPart(@PathVariable("repoCode") String repoCode,
                                          @RequestParam("uploadId") String uploadId,
                                          @RequestParam("offset") long offset,
                                          @RequestParam("sha256") String sha256,
                                          HttpServletRequest request) {
        IndexRepo repo = registryStore.find(repoCode);
        if (repo == null) {
            return fail("repo not found: " + repoCode);
        }
        if (uploadId == null || !uploadId.matches("[0-9a-zA-Z-]{8,64}")) {
            return fail("invalid uploadId: " + uploadId);
        }
        if (offset < 0) {
            return fail("invalid offset: " + offset);
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            return fail("invalid sha256 (64 hex chars expected): " + sha256);
        }
        long expected = request.getContentLengthLong();
        if (expected <= 0 || expected > MAX_UPLOAD_BYTES) {
            return fail("Content-Length required (got " + expected + "), max " + MAX_UPLOAD_BYTES);
        }
        // 小片直接读入内存（CL 硬截断，流异常也不多读）
        byte[] buf = new byte[(int) expected];
        int total = 0;
        try {
            java.io.InputStream in = request.getInputStream();
            while (total < expected) {
                int n = in.read(buf, total, buf.length - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (Exception e) {
            return fail("read chunk failed: " + e.getMessage());
        }
        if (total != expected) {
            return fail("stream truncated: read " + total + " of " + expected + "; retry chunk");
        }
        String actual;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(buf, 0, total);
            actual = hex(md.digest());
        } catch (Exception e) {
            return fail("digest failed: " + e.getMessage());
        }
        if (!actual.equalsIgnoreCase(sha256)) {
            return fail("chunk sha256 mismatch (server got " + actual + ", declared " + sha256
                    + "); retry chunk");
        }
        File part = new File(buildService.indexerDir(), ".upload-" + repoCode + "-" + uploadId + ".part");
        try {
            buildService.indexerDir().mkdirs();
            RandomAccessFile raf = new RandomAccessFile(part, "rw");
            try {
                raf.seek(offset);
                raf.write(buf, 0, total);
            } finally {
                raf.close();
            }
        } catch (Exception e) {
            part.delete();
            return fail("write chunk failed: " + e.getMessage());
        }
        Map<String, Object> resp = ok();
        resp.put("uploadId", uploadId);
        resp.put("offset", offset);
        resp.put("bytes", total);
        return resp;
    }

    /**
     * 分片上传 finish：校验拼装产物整体 sha256 与 commit（ls-remote），通过则原子替换
     * + meta/status 对齐（同单次 upload 语义）；不通过删除 part 要求重传。
     * <p>lines 在此重读产物统计（不信任客户端，防错报）。
     */
    @RequestMapping(value = "/repos/{repoCode}/upload/finish", method = RequestMethod.POST)
    public Map<String, Object> uploadFinish(@PathVariable("repoCode") String repoCode,
                                            @RequestParam("uploadId") String uploadId,
                                            @RequestParam("commit") String commit,
                                            @RequestParam("sha256") String sha256) {
        long startedAt = System.currentTimeMillis();
        IndexRepo repo = registryStore.find(repoCode);
        if (repo == null) {
            return fail("repo not found: " + repoCode);
        }
        if (uploadId == null || !uploadId.matches("[0-9a-zA-Z-]{8,64}")) {
            return fail("invalid uploadId: " + uploadId);
        }
        if (commit == null || !commit.matches("[0-9a-fA-F]{7,40}")) {
            return fail("invalid commit sha: " + commit);
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            return fail("invalid sha256 (64 hex chars expected): " + sha256);
        }
        File part = new File(buildService.indexerDir(), ".upload-" + repoCode + "-" + uploadId + ".part");
        if (!part.isFile() || part.length() <= 0) {
            return fail("no uploaded part for uploadId " + uploadId + "; upload chunks first");
        }
        // 整体 sha256 + 行数重读统计
        String actualSha256;
        long lines;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            java.io.InputStream in = new FileInputStream(part);
            try {
                byte[] buf = new byte[65536];
                int n;
                long nl = 0;
                boolean tailNewline = true;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == '\n') {
                            nl++;
                            tailNewline = true;
                        } else {
                            tailNewline = false;
                        }
                    }
                }
                lines = nl + (tailNewline ? 0 : 1);
            } finally {
                in.close();
            }
            actualSha256 = hex(digest.digest());
        } catch (Exception e) {
            return fail("read assembled part failed: " + e.getMessage());
        }
        if (!actualSha256.equalsIgnoreCase(sha256)) {
            part.delete();
            return fail("assembled sha256 mismatch (server got " + actualSha256 + ", declared " + sha256
                    + "); re-upload chunks");
        }
        if (lines <= 0) {
            part.delete();
            return fail("empty payload (0 index lines)");
        }
        // 与单次 upload 相同的远端 HEAD 校验
        GitOps.RemoteRef remote = null;
        try {
            remote = buildService.getGitOps().lsRemoteRef(
                    repo.getGitUrl(), repo.getRefMode(), repo.getRefValue());
        } catch (Exception e) {
            log.warn("[IndexAdmin] upload-finish ls-remote failed for {}: {}", repoCode, e.getMessage());
        }
        if (remote == null) {
            return fail("ls-remote failed for " + repoCode + ": " + buildService.getGitOps().getLastLsRemoteError()
                    + " (cannot verify commit, upload rejected)");
        }
        if (!commit.equalsIgnoreCase(remote.getSha())) {
            part.delete();
            return fail("commit mismatch: remote " + repo.getRefValue() + " HEAD is " + remote.getSha()
                    + " but payload claims " + commit + "; regenerate locally and re-upload");
        }
        String resolvedRef = repo.isBranchMode() ? repo.getRefValue() : GitOps.normalizeRefName(remote.getRef());
        // 原子替换（与 buildOne 同策略）
        File target = buildService.indexFile(repoCode);
        if (!part.renameTo(target)) {
            target.delete();
            if (!part.renameTo(target)) {
                part.delete();
                return fail("atomic replace failed for " + target.getAbsolutePath());
            }
        }
        // meta（与 buildOne 同 schema，与单次 upload 一致含 sha256）
        long finishedAt = System.currentTimeMillis();
        JsonObject meta = new JsonObject();
        meta.put("repoCode", repoCode);
        meta.put("gitUrl", repo.getGitUrl());
        meta.put("ref", resolvedRef);
        meta.put("commit", remote.getSha());
        meta.put("builtAt", finishedAt);
        meta.put("durationMs", finishedAt - startedAt);
        meta.put("lines", lines);
        meta.put("indexType", "STATIC");
        meta.put("sha256", actualSha256);
        writeTextFile(buildService.metaFile(repoCode), JsonUtil.toJson(meta) + "\n");
        // status 对齐 SUCCESS（lastIndexedCommit=远端 HEAD → poll 无变更跳过）
        repo.setLastIndexedCommit(remote.getSha());
        repo.setLastIndexedRef(resolvedRef);
        repo.setLastBuildStatus("SUCCESS");
        repo.setLastIndexType("STATIC");   // 上传产物即静态分析产物（与容器内主索引同语义）
        repo.setLastBuildDurationMs(finishedAt - startedAt);
        repo.setLastIndexLines(lines);
        repo.setLastBuildTime(finishedAt);
        repo.setLastErrorMsg(null);
        repo.setBuiltBy("upload");
        repo.setUpdatedAt(finishedAt);
        registryStore.persistStatus(repo);
        queryService.preloadAsync(repoCode);
        log.info("[IndexAdmin] repo={} uploaded index (chunked): commit={} lines={} bytes={} sha256={} in {}ms",
                repoCode, remote.getSha(), lines, target.length(), actualSha256, finishedAt - startedAt);
        Map<String, Object> resp = ok();
        resp.put("repo", toMap(repo));
        resp.put("lines", lines);
        resp.put("sha256", actualSha256);
        return resp;
    }

    /** tmp+rename 原子写（与构建链路同策略）。 */
    private static void writeTextFile(File file, String content) {
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Exception e) {
            log.warn("[IndexAdmin] write meta failed for {}: {}", file.getName(), e.getMessage());
        }
    }

    private static Map<String, Object> toMap(IndexRepo repo) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("repoCode", repo.getRepoCode());
        m.put("repoName", repo.getRepoName());
        m.put("gitUrl", repo.getGitUrl());
        m.put("refMode", repo.getRefMode());
        m.put("refValue", repo.getRefValue());
        m.put("buildType", repo.getBuildType());
        m.put("language", repo.getLanguage());
        m.put("enabled", repo.isEnabled());
        m.put("lastBuildStatus", repo.getLastBuildStatus());
        m.put("lastIndexedCommit", repo.getLastIndexedCommit());
        m.put("lastIndexedRef", repo.getLastIndexedRef());
        m.put("lastIndexLines", repo.getLastIndexLines());
        m.put("lastIndexType", repo.getLastIndexType());
        m.put("lastCompiledStatus", repo.getLastCompiledStatus());
        m.put("lastCompiledLines", repo.getLastCompiledLines());
        m.put("lastCompiledError", repo.getLastCompiledError());
        m.put("lastBuildDurationMs", repo.getLastBuildDurationMs());
        m.put("lastBuildTime", repo.getLastBuildTime());
        m.put("lastErrorMsg", repo.getLastErrorMsg());
        m.put("remark", repo.getRemark());
        m.put("createdAt", repo.getCreatedAt());
        m.put("updatedAt", repo.getUpdatedAt());
        return m;
    }

    private static IndexRepo fromMap(Map<String, Object> body) {
        IndexRepo repo = new IndexRepo();
        repo.setRepoCode(str(body.get("repoCode")));
        repo.setRepoName(str(body.get("repoName")));
        repo.setGitUrl(str(body.get("gitUrl")));
        repo.setRefMode(str(body.get("refMode")));
        repo.setRefValue(str(body.get("refValue")));
        repo.setLanguage(str(body.get("language")));
        repo.setRemark(str(body.get("remark")));
        Object enabled = body.get("enabled");
        repo.setEnabled(enabled == null || Boolean.parseBoolean(String.valueOf(enabled)));
        return repo;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }
}
