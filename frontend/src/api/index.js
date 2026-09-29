import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }
)

/** 模块一：机房与机柜 + 机架 U 位图 */
export const cabinetApi = {
  rooms: () => http.get('/rooms'),
  createRoom: (data) => http.post('/rooms', data),
  cabinets: (roomId) => http.get('/cabinets', { params: roomId ? { roomId } : {} }),
  createCabinet: (data) => http.post('/cabinets', data),
  /** 机架图：42U 画到 1U */
  rack: (id) => http.get(`/cabinets/${id}/rack`)
}

/** 模块二：服务器资产（多条件筛选走 Query by Example） */
export const serverApi = {
  search: (params) => http.get('/servers', { params }),
  get: (id) => http.get(`/servers/${id}`),
  create: (data) => http.post('/servers', data),
  update: (id, data) => http.put(`/servers/${id}`, data),
  setStatus: (id, status) => http.post(`/servers/${id}/status`, { status }),
  options: () => http.get('/servers/options')
}

/** 模块三：上架 / 下架 / 迁移工单 */
export const orderApi = {
  search: (params) => http.get('/orders', { params }),
  create: (data) => http.post('/orders', data),
  advance: (id) => http.post(`/orders/${id}/advance`),
  reject: (id, reason) => http.post(`/orders/${id}/reject`, { reason }),
  byServer: (id) => http.get(`/servers/${id}/orders`)
}

/** 模块四：巡检记录 */
export const inspectApi = {
  search: (params) => http.get('/inspections', { params }),
  summary: () => http.get('/inspections/summary'),
  create: (data) => http.post('/inspections', data),
  handle: (id, note) => http.post(`/inspections/${id}/handle`, { note })
}

/** 模块五：能耗记录 */
export const energyApi = {
  search: (params) => http.get('/energy', { params }),
  summary: (month) => http.get('/energy/summary', { params: month ? { month } : {} }),
  create: (data) => http.post('/energy', data)
}

/** 模块六：机柜停电检修隔离 */
export const isolationApi = {
  search: (params) => http.get('/isolations', { params }),
  create: (data) => http.post('/isolations', data),
  detail: (id) => http.get(`/isolations/${id}`),
  activate: (id, operator) => http.post(`/isolations/${id}/activate`, { operator }),
  cancel: (id, data) => http.post(`/isolations/${id}/cancel`, data),
  release: (id, data) => http.post(`/isolations/${id}/release`, data),
  activeList: () => http.get('/isolations/active'),
  currentByCabinet: (cabinetId) => http.get(`/cabinets/${cabinetId}/isolation`)
}

/** 状态 → 中文名 */
export const SERVER_STATUS = {
  ONLINE: '在架',
  OFFLINE: '下架',
  REPAIR: '维修',
  SCRAP: '报废'
}

export const ORDER_TYPE = { ON: '上架', OFF: '下架', MOVE: '迁移' }

export const ORDER_STATUS = {
  PENDING: '待审批',
  APPROVED: '已批复',
  WORKING: '施工中',
  DONE: '已完成',
  REJECTED: '已驳回'
}

export const POWER_STATUS = { NORMAL: '正常', ABNORMAL: '异常' }

export const ISOLATION_STATUS = {
  PENDING: '待生效',
  ACTIVE: '隔离中',
  RELEASED: '已解除',
  CANCELED: '已取消'
}

/** 隔离快照差异类型 */
export const DIFF_TYPE = {
  SAME: '与快照一致',
  CHANGED: '有变化',
  MOVED_OUT: '已移出本柜',
  GONE: '资产已删除',
  ADDED: '隔离后新进入'
}

/** 隔离操作记录动作 */
export const ISOLATION_ACTION = {
  CREATE: '创建',
  ACTIVATE: '生效',
  ACTIVATE_FAIL: '生效失败',
  CANCEL: '取消',
  RELEASE: '解除'
}

/** 状态标签类型（Element Plus tag） */
export const statusTagType = (status) =>
  ({
    ONLINE: 'primary',
    OFFLINE: 'info',
    REPAIR: 'warning',
    SCRAP: 'danger',
    PENDING: 'info',
    APPROVED: 'primary',
    WORKING: 'warning',
    DONE: 'success',
    REJECTED: 'danger',
    NORMAL: 'success',
    ABNORMAL: 'danger',
    ACTIVE: 'danger',
    RELEASED: 'success',
    CANCELED: 'info'
  })[status] || 'info'

/** 后端 LocalDateTime 出来是 "2026-09-21T09:10:00"，页面上显示到分钟 */
export const fmtTime = (value) => (value ? String(value).replace('T', ' ').slice(0, 16) : '—')

/**
 * 工单位置文案：
 *  - OFF 下架：展示【下架前位置】永久快照（src_* 优先，兼容老单的 cabinet_id/target_u），
 *    不随资产重新上架而变；
 *  - ON 上架 / MOVE 迁移：展示目标位置。
 * cabinets 为机柜下拉清单（带 code）。
 */
export const orderLocation = (row, cabinetName) => {
  const name = (id) => (cabinetName ? cabinetName(id) : id)
  if (row.orderType === 'OFF') {
    const cabinetId = row.srcCabinetId ?? row.cabinetId
    const startU = row.srcStartU ?? row.targetU
    if (cabinetId == null) return '下架前位置未记录'
    return `下架前 ${name(cabinetId)} · ${startU}U`
  }
  return `${name(row.cabinetId)} · ${row.targetU}U`
}

/**
 * 服务器当前位置文案：无机柜 = 当前未分配（下架完成后的口径），避免和「下架前在哪」混淆。
 */
export const serverLocation = (row, cabinetName) => {
  if (!row || row.cabinetId == null) return '未分配机柜 / 未分配 U 位'
  const code = cabinetName ? cabinetName(row.cabinetId) : row.cabinetId
  return `${code} · ${row.startU}U 起 ${row.sizeU || 1}U`
}

/** 工单里的历史位置快照（src_* 优先，老单回退 cabinet_id/target_u），供服务器详情展示下架前位置 */
export const orderSnapshotLocation = (order, cabinetName) => {
  const cabinetId = order.srcCabinetId ?? order.cabinetId
  const startU = order.srcStartU ?? order.targetU
  if (cabinetId == null) return null
  const code = cabinetName ? cabinetName(cabinetId) : cabinetId
  return `${code} · ${startU}U`
}

/** 当前月 yyyy-MM */
export const currentMonth = () => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

export default http
