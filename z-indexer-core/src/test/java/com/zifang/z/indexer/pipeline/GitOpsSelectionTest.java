package com.zifang.z.indexer.pipeline;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link GitOps} 的凭证解析与 tag 择优（两处原先无直接测试）。
 *
 * <p>两处的共同形状是"看着对、实测错"，且都在 {@code lsRemoteRef} 内联，
 * 没有真 git 远端就测不到，故把纯逻辑抽到包级可见后直接测。
 */
public class GitOpsSelectionTest {

    // ---------------------------------------------------------------- 凭证解析

    /**
     * 回归：{@code URI#getUserInfo()} 返回的已是解码后的分量，再叠一层
     * {@code URLDecoder.decode} 会做两件坏事 ——
     * ① {@code %252B} 这类真值被再剥一层，令牌变成另一个值；
     * ② {@code URLDecoder} 按 form-urlencoded 规则把 {@code '+'} 当空格，令牌被改写。
     */
    @Test
    public void tokenWithPlusIsNotTurnedIntoSpace() {
        Assert.assertEquals(GitOps.userInfoOf("https://bot:tok+en123@git.example.com/a/b.git"),
                "bot:tok+en123",
                "'+' 在 userinfo 里是字面量，不得被 URLDecoder 换成空格");
    }

    @Test
    public void percentEncodedTokenIsDecodedExactlyOnce() {
        Assert.assertEquals(GitOps.userInfoOf("https://bot:a%252Bb@git.example.com/a/b.git"),
                "bot:a%2Bb", "真值 a%2Bb 只应解码一层，二次解码会把它变成 a+b");
    }

    @Test
    public void percentEncodedPlusIsDecodedOnce() {
        Assert.assertEquals(GitOps.userInfoOf("https://bot:tok%2Ben@git.example.com/a/b.git"),
                "bot:tok+en", "%2B 应解码成单个 '+'，而不是先成 '+' 再被换成空格");
    }

    @Test
    public void percentEncodedAtSignInTokenIsPreserved() {
        // %40 = '@'。URI 以第一个 '@' 之后的为 host，token 里的 @ 必须保持编码，
        // 解码后不能重新参与 userinfo 与 host 的切分。
        Assert.assertEquals(GitOps.userInfoOf("https://bot:t%40k@git.example.com/a/b.git"),
                "bot:t@k");
    }

    @Test
    public void urlWithoutUserInfoYieldsNull() {
        Assert.assertNull(GitOps.userInfoOf("https://git.example.com/a/b.git"),
                "无 userinfo 应走匿名");
        Assert.assertNull(GitOps.userInfoOf(null));
    }

    /** 只有 user 没有 ':' 时仍解析出 userinfo，但构不成 user:password ⇒ 应走匿名。 */
    @Test
    public void userInfoWithoutColonCannotFormCredentials() {
        String ui = GitOps.userInfoOf("https://tokenonly@git.example.com/a/b.git");
        Assert.assertEquals(ui, "tokenonly");
        Assert.assertTrue(ui.indexOf(':') < 0, "缺 ':' 不构成 user:password");
    }

    @Test
    public void malformedUrlDoesNotThrow() {
        Assert.assertNull(GitOps.userInfoOf("ht tp://bad url"), "非法 URL 应吞掉走匿名");
    }

    // ---------------------------------------------------------------- tag 择优

    /** 回归：数字(0x30~0x39)小于任何字母，"带时间戳优先"被反转成"带时间戳垫底"。 */
    @Test
    public void timestampedTagBeatsLexicallyLargerPlainTag() {
        Assert.assertTrue(GitOps.isBetterTag("v1.2.3-20240102030405", "v1.0.0"),
                "v1.2.3-20240102030405 应胜过 v1.0.0（带时间戳优先）");
        Assert.assertFalse(GitOps.isBetterTag("v1.0.0", "v1.2.3-20240102030405"));
    }

    @Test
    public void timestampedTagBeatsPlainTagStartingWithR() {
        Assert.assertTrue(GitOps.isBetterTag("release-2-20240102030405", "release-1"),
                "r 开头同样会输给数字前缀，必须被标记位纠正");
    }

    @Test
    public void newestTimestampWinsAmongTimestampedTags() {
        Assert.assertTrue(GitOps.isBetterTag("v1.2.3-20250102030405", "v1.2.3-20240102030405"));
        Assert.assertFalse(GitOps.isBetterTag("v1.2.3-20240102030405", "v1.2.3-20250102030405"));
    }

    @Test
    public void plainTagsStillFallBackToLexicographicOrder() {
        // 全都没有 14 位时间戳时退化为纯字典序（分组标记相同 ⇒ 等价于原 tag 名比较）
        Assert.assertTrue(GitOps.isBetterTag("v1.1.0", "v1.0.0"));
        Assert.assertFalse(GitOps.isBetterTag("v1.0.0", "v1.1.0"));
        Assert.assertTrue(GitOps.isBetterTag("v2", "v1"));
    }

    /**
     * 已知局限，钉住以免日后误以为是回归：字典序不是语义化版本序，
     * {@code v1.10.0} 排在 {@code v1.9.0} <b>前面</b>。
     * 这是 {@code tagSortKey} 注释里「抽不到则用整个 tag 名字典序」的既定行为，
     * 要按语义版本比较需另加解析逻辑（属功能增强，不在本次范围）。
     */
    @Test
    public void plainTagOrderIsLexicographicNotSemanticVersion() {
        Assert.assertFalse(GitOps.isBetterTag("v1.10.0", "v1.9.0"),
                "字典序下 '1'<'9'，故 v1.10.0 排在 v1.9.0 之前（既定行为，非缺陷）");
        Assert.assertTrue(GitOps.isBetterTag("v1.9.0", "v1.10.0"));
    }

    @Test
    public void sameTimestampFallsBackToTagName() {
        Assert.assertTrue(GitOps.isBetterTag("b-20240102030405", "a-20240102030405"));
        Assert.assertFalse(GitOps.isBetterTag("a-20240102030405", "b-20240102030405"));
    }

    @Test
    public void noCurrentBestAlwaysWins() {
        Assert.assertTrue(GitOps.isBetterTag("anything", null));
    }

    /** 与 lsRemoteRef 同形的择优循环：断言整批 tag 里最终选出的那个（期望值写死，不复算）。 */
    @Test
    public void foldOverTagListPicksNewestTimestamp() {
        String[][] cases = {
                // tags,                                          期望胜出
                {"v1.0.0", "v1.2.3-20240102030405", "v1.2.3-20250102030405", "v1.2.3-20250102030405"},
                {"v1.2.3-20250102030405", "v1.0.0", "v1.2.3-20240102030405", "v1.2.3-20250102030405"},
                {"v1.0.0", "v1.1.0", "v1.2.3-20240102030405", "v1.2.3-20240102030405"},
                {"20240102030405", "v9.9.9", "20240102030405"},
                {"release-1", "release-2-20240102030405", "release-2-20240102030405"},
                {"1.0.0", "1.0.1", "1.0.1"},
                {"v1.0.0", "v1.0.0", "v1.0.0"},
        };
        for (String[] c : cases) {
            String[] tags = java.util.Arrays.copyOf(c, c.length - 1);
            String expected = c[c.length - 1];
            String best = null;
            for (String t : tags) {
                if (GitOps.isBetterTag(t, best)) {
                    best = t;
                }
            }
            Assert.assertEquals(best, expected, "tags=" + java.util.Arrays.toString(tags));
        }
    }

    // ---------------------------------------------------------------- ref 名规范化

    @Test
    public void normalizeRefNameStripsPrefixAndPeelSuffix() {
        Assert.assertEquals(GitOps.normalizeRefName("refs/tags/v1.0.0"), "v1.0.0");
        Assert.assertEquals(GitOps.normalizeRefName("refs/heads/main"), "main");
        Assert.assertEquals(GitOps.normalizeRefName("refs/tags/v1.0.0^{}"), "v1.0.0");
        Assert.assertEquals(GitOps.normalizeRefName("v1.0.0"), "v1.0.0");
        Assert.assertEquals(GitOps.normalizeRefName(null), "");
    }
}
