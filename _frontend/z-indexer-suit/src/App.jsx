import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '@yuku123/z-frontend-common'
import {menuItems, routeTable} from '@yuku123/z-indexer-component/pages'

// 壳只做组装（lead 005 §9.1）：AppLayout + manifest 路由（z-indexer 无鉴权）。
export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/overview" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-indexer 索引服务" appShort="IDX"/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
