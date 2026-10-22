import { AppstoreOutlined, DashboardOutlined, DatabaseOutlined, HomeOutlined, ReadOutlined, SearchOutlined } from '@ant-design/icons'
import Overview from './pages/Overview'
import Repos from './pages/Repos'
import Query from './pages/Query'

/** 菜单 + 路由清单（lead 005 §8.2 manifest）。App 壳在 suit/宿主侧组装。 */

export {default as Overview} from './pages/Overview'
export {default as Repos} from './pages/Repos'
export {default as Query} from './pages/Query'
import HomePage from './pages/HomePage'
import ComponentDocPage from './pages/ComponentDoc.jsx'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-indexer 索引服务', short: 'z-indexer' }

export const menuItems = [
    { key: '/z-indexer/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-indexer/overview', label: '概览', icon: <DashboardOutlined /> },
    { key: '/z-indexer/repos', label: '仓库注册表', icon: <DatabaseOutlined /> },
    { key: '/z-indexer/query', label: '符号检索', icon: <SearchOutlined /> },
    { key: '/z-indexer/docs', label: '组件文档', icon: <ReadOutlined /> },
]

export const routes = [
    { path: '/z-indexer/home', Component: HomePage },
    { path: '/z-indexer/overview', Component: Overview },
    { path: '/z-indexer/repos', Component: Repos },
    { path: '/z-indexer/query', Component: Query },
    { path: '/z-indexer/docs', Component: ComponentDocPage },
]

export { default as HomePage } from './pages/HomePage'
export { default as LoginPage } from './pages/LoginPage'
// 主壳接线用：文档页统一以 ComponentDocPage 这个名字从主入口导出。
export { default as ComponentDocPage } from './pages/ComponentDoc.jsx'
