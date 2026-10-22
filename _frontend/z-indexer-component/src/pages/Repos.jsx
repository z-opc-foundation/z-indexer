/** 仓库注册表 CRUD：列表 / 启停 / 重建 / 删除 / 新增。 */
import {useEffect, useState} from 'react'
import {
    Button, Card, Form, Input, message, Modal, Popconfirm, Select, Space, Switch, Tag, Tooltip, Typography,
} from 'antd'
import {
    DeleteOutlined, PlusOutlined, ReloadOutlined, SyncOutlined,
} from '@ant-design/icons'
import {ListTable, StatusBadge, TableToolbar} from '@yuku123/z-frontend-common/ui'
import {deleteRepo, getBuildLog, listRepos, rebuildRepo, saveRepo, toggleRepo, triggerPoll} from '../services/api'

const {Title, Paragraph, Text} = Typography
const {Option} = Select

// 构建状态不属于 StatusBadge 内置的任何一类，用 mapping 显式传，
// 文案与颜色与原来的手写 StatusTag 完全一致。
const BUILD_STATUS = {
    SUCCESS: {label: '成功', color: 'green'},
    FAILED: {label: '失败', color: 'red'},
    BUILDING: {label: '构建中', color: 'processing'},
    NEVER: {label: '未构建', color: 'default'},
}

function fmtDuration(ms) {
    if (!ms || ms <= 0) return '—'
    if (ms < 60000) return `${Math.round(ms / 1000)}s`
    return `${(ms / 60000).toFixed(1)}min`
}

const EMPTY = {
    repoCode: '', repoName: '', gitUrl: '',
    refMode: 'BRANCH', refValue: 'main', enabled: true, remark: '',
}

export default function Repos() {
    const [repos, setRepos] = useState([])
    const [loading, setLoading] = useState(false)
    const [editing, setEditing] = useState(null)  // null | EMPTY | row
    const [logRow, setLogRow] = useState(null)

    const load = async () => {
        setLoading(true)
        try {
            const r = await listRepos()
            setRepos(r || [])
        } finally {
            setLoading(false)
        }
    }
    useEffect(() => { load() }, [])

    const onToggle = async (row) => {
        try {
            await toggleRepo(row.repoCode, !row.enabled)
            message.success(row.enabled ? '已停用' : '已启用')
            load()
        } catch (e) { message.error(e.message || '操作失败') }
    }
    const onRebuild = async (row) => {
        try {
            await rebuildRepo(row.repoCode)
            message.success('重建请求已提交')
            load()
        } catch (e) { message.error(e.message || '操作失败') }
    }
    const onDelete = async (row) => {
        try {
            await deleteRepo(row.repoCode)
            message.success('已删除')
            load()
        } catch (e) { message.error(e.message || '操作失败') }
    }
    const onPoll = async () => {
        try {
            const r = await triggerPoll()
            message.success(`已触发全量轮询，扫描 ${r?.scanned || '?'} 仓`)
            load()
        } catch (e) { message.error(e.message || '操作失败') }
    }
    const onShowLog = async (row) => {
        try {
            const log = await getBuildLog(row.repoCode, 200)
            setLogRow({...row, log: log || '（暂无日志）'})
        } catch (e) {
            setLogRow({...row, log: `读取失败：${e.message}`})
        }
    }

    const columns = [
        {title: '仓代码', dataIndex: 'repoCode', key: 'repoCode', width: 120},
        {title: '名称', dataIndex: 'repoName', key: 'repoName', width: 160},
        {
            title: 'Git', dataIndex: 'gitUrl', key: 'gitUrl', ellipsis: true,
            render: (v) => <Text code style={{fontSize: 12}}>{v || '—'}</Text>,
        },
        {title: 'Ref', key: 'ref', width: 180,
            render: (_, r) => <Tag>{r.refMode || '—'}: {r.refValue || '—'}</Tag>},
        {title: '状态', dataIndex: 'lastBuildStatus', key: 'status', width: 90,
            render: (s) => <StatusBadge status={s} mapping={BUILD_STATUS}/>},
        {title: '索引行数', dataIndex: 'lastIndexLines', key: 'lines', width: 110,
            render: (n) => n ? n.toLocaleString('zh-CN') : '—'},
        {title: '构建耗时', dataIndex: 'lastBuildDurationMs', key: 'dur', width: 90,
            render: fmtDuration},
        {title: '最近', dataIndex: 'lastBuildTime', key: 'when', width: 150,
            render: (ts) => ts ? new Date(ts).toLocaleString() : '—'},
        {title: '启用', dataIndex: 'enabled', key: 'enabled', width: 70,
            render: (v, r) => <Switch size="small" checked={!!v} onChange={() => onToggle(r)}/>},
        {title: '操作', key: 'op', width: 260, fixed: 'right',
            render: (_, r) => (
                <Space size="small">
                    <Tooltip title="查看构建日志"><Button size="small" onClick={() => onShowLog(r)}>日志</Button></Tooltip>
                    <Popconfirm title="重建索引？" onConfirm={() => onRebuild(r)} okText="重建" cancelText="取消">
                        <Button size="small" icon={<SyncOutlined/>}>重建</Button>
                    </Popconfirm>
                    <Button size="small" icon={<PlusOutlined/>} onClick={() => setEditing(r)}>编辑</Button>
                    <Popconfirm title="删除注册？" description="会同时删除本地索引产物" onConfirm={() => onDelete(r)} okText="删除" cancelText="取消" okButtonProps={{danger: true}}>
                        <Button size="small" danger icon={<DeleteOutlined/>}>删除</Button>
                    </Popconfirm>
                </Space>
            )},
    ]

    return (
        <div>
            <TableToolbar
                left={<Title level={4} style={{margin: 0}}>仓库注册表</Title>}
                right={
                    <>
                        <Button icon={<ReloadOutlined/>} onClick={load}>刷新</Button>
                        <Button icon={<SyncOutlined/>} onClick={onPoll}>触发全量轮询</Button>
                        <Button type="primary" icon={<PlusOutlined/>} onClick={() => setEditing({...EMPTY})}>新增</Button>
                    </>
                }
            />
            <Paragraph type="secondary">注册 Git 仓库后由 IndexSyncRunner 周期构建。Ref 模式：BRANCH（固定分支）/ TAG_PATTERN（正则匹配）/ TAG_LATEST（最新 tag）。</Paragraph>

            {/* 空态用共享 EmptyState（lead 008 §2 强制规则 4），不要 antd 默认空图；
                分页/过滤/空态三件套由基座 ListTable 统一承接。 */}
            <ListTable
                rows={repos}
                columns={columns}
                rowKey="repoCode"
                loading={loading}
                scroll={{x: 1200}}
                searchKeys="repoCode,repoName,gitUrl"
                searchPlaceholder="按仓库代码 / 显示名 / Git URL 过滤"
                emptyTitle="暂无仓库"
                emptyDescription="还没有注册 Git 仓库，点右上角「新增」注册第一个"
            />

            <Modal
                title={editing && repos.find(r => r.repoCode === editing.repoCode) ? '编辑仓库' : '新增仓库'}
                open={!!editing}
                onCancel={() => setEditing(null)}
                onOk={async () => {
                    try {
                        await saveRepo(editing)
                        message.success('已保存')
                        setEditing(null)
                        load()
                    } catch (e) { message.error(e.message || '保存失败') }
                }}
                width={600}
            >
                {editing && <Form layout="vertical" initialValues={editing}>
                    <Form.Item label="仓代码（小写英文+数字+下划线，唯一）" required>
                        <Input value={editing.repoCode} onChange={e => setEditing({...editing, repoCode: e.target.value})}/>
                    </Form.Item>
                    <Form.Item label="显示名">
                        <Input value={editing.repoName} onChange={e => setEditing({...editing, repoName: e.target.value})}/>
                    </Form.Item>
                    <Form.Item label="Git URL（HTTP(S)；凭证后端注入，不在此显）" required>
                        <Input value={editing.gitUrl} onChange={e => setEditing({...editing, gitUrl: e.target.value})}/>
                    </Form.Item>
                    <Form.Item label="Ref 模式">
                        <Select value={editing.refMode} onChange={v => setEditing({...editing, refMode: v})}>
                            <Option value="BRANCH">BRANCH（固定分支）</Option>
                            <Option value="TAG_PATTERN">TAG_PATTERN（正则匹配）</Option>
                            <Option value="TAG_LATEST">TAG_LATEST（最新 tag）</Option>
                        </Select>
                    </Form.Item>
                    <Form.Item label="Ref 值">
                        <Input value={editing.refValue} onChange={e => setEditing({...editing, refValue: e.target.value})}/>
                    </Form.Item>
                    <Form.Item label="启用">
                        <Switch checked={editing.enabled} onChange={v => setEditing({...editing, enabled: v})}/>
                    </Form.Item>
                    <Form.Item label="备注">
                        <Input.TextArea rows={2} value={editing.remark} onChange={e => setEditing({...editing, remark: e.target.value})}/>
                    </Form.Item>
                </Form>}
            </Modal>

            <Modal
                title={logRow ? `构建日志：${logRow.repoCode}` : ''}
                open={!!logRow}
                onCancel={() => setLogRow(null)}
                footer={<Button onClick={() => setLogRow(null)}>关闭</Button>}
                width={900}
            >
                {logRow && <pre style={{
                    maxHeight: 500, overflow: 'auto',
                    background: '#1e1e1e', color: '#d4d4d4',
                    padding: 12, fontSize: 12, borderRadius: 4,
                }}>{logRow.log}</pre>}
            </Modal>
        </div>
    )
}
