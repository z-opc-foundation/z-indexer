package com.zifang.z.indexer.pipeline;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonObject;
import com.zifang.z.indexer.query.RepoIndex;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * StaticJavaIndexer 单元验证：fixture 覆盖 sym 规约全场景
 * （嵌套类/枚举/接口分派/继承链/泛型/varargs/数组/外部依赖/隐式构造器/字段初始化 from=?），
 * 并用真实 RepoIndex 装载静态 jsonl 验证 schema 兼容与 def/call join。
 */
public class StaticJavaIndexerTest {

    @Test
    public void symContractAndJoin() throws Exception {
        File work = createTempDir("z-indexer-static-idx");
        File src = new File(work, "src/main/java/demo");
        writeText(new File(src, "PriceCalculator.java"),
                "package demo;\n"
                + "public class PriceCalculator {\n"
                + "    public double calcPrice(double base) {\n"
                + "        return base * 1.1;\n"
                + "    }\n"
                + "}\n");
        writeText(new File(src, "Item.java"),
                "package demo;\n"
                + "public class Item { }\n");
        writeText(new File(src, "Repository.java"),
                "package demo;\n"
                + "import java.util.List;\n"
                + "public interface Repository {\n"
                + "    int save(List<Item> items);\n"
                + "}\n");
        writeText(new File(src, "BaseService.java"),
                "package demo;\n"
                + "public class BaseService {\n"
                + "    protected void hook() { }\n"
                + "    protected BaseService self() { return this; }\n"
                + "}\n");
        writeText(new File(src, "Misc.java"),
                "package demo;\n"
                + "public class Misc {\n"
                + "    public enum Status { OK, FAIL }\n"
                + "    public static class Inner {\n"
                + "        public void ping(String... args) { }\n"
                + "    }\n"
                + "    public void arrays(int[] counts, String[][] names) { }\n"
                + "    public void callInner() {\n"
                + "        new Inner().ping(\"a\");\n"
                + "    }\n"
                + "}\n");
        // 注意：OrderService 按字典序先于 PriceCalculator 处理，覆盖“同包跨文件解析顺序无关”（A1/A2 双遍架构）
        writeText(new File(src, "OrderService.java"),
                "package demo;\n"
                + "import java.util.Objects;\n"
                + "public class OrderService extends BaseService {\n"
                + "    private PriceCalculator calculator = new PriceCalculator();\n"
                + "    private Repository repository;\n"
                + "\n"
                + "    public OrderService(Repository repository) {\n"
                + "        this.repository = repository;\n"
                + "        self();\n"
                + "    }\n"
                + "\n"
                + "    public double submit(double amount) {\n"
                + "        double price = calculator.calcPrice(amount);\n"
                + "        audit(amount);\n"
                + "        super.hook();\n"
                + "        repository.save(null);\n"
                + "        Objects.requireNonNull(price);\n"
                + "        return price;\n"
                + "    }\n"
                + "\n"
                + "    private void audit(double v) {\n"
                + "        System.out.println(v);\n"
                + "    }\n"
                + "}\n");

        // JDK wildcard import + 参数 TYPE_USE 注解渲染（对齐 javac8 AnnotatedType.toString）
        writeText(new File(src, "AuditController.java"),
                "package demo;\n"
                + "import java.util.*;\n"
                + "import javax.validation.Valid;\n"
                + "public class AuditController {\n"
                + "    public String check(@Valid Item item, List<String> tags, Map<String, Item> memo) {\n"
                + "        return item.toString();\n"
                + "    }\n"
                + "    public String tagged(@java.lang.SuppressWarnings(\"x\") Item item) {\n"
                + "        return item.toString();\n"
                + "    }\n"
                + "}\n");

        // Lombok 合成访问器（编译期 Lombok 改 AST 后处理器可见，静态版镜像之）
        writeText(new File(src, "LombokDto.java"),
                "package demo;\n"
                + "import java.util.List;\n"
                + "import lombok.Data;\n"
                + "import lombok.Getter;\n"
                + "@Data\n"
                + "public class LombokDto {\n"
                + "    private String name;\n"
                + "    private boolean active;\n"
                + "    private List<LombokDto> children;\n"
                + "    @Getter\n"
                + "    private int count;\n"
                + "    public String getName() { return name; }\n"
                + "}\n");

        // 静态通配导入（枚举常量）文件里的裸调用：不得错挂到通配 owner（旧 bug：get(0) 错挂任意裸调用）
        writeText(new File(src, "TraceCodes.java"),
                "package demo;\n"
                + "public enum TraceCodes {\n"
                + "    OK, FAIL;\n"
                + "    public String label() { return name(); }\n"
                + "}\n");
        writeText(new File(src, "BareCalls.java"),
                "package demo;\n"
                + "import static demo.TraceCodes.*;\n"
                + "public class BareCalls {\n"
                + "    public void run() {\n"
                + "        helper();\n"
                + "        String s = OK.label();\n"
                + "    }\n"
                + "    private void helper() { }\n"
                + "}\n");

        // @Slf4j 合成 log 字段：接收者推断为外部 Logger（展示边）
        writeText(new File(src, "LogUser.java"),
                "package demo;\n"
                + "import lombok.extern.slf4j.Slf4j;\n"
                + "@Slf4j\n"
                + "public class LogUser {\n"
                + "    public void work() {\n"
                + "        log.info(\"x\");\n"
                + "    }\n"
                + "}\n");

        // MapStruct @Mapper 接口：镜像合成 XxxImpl（编译期生成源码被索引，仓内无源码）
        writeText(new File(src, "Order.java"),
                "package demo;\n"
                + "public class Order { }\n");
        // 嵌套类点分类型引用（参数 / 泛型实参）：dotted 拼接点在段前，不再拼成 OuterInner.
        writeText(new File(src, "NestedRef.java"),
                "package demo;\n"
                + "import java.util.List;\n"
                + "public class NestedRef {\n"
                + "    public void takeInner(Misc.Inner inner) { }\n"
                + "    public void takeList(List<Misc.Inner> inners) { }\n"
                + "}\n");
        writeText(new File(src, "OrderConvert.java"),
                "package demo;\n"
                + "import org.mapstruct.Mapper;\n"
                + "@Mapper\n"
                + "public interface OrderConvert {\n"
                + "    Item toDto(Order order);\n"
                + "    default Item backup(Order order) { return null; }\n"
                + "}\n");

        // 多方信息聚合：JDK 内置签名表（声明处擦除格式）+ lambda 参数绑定 + 链式泛型代入
        // + guava wildcard 消歧 + 外部依赖边签名升级（import FQN + arity + 实参类型）
        writeText(new File(src, "StreamChain.java"),
                "package demo;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "import java.util.stream.Collectors;\n"
                + "import com.google.common.collect.*;\n"
                + "import com.example.stock.client.dto.StockFormDTO;\n"
                + "import com.example.stock.client.rpc.StockClient;\n"
                + "public class StreamChain {\n"
                + "    private StockClient client;\n"
                + "\n"
                + "    public List<StockFormDTO> filterActive(List<StockFormDTO> forms) {\n"
                + "        return forms.stream()\n"
                + "                .filter(f -> f.getActive())\n"
                + "                .collect(Collectors.toList());\n"
                + "    }\n"
                + "\n"
                + "    public void forEachName(List<StockFormDTO> forms) {\n"
                + "        forms.forEach(f -> f.getWarehouseId());\n"
                + "    }\n"
                + "\n"
                + "    public void mapForEach(Map<String, StockFormDTO> byId) {\n"
                + "        byId.forEach((k, v) -> v.getWarehouseId());\n"
                + "    }\n"
                + "\n"
                + "    public String firstWarehouse(List<StockFormDTO> forms) {\n"
                + "        return forms.get(0).getWarehouseId();\n"
                + "    }\n"
                + "\n"
                + "    public List<StockFormDTO> guavaCopy(List<StockFormDTO> forms) {\n"
                + "        return Lists.newArrayList(forms);\n"
                + "    }\n"
                + "\n"
                + "    public String rpcCall() {\n"
                + "        StockFormDTO form = new StockFormDTO();\n"
                + "        form.setWarehouseId(\"x\");\n"
                + "        return client.listByCondition(form);\n"
                + "    }\n"
                + "}\n");

        // 多方信息聚合：仓内同名同 arity 重载消歧（生产实证 adapter 4 个单参 provide 重载，
        // 旧逻辑“返回首个声明”选错——实参静态类型（scope 局部变量）即完整消歧信息）
        writeText(new File(src, "OverloadAdapter.java"),
                "package demo;\n"
                + "import java.util.List;\n"
                + "public class OverloadAdapter {\n"
                + "    public String provide(Order order) { return \"o\"; }\n"
                + "    public String provide(List<Order> orders) { return \"l\"; }\n"
                + "    public String provide(String code, Order order) { return \"s\"; }\n"
                + "}\n");
        writeText(new File(src, "OverloadCaller.java"),
                "package demo;\n"
                + "import java.util.List;\n"
                + "public class OverloadCaller {\n"
                + "    private OverloadAdapter adapter = new OverloadAdapter();\n"
                + "\n"
                + "    public String callSingle(Order o) { return adapter.provide(o); }\n"
                + "\n"
                + "    public String callList(List<Order> os) { return adapter.provide(os); }\n"
                + "\n"
                + "    public String callTwo(Order o) { return adapter.provide(\"x\", o); }\n"
                + "}\n");

        // 框架生成器镜像（静态代码人可读则可补全）：MapStruct 通配 import + @MappingTarget 参数
        // + xToY 辅助方法（源/目标字段 diff）；@Builder 全套；Lombok 构造器；匿名类 ctor
        writeText(new File(src, "SdkTag.java"),
                "package demo;\n"
                + "public class SdkTag { }\n");
        writeText(new File(src, "TagDto.java"),
                "package demo;\n"
                + "public class TagDto { }\n");
        writeText(new File(src, "SdkItem.java"),
                "package demo;\n"
                + "import java.util.List;\n"
                + "public class SdkItem {\n"
                + "    private Long id;\n"
                + "    private String name;\n"
                + "    private SdkTag tag;\n"
                + "    private List<SdkTag> tags;\n"
                + "}\n");
        writeText(new File(src, "ItemDto.java"),
                "package demo;\n"
                + "import java.util.List;\n"
                + "public class ItemDto {\n"
                + "    private Long id;\n"
                + "    private String name;\n"
                + "    private TagDto tag;\n"
                + "    private List<TagDto> tags;\n"
                + "}\n");
        // 通配 import org.mapstruct.*（生产实证 IndividualConvertUtil 形态，旧版仅认精确 import 漏合成）
        writeText(new File(src, "WildcardConvert.java"),
                "package demo;\n"
                + "import org.mapstruct.*;\n"
                + "@Mapper\n"
                + "public interface WildcardConvert {\n"
                + "    ItemDto toDto(SdkItem src);\n"
                + "\n"
                + "    default void fill(SdkItem src, @MappingTarget ItemDto target) { }\n"
                + "}\n");

        writeText(new File(src, "BuilderDto.java"),
                "package demo;\n"
                + "import java.util.List;\n"
                + "import lombok.Builder;\n"
                + "@Builder\n"
                + "public class BuilderDto {\n"
                + "    private String name;\n"
                + "    private List<String> tags;\n"
                + "}\n");
        writeText(new File(src, "BuilderUser.java"),
                "package demo;\n"
                + "public class BuilderUser {\n"
                + "    public BuilderDto make() {\n"
                + "        return BuilderDto.builder().name(\"x\").build();\n"
                + "    }\n"
                + "}\n");

        writeText(new File(src, "OpDto.java"),
                "package demo;\n"
                + "import lombok.Data;\n"
                + "@Data\n"
                + "public class OpDto {\n"
                + "    private Long id;\n"
                + "    private String name;\n"
                + "}\n");
        writeText(new File(src, "FullDto.java"),
                "package demo;\n"
                + "import lombok.AllArgsConstructor;\n"
                + "@AllArgsConstructor\n"
                + "public class FullDto {\n"
                + "    private Long a;\n"
                + "    private String b;\n"
                + "}\n");

        writeText(new File(src, "Callback.java"),
                "package demo;\n"
                + "public interface Callback {\n"
                + "    void run();\n"
                + "}\n");
        writeText(new File(src, "AnonUser.java"),
                "package demo;\n"
                + "public class AnonUser {\n"
                + "    public void work() {\n"
                + "        Callback c = new Callback() {\n"
                + "            public void run() { }\n"
                + "        };\n"
                + "        c.run();\n"
                + "    }\n"
                + "}\n");

        File out = new File(work, "static.jsonl");
        StaticJavaIndexer.Result r = new StaticJavaIndexer().index(work, out);
        Assert.assertTrue(r.ok(), "static index should produce lines: " + r.summary());
        Assert.assertEquals(r.parseFailures, 0, "no parse failures");
        Assert.assertTrue(r.files >= 6);

        // 全行 schema 校验 + def/call 集合构建
        List<String> raw = readAllLines(out);
        Set<String> defs = new HashSet<String>();
        Set<String> calls = new HashSet<String>(); // "from->to"
        Map<String, JsonObject> defBySym = new HashMap<String, JsonObject>();
        for (String line : raw) {
            JsonObject o = JsonUtil.parseObject(line);
            if ("def".equals(o.getString("t"))) {
                Assert.assertEquals(o.getString("kind"), o.getString("kind").equals("class") ? "class" : "method");
                Assert.assertTrue(o.getString("sym").length() > 0);
                Assert.assertTrue(o.getString("file").startsWith("src/main/java/"));
                Assert.assertTrue(o.getInt("line") > 0);
                defs.add(o.getString("sym"));
                defBySym.put(o.getString("sym"), o);
            } else {
                Assert.assertEquals(o.getString("t"), "call");
                Assert.assertTrue(o.getString("from").length() > 0);
                Assert.assertTrue(o.getString("to").length() > 0);
                Assert.assertTrue(o.getInt("line") > 0);
                calls.add(o.getString("from") + "->" + o.getString("to"));
            }
        }

        // ── def sym 规约（对齐编译期格式）──
        Assert.assertTrue(defs.contains("demo.OrderService"), "class def");
        Assert.assertTrue(defs.contains("demo.Misc.Status"), "enum def（kind=class，嵌套用 . 连接）");
        Assert.assertTrue(defs.contains("demo.Misc.Inner"), "nested class def");
        Assert.assertTrue(defs.contains("demo.OrderService#<init>(demo.Repository)"), "ctor def：name=<init>，参数 FQN");
        Assert.assertTrue(defs.contains("demo.OrderService#submit(double)"), "primitive 参数");
        Assert.assertTrue(defs.contains("demo.Repository#save(java.util.List<demo.Item>)"),
                "泛型参数带 FQN 实参：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.Misc.Inner#ping(java.lang.String[])"), "varargs 按数组渲染");
        Assert.assertTrue(defs.contains("demo.Misc#arrays(int[],java.lang.String[][])"), "多维数组，逗号无空格");
        Assert.assertTrue(defs.contains("demo.NestedRef#takeInner(demo.Misc.Inner)"),
                "嵌套类点分参数 FQN（不再拼成 MiscInner.）：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.NestedRef#takeList(java.util.List<demo.Misc.Inner>)"),
                "泛型实参内嵌套类 FQN：实际 defs=" + defs);
        Assert.assertTrue(defs.contains(
                "demo.AuditController#check((@javax.validation.Valid :: demo.Item),java.util.List<java.lang.String>,java.util.Map<java.lang.String,demo.Item>)"),
                "JDK wildcard 补全（List→java.util.List）+ 参数注解渲染（(@FQN :: T)：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.AuditController#tagged(demo.Item)"),
                "带值注解不渲染（javac8 AnnotatedType.toString 只渲染无值注解）：实际 defs=" + defs);

        // ── call 边仲裁（def/call join 保证）──
        Assert.assertTrue(calls.contains("demo.OrderService#submit(double)->demo.PriceCalculator#calcPrice(double)"),
                "字段接收者解析：实际 calls=" + calls);
        Assert.assertTrue(calls.contains("demo.OrderService#submit(double)->demo.OrderService#audit(double)"),
                "裸调用当前类方法");
        Assert.assertTrue(calls.contains("demo.OrderService#submit(double)->demo.BaseService#hook()"),
                "super.method() 沿父类解析");
        Assert.assertTrue(calls.contains("demo.OrderService#<init>(demo.Repository)->demo.BaseService#self()"),
                "裸调用继承方法（层级查找，构造器体内）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains("demo.OrderService#submit(double)->demo.Repository#save(java.util.List<demo.Item>)"),
                "接口类型形参接收者 → 挂接口声明（与编译期 Trees.getElement 一致）");
        Assert.assertTrue(calls.contains("demo.OrderService#submit(double)->java.util.Objects#requireNonNull(T)"),
                "外部依赖 JDK 表签名边（声明处擦除格式，对齐生产 TypeMirror.toString）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains("?->demo.PriceCalculator#<init>()"),
                "字段初始化 from=? + 隐式默认构造器边");
        Assert.assertTrue(calls.contains("demo.Misc#callInner()->demo.Misc.Inner#ping(java.lang.String[])"),
                "new Inner().ping(...) 接收者解析");

        // ── Lombok 合成访问器（@Data/@Getter：def + 边 join）──
        Assert.assertTrue(defs.contains("demo.LombokDto#getName()"), "显式 getName def（遮蔽合成）");
        int getNameDefs = 0;
        for (String line : raw) {
            if (line.contains("\"sym\":\"demo.LombokDto#getName()\"")) {
                getNameDefs++;
            }
        }
        Assert.assertEquals(getNameDefs, 1, "显式声明遮蔽合成，getName 只一份：实际=" + getNameDefs);
        Assert.assertTrue(defs.contains("demo.LombokDto#setName(java.lang.String)"), "@Data 合成 setter：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.LombokDto#isActive()"), "bare boolean 用 is 前缀");
        Assert.assertTrue(defs.contains("demo.LombokDto#setActive(boolean)"), "primitive setter 参数");
        Assert.assertTrue(defs.contains("demo.LombokDto#getChildren()"), "@Data 合成 getter");
        Assert.assertTrue(defs.contains("demo.LombokDto#setChildren(java.util.List<demo.LombokDto>)"),
                "合成 setter 带完整泛型实参：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.LombokDto#getCount()"), "字段级 @Getter 合成");

        // ── 静态通配导入文件的裸调用（ownerHint 修复：不错挂通配 owner）──
        Assert.assertTrue(calls.contains("demo.BareCalls#run()->demo.BareCalls#helper()"),
                "裸调用本类私有方法解析到本类（不再错挂通配 owner）：实际 calls=" + calls);
        Assert.assertFalse(calls.toString().contains("TraceCodes#helper"),
                "裸调用不得错挂到静态通配 owner");
        Assert.assertTrue(calls.contains("demo.BareCalls#run()->demo.TraceCodes#label()"),
                "枚举常量接收者方法调用：实际 calls=" + calls);

        // ── @Slf4j 合成 log 字段（外部 Logger 完整签名边：实参类型推断升级）──
        Assert.assertTrue(calls.contains("demo.LogUser#work()->org.slf4j.Logger#info(java.lang.String)"),
                "log.info 实参类型推断渲染完整签名（对齐生产从 jar 解析的 info(String)）：实际 calls=" + calls);

        // ── MapStruct @Mapper 镜像合成 XxxImpl（symbol 搜索对齐编译期生成源码）──
        Assert.assertTrue(defs.contains("demo.OrderConvertImpl"), "合成 Impl 类 def");
        Assert.assertTrue(defs.contains("demo.OrderConvertImpl#toDto(demo.Order)"),
                "合成 Impl 方法 def（抽象方法）：实际 defs=" + defs);
        Assert.assertFalse(defs.contains("demo.OrderConvertImpl#backup(demo.Order)"),
                "default 方法不合成（MapStruct 只实现抽象方法）");

        // ── 多方信息聚合：JDK 表声明处归一 + lambda 绑定 + 链式代入 + guava 消歧 + 外部边签名 ──
        String filterActiveFrom = "demo.StreamChain#filterActive(java.util.List<com.example.stock.client.dto.StockFormDTO>)";
        Assert.assertTrue(calls.contains(filterActiveFrom + "->java.util.Collection#stream()"),
                "stream 边声明处归一（List → Collection，对齐生产 157 次实测形态）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(filterActiveFrom + "->java.util.stream.Stream#filter(java.util.function.Predicate<? super T>)"),
                "链式接收者推断（stream() 返回 Stream<E> 代入后 filter 边可解析）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(filterActiveFrom + "->java.util.stream.Stream#collect(java.util.stream.Collector<? super T,A,R>)"),
                "collect 边（对齐生产 126 次实测形态）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(filterActiveFrom + "->java.util.stream.Collectors#toList()"),
                "Collectors.toList() 静态接收者解析：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(filterActiveFrom + "->com.example.stock.client.dto.StockFormDTO#getActive()"),
                "filter lambda 参数绑定（f 类型 = Stream 元素，import FQN 拿到外部类）：实际 calls=" + calls);
        String forEachFrom = "demo.StreamChain#forEachName(java.util.List<com.example.stock.client.dto.StockFormDTO>)";
        Assert.assertTrue(calls.contains(forEachFrom + "->java.lang.Iterable#forEach(java.util.function.Consumer<? super T>)"),
                "Iterable#forEach 声明处签名边（对齐生产 58 次实测形态）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(forEachFrom + "->com.example.stock.client.dto.StockFormDTO#getWarehouseId()"),
                "forEach lambda 体内外部 DTO getter 边（无参 () 渲染）：实际 calls=" + calls);
        String mapFrom = "demo.StreamChain#mapForEach(java.util.Map<java.lang.String,com.example.stock.client.dto.StockFormDTO>)";
        Assert.assertTrue(calls.contains(mapFrom + "->java.util.Map#forEach(java.util.function.BiConsumer<? super K,? super V>)"),
                "Map#forEach 双参 lambda 声明处签名边：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(mapFrom + "->com.example.stock.client.dto.StockFormDTO#getWarehouseId()"),
                "Map.forEach 双参 lambda 的 v 绑定到 V 实参：实际 calls=" + calls);
        String firstFrom = "demo.StreamChain#firstWarehouse(java.util.List<com.example.stock.client.dto.StockFormDTO>)";
        Assert.assertTrue(calls.contains(firstFrom + "->java.util.List#get(int)"),
                "get(int) 表签名边：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(firstFrom + "->com.example.stock.client.dto.StockFormDTO#getWarehouseId()"),
                "get(0) 链式返回类型代入（E → DTO）后续边可解析：实际 calls=" + calls);
        String guavaFrom = "demo.StreamChain#guavaCopy(java.util.List<com.example.stock.client.dto.StockFormDTO>)";
        Assert.assertTrue(calls.contains(guavaFrom + "->com.google.common.collect.Lists#newArrayList(java.lang.Iterable<? extends E>)"),
                "guava wildcard 消歧（import com.google.common.collect.*）+ 重载实参形态匹配（Iterable 版而非 E[]）：实际 calls=" + calls);
        String rpcFrom = "demo.StreamChain#rpcCall()";
        Assert.assertTrue(calls.contains(rpcFrom + "->com.example.stock.client.dto.StockFormDTO#<init>()"),
                "外部构造器边（import FQN）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(rpcFrom + "->com.example.stock.client.dto.StockFormDTO#setWarehouseId(java.lang.String)"),
                "外部 setter 实参类型推断完整签名（String 字面量）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(rpcFrom + "->com.example.stock.client.rpc.StockClient#listByCondition(com.example.stock.client.dto.StockFormDTO)"),
                "外部 RPC client 带参完整签名（实参类型推断，import 即信息源）：实际 calls=" + calls);

        // ── 仓内同名同 arity 重载消歧（实参静态类型聚合匹配形参）──
        Assert.assertTrue(calls.contains("demo.OverloadCaller#callSingle(demo.Order)->demo.OverloadAdapter#provide(demo.Order)"),
                "重载消歧：实参 Order 选 provide(Order) 而非首个声明的 List 版（生产 provide miss 同根因）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains("demo.OverloadCaller#callList(java.util.List<demo.Order>)->demo.OverloadAdapter#provide(java.util.List<demo.Order>)"),
                "重载消歧：实参 List<Order> 选 provide(List<Order>)（剥泛型后逐位匹配）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains("demo.OverloadCaller#callTwo(demo.Order)->demo.OverloadAdapter#provide(java.lang.String,demo.Order)"),
                "不同 arity 重载按参数个数命中（String 字面量实参类型）：实际 calls=" + calls);

        // ── MapStruct 通配 import + @MappingTarget 参数渲染 + xToY 辅助方法（接口无声明，生成器产物镜像）──
        Assert.assertTrue(defs.contains("demo.WildcardConvertImpl"),
                "通配 import org.mapstruct.* 命中 Mapper 判定（生产实证 IndividualConvertUtil 形态）：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.WildcardConvertImpl#toDto(demo.SdkItem)"),
                "通配导入下抽象方法镜像合成：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.WildcardConvert#fill(demo.SdkItem,demo.ItemDto)"),
                "@MappingTarget 参数注解不渲染（javac 参数声明注解非 TYPE_USE）：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.WildcardConvertImpl#sdkTagToTagDto(demo.SdkTag)"),
                "MapStruct xToY 辅助方法合成（源/目标同名不同型字段对）：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.WildcardConvertImpl#sdkTagListToTagDtoList(java.util.List<demo.SdkTag>)"),
                "MapStruct List 字段变体 helper（xListToYList）：实际 defs=" + defs);

        // ── @Builder 全套镜像（builder() + 嵌套类 + fluent 字段方法 + build() + 链式续链）──
        Assert.assertTrue(defs.contains("demo.BuilderDto#builder()"),
                "@Builder 合成静态 builder()：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.BuilderDto.BuilderDtoBuilder"),
                "@Builder 合成嵌套类 def（点分 FQN 对齐生产）：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.BuilderDto.BuilderDtoBuilder#name(java.lang.String)"),
                "fluent 字段方法（字段类型渲染）：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.BuilderDto.BuilderDtoBuilder#tags(java.util.List<java.lang.String>)"),
                "fluent 泛型字段方法（完整泛型实参）：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.BuilderDto.BuilderDtoBuilder#build()"),
                "build() 方法：实际 defs=" + defs);
        Assert.assertTrue(defs.contains("demo.BuilderDto#<init>(java.lang.String,java.util.List<java.lang.String>)"),
                "@Builder 单独隐含全参构造器：实际 defs=" + defs);
        String makeFrom = "demo.BuilderUser#make()";
        Assert.assertTrue(calls.contains(makeFrom + "->demo.BuilderDto#builder()"),
                "builder() 静态调用边：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(makeFrom + "->demo.BuilderDto.BuilderDtoBuilder#name(java.lang.String)"),
                "builder 链式续链（builder() 返回 Builder → fluent 方法边可解析）：实际 calls=" + calls);
        Assert.assertTrue(calls.contains(makeFrom + "->demo.BuilderDto.BuilderDtoBuilder#build()"),
                "build() 链式边（fluent 返回 Builder 自身续链）：实际 calls=" + calls);

        // ── Lombok 构造器镜像（@Data 零必填 → 无参；@AllArgsConstructor → 全参；含隐式 super 边）──
        Assert.assertTrue(defs.contains("demo.OpDto#<init>()"),
                "@Data 零必填字段合成无参构造器（生产 Operator#<init>() 同形态）：实际 defs=" + defs);
        Assert.assertTrue(calls.contains("demo.OpDto#<init>()->java.lang.Object#<init>()"),
                "合成构造器的隐式 super() 边：实际 calls=" + calls);
        Assert.assertTrue(defs.contains("demo.FullDto#<init>(java.lang.Long,java.lang.String)"),
                "@AllArgsConstructor 全字段构造器：实际 defs=" + defs);

        // ── 匿名类 ctor 镜像（生产实测形态 EnclosingCls.methodName.#<init()）──
        Assert.assertTrue(defs.contains("demo.AnonUser.work.#<init>()"),
                "匿名类 ctor def（外围类.方法名. 前缀）：实际 defs=" + defs);
        Assert.assertTrue(calls.contains("demo.AnonUser#work()->demo.AnonUser.work.#<init>()"),
                "new Iface(){} 调用边挂匿名类 ctor：实际 calls=" + calls);
        Assert.assertTrue(calls.contains("demo.AnonUser.work.#<init>()->java.lang.Object#<init>()"),
                "匿名类（接口实现）ctor 隐式 super 边挂 Object：实际 calls=" + calls);
        Assert.assertTrue(calls.contains("demo.AnonUser#work()->demo.Callback#run()"),
                "匿名类实例变量的接收者解析（c.run() 边仍可解析）：实际 calls=" + calls);

        // ── 真实 RepoIndex 装载：schema 兼容 + join 生效（查询端零改动可用）──
        File meta = new File(work, "fixture.meta.json");
        writeText(meta, "{\"repoCode\":\"fixture\",\"commit\":\"c0ffee\",\"ref\":\"master\",\"indexType\":\"STATIC\"}\n");
        RepoIndex idx = RepoIndex.load("fixture", out, meta);
        Assert.assertEquals(idx.getIndexType(), "STATIC", "meta indexType 读取");
        List<RepoIndex.Def> found = idx.searchSymbols("calcPrice", 10, false);
        Assert.assertFalse(found.isEmpty(), "符号搜索命中");
        Assert.assertEquals(found.get(0).sym, "demo.PriceCalculator#calcPrice(double)");
        List<RepoIndex.Call> incoming = idx.incomingCalls("demo.PriceCalculator#calcPrice(double)");
        boolean joinOk = false;
        for (RepoIndex.Call c : incoming) {
            if ("demo.OrderService#submit(double)".equals(c.from)) {
                joinOk = true;
                Assert.assertTrue(c.line > 0);
            }
        }
        Assert.assertTrue(joinOk, "静态索引 call 边能被 RepoIndex incoming 反查（join 成立）");
        Assert.assertFalse(idx.incomingCalls("demo.Misc.Inner#ping(java.lang.String[])").isEmpty(),
                "varargs 方法可反查");

        // ── 旧 meta（无 indexType 字段）向后兼容：视为 COMPILED ──
        File metaOld = new File(work, "old.meta.json");
        writeText(metaOld, "{\"repoCode\":\"fixture\",\"commit\":\"c0ffee\",\"ref\":\"master\"}\n");
        RepoIndex idxOld = RepoIndex.load("fixture", out, metaOld);
        Assert.assertEquals(idxOld.getIndexType(), "COMPILED", "旧 meta 缺字段视为 COMPILED");
    }

    private static List<String> readAllLines(File f) throws Exception {
        List<String> lines = new ArrayList<String>();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        } finally {
            r.close();
        }
        return lines;
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

    private static File createTempDir(String prefix) throws Exception {
        File f = File.createTempFile(prefix, "");
        Assert.assertTrue(f.delete());
        Assert.assertTrue(f.mkdirs());
        return f;
    }
}
