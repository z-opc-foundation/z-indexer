package com.zifang.z.indexer.pipeline;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * pom 注入器：把 code-indexer（javac annotation processor）挂进仓库根 pom 的
 * maven-compiler-plugin（annotationProcessorPaths + compilerArgs -AindexOut/-AindexRoot）。
 * <p>仅在索引服务管理的 clone 工作副本上生效：
 * 构建前 patch，构建完成后下次 syncTo() 的 reset --hard 会自动还原，
 * 不污染远端仓库，也保证 git pull 永不冲突。
 * <p>幂等：已注入（存在同 GAV path）则只刷新 -A 参数。
 * <p>已知边界：若某子模块自带独立 maven-compiler-plugin 配置覆盖了根配置，
 * 该子模块不会被索引（ Maven 继承覆盖行为，无法从外部规避）。
 */
public class PomIndexerPatcher {

    public static final String INDEXER_GROUP_ID = "com.zifang";
    public static final String INDEXER_ARTIFACT_ID = "code-indexer";
    public static final String INDEXER_VERSION = "1.0";

    private static final String COMPILER_PLUGIN_ARTIFACT = "maven-compiler-plugin";

    private final String indexOutAbsPath;
    private final String indexRootAbsPath;

    public PomIndexerPatcher(File repoDir, File indexOutFile) {
        this.indexOutAbsPath = indexOutFile.getAbsolutePath();
        this.indexRootAbsPath = repoDir.getAbsolutePath();
    }

    /** 对仓库根 pom 执行注入（返回简述，异常向上抛由调用方记失败）。 */
    public String patch(File pomFile) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document doc = dbf.newDocumentBuilder().parse(pomFile);
        doc.setXmlStandalone(true);

        Element project = doc.getDocumentElement();
        Element build = child(project, "build");
        if (build == null) {
            build = doc.createElement("build");
            project.appendChild(build);
        }
        Element plugins = child(build, "plugins");
        if (plugins == null) {
            plugins = doc.createElement("plugins");
            build.appendChild(plugins);
        }

        Element compilerPlugin = null;
        NodeList pluginNodes = plugins.getElementsByTagName("plugin");
        for (int i = 0; i < pluginNodes.getLength(); i++) {
            Element p = (Element) pluginNodes.item(i);
            if (COMPILER_PLUGIN_ARTIFACT.equals(textOf(child(p, "artifactId")))) {
                compilerPlugin = p;
                break;
            }
        }
        if (compilerPlugin == null) {
            compilerPlugin = doc.createElement("plugin");
            appendChild(doc, compilerPlugin, "groupId", "org.apache.maven.plugins");
            appendChild(doc, compilerPlugin, "artifactId", COMPILER_PLUGIN_ARTIFACT);
            appendChild(doc, compilerPlugin, "version", "3.8.1");
            plugins.appendChild(compilerPlugin);
        }

        Element configuration = child(compilerPlugin, "configuration");
        if (configuration == null) {
            configuration = doc.createElement("configuration");
            // configuration 必须位于 groupId/artifactId/version 之后
            compilerPlugin.appendChild(configuration);
        }

        // 1) annotationProcessorPaths：追加 code-indexer（幂等）。
        //    新建节点必须带 combine.children="append"：与 parent 链（pluginManagement/
        //    execution）合并时追加而非覆盖，否则 parent 声明的 lombok/mapstruct 等
        //    processor 会整体丢失，@Data 类直接 cannot find symbol（demo-charge 实证）。
        //    已存在的 paths 节点本身就是 append 语义（同列表追加 path），无需补属性。
        Element paths = child(configuration, "annotationProcessorPaths");
        if (paths == null) {
            paths = doc.createElement("annotationProcessorPaths");
            paths.setAttribute("combine.children", "append");
            configuration.appendChild(paths);
        }
        boolean already = false;
        NodeList pathNodes = paths.getElementsByTagName("path");
        for (int i = 0; i < pathNodes.getLength(); i++) {
            Element pathEl = (Element) pathNodes.item(i);
            if (INDEXER_ARTIFACT_ID.equals(textOf(child(pathEl, "artifactId")))
                    && INDEXER_GROUP_ID.equals(textOf(child(pathEl, "groupId")))) {
                already = true;
                break;
            }
        }
        if (!already) {
            Element pathEl = doc.createElement("path");
            appendChild(doc, pathEl, "groupId", INDEXER_GROUP_ID);
            appendChild(doc, pathEl, "artifactId", INDEXER_ARTIFACT_ID);
            appendChild(doc, pathEl, "version", INDEXER_VERSION);
            paths.appendChild(pathEl);
        }

        // 2) compilerArgs：刷新（去重后追加）-AindexOut / -AindexRoot
        Element compilerArgs = child(configuration, "compilerArgs");
        if (compilerArgs == null) {
            compilerArgs = doc.createElement("compilerArgs");
            configuration.appendChild(compilerArgs);
        }
        removeArg(compilerArgs, "-AindexOut");
        removeArg(compilerArgs, "-AindexRoot");
        appendArg(doc, compilerArgs, "-AindexOut=" + indexOutAbsPath);
        appendArg(doc, compilerArgs, "-AindexRoot=" + indexRootAbsPath);

        // 3) 写回（保持声明头）
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        t.setOutputProperty(OutputKeys.STANDALONE, "no");
        File tmp = new File(pomFile.getParentFile(), "pom.xml.indexer-tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            t.transform(new DOMSource(doc), new StreamResult(fos));
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(pomFile)) {
            pomFile.delete();
            tmp.renameTo(pomFile);
        }
        return "injected processor=" + INDEXER_GROUP_ID + ":" + INDEXER_ARTIFACT_ID + ":" + INDEXER_VERSION
                + (already ? " (existed)" : " (added)");
    }

    private static void removeArg(Element compilerArgs, String prefix) {
        List<Node> toRemove = new ArrayList<Node>();
        NodeList argNodes = compilerArgs.getElementsByTagName("arg");
        for (int i = 0; i < argNodes.getLength(); i++) {
            Element argEl = (Element) argNodes.item(i);
            if (textOf(argEl).startsWith(prefix)) {
                toRemove.add(argEl);
            }
        }
        for (Node n : toRemove) {
            compilerArgs.removeChild(n);
        }
    }

    private static void appendArg(Document doc, Element compilerArgs, String value) {
        Element argEl = doc.createElement("arg");
        argEl.setTextContent(value);
        compilerArgs.appendChild(argEl);
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String textOf(Element el) {
        return el == null || el.getTextContent() == null ? "" : el.getTextContent().trim();
    }

    private static void appendChild(Document doc, Element parent, String name, String text) {
        Element el = doc.createElement(name);
        el.setTextContent(text);
        parent.appendChild(el);
    }
}
