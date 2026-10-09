/**
 * z-indexer API client
 *
 * 后端 REST（/index/admin + /index/query）通过 z-frontend-common createRequest
 * 工厂创建。baseURL 默认 ''（路径自带 /index 前缀，原行为）。
 *
 * 注入 X-Indexer-Token 头（后端 IndexerProperties.token，开启后所有 endpoint 必带）。
 */
import {createRequest} from '@yuku123/z-frontend-common'

const request = createRequest({baseURL: '/index', tokenKey: 'zindexer_token'})

// 注入索引服务管理 token（后端 IndexerProperties.indexer.token 头校验）
request.interceptors.request.use((config) => {
    const adminTk = localStorage.getItem('zindexer_admin_token')
    if (adminTk) {
        config.headers['X-Indexer-Token'] = adminTk
    }
    return config
})

export default request

// 前缀参数注入（lead 005 section 9.3）：默认 '/index'。
// 宿主换挂载前缀时 configureIndexer({ apiBase })，直接改写实例 defaults。
export function configureIndexer(config) {
    if (config && config.apiBase !== undefined) {
        request.defaults.baseURL = config.apiBase
    }
}

// ========== /index/admin ==========

/** 仓库列表（含构建状态） */
export const listRepos = () => request.get('/admin/repos').then(r => r?.repos || [])

/** 注册/更新仓库 */
export const saveRepo = (data) => request.post('/admin/repos', data)

/** 启停 */
export const toggleRepo = (repoCode, enabled) => request.put(`/admin/repos/${repoCode}/enabled`, null, {params: {enabled}})

/** 删除 */
export const deleteRepo = (repoCode) => request.delete(`/admin/repos/${repoCode}`)

/** 重建 */
export const rebuildRepo = (repoCode) => request.post(`/admin/repos/${repoCode}/rebuild`)

/** 构建日志（取最后 N 行） */
export const getBuildLog = (repoCode, lines = 200) =>
    request.get(`/admin/repos/${repoCode}/build-log`, {params: {lines}}).then(r => r?.log || '')

/** 触发全量轮询 */
export const triggerPoll = () => request.post('/admin/poll')

// ========== /index/query ==========

/** 可查询仓库列表 */
export const listQueryableRepos = () => request.get('/query/repos').then(r => r?.repos || [])

/** 符号搜索 */
export const searchSymbols = (repoCode, query, limit = 20, includeAccessors = false) =>
    request.post('/query/symbol', {repoCode, query, limit, includeAccessors})

/** 谁调用了它 */
export const getCallers = (repoCode, symbol, depth = 1, includeAccessors = false) =>
    request.post('/query/callers', {repoCode, symbol, depth, includeAccessors})

/** 它调用了谁 */
export const getCallees = (repoCode, symbol, depth = 1, includeAccessors = false) =>
    request.post('/query/callees', {repoCode, symbol, depth, includeAccessors})
