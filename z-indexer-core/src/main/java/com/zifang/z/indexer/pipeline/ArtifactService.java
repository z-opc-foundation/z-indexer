package com.zifang.z.indexer.pipeline;

import com.zifang.z.indexer.IndexerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * master 纯内容制品维护：构建 SUCCESS 后把仓库工作区打包为 {@code artifacts/{repoCode}.zip}
 * （排除 .git/target/node_modules 等非源码目录，tmp+原子改名），供下游快照链路直取——
 * 消费方 cp 单文件 zip + 解压即得纯内容快照，免去整仓 cp（.git 历史占体积大头，NFS 海量小文件
 * 元数据 IO 是耗时主因，实测 176M 仓需 200s+）+ 本地 checkout + 删 .git 三步。
 *
 * <p>设计约定：
 * <ul>
 *   <li>制品只覆盖 master/默认分支；非 master 的分支/tag 由下游走原整卷 cp + 本地 checkout 路径</li>
 *   <li>新鲜度由构建节奏保证：syncTo 后工作区即 master 最新，构建 SUCCESS 即触发打包；
 *       打包在独立单线程串行执行（防多仓并发打包抢 NAS IO），失败仅记日志、不影响构建结果与索引可用性</li>
 *   <li>zip 内首个条目固定为 {@link #META_ENTRY}（repoCode/commit/打包时间），供下游完整性校验</li>
 *   <li>产物原子替换（tmp → ATOMIC/普通 rename），下游 cp 读到的要么是完整旧版要么是完整新版</li>
 * </ul>
 */
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    /** zip 内元数据条目名（下游解压后以此作为完整性校验依据） */
    public static final String META_ENTRY = "_artifact_meta.json";

    /** 打包时整树排除的目录名（构建产物/依赖目录可能比源码还大，必须剔除） */
    private static final Set<String> EXCLUDE_DIRS = new HashSet<>(Arrays.asList(
            ".git", "target", "node_modules", "build", "dist", ".idea", ".vscode"));

    private final IndexerConfig config;

    /** 串行打包队列：单线程 daemon，防多仓构建并发打包抢 NAS IO */
    private final ExecutorService packer = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "artifact-packer-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    public ArtifactService(IndexerConfig config) {
        this.config = config;
    }

    public File artifactsDir() {
        return new File(config.getReposRoot(), "artifacts");
    }

    public File artifactFile(String repoCode) {
        return new File(artifactsDir(), repoCode + ".zip");
    }

    public File metaFile(String repoCode) {
        return new File(artifactsDir(), repoCode + ".meta");
    }

    /** 构建成功后调用：异步打包（串行队列，失败仅记日志）。 */
    public void submitBuild(final String repoCode, final String commitSha) {
        packer.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    buildArtifact(repoCode, commitSha);
                } catch (Exception e) {
                    log.warn("[Artifact] pack {} failed: {}: {}", repoCode,
                            e.getClass().getSimpleName(), e.getMessage());
                }
            }
        });
    }

    /** 同步打包 master 纯内容 zip（tmp+原子改名）+ 外部 meta 文件。 */
    void buildArtifact(String repoCode, String commitSha) throws IOException {
        File repoDir = new File(new File(config.getReposRoot(), "repos"), repoCode);
        if (!new File(repoDir, ".git").isDirectory()) {
            throw new IOException("repo dir invalid: " + repoDir);
        }
        File artifacts = artifactsDir();
        if (!artifacts.isDirectory() && !artifacts.mkdirs()) {
            throw new IOException("mkdirs failed: " + artifacts);
        }
        File tmpZip = new File(artifacts, repoCode + ".zip.tmp-" + UUID.randomUUID().toString().substring(0, 8));
        long t0 = System.currentTimeMillis();
        long files = 0;
        long bytes = 0;
        // NFS 大文件坑：FileOutputStream.close() 只刷 client 缓冲，rename 后 server 端可能是 0 字节
        // （实测：几十 MB 制品 rename 后 Content-Length=0）——必须 move 前 fsync 强制 NFS COMMIT
        FileOutputStream fos = new FileOutputStream(tmpZip);
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED);
            String meta = "{\"repoCode\":\"" + repoCode + "\",\"commit\":\"" + commitSha
                    + "\",\"packedAt\":" + System.currentTimeMillis() + "}";
            zos.putNextEntry(new ZipEntry(META_ENTRY));
            zos.write(meta.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            long[] stat = packDir(repoDir, repoDir, zos);
            files = stat[0];
            bytes = stat[1];
            zos.finish();
            zos.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        File zip = artifactFile(repoCode);
        try {
            Files.move(tmpZip.toPath(), zip.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmpZip.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        File metaFile = metaFile(repoCode);
        File tmpMeta = new File(artifacts, repoCode + ".meta.tmp-" + UUID.randomUUID().toString().substring(0, 8));
        FileOutputStream mfs = new FileOutputStream(tmpMeta);
        try {
            mfs.write(("{\"repoCode\":\"" + repoCode + "\",\"commit\":\"" + commitSha
                    + "\",\"packedAt\":" + System.currentTimeMillis() + ",\"files\":" + files
                    + ",\"bytes\":" + bytes + "}").getBytes(StandardCharsets.UTF_8));
            mfs.getFD().sync();
        } finally {
            mfs.close();
        }
        try {
            Files.move(tmpMeta.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (tmpMeta.isFile() && !tmpMeta.delete()) {
                log.warn("[Artifact] tmp meta not removed: {}", tmpMeta);
            }
        }
        log.info("[Artifact] {} packed: {} files, {}MB in {}ms",
                repoCode, files, bytes / 1048576, System.currentTimeMillis() - t0);
    }

    /** 递归打包目录（排除 EXCLUDE_DIRS 整棵子树），返回 [文件数, 字节数]。 */
    private long[] packDir(File root, File dir, ZipOutputStream zos) throws IOException {
        File[] children = dir.listFiles();
        long files = 0;
        long bytes = 0;
        if (children == null) {
            return new long[]{0, 0};
        }
        // 同目录文件名排序，保证制品内容确定性（便于对账）
        Arrays.sort(children);
        for (File f : children) {
            // 符号链接不进制品：悬空链接（工具链残留的依赖目录断链）会让
            // Files.copy 抛 NoSuchFileException 毁整包，指向真目录的链接则可能拖入依赖巨树
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                continue;
            }
            if (f.isDirectory()) {
                if (EXCLUDE_DIRS.contains(f.getName())) {
                    continue;
                }
                long[] sub = packDir(root, f, zos);
                files += sub[0];
                bytes += sub[1];
                continue;
            }
            String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            try {
                zos.putNextEntry(new ZipEntry(rel));
                Files.copy(f.toPath(), zos);
                zos.closeEntry();
            } catch (IOException e) {
                // 单文件读失败不毁整包（宁缺勿错，同静态索引原则）
                log.warn("[Artifact] skip unreadable file {}: {}", rel, e);
                continue;
            }
            files++;
            bytes += f.length();
        }
        return new long[]{files, bytes};
    }
}
