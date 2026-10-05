package com.zifang.z.indexer.pipeline;

import com.zifang.z.indexer.IndexerConfig;
import com.zifang.z.indexer.registry.IndexRepo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * git 操作封装（JGit 纯 Java 实现，无容器 git 命令依赖）。
 * <p>生产容器为纯 JRE 镜像无 git 命令（Cannot run program "git"），故全链路
 * 走 JGit：ls-remote 变更检测（不 clone 零成本）、clone、fetch+checkout+reset+clean 同步。
 * <p>凭证：gitUrl 内嵌 userinfo（https://user:token@host/...）自动解析；
 * 匿名可读仓库（司内 gitlab 默认）无需凭证。
 */
public class GitOps {

    private static final Logger log = LoggerFactory.getLogger(GitOps.class);

    /** ls-remote 单条结果：sha（tag 为解引用后的 commit SHA）+ ref 全名。 */
    public static class RemoteRef {
        private final String sha;
        private final String ref;

        public RemoteRef(String sha, String ref) {
            this.sha = sha;
            this.ref = ref;
        }

        public String getSha() {
            return sha;
        }

        public String getRef() {
            return ref;
        }
    }

    /** 操作结果（与旧命令行封装契约一致，供 IndexBuildService 消费）。 */
    public static class ExecResult {
        private final int exitCode;
        private final String output;

        public ExecResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public boolean isOk() {
            return exitCode == 0;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public String tail(int maxChars) {
            if (output.length() <= maxChars) {
                return output;
            }
            return "...(truncated)..." + output.substring(output.length() - maxChars);
        }
    }

    private final IndexerConfig config;

    /**
     * 最近一次 ls-remote 失败详情（供 lastErrorMsg 透传定位环境问题：
     * DNS 失败/认证失败/仓库不存在等）。pollOnce 串行调用无竞态。
     */
    private volatile String lastLsRemoteError = "";

    public GitOps(IndexerConfig config) {
        this.config = config;
    }

    public String getLastLsRemoteError() {
        return lastLsRemoteError;
    }

    /**
     * 取出 URL userinfo 中<b>已解码</b>的 {@code user:password}（包级可见以便测试）。
     * <p>
     * {@link URI#getUserInfo()} 返回的就是解码后的分量，<b>不能</b>再叠一层
     * {@code URLDecoder.decode}：
     * <ul>
     *   <li>二次解码会把 {@code %252B} 这类真值再剥一层，令牌被改写成另一个值；</li>
     *   <li>{@code URLDecoder} 按 application/x-www-form-urlencoded 规则把 {@code '+'} 当空格，
     *       令牌里的 {@code '+'} 会被换成 {@code ' '}，认证直接失败
     *       （实测 {@code tok+en123} → {@code "tok en123"}）。</li>
     * </ul>
     *
     * @return 解码后的 userinfo；URL 无 userinfo 或格式非标准时返回 null
     */
    static String userInfoOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            return new URI(url).getUserInfo();
        } catch (Exception ignore) {
            // URL 无 userinfo 或非标准格式，走匿名
            return null;
        }
    }

    /** 从 URL userinfo 提取凭证（https://user:token@host/...），匿名返回 null。 */
    private static CredentialsProvider credentialsOf(String url) {
        String userInfo = userInfoOf(url);
        if (userInfo != null) {
            int c = userInfo.indexOf(':');
            if (c > 0) {
                return new UsernamePasswordCredentialsProvider(
                        userInfo.substring(0, c), userInfo.substring(c + 1));
            }
        }
        return null;
    }

    /** 远端分支/tag SHA 检测（不 clone 不 pull，成本为一次 ls-remote）。 */
    public RemoteRef lsRemoteRef(String gitUrl, String mode, String value) {
        boolean branchMode = IndexRepo.REF_MODE_BRANCH.equalsIgnoreCase(mode);
        boolean tagPattern = IndexRepo.REF_MODE_TAG_PATTERN.equalsIgnoreCase(mode);
        Collection<Ref> refs;
        try {
            LsRemoteCommand cmd = Git.lsRemoteRepository().setRemote(gitUrl).setTimeout(30)
                    .setCredentialsProvider(credentialsOf(gitUrl));
            if (branchMode) {
                cmd.setHeads(true).setTags(false);
            } else {
                cmd.setHeads(false).setTags(true);
            }
            refs = cmd.call();
        } catch (Exception e) {
            lastLsRemoteError = "ls-remote error: " + e.getMessage();
            log.warn("[GitOps] ls-remote failed for {} (mode={} value={}): {}", gitUrl, mode, value, lastLsRemoteError);
            return null;
        }
        if (branchMode) {
            String wanted = "refs/heads/" + value;
            for (Ref r : refs) {
                if (wanted.equals(r.getName())) {
                    return new RemoteRef(r.getObjectId().name(), r.getName());
                }
            }
            lastLsRemoteError = "refs/heads/" + value + " not found (ls-remote ok, " + refs.size() + " heads)";
            log.warn("[GitOps] ls-remote no match for {} (mode={} value={})", gitUrl, mode, value);
            return null;
        }
        // tag 模式：全 tag 后按 pattern / 字典序取最新
        RemoteRef best = null;
        for (Ref r : refs) {
            String name = normalizeRefName(r.getName());
            if (name.endsWith("^{}")) {
                continue;
            }
            if (tagPattern && !wildcardMatch(name, value)) {
                continue;
            }
            if (isBetterTag(name, best == null ? null : normalizeRefName(best.getRef()))) {
                best = new RemoteRef(peeledSha(r), r.getName());
            }
        }
        if (best == null) {
            lastLsRemoteError = "no tag matches pattern: " + value + " (total " + refs.size() + " tags)";
            log.warn("[GitOps] no tag matches {} for {} (total {} tags)", value, gitUrl, refs.size());
        }
        return best;
    }

    /** annotated tag 取解引用 commit SHA（轻量 tag 直接就是 commit）。 */
    private static String peeledSha(Ref r) {
        if (r.isPeeled()) {
            ObjectId peeled = r.getPeeledObjectId();
            if (peeled != null) {
                return peeled.name();
            }
        }
        return r.getObjectId().name();
    }

    /** ref 名规范化：去掉 refs/tags/、refs/heads/ 前缀与 ^{} 后缀。 */
    public static String normalizeRefName(String ref) {
        String name = ref == null ? "" : ref;
        if (name.endsWith("^{}")) {
            name = name.substring(0, name.length() - 3);
        }
        if (name.startsWith("refs/tags/")) {
            return name.substring("refs/tags/".length());
        }
        if (name.startsWith("refs/heads/")) {
            return name.substring("refs/heads/".length());
        }
        return name;
    }

    /** 通配匹配（* 任意串）。 */
    private static boolean wildcardMatch(String text, String pattern) {
        String[] parts = pattern.split("\\*", -1);
        if (parts.length == 1) {
            return text.equals(pattern);
        }
        int pos = 0;
        if (!parts[0].isEmpty() && !text.startsWith(parts[0])) {
            return false;
        }
        pos += parts[0].length();
        for (int i = 1; i < parts.length - 1; i++) {
            int idx = text.indexOf(parts[i], pos);
            if (idx < 0) {
                return false;
            }
            pos = idx + parts[i].length();
        }
        String last = parts[parts.length - 1];
        if (!text.endsWith(last) || text.length() - last.length() < pos) {
            return false;
        }
        return true;
    }

    /**
     * tag 排序 key：优先抽 tag 名中的 14 位时间戳（matrix 发版 tag 含 yyyyMMddHHmmss），
     * 抽不到则用整个 tag 名字典序。
     * <p>
     * key 首位是分组标记：带时间戳的 {@code '1'}、不带时间戳的 {@code '0'}，
     * 于是"抽不到时间戳"= 排在所有带时间戳的 tag 之后，同组内仍按 tag 名字典序。
     * <p>
     * <b>不能</b>把裸 tag 名直接和 {@code "yyyyMMddHHmmss|name"} 比：数字(0x30~0x39)
     * 小于任何字母，于是 {@code v1.2.3-20240102030405} 会输给 {@code v1.0.0}
     * （实测），"带时间戳优先"被反转成"带时间戳垫底"，选出的 tag 不是最新的。
     */
    private static String tagSortKey(String tagName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{14})").matcher(tagName);
        if (m.find()) {
            return "1" + m.group(1) + "|" + tagName;
        }
        return "0" + tagName;
    }

    /**
     * tag 择优判据（包级可见以便测试）：{@link #tagSortKey} 字典序大者胜；
     * 尚无当前最优者则直接胜出。
     *
     * @param candidate   已 {@link #normalizeRefName} 规范化的候选 tag 名
     * @param currentBest 已规范化的当前最优 tag 名，无则 null
     */
    static boolean isBetterTag(String candidate, String currentBest) {
        return currentBest == null
                || tagSortKey(candidate).compareTo(tagSortKey(currentBest)) > 0;
    }

    /** clone 到目标目录（已存在则跳过，由调用方判断）。 */
    public ExecResult clone(String gitUrl, File dir, long timeoutMinutes) {
        try {
            try (Git ignored = Git.cloneRepository().setURI(gitUrl).setDirectory(dir)
                    .setCloneAllBranches(true)
                    .setTimeout((int) Math.min(timeoutMinutes * 60, 3600))
                    .setCredentialsProvider(credentialsOf(gitUrl)).call()) {
                return new ExecResult(0, "");
            }
        } catch (Exception e) {
            return new ExecResult(-1, "clone failed: " + e.getMessage());
        }
    }

    /**
     * 同步工作副本到目标 ref：
     * fetch --tags → checkout -f → reset --hard <sha>（还原 pom 注入）→ clean -fdq。
     */
    public ExecResult syncTo(File repoDir, String resolvedRef, String sha, boolean branchMode, String branchName) {
        Git git = null;
        try {
            git = Git.open(repoDir);
            String originUrl = git.getRepository().getConfig().getString("remote", "origin", "url");
            CredentialsProvider cp = credentialsOf(originUrl);
            git.fetch().setRemote("origin").setForceUpdate(true)
                    .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                    .setTagOpt(TagOpt.FETCH_TAGS)
                    .setCredentialsProvider(cp)
                    .setTimeout(600).call();
            if (branchMode) {
                // clone(AllBranches) / 上次同步会在本地留下同名分支，createBranch 对已存在 ref
                // 直接抛 RefAlreadyExistsException（force 不豁免该检查）→ 已存在则纯切换，
                // 内容对齐统一交给下方 reset --hard <sha>；不存在才从远端跟踪分支创建。
                boolean localExists = git.getRepository().exactRef("refs/heads/" + branchName) != null;
                if (localExists) {
                    git.checkout().setName(branchName).setForce(true).call();
                } else {
                    git.checkout().setName(branchName).setCreateBranch(true)
                            .setStartPoint("refs/remotes/origin/" + branchName).call();
                }
            } else {
                try {
                    git.checkout().setName(resolvedRef).setForce(true).call();
                } catch (Exception missing) {
                    // tag 不在本地时先 fetch 单 ref 再试
                    git.fetch().setRemote("origin")
                            .setRefSpecs(new RefSpec("+refs/tags/" + resolvedRef + ":refs/tags/" + resolvedRef))
                            .setForceUpdate(true).setCredentialsProvider(cp).call();
                    git.checkout().setName(resolvedRef).setForce(true).call();
                }
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(sha).call();
            git.clean().setCleanDirectories(true).setIgnore(false).call();
            return new ExecResult(0, "");
        } catch (Exception e) {
            return new ExecResult(-1, "sync failed: " + e.getMessage());
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    /** 还原根 pom（checkout -- pom.xml，撤销构建前的索引器注入）。 */
    public ExecResult checkoutPom(File repoDir) {
        Git git = null;
        try {
            git = Git.open(repoDir);
            git.checkout().addPath("pom.xml").setStartPoint("HEAD").call();
            return new ExecResult(0, "");
        } catch (Exception e) {
            return new ExecResult(-1, "checkout pom failed: " + e.getMessage());
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    /** 当前 HEAD SHA。 */
    public String currentSha(File repoDir) {
        Git git = null;
        try {
            git = Git.open(repoDir);
            ObjectId id = git.getRepository().resolve("HEAD");
            return id == null ? null : id.name();
        } catch (Exception e) {
            return null;
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    /** tracked 文件是否有修改（忽略 untracked，如 mvn 产物 target/；监控用）。 */
    public boolean isClean(File repoDir) {
        Git git = null;
        try {
            git = Git.open(repoDir);
            org.eclipse.jgit.api.Status s = git.status().call();
            return s.getConflicting().isEmpty()
                    && s.getModified().isEmpty()
                    && s.getChanged().isEmpty()
                    && s.getAdded().isEmpty()
                    && s.getRemoved().isEmpty()
                    && s.getMissing().isEmpty();
        } catch (Exception e) {
            return false;
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }
}
