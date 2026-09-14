# _frontend

前端工程索引器 (frontend-indexer.cjs) 的暂存位置。

## 状态

**未迁移** — 原文件位于 `idea_workplace/feature-team/backend/future-team/future-team-indexer/frontend-indexer/frontend-indexer.cjs`，
与 z-indexer（Java 后端）仓分离存放以避免语言混淆。

## 设计

frontend-indexer.cjs 是一个 Node.js 脚本，扫描前端工程（TS/JS 源码），
输出符号/调用 jsonl，与 Java code-indexer 同 schema。

## 计划

未来如有需要，将作为独立仓 `z-opc-foundation/z-frontend-indexer`（或类似命名）单独发布，
不在 z-indexer Java 仓中。
