# _frontend

前端工程索引器（`frontend-indexer.cjs`）的源码暂存位。

## 状态

脚本已随内置工具链进入本仓，运行时用的是打包在 jar 里的那份：

- `z-indexer-server/src/main/resources/bin/frontend-indexer.cjs` —— 交付副本，启动时由
  `IndexBuildService.ensureFrontendToolchain()` 幂等释放到 `<repos-root>/bin/`
- `z-indexer-core/src/test/resources/bin/frontend-indexer.cjs` —— 前端 E2E 测试副本

本目录只是编辑用暂存位，**不被构建读取**；改动须同步上面两份，否则运行时仍跑旧版。

## 设计

`frontend-indexer.cjs` 是一个 Node.js 脚本，扫描前端工程（TS/JS 源码），
输出符号/调用 jsonl，与 Java 侧索引产物同 schema，因此查询端无需区分语言。
它唯一的依赖是 `@babel/parser`，同样内置为 zip 并在释放时展开。

## 计划

未来如有需要，可独立成仓 `z-opc-foundation/z-frontend-indexer`（或类似命名）单独发布，
不再混在 z-indexer 这个 Java 仓里。
