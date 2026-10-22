/** z-indexer 状态展示：注册表总数 / 已启 / 总行数 / 最近轮询。 */
import {useEffect, useState} from 'react'
import {Card, Col, Row, Statistic, Typography} from 'antd'
import {ApiOutlined, CheckCircleOutlined, ClockCircleOutlined, DatabaseOutlined, SyncOutlined} from '@ant-design/icons'
import {EmptyState, LoadingState, StatusBadge} from '@yuku123/z-frontend-common/ui'
import {listRepos, listQueryableRepos} from '../services/api'

const {Title, Paragraph} = Typography

function fmtTime(ts) {
    if (!ts || ts <= 0) return '—'
    return new Date(ts).toLocaleString()
}

// 构建状态不属于 StatusBadge 内置的任何一类（task/model/priority/approval/schedule），
// 所以用 mapping 显式传 —— 文案与颜色与原来的手写 StatusTag 完全一致。
const BUILD_STATUS = {
    SUCCESS: {label: '成功', color: 'green'},
    FAILED: {label: '失败', color: 'red'},
    BUILDING: {label: '构建中', color: 'processing'},
    NEVER: {label: '未构建', color: 'default'},
}

export default function Overview() {
    const [admin, setAdmin] = useState([])
    const [query, setQuery] = useState([])
    const [loading, setLoading] = useState(true)

    const load = async () => {
        try {
            const [a, q] = await Promise.all([listRepos(), listQueryableRepos()])
            setAdmin(a)
            setQuery(q)
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        load()
        const t = setInterval(load, 30000)
        return () => clearInterval(t)
    }, [])

    // 加载态走共享骨架屏（cards 变体对应下面 4 张统计卡），不再用裸 Skeleton
    if (loading) return <LoadingState variant="cards" rows={4}/>

    const enabled = admin.filter(r => r.enabled).length
    const indexed = query.filter(r => r.indexType && r.indexType !== 'NOT_INDEXED').length
    const totalLines = query.reduce((s, r) => s + (r.indexLines || 0), 0)
    const statusCount = admin.reduce((acc, r) => {
        const k = r.lastBuildStatus || 'NEVER'
        acc[k] = (acc[k] || 0) + 1
        return acc
    }, {})

    return (
        <div>
            <Title level={4}>索引概览</Title>
            <Paragraph type="secondary">注册表与可查询索引的实时统计</Paragraph>

            <Row gutter={16} style={{marginBottom: 24}}>
                <Col span={6}>
                    <Card><Statistic title="注册仓库" value={admin.length} prefix={<DatabaseOutlined/>}/></Card>
                </Col>
                <Col span={6}>
                    <Card><Statistic title="已启用" value={enabled} prefix={<CheckCircleOutlined/>}
                                     valueStyle={{color: '#3f8600'}}/></Card>
                </Col>
                <Col span={6}>
                    <Card><Statistic title="已建索引" value={indexed} prefix={<ApiOutlined/>}/></Card>
                </Col>
                <Col span={6}>
                    <Card><Statistic title="总索引行数" value={totalLines.toLocaleString('zh-CN')}
                                     prefix={<SyncOutlined/>}/></Card>
                </Col>
            </Row>

            <Card title="构建状态分布">
                <Row gutter={16}>
                    {Object.entries(statusCount).map(([k, v]) => (
                        <Col key={k} span={6}>
                            <Card size="small">
                                <Statistic title={<StatusBadge status={k} mapping={BUILD_STATUS}/>} value={v}/>
                            </Card>
                        </Col>
                    ))}
                    {Object.keys(statusCount).length === 0 && (
                        <Col span={24}>
                            <EmptyState title="暂无数据" description="还没有构建记录，注册仓库后触发一次构建即可看到分布" size="sm"/>
                        </Col>
                    )}
                </Row>
            </Card>
        </div>
    )
}
