import {
    AppstoreOutlined, DatabaseOutlined, SearchOutlined, DashboardOutlined,
} from '@ant-design/icons'
import Overview from './pages/Overview'
import Repos from './pages/Repos'
import Query from './pages/Query'

/** 菜单 + 路由清单（lead 005 §8.2 manifest）。App 壳在 suit/宿主侧组装。 */
export const menuItems = [
    {key: '/overview', icon: <DashboardOutlined/>, label: '概览'},
    {key: '/repos', icon: <DatabaseOutlined/>, label: '仓库注册表'},
    {key: '/query', icon: <SearchOutlined/>, label: '符号检索'},
]

const routeTable = [
    {path: 'overview', Component: Overview},
    {path: 'repos', Component: Repos},
    {path: 'query', Component: Query},
]
export {routeTable}
export {default as Overview} from './pages/Overview'
export {default as Repos} from './pages/Repos'
export {default as Query} from './pages/Query'
