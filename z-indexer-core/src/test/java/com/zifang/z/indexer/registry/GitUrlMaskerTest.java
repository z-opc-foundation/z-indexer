package com.zifang.z.indexer.registry;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link GitUrlMasker} 的判别式。
 *
 * <p><b>判别式的构造</b>：每条都断言<b>精确字符串</b>而不是"不含 token"。
 * 只断言"不含 token"的话，一个把整个 URL 换成 {@code ***} 的实现也能过——
 * 那种实现会让日志失去"是哪个仓库"的诊断价值，而脱敏的职责只该落在凭证上。</p>
 *
 * <p>同时钉住<b>不该动的东西</b>：scp 形式（{@code git@host:org/repo.git}）里的
 * {@code @} 是用户分隔符而不是 userinfo，脱敏必须原样放过；误伤它会把
 * "GitHub SSH 地址"变成一条没法用的地址。</p>
 */
public class GitUrlMaskerTest {

    private static final String TOKEN = "ghp_AAAABBBBCCCCDDDDEEEEFFFF";

    // ==================================================================
    // 真正要脱敏的形态
    // ==================================================================

    @Test
    public void httpsWithPasswordLosesSecretButKeepsEverythingElse() {
        String masked = GitUrlMasker.mask("https://deploybot:" + TOKEN + "@github.com/acme/repo.git");

        Assert.assertEquals(masked, "https://deploybot:***@github.com/acme/repo.git",
                "密码段必须被掩掉，而用户名/主机/路径必须逐字保留（它们是诊断价值所在）");
        Assert.assertFalse(masked.contains(TOKEN), "脱敏后仍能搜到原始凭证");
    }

    @Test
    public void plainHttpIsMaskedToo() {
        // 只判 https 会漏掉司内 gitlab 的 http 部署形态
        Assert.assertEquals(
                GitUrlMasker.mask("http://bot:" + TOKEN + "@gitlab.internal:8080/team/app.git"),
                "http://bot:***@gitlab.internal:8080/team/app.git",
                "http 形态同样承载凭证，必须走同一条脱敏（只特判 https 等于没脱敏）");
    }

    @Test
    public void portAndPathSurviveMasking() {
        // 主机后面的 :端口 不能被当成密码分隔符
        Assert.assertEquals(
                GitUrlMasker.mask("https://bot:" + TOKEN + "@git.corp.cn:8443/a/b/c.git"),
                "https://bot:***@git.corp.cn:8443/a/b/c.git",
                "端口被误当成密码分隔符 ⇒ host 之后的定位信息全丢");
    }

    @Test
    public void passwordContainingAtIsSplitOnTheLastAt() {
        // 未编码的 token 里带 @ 很常见（base64 尾缀等）。
        // 这个 URL 里密码是 "pa<TOKEN>@ss"，主机是 git.corp.cn ——
        // 按第一个 @ 切会把 "ss@git.corp.cn/a/b.git" 整个当成主机，路径都保不住。
        String url = "https://bot:pa" + TOKEN + "@ss@git.corp.cn/a/b.git";

        String masked = GitUrlMasker.mask(url);

        Assert.assertFalse(masked.contains(TOKEN), "含 @ 的密码没被掩干净");
        Assert.assertEquals(masked, "https://bot:***@git.corp.cn/a/b.git",
                "主机与路径必须完整保留；按第一个 @ 切分会把 ss@git.corp.cn/a/b.git 当成主机");
    }

    @Test
    public void passwordContainingColonIsFullyMasked() {
        // 密码里自带冒号时，第一个冒号之后到 @ 的整段都是秘密
        String url = "https://bot:aaa:bbb:" + TOKEN + "@git.corp.cn/a.git";

        String masked = GitUrlMasker.mask(url);

        Assert.assertFalse(masked.contains("aaa"), "密码第一段没被掩");
        Assert.assertFalse(masked.contains("bbb"), "密码第二段没被掩");
        Assert.assertFalse(masked.contains(TOKEN), "密码第三段没被掩");
        Assert.assertEquals(masked, "https://bot:***@git.corp.cn/a.git");
    }

    // ==================================================================
    // 不该动的形态（误伤同样是缺陷）
    // ==================================================================

    @Test
    public void scpStyleSshUrlIsUntouched() {
        String scp = "git@github.com:acme/repo.git";

        Assert.assertEquals(GitUrlMasker.mask(scp), scp,
                "scp 形式没有 ://，@ 是用户分隔符不是 userinfo；被改写会让这条地址直接不可用");
    }

    @Test
    public void sshUrlWithOnlyUserIsUntouched() {
        // ssh://git@host/... 的 git 是用户名不是密码，掩掉它反而丢信息
        Assert.assertEquals(GitUrlMasker.mask("ssh://git@github.com/acme/repo.git"),
                "ssh://git@github.com/acme/repo.git",
                "只有用户名没有密码段时必须原样返回");
    }

    @Test
    public void anonymousUrlIsUntouched() {
        Assert.assertEquals(GitUrlMasker.mask("https://github.com/acme/repo.git"),
                "https://github.com/acme/repo.git", "匿名可读仓库（司内 gitlab 默认）不该被改写");
    }

    @Test
    public void nullAndEmptyArePassedThrough() {
        Assert.assertNull(GitUrlMasker.mask(null), "null 必须原样返回而不是 NPE");
        Assert.assertEquals(GitUrlMasker.mask(""), "", "空串必须原样返回");
    }

    @Test
    public void noCredentialMeansNoRewrite() {
        // 脱敏只该动凭证本身，不该顺手规范化 URL（去尾斜杠、补 scheme 之类）
        Assert.assertEquals(GitUrlMasker.mask("https://git.corp.cn:8443/a/b/"),
                "https://git.corp.cn:8443/a/b/",
                "不含凭证的 URL 必须逐字原样返回，脱敏不是 URL 规范化");
    }
}