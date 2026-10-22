# z-indexer-component

z-indexer（索引服务）跨仓唯一前端面：把「概览 / 仓库注册表 / 符号检索」这一整套代码索引服务页面
收敛成一个可复用的 React 组件包。

- **npm 包名**：`@yuku123/z-indexer-component`
- **宿主接入**：`@yuku123/z-indexer-component/pages`（manifest + 页面 + API），主壳可只取 `ComponentDocPage`
- **后端**：索引服务的管理面 `/index/admin/**` 与查询面 `/index/query/**`
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + vite（library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

路由前缀 `/z-indexer`，菜单与路由在 `src/pages-manifest.jsx` 里声明。

| 页面 | 路由（相对 `/z-indexer/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 登录 | `login` | `pages/LoginPage.jsx` | 登录页（suit 侧单独挂这条路由） |
| 首页 | `home` | `pages/HomePage.jsx` | 入口卡片页 |
| 概览 | `overview` | `pages/Overview.jsx` | 注册仓库 / 已启用 / 已建索引 / 总索引行数四张统计卡 + 构建状态分布 |
| 仓库注册表 | `repos` | `pages/Repos.jsx` | 仓库注册 / 更新 / 启停 / 删除 / 重建 / 查看构建日志 / 触发全量轮询 |
| 符号检索 | `query` | `pages/Query.jsx` | 符号搜索 + 谁调用了它 / 它调用了谁（调用链） |
| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

本包的 `routes` **没有**兜底通配（`*`）项，未列出的路径由宿主的 404 兜底。

## 二、导出面

```js
// 主入口（. 桶）
import {listRepos, searchSymbols, getCallers, getCallees, configureIndexer, ComponentDocPage}
    from '@yuku123/z-indexer-component'

// 宿主组装面（./pages 桶）
import {menuItems, routes, HomePage, LoginPage, Overview, Repos, Query} from '@yuku123/z-indexer-component/pages'
```

`./pages` 桶是**显式具名转发**（`export {HomePage, LoginPage, Overview, Query, Repos, appMeta, menuItems, routes} from './pages-manifest.jsx'`）。
新增页面时必须回来补一行，否则消费方构建会报 `"Xxx" is not exported by dist/pages.js`。

## 三、API 端点

请求实例由基座 `createRequest` 工厂创建（`src/services/api.js`），`baseURL` 默认 `'/index'`
（路径自带 `/index` 前缀），token 键 `zindexer_token`，并额外注入 `X-Indexer-Token` 头
（后端 `IndexerProperties.token` 校验，开启后**所有**端点必带）。

### 3.1 管理面 `/index/admin`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/repos` | 仓库列表（含构建状态），返回体取 `repos` 字段 |
| POST | `/admin/repos` | 注册 / 更新仓库 |
| PUT | `/admin/repos/{repoCode}/enabled?enabled=` | 启用 / 停用 |
| DELETE | `/admin/repos/{repoCode}` | 删除仓库 |
| POST | `/admin/repos/{repoCode}/rebuild` | 重建索引 |
| GET | `/admin/repos/{repoCode}/build-log?lines=` | 构建日志（默认取最后 200 行） |
| POST | `/admin/poll` | 触发全量轮询 |

### 3.2 查询面 `/index/query`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/query/repos` | 可查询仓库列表（返回体取 `repos` 字段） |
| POST | `/query/symbol` | 符号搜索（body `repoCode` / `query` / `limit` / `includeAccessors`） |
| POST | `/query/callers` | 谁调用了它（body `repoCode` / `symbol` / `depth` / `includeAccessors`） |
| POST | `/query/callees` | 它调用了谁（同上） |

## 四、页面说明

### 4.1 概览

四张统计卡（注册仓库 / 已启用 / 已建索引 / 总索引行数）+ 「构建状态分布」卡片。
没有构建记录时用基座 `EmptyState` 提示「还没有构建记录，注册仓库后触发一次构建即可看到分布」。

### 4.2 仓库注册表

仓库的注册 / 更新 / 启停 / 删除 / 重建 + 查看构建日志 + 触发全量轮询。行内操作：

- **日志**：`getBuildLog(repoCode, lines)` 展示构建日志；
- **重建**：`Popconfirm` 二次确认后 `rebuildRepo`；
- **删除**：`Popconfirm` 二次确认（危险色按钮），提示「会同时删除本地索引产物」。

分页 / 过滤 / 空态三件套由基座 `ListTable` 统一承接，状态标签用基座 `StatusBadge`，
工具栏用 `TableToolbar`。

### 4.3 符号检索

- 选仓库 → 输入符号名 → 搜索（`searchSymbols`，默认 `limit=20`）；
- 命中后可以展开**调用链**：谁调用了它（`getCallers`）与它调用了谁（`getCallees`），
  默认深度 `depth=1`；
- 两种特殊状态各有明确提示：
  - 首次查询该仓、索引正在加载 ⇒ 警告「冷加载中 … 请 30s 后重试同一查询」；
  - 该仓尚未构建索引 ⇒ 错误「未建索引 … 请先在「仓库注册表」页执行重建」；
- 无结果用基座 `EmptyState`（标题「暂无结果」，描述「输入符号名开始搜索」）；
- 查询失败用基座 `ErrorState` + 重试。

### 4.4 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `configureIndexer({apiBase})` | `'/index'` | 改写请求实例的 `baseURL`，宿主换挂载前缀时用 |
| `X-Indexer-Token` 头 | localStorage `zindexer_admin_token` | 后端开了 token 校验时**所有**端点必带 |
| token 存储键 | `zindexer_token` | 基座 token 键，独立命名避免与其他套件串号 |
| 构建日志默认行数 | 200 | `getBuildLog(repoCode, lines = 200)` |
| 符号搜索默认条数 | 20 | `searchSymbols(..., limit = 20)` |
| 调用链默认深度 | 1 | `getCallers` / `getCallees` 的 `depth` |

## 六、已知限制

- **后端开了 token 校验时，缺 `X-Indexer-Token` 会全站 401**：这个 token 存在
  localStorage 的 `zindexer_admin_token`，与登录用的 `zindexer_token` 是两回事。
- 冷加载（首次查询某仓）需要**等 30s 再重试同一查询**，页面不会自动重试。
- 符号搜索是**单仓**的（每次都要选 `repoCode`），没有跨仓聚合搜索。
- 调用链深度默认只有 1 层，页面不提供深度调整。
- `listRepos` / `listQueryableRepos` 都取返回体的 `repos` 字段
  （`r?.repos || []`）—— 后端若改成裸数组，这里要一起改。
- `routes` 里没有 `*` 兜底 —— 宿主必须自己提供 404 路由。
- `./pages` 桶是显式具名转发，新增页面**必须**补一行。
- 组件包必须 external 基座；`vite.config.js` 的 `external` 是精确字符串匹配，
  `@yuku123/z-frontend-common/ui` 与 `/ui/echarts` 两个子路径必须逐个列出，漏了基座会被复制进 dist。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.0 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |
| 0.1.0 | 首版：概览 / 仓库注册表 / 符号检索页面集 + manifest + `createRequest` 请求层 |
