/** z-indexer 状态展示：注册表总数 / 已启 / 总行数 / 最近轮询。 */
import {useEffect, useState} from 'react'
import {Card, Col, Row, Skeleton, Statistic, Tag, Typography} from 'antd'
import {ApiOutlined, CheckCircleOutlined, ClockCircleOutlined, DatabaseOutlined, SyncOutlined} from '@ant-design/icons'
import {listRepos, listQueryableRepos} from '../services/api'

const {Title, Paragraph} = Typography

function fmtTime(ts) {
    if (!ts || ts <= 0) return '—'
    return new Date(ts).toLocaleString()
}

function StatusTag({s}) {
    const map = {SUCCESS: 'green', FAILED: 'red', BUILDING: 'processing', NEVER: 'default'}
    const text = {SUCCESS: '成功', FAILED: '失败', BUILDING: '构建中', NEVER: '未构建'}[s] || s
    return <Tag color={map[s] || 'orange'}>{text}</Tag>
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

    if (loading) return <Skeleton active/>

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
                                <Statistic title={<StatusTag s={k}/>} value={v}/>
                            </Card>
                        </Col>
                    ))}
                    {Object.keys(statusCount).length === 0 && <Col><Paragraph type="secondary">暂无数据</Paragraph></Col>}
                </Row>
            </Card>
        </div>
    )
}
