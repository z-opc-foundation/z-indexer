package com.zifang.z.indexer.registry;

/**
 * gitUrl 脱敏：userinfo 里的密码段就是仓库的推送/拉取凭证。
 *
 * <p>{@code IndexRepo} 没有独立的 token 字段，凭证一律内嵌在 gitUrl 的 userinfo 里
 * （{@code https://user:token@host/org/repo.git}，见 {@code GitOps#credentialsOf} 的解析）。
 * 也就是说<b>任何把 gitUrl 原样输出的地方都是一次凭证泄露</b>：管理 API 的响应体、
 * 落盘的 {@code meta.json}、以及失败日志。</p>
 *
 * <p><b>为什么保留用户名、只掩密码</b>：用户名是仓库的组织名/账号（GitHub 上公开可见），
 * 掩掉它会让日志失去"是哪个仓库/哪个账号"的诊断价值；真正的秘密是密码段（PAT / deploy token）。
 * 没有密码段（{@code ssh://git@host/...}）时原样保留。</p>
 *
 * <p><b>形态</b>：只处理 {@code scheme://user:secret@host/...}。
 * scp 形式 {@code git@host:org/repo.git} 里的 {@code @} 是用户分隔符、不是 userinfo，
 * 原样返回。密码里含 {@code @} 时按<b>最后一个</b> {@code @} 切分（未编码的 token 很常见）。</p>
 */
public final class GitUrlMasker {

    /** 掩码后的密码段。 */
    private static final String MASK = "***";

    private GitUrlMasker() {
    }

    /**
     * 脱敏一个 gitUrl。无法判定含凭证时（无 scheme、无 userinfo、无密码段）原样返回，
     * 不做任何改写——脱敏只该动凭证本身，不该顺手规范化 URL。
     *
     * @param gitUrl 原始 gitUrl，可为 {@code null}
     * @return 掩掉密码段的 gitUrl；其余形态与入参逐字相同
     */
    public static String mask(String gitUrl) {
        if (gitUrl == null || gitUrl.isEmpty()) {
            return gitUrl;
        }
        int schemeEnd = gitUrl.indexOf("://");
        if (schemeEnd < 0) {
            // scp 形式（git@host:org/repo.git）：@ 不是 userinfo
            return gitUrl;
        }
        int at = gitUrl.lastIndexOf('@');
        int userInfoStart = schemeEnd + 3;
        if (at <= userInfoStart) {
            // 没有 userinfo（https://host/...），或 userinfo 为空
            return gitUrl;
        }
        String userInfo = gitUrl.substring(userInfoStart, at);
        int colon = userInfo.indexOf(':');
        if (colon < 0) {
            // 只有用户名没有密码（ssh://git@host/...）
            return gitUrl;
        }
        String user = userInfo.substring(0, colon);
        return gitUrl.substring(0, userInfoStart) + user + ":" + MASK + gitUrl.substring(at);
    }
}