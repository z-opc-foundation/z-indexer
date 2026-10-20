package com.zifang.z.indexer;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * README 里每一条定量主张都必须能从源码重算出来 —— 广告出去的话就是承诺。
 *
 * <p>为什么要有这一层：本仓 README 把模块数、版本号、Controller 数、REST 端点数、
 * 配置表的两列默认值、内置工具链的字节数、git 地址脱敏的出口数都写死了
 * （「模块数 3」「5 个 REST Controller」「共 19 个方法级端点」「7 个出口」…），
 * 而这些数每加一个类 / 改一次配置就会漂一次。没有任何尺子去问源码「现在还是吗」，
 * 那这些数字的保质期就是上一次编辑的时刻。</p>
 *
 * <p>口径四条（前几轮踩过的坑逐条钉在这里）：</p>
 * <ol>
 *   <li><b>事实对象只装源码侧</b>：{@link Facts} 里没有一个数字来自 README。
 *       README 侧的数字一律在核对<b>当下</b>从被核对的那份 README 里现解析 ——
 *       两边都从 README 取数再互相比，那是恒等式比较，尺子等于没有。</li>
 *   <li><b>同一个数出现在多处，每一处都要单独钉</b>：版本号同时出现在「当前版本」行、
 *       结构树的 pom.xml 行、jar 文件名里；「7 个出口」同时出现在小节标题和正文里。
 *       只钉一处的话，改另一处不红。</li>
 *   <li><b>数量之外还要钉名单</b>：只断言「3 个模块」会漏掉「README 少列一个模块名」，
 *       所以模块名 / Controller 名 / 内置工具链文件名都做双向比对。</li>
 *   <li><b>用唯一锚点文本 + 结构特征定位</b>，不拿数字当定位标签：短数字是长数字的子串
 *       （「3 个模块」⊂「13 个模块」），按数字定位会匹配到别人的行上 ——
 *       尺子先失灵、后冤枉健康页，这是尺子自己的 bug，不是 README 的问题。</li>
 * </ol>
 *
 * <p>数值不等 = 红且把<b>重算值</b>打进消息（红了要知道真数是多少，否则只是又一处猜）。
 * 尺自己的牙口由 {@link #rulerActuallyCondemnsWrongNumbers()} 用合成 README 证明，
 * 而「不许冤枉健康页」由 {@link #readmeClaimsMatchTheCode()} 本身证明。</p>
 */
public class ReadmeClaimsTest {

    // ==================================================================
    // 仓内坐标（相对仓根）
    // ==================================================================

    private static final String CORE = "z-indexer-core";
    private static final String STARTER = "z-indexer-spring-boot-starter";
    private static final String SERVER = "z-indexer-server";

    /** README 配置表逐行 ↔ {@code IndexerProperties} 字段：{README 的键, 字段名}。 */
    private static final String[][] CONFIG_ROWS = {
            {"enabled", "enabled"},
            {"repos-root", "reposRoot"},
            {"poll-interval-ms", "pollIntervalMs"},
            {"poll-initial-delay-ms", "pollInitialDelayMs"},
            {"build-timeout-minutes", "buildTimeoutMinutes"},
            {"static-fallback-enabled", "staticFallbackEnabled"},
            {"maven-opts", "mavenOpts"},
            {"build-concurrency", "buildConcurrency"},
            {"lru-max-repos", "lruMaxRepos"},
            {"warmup-repos", "warmupRepos"},
            {"big-repo-codes", "bigRepoCodes"},
            {"build-yield-repos", "buildYieldRepos"},
            {"default-depth", "defaultDepth"},
            {"max-result-nodes", "maxResultNodes"},
    };

    /** README 配置表里那行三键合一（`mvn-cmd` / `git-cmd` / `node-cmd`）——不参与逐键对账。 */
    private static final String MERGED_CMD_ROW_KEY = "mvn-cmd";

    // ==================================================================
    // 事实对象：只装源码侧，一个数字都不许来自 README
    // ==================================================================

    private static final class Facts {
        /** 根 pom {@code <modules>} 的模块名（声明顺序）。 */
        final List<String> modules;
        /** 根 pom {@code <properties><revision>}（CI-friendly 版本号）。 */
        final String revision;
        /** 根 pom {@code <parent><version>}。 */
        final String parentVersion;
        /** 根 pom {@code <properties>} 的全部键值。 */
        final Map<String, String> pomProperties;
        /** starter src/main 下 Controller 的简单名（去掉 Controller 后缀）。 */
        final List<String> controllerNames;
        /** 方法级映射注解数（{@code @RequestMapping(value=...)} 等）。 */
        final int methodLevelMappings;
        /** 类级 {@code @RequestMapping} 数。 */
        final int classLevelMappings;
        /** {@code IndexerProperties} 字段默认值：字段名 → 字面量。 */
        final Map<String, String> propertyDefaults;
        /** {@code application.yaml} 的 {@code z.indexer.*} 段：键 → 值。 */
        final Map<String, String> yamlValues;
        /** 内置工具链每个文件的字节数。 */
        final Map<String, Long> binSizes;
        /** src/main 里 {@code GitUrlMasker.mask(} 的调用点数 = git 地址脱敏出口数。 */
        final int maskOutlets;
        /** {@code IndexAdminController#toMap} 的调用点数 = 走管理 API 响应体这个出口的端点数。 */
        final int toMapCallSites;
        /** 全部模块 src/test 下的测试类简单名。 */
        final Set<String> testClassNames;
        /** {@code _frontend/} 下的受控文件数。 */
        final int frontendFileCount;

        Facts(List<String> modules, String revision, String parentVersion, Map<String, String> pomProperties,
                List<String> controllerNames, int methodLevelMappings, int classLevelMappings,
                Map<String, String> propertyDefaults, Map<String, String> yamlValues, Map<String, Long> binSizes,
                int maskOutlets, int toMapCallSites, Set<String> testClassNames, int frontendFileCount) {
            this.modules = modules;
            this.revision = revision;
            this.parentVersion = parentVersion;
            this.pomProperties = pomProperties;
            this.controllerNames = controllerNames;
            this.methodLevelMappings = methodLevelMappings;
            this.classLevelMappings = classLevelMappings;
            this.propertyDefaults = propertyDefaults;
            this.yamlValues = yamlValues;
            this.binSizes = binSizes;
            this.maskOutlets = maskOutlets;
            this.toMapCallSites = toMapCallSites;
            this.testClassNames = testClassNames;
            this.frontendFileCount = frontendFileCount;
        }
    }

    /** 仓根：向上找同时有 README.md 与 pom.xml 的目录（测试跑在模块目录，user.dir 不是仓根）。 */
    private static Path repoRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("README.md")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
        }
        throw new IllegalStateException("定位仓根失败：user.dir=" + System.getProperty("user.dir"));
    }

    private static String read(Path f) {
        try {
            return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String norm(Path p) {
        return p.toString().replace('\\', '/');
    }

    // ==================================================================
    // 源码侧重算
    // ==================================================================

    private static Element parseXml(Path pom) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            Document doc = f.newDocumentBuilder().parse(pom.toFile());
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("解析 pom 失败：" + pom, e);
        }
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    /** 根 pom 的 {@code <modules>}：模块名清单（XML 解析，天然排除被注释掉的 module）。 */
    private static List<String> pomModules(Element project) {
        Element modules = child(project, "modules");
        if (modules == null) {
            throw new IllegalStateException("根 pom 里没有 <modules>");
        }
        List<String> out = new ArrayList<>();
        NodeList nl = modules.getElementsByTagName("module");
        for (int i = 0; i < nl.getLength(); i++) {
            out.add(nl.item(i).getTextContent().trim());
        }
        return out;
    }

    /** 根 pom 的 {@code <properties>}：键 → 字面值。 */
    private static Map<String, String> pomProperties(Element project) {
        Map<String, String> out = new TreeMap<>();
        Element props = child(project, "properties");
        if (props == null) {
            return out;
        }
        NodeList cs = props.getChildNodes();
        for (int i = 0; i < cs.getLength(); i++) {
            Node n = cs.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.put(n.getNodeName(), n.getTextContent().trim());
            }
        }
        return out;
    }

    /** 根 pom 的项目版本：CI-friendly 走 {@code <revision>}，否则取自身的 {@code <version>}。 */
    private static String projectVersion(Element project, Map<String, String> properties) {
        String revision = properties.get("revision");
        if (revision != null && !revision.isEmpty()) {
            return revision;
        }
        Element v = child(project, "version");
        if (v == null) {
            throw new IllegalStateException("根 pom 里既没有 <revision> 也没有 <version>");
        }
        return v.getTextContent().trim();
    }

    /** 根 pom 的父项目版本。 */
    private static String parentVersion(Element project) {
        Element parent = child(project, "parent");
        if (parent == null) {
            throw new IllegalStateException("根 pom 里没有 <parent>");
        }
        Element v = child(parent, "version");
        if (v == null) {
            throw new IllegalStateException("根 pom 的 <parent> 里没有 <version>");
        }
        return v.getTextContent().trim();
    }

    /** 某模块 src/main 下的 .java 相对路径集合（相对仓根，只数 src/main）。 */
    private static List<Path> mainJavaFiles(Path root, String module) {
        Path d = root.resolve(module).resolve("src/main");
        if (!Files.isDirectory(d)) {
            throw new IllegalStateException("目录不存在：" + module + "/src/main");
        }
        try (Stream<Path> s = Files.walk(d)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !norm(p).contains("/target/"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** starter 的 Controller 简单名（去掉 {@code Controller} 后缀）。 */
    private static List<String> controllerNames(Path root) {
        Path d = root.resolve(STARTER).resolve("src/main/java/com/zifang/z/indexer/controller");
        if (!Files.isDirectory(d)) {
            throw new IllegalStateException("目录不存在：" + STARTER + "/src/main/.../controller");
        }
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("Controller.java"))
                    .map(n -> n.substring(0, n.length() - "Controller.java".length()))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 剥掉注释（<b>保留字符串字面量</b>），行号列号不变。
     *
     * <p>不剥注释就会把 Javadoc 里出现的 {@code @GetMapping} 字样当成真映射 ——
     * 那正是尺子冤枉健康页的经典姿势。</p>
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                for (int k = 0; k < 2 && i < n; k++) {
                    out.append(' ');
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static final Pattern MAPPING_ANN =
            Pattern.compile("@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\b");
    private static final Pattern CLASS_DECL = Pattern.compile(
            "^(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)\\s+)*class\\s");

    /** 找到注解的结束位置（带括号的参数列表要配平）。 */
    private static int annotationEnd(String src, int from) {
        int i = from;
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
        if (i >= src.length() || src.charAt(i) != '(') {
            return from;
        }
        int depth = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return src.length();
    }

    /** 跳过紧随其后的其它注解与空白，返回下一个实体的起始位置。 */
    private static int skipAnnotations(String src, int from) {
        int i = from;
        while (true) {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
                i++;
            }
            if (i < src.length() && src.charAt(i) == '@') {
                i++;
                while (i < src.length() && (Character.isJavaIdentifierPart(src.charAt(i)))) {
                    i++;
                }
                i = annotationEnd(src, i);
                continue;
            }
            return i;
        }
    }

    /**
     * 数方法级 / 类级映射注解。
     *
     * <p>类级判定：注解之后（跳过其它注解与空白）紧跟的是 {@code class} 声明 ⇒ 类级；
     * 否则是方法级。只数 src/main —— 测试类不是 REST 面。</p>
     */
    private static int[] countMappings(String src) {
        String code = stripComments(src);
        int classLevel = 0;
        int methodLevel = 0;
        Matcher m = MAPPING_ANN.matcher(code);
        while (m.find()) {
            int end = annotationEnd(code, m.end());
            int next = skipAnnotations(code, end);
            String upcoming = code.substring(next, Math.min(code.length(), next + 200));
            if (CLASS_DECL.matcher(upcoming).find()) {
                classLevel++;
            } else {
                methodLevel++;
            }
        }
        return new int[]{classLevel, methodLevel};
    }

    /** {@code IndexerProperties} 的字段默认值：字段名 → 字面量（去掉引号 / long 的 L 后缀）。 */
    private static Map<String, String> propertyDefaults(Path root) {
        Path f = root.resolve(STARTER).resolve("src/main/java/com/zifang/z/indexer/config/IndexerProperties.java");
        if (!Files.isRegularFile(f)) {
            throw new IllegalStateException("源码文件不存在：" + f);
        }
        Map<String, String> out = new TreeMap<>();
        Matcher m = Pattern.compile("private\\s+(?:int|long|boolean|String)\\s+(\\w+)\\s*=\\s*([^;]+);")
                .matcher(read(f));
        while (m.find()) {
            out.put(m.group(1), normalizeLiteral(m.group(2).trim()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("一个字段默认值都没抠出来 —— 口径失效：" + f);
        }
        return out;
    }

    /** 字面量归一：去引号、去 long 的 L 后缀。 */
    private static String normalizeLiteral(String raw) {
        String v = raw.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        if (v.matches("\\d+[lL]")) {
            return v.substring(0, v.length() - 1);
        }
        return v;
    }

    /** 解析 {@code application.yaml} 的 {@code z.indexer.*} 段（只支持平铺的 key: value）。 */
    private static Map<String, String> parseIndexerYaml(Path yaml) {
        if (!Files.isRegularFile(yaml)) {
            throw new IllegalStateException("配置文件不存在：" + yaml);
        }
        Map<String, String> out = new TreeMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean inZ = false;
        boolean inIndexer = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            if (inIndexer) {
                if (indent <= 2) {
                    inIndexer = false;
                    inZ = false;
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon < 0) {
                    throw new IllegalStateException("yaml 行不是 key: value：" + line);
                }
                String value = trimmed.substring(colon + 1).trim();
                if (value.isEmpty()) {
                    throw new IllegalStateException("z.indexer 下出现了嵌套结构，解析口径不再成立：" + line);
                }
                out.put(trimmed.substring(0, colon).trim(), normalizeLiteral(value));
                continue;
            }
            if (indent == 0 && trimmed.equals("z:")) {
                inZ = true;
            } else if (inZ && indent == 2 && trimmed.equals("indexer:")) {
                inIndexer = true;
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("application.yaml 里一段 z.indexer.* 都没读到 —— 口径失效：" + yaml);
        }
        return out;
    }

    /** 内置工具链目录里每个文件的字节数。 */
    private static Map<String, Long> binSizes(Path root) {
        Path d = root.resolve(SERVER).resolve("src/main/resources/bin");
        if (!Files.isDirectory(d)) {
            throw new IllegalStateException("目录不存在：" + SERVER + "/src/main/resources/bin");
        }
        Map<String, Long> out = new TreeMap<>();
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                out.put(p.getFileName().toString(), Files.size(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** src/main 里 {@code GitUrlMasker.mask(} 的调用点数 —— 每一处对应脱敏表里的一行。 */
    private static int maskOutletCount(Path root, List<String> modules) {
        int n = 0;
        for (String module : modules) {
            for (Path f : mainJavaFiles(root, module)) {
                String src = read(f);
                Matcher m = Pattern.compile("GitUrlMasker\\.mask\\(").matcher(src);
                while (m.find()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** {@code IndexAdminController#toMap} 的调用点数（声明本身不算）。 */
    private static int toMapCallSites(Path root) {
        Path f = root.resolve(STARTER).resolve("src/main/java/com/zifang/z/indexer/controller/IndexAdminController.java");
        if (!Files.isRegularFile(f)) {
            throw new IllegalStateException("源码文件不存在：" + f);
        }
        String src = stripComments(read(f));
        Matcher calls = Pattern.compile("\\btoMap\\s*\\(").matcher(src);
        int n = 0;
        while (calls.find()) {
            n++;
        }
        Matcher decl = Pattern.compile("static\\s+Map<[^>]*>\\s+toMap\\s*\\(").matcher(src);
        int decls = 0;
        while (decl.find()) {
            decls++;
        }
        if (decls != 1) {
            throw new IllegalStateException("toMap 的声明数不是 1（" + decls + "），口径失效：" + f);
        }
        return n - decls;
    }

    /** 全部模块 src/test 下的测试类简单名。 */
    private static Set<String> testClassNames(Path root, List<String> modules) {
        Set<String> out = new LinkedHashSet<>();
        for (String module : modules) {
            Path d = root.resolve(module).resolve("src/test/java");
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(d)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> out.add(p.getFileName().toString().replace(".java", "")));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /** {@code _frontend/} 下的受控文件数。 */
    private static int frontendFileCount(Path root) {
        Path d = root.resolve("_frontend");
        if (!Files.isDirectory(d)) {
            throw new IllegalStateException("目录不存在：_frontend");
        }
        try (Stream<Path> s = Files.walk(d)) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一次重算出的全部源码侧事实。 */
    private static Facts recompute() {
        Path root = repoRoot();
        Element project = parseXml(root.resolve("pom.xml"));
        Map<String, String> properties = pomProperties(project);
        List<String> modules = pomModules(project);

        int methodLevel = 0;
        int classLevel = 0;
        for (String module : modules) {
            for (Path f : mainJavaFiles(root, module)) {
                int[] c = countMappings(read(f));
                classLevel += c[0];
                methodLevel += c[1];
            }
        }

        return new Facts(
                modules,
                projectVersion(project, properties),
                parentVersion(project),
                properties,
                controllerNames(root),
                methodLevel,
                classLevel,
                propertyDefaults(root),
                parseIndexerYaml(root.resolve(SERVER).resolve("src/main/resources/application.yaml")),
                binSizes(root),
                maskOutletCount(root, modules),
                toMapCallSites(root),
                testClassNames(root, modules),
                frontendFileCount(root));
    }

    // ==================================================================
    // README 侧解析（全部在核对当下从被核对的那份 README 现取）
    // ==================================================================

    /** 取标题行本身（以给定标题文本开头的那一行）。 */
    private static String headingLine(String readme, String headingPrefix) {
        for (String line : readme.split("\n", -1)) {
            if (line.startsWith(headingPrefix)) {
                return line;
            }
        }
        throw new AssertionError("README 里找不到标题行：" + headingPrefix);
    }

    /**
     * 取某个标题之后的内容，直到下一个<b>同级或更高级</b>的标题为止。
     *
     * <p>按级别停而不是见 {@code #} 就停：{@code ## 🚀 快速开始} 下面还有
     * {@code ### 编译} / {@code ### 起进程（fat jar）} 两个子标题，
     * jar 文件名那条主张就在子标题底下，停在子标题上会把它切掉。</p>
     */
    private static String section(String readme, String headingPrefix) {
        int idx = readme.indexOf(headingPrefix);
        if (idx < 0) {
            throw new AssertionError("README 里找不到章节标题：" + headingPrefix);
        }
        int level = headingLevel(headingPrefix);
        int start = readme.indexOf('\n', idx);
        if (start < 0) {
            start = readme.length();
        }
        int end = start + 1;
        while (end < readme.length()) {
            int lineEnd = readme.indexOf('\n', end);
            if (lineEnd < 0) {
                lineEnd = readme.length();
            }
            String line = readme.substring(end, lineEnd);
            if (line.startsWith("#") && headingLevel(line) <= level) {
                return readme.substring(start + 1, end);
            }
            end = lineEnd + 1;
        }
        return readme.substring(start + 1);
    }

    /** 标题的层级（{@code ##} → 2）。 */
    private static int headingLevel(String heading) {
        int n = 0;
        while (n < heading.length() && heading.charAt(n) == '#') {
            n++;
        }
        return n;
    }

    /** 取章节里的第一个围栏代码块。 */
    private static String firstCodeBlock(String section) {
        int a = section.indexOf("```");
        if (a < 0) {
            throw new AssertionError("章节里没有代码块");
        }
        int b = section.indexOf("```", a + 3);
        if (b < 0) {
            throw new AssertionError("代码块没有闭合");
        }
        return section.substring(a + 3, b);
    }

    /** 结构树的一个节点。 */
    private static final class TreeNode {
        final String name;
        final String comment;
        final int depth;
        final List<TreeNode> children = new ArrayList<>();

        TreeNode(String name, String comment, int depth) {
            this.name = name;
            this.comment = comment;
            this.depth = depth;
        }
    }

    /** 解析结构树：按树形前缀还原层级（不看数字，只看 {@code ├──} / {@code └──} 与缩进）。 */
    private static TreeNode parseTree(String block) {
        TreeNode root = new TreeNode("", "", -1);
        List<TreeNode> stack = new ArrayList<>();
        stack.add(root);
        for (String line : block.split("\n", -1)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            // 缩进前缀：竖线是 U+2502（不是 ASCII 的 |），每 4 个字符一级
            Matcher m = Pattern.compile("^((?:(?:\\u2502   )|    )*)(?:├──|└──)\\s+(.*)$").matcher(line);
            int depth;
            String rest;
            if (m.find()) {
                depth = m.group(1).length() / 4;
                rest = m.group(2);
            } else {
                depth = -1;
                rest = line.trim();
            }
            if (depth < 0) {
                // 树根行（如 "z-indexer/"）：重置层级，它自己不是仓根目录的一个条目
                stack.clear();
                stack.add(root);
                continue;
            }
            String name = rest;
            String comment = "";
            int hash = rest.indexOf('#');
            if (hash >= 0) {
                name = rest.substring(0, hash).trim();
                comment = rest.substring(hash + 1).trim();
            } else {
                name = rest.trim();
            }
            while (stack.size() > depth + 1) {
                stack.remove(stack.size() - 1);
            }
            TreeNode node = new TreeNode(name, comment, depth);
            stack.get(depth).children.add(node);
            stack.add(node);
        }
        if (root.children.isEmpty()) {
            throw new AssertionError("结构树解析不出任何节点 —— 树形被改过形？");
        }
        return root;
    }

    /** 深度优先找第一个同名节点（节点名带不带结尾的 {@code /} 都认）。 */
    private static TreeNode findNode(TreeNode node, String name) {
        for (TreeNode c : node.children) {
            if (c.name.equals(name) || c.name.equals(name + "/")) {
                return c;
            }
            TreeNode hit = findNode(c, name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 节点及其后代的全部名字。 */
    private static void collectNames(TreeNode node, Set<String> out) {
        for (TreeNode c : node.children) {
            out.add(c.name);
            collectNames(c, out);
        }
    }

    /** 抠出反引号包着的词。 */
    private static Set<String> backticked(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(text);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    /** 去掉 Markdown 行内代码的反引号。 */
    private static String unbacktick(String cell) {
        String s = cell.trim();
        if (s.startsWith("`") && s.endsWith("`") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.trim();
    }

    // ==================================================================
    // 对账
    // ==================================================================

    /** 主张对账：README 侧的值必须等于源码侧重算值，不等就红并把真数打进去。 */
    private static void claim(String tag, Object readmeValue, Object sourceValue) {
        boolean same = (readmeValue == null) ? sourceValue == null : readmeValue.equals(sourceValue);
        if (!same) {
            throw new AssertionError("[" + tag + "] 对不上：README 写的是 " + readmeValue
                    + "，源码重算是 " + sourceValue);
        }
    }

    /** 名单双向对账：README 少的、多的都要报出来。 */
    private static void claimSet(String tag, Set<String> readmeNames, Set<String> sourceNames) {
        Set<String> missing = new TreeSet<>(sourceNames);
        missing.removeAll(readmeNames);
        Set<String> extra = new TreeSet<>(readmeNames);
        extra.removeAll(sourceNames);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new AssertionError("[" + tag + "] 对不上：README 少了 " + missing
                    + "，README 多了 " + extra + "（源码重算的名单 = " + sourceNames + "）");
        }
    }

    /** 抠不到就红：绝不「没匹配上就跳过」，那正是空跑。 */
    private static Matcher require(Pattern p, String text, String what) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            throw new AssertionError("README 里抠不到" + what + " —— 主张被删或被改了形？");
        }
        return m;
    }

    /** README 里每条定量主张逐条对账。 */
    private static void verifyClaims(String readme, Facts f) {
        verifyModuleSet(readme, f);
        verifyVersions(readme, f);
        verifyPinnedProperties(readme, f);
        verifyControllers(readme, f);
        verifyEndpoints(readme, f);
        verifyConfigTable(readme, f);
        verifyToolchain(readme, f);
        verifyMaskOutlets(readme, f);
        verifyTestClassNames(readme, f);
    }

    // ---------------------------------------------------------------- 主张 1：模块集合

    private static void verifyModuleSet(String readme, Facts f) {
        // (a) 基本信息表「模块数」行：数量 + 名单
        Matcher row = require(Pattern.compile("(?m)^\\|\\s*\\*\\*模块数\\*\\*\\s*\\|(.*)$"),
                readme, "「模块数」这一行");
        String cell = row.group(1);
        Matcher num = require(Pattern.compile("^\\s*(\\d+)"), cell, "「模块数」行里的数字");
        claim("主张1a 模块数", Integer.parseInt(num.group(1)), f.modules.size());
        claimSet("主张1b 模块名单", backticked(cell), new TreeSet<>(f.modules));

        // (b) 项目结构树：顶层目录里带 src/main 的那些就是 Maven 模块，与 pom 双向一致
        TreeNode tree = parseTree(firstCodeBlock(section(readme, "## 🏗️ 项目结构")));
        Set<String> treeModules = new TreeSet<>();
        for (TreeNode top : tree.children) {
            Set<String> names = new TreeSet<>();
            collectNames(top, names);
            boolean hasSrcMain = false;
            for (String n : names) {
                if (n.startsWith("src/main")) {
                    hasSrcMain = true;
                    break;
                }
            }
            if (hasSrcMain) {
                treeModules.add(top.name.endsWith("/") ? top.name.substring(0, top.name.length() - 1) : top.name);
            }
        }
        claimSet("主张1c 结构树里的模块目录", treeModules, new TreeSet<>(f.modules));

        // (c) 结构树里 _frontend 的受控文件数
        TreeNode frontend = findNode(tree, "_frontend/");
        if (frontend == null) {
            throw new AssertionError("README 的结构树里找不到 _frontend/ 节点");
        }
        Matcher fc = require(Pattern.compile("(\\d+)\\s*个受控文件"), frontend.comment, "_frontend 那一行的受控文件数");
        claim("主张1d _frontend 受控文件数", Integer.parseInt(fc.group(1)), f.frontendFileCount);
    }

    // ---------------------------------------------------------------- 主张 2：版本

    private static void verifyVersions(String readme, Facts f) {
        // (a) 基本信息表「当前版本」行
        Matcher row = require(Pattern.compile("(?m)^\\|\\s*\\*\\*当前版本\\*\\*\\s*\\|(.*)$"),
                readme, "「当前版本」这一行");
        Matcher cur = require(Pattern.compile("`<revision>`\\s*=\\s*`(\\d+\\.\\d+\\.\\d+)`"),
                row.group(1), "「当前版本」行里的版本号");
        claim("主张2a 当前版本", cur.group(1), f.revision);

        // (b) 结构树的 pom.xml 行（同一个版本号的第二处出现）
        TreeNode tree = parseTree(firstCodeBlock(section(readme, "## 🏗️ 项目结构")));
        TreeNode pomNode = findNode(tree, "pom.xml");
        if (pomNode == null) {
            throw new AssertionError("README 的结构树里找不到 pom.xml 节点");
        }
        Matcher treeVer = require(Pattern.compile("<revision>(\\d+\\.\\d+\\.\\d+)"),
                pomNode.comment, "结构树 pom.xml 行里的版本号");
        claim("主张2b 结构树里的版本号", treeVer.group(1), f.revision);

        // (c) 快速开始节的 jar 文件名（版本号随 revision 走）
        String quick = section(readme, "## 🚀 快速开始");
        Matcher jar = Pattern.compile("z-indexer-[a-z-]+-(\\d+\\.\\d+\\.\\d+)\\.jar").matcher(quick);
        int jars = 0;
        while (jar.find()) {
            jars++;
            claim("主张2c 快速开始节的 jar 名 #" + jars, jar.group(1), f.revision);
        }
        if (jars == 0) {
            throw new AssertionError("快速开始节里一条 jar 文件名主张都抠不到");
        }

        // (d) 父项目版本：README 里每一处都要等于根 pom 的 <parent><version>
        Matcher parent = Pattern.compile("z-boot-parent:(\\d+\\.\\d+\\.\\d+)").matcher(readme);
        int parents = 0;
        while (parent.find()) {
            parents++;
            claim("主张2d 父项目版本 #" + parents, parent.group(1), f.parentVersion);
        }
        if (parents == 0) {
            throw new AssertionError("README 里一条 z-boot-parent:X.Y.Z 主张都抠不到");
        }
    }

    // ---------------------------------------------------------------- 主张 3：5 格刻意属性

    private static void verifyPinnedProperties(String readme, Facts f) {
        // README：「本仓 pom.xml 只保留 **N 格……的属性：a、b、c」
        // 注意加粗的范围是「N 格刻意与父链不同值/父链不供」整段，不是只有「N 格」。
        Matcher m = require(Pattern.compile("只保留\\s*\\*\\*(\\d+)\\s*格[^*]*\\*\\*\\s*的?属性：([^；]*)；", Pattern.DOTALL),
                readme, "「只保留 N 格……的属性」这一句");
        claim("主张3a 刻意属性格数", Integer.parseInt(m.group(1)), f.pomProperties.size() - 1);

        Set<String> readmeNames = backticked(m.group(2));
        Set<String> sourceNames = new TreeSet<>(f.pomProperties.keySet());
        sourceNames.remove("revision");
        claimSet("主张3b 刻意属性名单", readmeNames, sourceNames);

        // 技术栈表里这 5 格的版本号必须等于根 pom 的属性值
        String stack = section(readme, "## 🔧 技术栈");
        claimValue("主张3c slf4j.version", stack,
                Pattern.compile("\\| 日志 \\| `slf4j-api` \\*\\*([^*]+)\\*\\*"), f.pomProperties.get("slf4j.version"));
        claimValue("主张3d jgit.version", stack,
                Pattern.compile("\\| Git 客户端 \\| `org\\.eclipse\\.jgit:org\\.eclipse\\.jgit` \\*\\*([^*]+)\\*\\*"),
                f.pomProperties.get("jgit.version"));
        claimValue("主张3e javaparser.version", stack,
                Pattern.compile("\\| 静态解析 \\| `com\\.github\\.javaparser:javaparser-core` \\*\\*([^*]+)\\*\\*"),
                f.pomProperties.get("javaparser.version"));
        claimValue("主张3f testng.version", stack,
                Pattern.compile("\\| 测试 \\| TestNG \\*\\*([^*]+)\\*\\*"), f.pomProperties.get("testng.version"));
        claimValue("主张3g maven-deploy-plugin.version", stack,
                Pattern.compile("\\| 构建 \\|[^\\n]*`maven-deploy-plugin`\\s+([0-9][0-9.]*)"),
                f.pomProperties.get("maven-deploy-plugin.version"));
    }

    /** 从一段文本里按锚点抠出版本号，与源码重算值对账。 */
    private static void claimValue(String tag, String text, Pattern p, String sourceValue) {
        Matcher m = require(p, text, tag + " 对应的版本号");
        claim(tag, m.group(1).trim(), sourceValue);
    }

    // ---------------------------------------------------------------- 主张 4：Controller

    private static void verifyControllers(String readme, Facts f) {
        int count = f.controllerNames.size();

        // (a) 结构树 starter 行
        TreeNode tree = parseTree(firstCodeBlock(section(readme, "## 🏗️ 项目结构")));
        TreeNode starter = findNode(tree, STARTER);
        if (starter == null) {
            throw new AssertionError("README 的结构树里找不到 " + STARTER + " 节点");
        }
        Matcher n1 = require(Pattern.compile("(\\d+)\\s*个\\s*REST\\s*Controller"),
                starter.comment, "结构树里的 REST Controller 数");
        claim("主张4a 结构树里的 Controller 数", Integer.parseInt(n1.group(1)), count);

        // (b) API 节：数量 + 名单
        String api = section(readme, "## 🔌 API");
        Matcher n2 = require(Pattern.compile("\\*\\*(\\d+)\\s*个\\*\\*\\s*Controller"), api, "API 节的 Controller 数");
        claim("主张4b API 节的 Controller 数", Integer.parseInt(n2.group(1)), count);
        int open = api.indexOf('（', n2.end());
        int close = open < 0 ? -1 : api.indexOf('）', open);
        if (open < 0 || close < 0) {
            throw new AssertionError("API 节的 Controller 名单括号不完整");
        }
        claimSet("主张4c API 节的 Controller 名单",
                backticked(api.substring(open, close)), new TreeSet<>(f.controllerNames));

        // (c) 结构树 controller 目录行里的名单
        TreeNode controllerNode = findNode(tree, "controller/");
        if (controllerNode == null) {
            throw new AssertionError("README 的结构树里找不到 controller/ 节点");
        }
        Set<String> treeNames = new TreeSet<>();
        for (String part : controllerNode.comment.split("/")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                treeNames.add(t);
            }
        }
        claimSet("主张4d 结构树里的 Controller 名单", treeNames, new TreeSet<>(f.controllerNames));
    }

    // ---------------------------------------------------------------- 主张 5：REST 端点数

    private static void verifyEndpoints(String readme, Facts f) {
        String api = section(readme, "## 🔌 API");
        Matcher m = require(Pattern.compile("\\*\\*(\\d+)\\*\\*\\s*个方法级端点"), api, "方法级端点数");
        claim("主张5a 方法级端点数", Integer.parseInt(m.group(1)), f.methodLevelMappings);

        Matcher c = require(Pattern.compile("类级\\s*`@RequestMapping`\\s*(\\d+)\\s*个"), api, "类级 @RequestMapping 数");
        claim("主张5b 类级 @RequestMapping 数", Integer.parseInt(c.group(1)), f.classLevelMappings);

        Matcher r = require(Pattern.compile("复算：[^⇒]*⇒\\s*`(\\d+)`"), api, "复算行给出的端点数");
        claim("主张5c 复算行的端点数", Integer.parseInt(r.group(1)), f.methodLevelMappings);
    }

    // ---------------------------------------------------------------- 主张 6：配置表

    private static void verifyConfigTable(String readme, Facts f) {
        String cfg = section(readme, "## ⚙️ 配置");
        List<String[]> rows = new ArrayList<>();
        int rawRows = 0;
        for (String line : cfg.split("\n", -1)) {
            if (!line.startsWith("|") || !line.contains("z.indexer.")) {
                continue;
            }
            rawRows++;
            String[] cells = line.split("\\|", -1);
            rows.add(new String[]{cells[1].trim(), cells[2].trim(), cells[3].trim()});
        }
        // 表里应当是 15 行（其中 1 行是三键合一的 mvn/git/node 行，不参与逐键对账）
        claim("主张6a 配置表行数", rawRows, CONFIG_ROWS.length + 1);

        Map<String, String[]> byKey = new TreeMap<>();
        boolean mergedSeen = false;
        for (String[] row : rows) {
            Matcher key = Pattern.compile("^`z\\.indexer\\.([a-z0-9-]+)`$").matcher(row[0]);
            if (key.find()) {
                byKey.put(key.group(1), row);
            } else {
                if (row[0].contains(MERGED_CMD_ROW_KEY)) {
                    mergedSeen = true;
                } else {
                    throw new AssertionError("配置表里有一行读不出键名（既不是单键也不是已知的合并行）：" + row[0]);
                }
            }
        }
        if (!mergedSeen) {
            throw new AssertionError("配置表里那行三键合一（" + MERGED_CMD_ROW_KEY + " / git-cmd / node-cmd）不见了");
        }
        claimSet("主张6b 配置表逐键清单", new TreeSet<>(byKey.keySet()), new TreeSet<>(keysOf(CONFIG_ROWS)));

        for (String[] pair : CONFIG_ROWS) {
            String key = pair[0];
            String field = pair[1];
            String[] row = byKey.get(key);
            if (row == null) {
                throw new AssertionError("配置表里找不到 z.indexer." + key + " 这一行");
            }
            String sourceDefault = f.propertyDefaults.get(field);
            if (sourceDefault == null) {
                throw new AssertionError("IndexerProperties 里找不到字段 " + field);
            }
            // 代码默认列
            if ("reposRoot".equals(field)) {
                // 代码默认是表达式 System.getProperty("java.io.tmpdir", ...) + "/z-indexer"，
                // README 写成归一化的 ${java.io.tmpdir}/z-indexer —— 按结构特征比，不按字面量比。
                String cell = unbacktick(row[1]);
                if (!cell.contains("java.io.tmpdir") || !cell.endsWith("/z-indexer")
                        || !sourceDefault.contains("java.io.tmpdir") || !sourceDefault.contains("/z-indexer")) {
                    throw new AssertionError("[主张6c 代码默认 z.indexer." + key + "] 对不上：README 写的是 " + cell
                            + "，源码重算是 " + sourceDefault);
                }
            } else {
                claim("主张6c 代码默认 z.indexer." + key, unbacktick(row[1]), sourceDefault);
            }
            // 仓内值列
            String yamlValue = f.yamlValues.get(key);
            String cell = unbacktick(row[2]);
            if (yamlValue == null) {
                if (!cell.contains("未覆盖")) {
                    throw new AssertionError("[主张6d 仓内值 z.indexer." + key + "] 对不上：application.yaml 里没有这一格，"
                            + "README 应写「未覆盖」，实际写的是 " + cell);
                }
            } else {
                claim("主张6d 仓内值 z.indexer." + key, cell, yamlValue);
            }
        }
    }

    private static List<String> keysOf(String[][] pairs) {
        List<String> out = new ArrayList<>();
        for (String[] p : pairs) {
            out.add(p[0]);
        }
        return out;
    }

    // ---------------------------------------------------------------- 主张 7：内置工具链

    private static void verifyToolchain(String readme, Facts f) {
        String pack = section(readme, "## 📦 打包与交付物");

        // (a) 逐个文件的字节数
        Matcher m = Pattern.compile("`([A-Za-z0-9._-]+)`\\s*([\\d,]+)B").matcher(pack);
        int checked = 0;
        while (m.find()) {
            String name = m.group(1);
            long readmeSize = Long.parseLong(m.group(2).replace(",", ""));
            Long real = f.binSizes.get(name);
            if (real == null) {
                throw new AssertionError("[主张7a 内置工具链文件 " + name + "] 对不上：内置工具链目录里没有这个文件，"
                        + "README 却写了它的字节数");
            }
            claim("主张7a 内置工具链文件 " + name, readmeSize, real.longValue());
            checked++;
        }
        if (checked == 0) {
            throw new AssertionError("打包与交付物节里一条文件字节数主张都抠不到");
        }

        // (b) 总量与文件名清单：取「实测 59 MB」那一段
        long total = 0;
        for (Long v : f.binSizes.values()) {
            total += v.longValue();
        }
        long megabytes = Math.round(total / 1048576.0);
        Matcher size = require(Pattern.compile("bin/`?\\s*实测\\s*(\\d+)\\s*MB"), pack, "内置工具链总大小");
        claim("主张7b 交付物节的内置工具链大小", Long.parseLong(size.group(1)), megabytes);

        int at = size.end();
        int end = pack.indexOf("\n\n", at);
        String paragraph = end < 0 ? pack.substring(at) : pack.substring(at, end);
        claimSet("主张7c 内置工具链文件名单", backticked(paragraph), new TreeSet<>(f.binSizes.keySet()));

        TreeNode tree = parseTree(firstCodeBlock(section(readme, "## 🏗️ 项目结构")));
        TreeNode resources = findNode(tree, "resources/{application.yaml, bootstrap.yaml, bin/}");
        if (resources == null) {
            throw new AssertionError("README 的结构树里找不到 resources/{application.yaml, bootstrap.yaml, bin/} 节点");
        }
        Matcher size2 = require(Pattern.compile("bin/\\s*实测\\s*(\\d+)\\s*MB"), resources.comment,
                "结构树里的内置工具链大小");
        claim("主张7d 结构树里的内置工具链大小", Long.parseLong(size2.group(1)), megabytes);
    }

    // ---------------------------------------------------------------- 主张 8：git 地址脱敏出口

    private static void verifyMaskOutlets(String readme, Facts f) {
        String heading = headingLine(readme, "### 🔐 git 地址脱敏");
        Matcher h = require(Pattern.compile("（(\\d+)\\s*个出口）"), heading, "小节标题里的出口数");
        claim("主张8a 小节标题里的出口数", Integer.parseInt(h.group(1)), f.maskOutlets);

        String sec = section(readme, "### 🔐 git 地址脱敏");
        Matcher b = require(Pattern.compile("分散在\\s*(\\d+)\\s*个互不相干的出口"), sec, "正文里的出口数");
        claim("主张8b 正文里的出口数", Integer.parseInt(b.group(1)), f.maskOutlets);

        // 脱敏表逐行编号：1..N，N = 源码侧出口数
        List<Integer> rowIds = new ArrayList<>();
        for (String line : sec.split("\n", -1)) {
            Matcher id = Pattern.compile("^\\|\\s*(\\d+)\\s*\\|").matcher(line);
            if (id.find()) {
                rowIds.add(Integer.parseInt(id.group(1)));
            }
        }
        claim("主张8c 脱敏表的行数", rowIds.size(), f.maskOutlets);
        for (int i = 0; i < rowIds.size(); i++) {
            claim("主张8d 脱敏表第 " + (i + 1) + " 行的编号", rowIds.get(i), i + 1);
        }

        // 管理 API 响应体这个出口覆盖了几个端点
        Matcher t = require(Pattern.compile("toMap`（(\\d+)\\s*个端点的公共出口）"), sec, "toMap 出口覆盖的端点数");
        claim("主张8e toMap 出口覆盖的端点数", Integer.parseInt(t.group(1)), f.toMapCallSites);
    }

    // ---------------------------------------------------------------- 主张 9：测试类名（只点名不计数）

    private static void verifyTestClassNames(String readme, Facts f) {
        String sec = section(readme, "## 🧪 测试");
        Set<String> named = new TreeSet<>();
        Matcher m = Pattern.compile("`([A-Z][A-Za-z0-9]*(?:Test|Main))(#[A-Za-z0-9_]+)?`").matcher(sec);
        while (m.find()) {
            named.add(m.group(1));
        }
        if (named.isEmpty()) {
            throw new AssertionError("测试章节里一条测试类名都抠不到");
        }
        Set<String> missing = new TreeSet<>(named);
        missing.removeAll(f.testClassNames);
        if (!missing.isEmpty()) {
            throw new AssertionError("README 点名的这些测试类在 src/test 下不存在：" + missing);
        }
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    public void readmeClaimsMatchTheCode() {
        Path root = repoRoot();
        Facts f = recompute();
        verifyClaims(read(root.resolve("README.md")), f);
    }

    @Test
    public void healthyReadmeIsNotCondemned() {
        Path root = repoRoot();
        Facts f = recompute();
        verifyClaims(read(root.resolve("README.md")), f);
    }

    /** 尺子的牙口：把 README 的数改错，判定必须判红，且报错要指向真正对不上的那条主张。 */
    @Test
    public void rulerActuallyCondemnsWrongNumbers() {
        String readme = read(repoRoot().resolve("README.md"));
        Facts f = recompute();

        // 变异 1：模块数改错
        String tamperedCount = tamper(readme, "| **模块数** | 3：", "| **模块数** | 4：");
        AssertionError e1 = Assert.expectThrows(AssertionError.class, () -> verifyClaims(tamperedCount, f));
        Assert.assertTrue(e1.getMessage().contains("模块数"),
                "模块数改错必须报出模块数，实际是：" + e1.getMessage());

        // 变异 2：版本改错
        String tamperedVersion = tamper(readme, "`<revision>` = `1.0.0`", "`<revision>` = `9.9.9`");
        AssertionError e2 = Assert.expectThrows(AssertionError.class, () -> verifyClaims(tamperedVersion, f));
        Assert.assertTrue(e2.getMessage().contains("当前版本"),
                "版本改错必须报出版本主张，实际是：" + e2.getMessage());

        // 变异 3：Controller 名单少一个
        String tamperedNames = tamper(readme, "`IndexArtifact` / `RedisProbe`", "`RedisProbe`");
        AssertionError e3 = Assert.expectThrows(AssertionError.class, () -> verifyClaims(tamperedNames, f));
        Assert.assertTrue(e3.getMessage().contains("IndexArtifact"),
                "名单少一个必须报出缺的那个类，实际是：" + e3.getMessage());

        // 变异 4：配置表的一个值改错
        String tamperedConfig = tamper(readme,
                "| `z.indexer.max-result-nodes` | `200` | `200` |",
                "| `z.indexer.max-result-nodes` | `999` | `200` |");
        AssertionError e4 = Assert.expectThrows(AssertionError.class, () -> verifyClaims(tamperedConfig, f));
        Assert.assertTrue(e4.getMessage().contains("max-result-nodes"),
                "配置值改错必须报出那一格，实际是：" + e4.getMessage());

        // 变异 5：内置工具链的字节数改错
        String tamperedSize = tamper(readme, "`tools.jar` 18,362,894B", "`tools.jar` 18,362,895B");
        AssertionError e5 = Assert.expectThrows(AssertionError.class, () -> verifyClaims(tamperedSize, f));
        Assert.assertTrue(e5.getMessage().contains("tools.jar"),
                "字节数改错必须报出那个文件，实际是：" + e5.getMessage());
    }

    /** 把 README 里的某段原文换成另一段（命中数必须正好是 1，否则说明口径写错了）。 */
    private static String tamper(String readme, String from, String to) {
        int hits = count(readme, from);
        if (hits != 1) {
            throw new IllegalStateException("篡改失败：原文 " + from + " 的命中数是 " + hits + "（应为 1）");
        }
        return readme.replace(from, to);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }
}
