#!/usr/bin/env node
/**
 * 前端工程索引器：TS/JS 源码 → 符号/调用 jsonl（与 Java code-indexer 同 schema）。
 *
 * 用法：node frontend-indexer.cjs --root <repoDir> --out <out.jsonl>
 * 依赖：@babel/parser（同目录 node_modules 或 NODE_PATH 提供）。
 *
 * sym 规则（与 Java FQN 语义对齐，RepoIndex.simpleName/ownerType 直接兼容）：
 *   moduleId = 仓库相对路径去扩展名、/ → .（如 src/services/api）
 *   class/组件/interface/type: <moduleId>.<Name>
 *   class 方法: <moduleId>.<Class>#<method>()
 *   顶层函数/箭头函数: <moduleId>#<func>()
 * call 规则：
 *   from = 调用发生的封闭作用域 sym（模块顶层用 moduleId 本体）
 *   to   = 被调方 def sym；解析顺序：本文件 def → import 绑定（含 tsconfig paths alias）
 *          → this.method（class 内）→ 同名唯一启发式（全仓仅一个同名 def）
 *   JSX 标签 <Foo/> 视为对组件 def 的引用 call（组件引用关系高价值）
 */
"use strict";

const fs = require("fs");
const path = require("path");
const parser = require("@babel/parser");

// ─── CLI ───
let root = null;
let outFile = null;
for (let i = 2; i < process.argv.length; i++) {
  const a = process.argv[i];
  if (a === "--root") root = process.argv[++i];
  else if (a === "--out") outFile = process.argv[++i];
}
if (!root || !outFile) {
  process.stderr.write("usage: node frontend-indexer.cjs --root <repoDir> --out <out.jsonl>\n");
  process.exit(2);
}
root = path.resolve(root);
outFile = path.resolve(outFile);

const SRC_EXT = [".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".vue"];
const SKIP_DIRS = new Set([
  "node_modules", "dist", "build", "coverage", ".umi", ".umi-prod", ".umi-test",
  "es", "lib", "docs", "mock", "public", "assets", "locales", "lang", ".git",
  ".dumi", "test", "tests", "__tests__", "e2e", "cypress", ".storybook",
]);

// ─── tsconfig paths alias（@/xxx → src/xxx）───
function loadAliases() {
  const aliases = [];
  const candidates = ["tsconfig.json", "src/tsconfig.json"];
  for (const c of candidates) {
    const f = path.join(root, c);
    if (!fs.existsSync(f)) continue;
    try {
      // tsconfig 可能有注释/尾逗号：粗剥 // 和 /* */ 后 JSON.parse
      const raw = fs.readFileSync(f, "utf8");
      const json = raw
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/(^|\s)\/\/.*$/gm, "$1");
      const conf = JSON.parse(json);
      const paths = (conf.compilerOptions || {}).paths || {};
      for (const [alias, targets] of Object.entries(paths)) {
        if (!Array.isArray(targets) || !targets.length) continue;
        const prefix = alias.endsWith("/*") ? alias.slice(0, -1) : alias; // "@/"
        const target = targets[0].endsWith("/*") ? targets[0].slice(0, -1) : targets[0]; // "src/"
        aliases.push([prefix, target]);
      }
      break;
    } catch (e) {
      // tsconfig 解析失败不阻塞（alias 降级为不解析）
    }
  }
  return aliases;
}
const ALIASES = loadAliases();

function moduleIdOf(absFile) {
  const rel = path.relative(root, absFile).replace(/\\/g, "/");
  return rel.replace(/\.(ts|tsx|js|jsx|mjs|cjs|vue)$/, "").replace(/\//g, ".");
}

function isSource(absFile) {
  const rel = path.relative(root, absFile).replace(/\\/g, "/");
  if (rel.split("/").some((seg) => SKIP_DIRS.has(seg) || seg.startsWith(".umi"))) return false;
  return SRC_EXT.includes(path.extname(absFile));
}

// ─── 文件收集 ───
function collectFiles(dir, acc) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (e) {
    return;
  }
  for (const ent of entries) {
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) collectFiles(p, acc);
    else if (ent.isFile() && isSource(p)) acc.push(p);
  }
  return acc;
}

// import source（'./foo'、'@/services/api'）→ 绝对路径候选（补扩展名/索引）
function resolveImportSource(spec) {
  let base;
  if (spec.startsWith(".")) {
    base = null; // 调用处按当前文件目录解析（需要 file 上下文，见 resolveImport）
    return { relative: true, spec };
  }
  for (const [prefix, target] of ALIASES) {
    if (spec === prefix || spec.startsWith(prefix)) {
      const rest = spec.slice(prefix.length);
      base = path.join(root, target, rest);
      break;
    }
  }
  if (base == null) return null; // npm 包，非本仓
  return { relative: false, base };
}

function tryResolveFile(base) {
  for (const ext of SRC_EXT) {
    const f = base + ext;
    if (fs.existsSync(f) && fs.statSync(f).isFile()) return f;
  }
  for (const ext of SRC_EXT) {
    const idx = path.join(base, "index" + ext);
    if (fs.existsSync(idx)) return idx;
  }
  return null;
}

// ─── Pass 1：逐文件解析，收集 defs / imports / raw calls ───
const defLines = [];            // 最终 jsonl def 行
const fileDefs = new Map();     // absFile -> Map(simpleName -> [sym])
const globalByName = new Map(); // simpleName -> [sym]（唯一性启发式用）
const rawCalls = [];            // {from, calleeKind, name, obj, member, jsx, file, line, importInfo}

function fname(n) {
  return n && n.name ? n.name : null;
}

/** 解析一个文件，填充 defs 与 raw calls。 */
function indexFile(absFile) {
  let code;
  try {
    code = fs.readFileSync(absFile, "utf8");
  } catch (e) {
    return;
  }
  // .vue：取 <script> 段（粗提取，够用）
  if (absFile.endsWith(".vue")) {
    const m = code.match(/<script[^>]*>([\s\S]*?)<\/script>/);
    if (!m) return;
    code = m[1];
  }
  let ast;
  try {
    ast = parser.parse(code, {
      sourceType: "unambiguous",
      errorRecovery: true,
      plugins: ["typescript", "jsx", "decorators-legacy", "classProperties", "optionalChaining", "nullishCoalescingOperator"],
    });
  } catch (e) {
    return; // 单文件语法失败不影响其它文件
  }

  const moduleId = moduleIdOf(absFile);
  const defs = new Map();
  fileDefs.set(absFile, defs);

  function addDef(kind, sym, line) {
    defLines.push(JSON.stringify({ t: "def", kind, sym, file: path.relative(root, absFile).replace(/\\/g, "/"), line }));
    const name = sym.includes("#") ? sym.slice(sym.indexOf("#") + 1).replace(/\(.*$/, "") : sym.split(".").pop();
    if (!defs.has(name)) defs.set(name, []);
    defs.get(name).push(sym);
    if (!globalByName.has(name)) globalByName.set(name, []);
    globalByName.get(name).push(sym);
  }

  // 作用域栈：元素 {module, classSym}
  const scopes = [{ module: moduleId, classSym: null }];

  function currentFrom() {
    const top = scopes[scopes.length - 1];
    if (top.fnSym) return top.fnSym;
    if (top.classSym) return top.classSym; // 类字段初始化（少见）
    return top.module;
  }

  // import 绑定：localName -> {spec, importedName}
  const imports = new Map();

  function recordCall(node, callee) {
    const line = node.loc ? node.loc.start.line : 0;
    if (!callee) return;
    if (callee.type === "Identifier") {
      rawCalls.push({ kind: "ident", name: callee.name, from: currentFrom(), file: absFile, line });
    } else if (callee.type === "MemberExpression" && !callee.computed && callee.property.type === "Identifier") {
      const obj = callee.object;
      rawCalls.push({
        kind: "member",
        member: callee.property.name,
        thisCtx: obj.type === "ThisExpression",
        from: currentFrom(),
        file: absFile,
        line,
      });
    }
  }

  function recordJsx(node, namePath) {
    // namePath: JSXIdentifier 名（如 Foo）或 MemberExpression 尾段
    const line = node.loc ? node.loc.start.line : 0;
    const name = namePath;
    if (name && /^[A-Z]/.test(name)) {
      rawCalls.push({ kind: "jsx", name, from: currentFrom(), file: absFile, line });
    }
  }

  function fnNameOf(node, parent) {
    // 函数声明/表达式命名的各种形态
    if (node.type === "FunctionDeclaration" || node.type === "ClassDeclaration" || node.type === "ClassExpression") {
      return fname(node.id);
    }
    if (node.type === "FunctionExpression" || node.type === "ArrowFunctionExpression") {
      if (parent && parent.type === "VariableDeclarator" && parent.id.type === "Identifier") {
        return parent.id.name;
      }
      if (parent && parent.type === "ObjectProperty" && parent.key.type === "Identifier") {
        return parent.key.name;
      }
      if (parent && parent.type === "ClassProperty" && parent.key.type === "Identifier") {
        return parent.key.name;
      }
    }
    return null;
  }

  function visit(node, parent, parentKey) {
    if (!node || typeof node.type !== "string") return;

    let pushedScope = false;

    switch (node.type) {
      case "ImportDeclaration": {
        const src = node.source && node.source.value;
        if (typeof src === "string") {
          for (const spec of node.specifiers || []) {
            let imported = null;
            if (spec.type === "ImportDefaultSpecifier") imported = "default";
            else if (spec.type === "ImportSpecifier") {
              imported = spec.imported && spec.imported.name ? spec.imported.name : fname(spec.imported);
            } else if (spec.type === "ImportNamespaceSpecifier") imported = "*";
            if (imported) imports.set(fname(spec.local), { spec: src, imported });
          }
        }
        break;
      }
      case "ExportNamedDeclaration":
      case "ExportDefaultDeclaration":
        // 递归进 declaration（def 记录在子节点处理）
        break;
      case "TSInterfaceDeclaration":
        if (node.id) addDef("interface", moduleId + "." + node.id.name, node.loc.start.line);
        break;
      case "TSTypeAliasDeclaration":
        if (node.id) addDef("type", moduleId + "." + node.id.name, node.loc.start.line);
        break;
      case "ClassDeclaration":
      case "ClassExpression": {
        const name = fname(node.id) || (parent && parent.type === "VariableDeclarator" && parent.id.type === "Identifier" ? parent.id.name : null);
        if (name) {
          const sym = moduleId + "." + name;
          addDef("class", sym, node.loc.start.line);
          scopes.push({ module: moduleId, classSym: sym, fnSym: null });
          pushedScope = true;
        }
        break;
      }
      case "FunctionDeclaration":
      case "FunctionExpression":
      case "ArrowFunctionExpression": {
        const name = fnNameOf(node, parent);
        let fnSym = null;
        if (name) {
          const top = scopes[scopes.length - 1];
          if (top.classSym) {
            fnSym = top.classSym + "#" + name + "()";
            addDef("method", fnSym, node.loc.start.line);
          } else {
            fnSym = top.module + "#" + name + "()";
            addDef("function", fnSym, node.loc.start.line);
          }
        }
        scopes.push({ module: moduleId, classSym: scopes[scopes.length - 1].classSym, fnSym: fnSym || scopes[scopes.length - 1].fnSym });
        pushedScope = true;
        break;
      }
      case "VariableDeclarator": {
        // 导出的常量/对象：类型级别可搜索（非函数）
        if (parent && parent.declarations && parent.declarations[0] === node) {
          const pparent = parent._parent;
          if (node.id.type === "Identifier" && pparent && pparent.type === "ExportNamedDeclaration") {
            addDef("variable", moduleId + "." + node.id.name, node.loc.start.line);
          }
        }
        break;
      }
      case "CallExpression":
        recordCall(node, node.callee);
        break;
      case "NewExpression":
        recordCall(node, node.callee);
        break;
      case "JSXElement":
        if (node.openingElement && node.openingElement.name) {
          const n = node.openingElement.name;
          if (n.type === "JSXIdentifier") recordJsx(node, n.name);
          else if (n.type === "JSXMemberExpression" && n.property) recordJsx(node, n.property.name);
        }
        break;
      default:
        break;
    }

    for (const key of Object.keys(node)) {
      if (key === "loc" || key === "leadingComments" || key === "trailingComments" || key === "innerComments" || key === "_parent") continue;
      const child = node[key];
      if (Array.isArray(child)) {
        for (const c of child) {
          if (c && typeof c.type === "string") {
            c._parent = node;
            visit(c, node, key);
          }
        }
      } else if (child && typeof child.type === "string") {
        child._parent = node;
        visit(child, node, key);
      }
    }

    if (pushedScope) scopes.pop();
  }

  // 挂 parent 引用后遍历
  ast._parent = null;
  visit(ast.program, null, "program");

  // imports 转存进 rawCalls 需要的文件级信息：存到该文件的 def/call 解析阶段用
  fileImports.set(absFile, imports);
}

const fileImports = new Map(); // absFile -> Map(localName -> {spec, imported})

// ─── Pass 2：raw call → to sym 解析 ───
function resolveTo(raw) {
  const defs = fileDefs.get(raw.file);
  const imports = fileImports.get(raw.file) || new Map();

  // 1) 本文件 def（含 class 方法/函数同名）
  if (raw.kind === "ident" || raw.kind === "jsx") {
    const local = defs && defs.get(raw.name);
    if (local && local.length) return local[0];

    // 2) import 绑定
    const imp = imports.get(raw.name);
    if (imp) {
      const target = resolveImportModule(raw.file, imp.spec);
      if (target) {
        const tdefs = fileDefs.get(target);
        if (tdefs) {
          if (imp.imported === "default") {
            // default 导入：找目标文件里与本地名同名的 def（约定俗成），否则唯一 def
            const same = tdefs.get(raw.name);
            if (same && same.length) return same[0];
          } else if (imp.imported === "*") {
            return null; // namespace：无法定位单符号
          } else {
            const same = tdefs.get(imp.imported);
            if (same && same.length) return same[0];
          }
        }
      }
      return null; // import 了但目标解析不到，别落到同名启发式（避免跨文件误连）
    }

    // 3) 同名唯一启发式（未 import 的全局/类型环境）
    const globals = globalByName.get(raw.name);
    if (globals && globals.length === 1 && raw.kind === "ident") return globals[0];
    return null;
  }

  if (raw.kind === "member") {
    // this.method()：类内调用
    if (raw.thisCtx) {
      const owner = raw.from.includes("#") ? raw.from.slice(0, raw.from.indexOf("#")) : null;
      if (owner) {
        const tdefs = fileDefs.get(raw.file);
        if (tdefs) {
          const same = tdefs.get(raw.member);
          for (const s of same || []) {
            if (s.startsWith(owner + "#")) return s;
          }
        }
      }
      return null;
    }
    // obj.method()：同名唯一（跨类）
    const globals = globalByName.get(raw.member);
    if (globals && globals.length === 1) return globals[0];
    return null;
  }
  return null;
}

function resolveImportModule(fromFile, spec) {
  if (!spec.startsWith(".")) {
    const aliased = resolveImportSource(spec);
    if (!aliased || aliased.relative) return null;
    return tryResolveFile(aliased.base);
  }
  return tryResolveFile(path.resolve(path.dirname(fromFile), spec));
}

// ─── 主流程 ───
const t0 = Date.now();
const files = collectFiles(root, []);
for (const f of files) indexFile(f);

const out = [];
for (const raw of rawCalls) {
  const to = resolveTo(raw);
  if (!to || to === raw.from) continue;
  out.push(JSON.stringify({
    t: "call",
    from: raw.from,
    to,
    file: path.relative(root, raw.file).replace(/\\/g, "/"),
    line: raw.line,
  }));
}

const all = defLines.concat(out);
fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, all.join("\n") + (all.length ? "\n" : ""), "utf8");
process.stderr.write(
  "[frontend-indexer] files=" + files.length +
  " defs=" + defLines.length + " calls=" + out.length +
  " cost=" + (Date.now() - t0) + "ms\n"
);
