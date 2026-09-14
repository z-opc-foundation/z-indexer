package com.zifang.z.indexer.query;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RepoIndex.load 字符串复用（0902 内存治理）：
 * jsonl 每行独立解析，from/to/file/kind 在边表/符号表中高度重复，不复用时
 * dtc 53 万边 ≈440MB（预热 6 大仓 OOM 实证），复用后仅保留唯一串。
 * 用 assertSame 锁定「同值同实例」语义，防止无声回退到每边独立拷贝。
 */
public class RepoIndexTest {

    @Test
    public void loadDedupsRepeatedStringsAndKeepsSemantics() throws Exception {
        File jsonl = File.createTempFile("repo-index-test", ".jsonl");
        jsonl.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(jsonl), StandardCharsets.UTF_8);
        w.write("{\"t\":\"def\",\"kind\":\"class\",\"sym\":\"com.demo.Foo\",\"file\":\"src/Foo.java\",\"line\":10}\n");
        w.write("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"com.demo.Foo#bar(java.lang.String)\",\"file\":\"src/Foo.java\",\"line\":20}\n");
        w.write("{\"t\":\"call\",\"from\":\"com.demo.Foo#bar(java.lang.String)\",\"to\":\"com.demo.Util#zap()\",\"file\":\"src/Foo.java\",\"line\":21}\n");
        w.write("{\"t\":\"call\",\"from\":\"com.demo.Foo#bar(java.lang.String)\",\"to\":\"com.demo.Util#zap()\",\"file\":\"src/Foo.java\",\"line\":22}\n");
        w.write("{\"t\":\"call\",\"from\":\"com.demo.Other#run()\",\"to\":\"com.demo.Foo#bar(java.lang.String)\",\"file\":\"src/Other.java\",\"line\":5}\n");
        w.close();

        RepoIndex idx = RepoIndex.load("demo", jsonl, new File(jsonl.getParentFile(), "absent.meta.json"));

        // 语义不变：符号/边/搜索行为与池化前一致
        Assert.assertEquals(idx.getSymbolCount(), 2);
        Assert.assertEquals(idx.getCallCount(), 3L);
        Assert.assertEquals(idx.searchSymbols("bar", 10, true).size(), 1);

        List<RepoIndex.Call> in = idx.incomingCalls("com.demo.Util#zap()");
        Assert.assertEquals(in.size(), 2);
        // 同值 from/to/file 共享同一实例（池化生效；不去重则各自独立拷贝，assertSame 失败）
        Assert.assertSame(in.get(0).from, in.get(1).from);
        Assert.assertSame(in.get(0).to, in.get(1).to);
        Assert.assertSame(in.get(0).file, in.get(1).file);

        List<RepoIndex.Call> out = idx.outgoingCalls("com.demo.Foo#bar(java.lang.String)");
        Assert.assertEquals(out.size(), 2);
        Assert.assertSame(out.get(0).to, out.get(1).to);
        // def 的 sym 与边表交叉复用（Foo#bar 既在符号表又在边表，同实例）
        RepoIndex.Def fooBar = idx.searchSymbols("bar", 10, true).get(0);
        Assert.assertSame(fooBar.sym, in.get(0).from);
    }
}
