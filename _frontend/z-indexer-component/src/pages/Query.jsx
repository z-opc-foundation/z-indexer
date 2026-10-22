/** 符号检索：跨仓 symbol 查询 / 调用链（callers + callees）。 */
import {useEffect, useState} from 'react'
import {Alert, AutoComplete, Button, Card, Form, Input, InputNumber, Select, Space, Table, Tabs, Tag, Typography} from 'antd'
import {ApiOutlined, ArrowDownOutlined, ArrowUpOutlined, SearchOutlined} from '@ant-design/icons'
import {EmptyState, ErrorState} from '@yuku123/z-frontend-common/ui'
import {getCallees, getCallers, listQueryableRepos, searchSymbols} from '../services/api'

const {Title, Paragraph, Text} = Typography
const {TabPane} = Tabs

function buildChainRows(chain) {
    if (!chain || !chain.nodes) return []
    return (chain.nodes || []).map((n, i) => ({
        key: i,
        depth: n.depth ?? 0,
        sym: n.symbol || n.sym || '',
        file: n.file || '',
        line: n.line || 0,
        kind: n.kind || '',
    }))
}

export default function Query() {
    const [repos, setRepos] = useState([])
    const [repo, setRepo] = useState(null)
    const [symbol, setSymbol] = useState('')
    const [depth, setDepth] = useState(1)
    const [includeAcc, setIncludeAcc] = useState(false)
    const [tab, setTab] = useState('symbol')

    const [symLoading, setSymLoading] = useState(false)
    const [symResult, setSymResult] = useState(null)
    const [symError, setSymError] = useState(null)

    const [chainLoading, setChainLoading] = useState(false)
    const [chainResult, setChainResult] = useState(null)
    const [chainError, setChainError] = useState(null)

    useEffect(() => {
        listQueryableRepos().then(r => {
            setRepos(r || [])
            if (r && r.length) setRepo(r[0].repoCode || r[0].code)
        })
    }, [])

    const onSearch = async () => {
        if (!repo || !symbol) return
        setSymError(null)
        setSymLoading(true)
        try {
            const r = await searchSymbols(repo, symbol, 30, includeAcc)
            setSymResult(r)
        } catch (e) {
            setSymError(e.message || '查询失败')
            setSymResult(null)
        } finally { setSymLoading(false) }
    }

    const onChain = async (incoming) => {
        if (!repo || !symbol) return
        setChainError(null)
        setChainLoading(true)
        try {
            const r = incoming
                ? await getCallers(repo, symbol, depth, includeAcc)
                : await getCallees(repo, symbol, depth, includeAcc)
            setChainResult(r)
        } catch (e) {
            setChainError(e.message || '查询失败')
            setChainResult(null)
        } finally { setChainLoading(false) }
    }

    const symColumns = [
        {title: 'kind', dataIndex: 'kind', key: 'kind', width: 90,
            render: (k) => <Tag color={k === 'CLASS' ? 'blue' : k === 'METHOD' ? 'green' : 'default'}>{k}</Tag>},
        {title: '符号', dataIndex: 'sym', key: 'sym', ellipsis: true},
        {title: '文件', dataIndex: 'file', key: 'file', ellipsis: true,
            render: (f) => <Text code style={{fontSize: 12}}>{f}</Text>},
        {title: '行', dataIndex: 'line', key: 'line', width: 70},
    ]

    return (
        <div>
            <Title level={4}>符号检索 / 调用链</Title>
            <Paragraph type="secondary">跨仓搜索 Java 符号（class/method/field），或查调用链（callers 谁调它 / callees 它调谁）。</Paragraph>

            <Card style={{marginBottom: 16}}>
                <Space size="middle" wrap>
                    <Form.Item label="仓" style={{marginBottom: 0, minWidth: 200}}>
                        <Select value={repo} onChange={setRepo} showSearch optionFilterProp="children"
                                placeholder="选可查询仓">
                            {repos.map(r => (
                                <Select.Option key={r.repoCode || r.code} value={r.repoCode || r.code}>
                                    {r.repoName || r.repoCode || r.code}
                                </Select.Option>
                            ))}
                        </Select>
                    </Form.Item>
                    <Form.Item label="符号名（substring）" style={{marginBottom: 0, minWidth: 320}}>
                        <Input value={symbol} onChange={e => setSymbol(e.target.value)} onPressEnter={onSearch} placeholder="如 UserService / createOrder"/>
                    </Form.Item>
                    <Form.Item label="深度" style={{marginBottom: 0}}>
                        <InputNumber min={0} max={5} value={depth} onChange={setDepth}/>
                    </Form.Item>
                    <Form.Item label="含 accessor" style={{marginBottom: 0}}>
                        <Select value={includeAcc} onChange={setIncludeAcc} style={{width: 90}}>
                            <Select.Option value={false}>否</Select.Option>
                            <Select.Option value={true}>是</Select.Option>
                        </Select>
                    </Form.Item>
                    <Button type="primary" icon={<SearchOutlined/>} onClick={onSearch}>搜索符号</Button>
                </Space>
            </Card>

            <Tabs activeKey={tab} onChange={setTab}>
                <TabPane tab={<span><ApiOutlined/>符号搜索</span>} key="symbol">
                    {symError && (
                        <div style={{marginBottom: 12}}>
                            {/* 错误就近显示，保留原来的文案；onRetry 复用同一个查询动作 */}
                            <ErrorState error={symError} title="查询失败" onRetry={onSearch}/>
                        </div>
                    )}
                    {!symError && symResult?.loading && (
                        <Alert type="warning" message="冷加载中" description="该仓首次查询，索引正在加载，请 30s 后重试同一查询"/>
                    )}
                    {!symError && symResult?.notIndexed && (
                        <Alert type="error" message="未建索引" description="该仓尚未构建索引，请先在「仓库注册表」页执行重建"/>
                    )}
                    {symResult?.results && (
                        <Table rowKey={(r, i) => i} dataSource={symResult.results} columns={symColumns}
                               loading={symLoading} size="small" pagination={false}
                               title={() => <Text>共 {symResult.count} 条</Text>}/>
                    )}
                    {!symLoading && !symResult && !symError && (
                        <EmptyState title="暂无结果" description="输入符号名开始搜索"/>
                    )}
                </TabPane>
                <TabPane tab={<span><ArrowUpOutlined/>Callers（谁调它）</span>} key="callers">
                    <Button onClick={() => onChain(true)} loading={chainLoading} icon={<ArrowUpOutlined/>}>查询 callers</Button>
                    {chainError && (
                        <div style={{marginTop: 12}}>
                            <ErrorState error={chainError} title="失败" onRetry={() => onChain(true)}/>
                        </div>
                    )}
                    {chainResult?.nodes && (
                        <Table style={{marginTop: 12}} rowKey={(r, i) => i} dataSource={buildChainRows(chainResult)}
                               loading={chainLoading} size="small" pagination={false}
                               columns={[
                                   {title: 'depth', dataIndex: 'depth', width: 70},
                                   {title: '符号', dataIndex: 'sym', ellipsis: true},
                                   {title: '文件', dataIndex: 'file', ellipsis: true, render: f => <Text code style={{fontSize: 12}}>{f}</Text>},
                                   {title: '行', dataIndex: 'line', width: 70},
                               ]}/>
                    )}
                </TabPane>
                <TabPane tab={<span><ArrowDownOutlined/>Callees（它调谁）</span>} key="callees">
                    <Button onClick={() => onChain(false)} loading={chainLoading} icon={<ArrowDownOutlined/>}>查询 callees</Button>
                    {chainError && (
                        <div style={{marginTop: 12}}>
                            <ErrorState error={chainError} title="失败" onRetry={() => onChain(false)}/>
                        </div>
                    )}
                    {chainResult?.nodes && (
                        <Table style={{marginTop: 12}} rowKey={(r, i) => i} dataSource={buildChainRows(chainResult)}
                               loading={chainLoading} size="small" pagination={false}
                               columns={[
                                   {title: 'depth', dataIndex: 'depth', width: 70},
                                   {title: '符号', dataIndex: 'sym', ellipsis: true},
                                   {title: '文件', dataIndex: 'file', ellipsis: true, render: f => <Text code style={{fontSize: 12}}>{f}</Text>},
                                   {title: '行', dataIndex: 'line', width: 70},
                               ]}/>
                    )}
                </TabPane>
            </Tabs>
        </div>
    )
}
