export function fmtQty(v, digits = 4) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return n.toLocaleString('zh-CN', { maximumFractionDigits: digits })
}

/** 金额：保留两位小数，带千分位；空值显示 — */
export function fmtMoney(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return `¥${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

export function fmtDate(v) {
  return v ? String(v).slice(0, 10) : '—'
}

export function fmtTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}

export function pct(ratio) {
  return ratio === null || ratio === undefined ? '—' : `${Math.round(ratio * 100)}%`
}

// “今天”按业务时区算，和后端 TimeProvider 一致。按浏览器时区算的话，电脑时区比上海早时，
// 默认生产日期就是明天，后端会拒绝；不用 toISOString，它按 UTC 算，早上 8 点前会少一天
export function today() {
  return new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' })
}

export function daysFromToday(n) {
  const [y, m, d] = today().split('-').map(Number)
  const t = new Date(y, m - 1, d + n)
  const p = (x) => String(x).padStart(2, '0')
  return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())}`
}

// 日期选择器的 disabled-date 要和本地零点的 Date 比较
export function startOfBusinessDay() {
  const [y, m, d] = today().split('-').map(Number)
  return new Date(y, m - 1, d)
}

export const ITEM_TYPE = { RM: '原料', PK: '包材', FG: '成品' }
export const PARTNER_KIND = { SUPPLIER: '物料供应商', OEM: '受托生产企业', OWN_FACTORY: '自有工厂', CUSTOMER: '客户渠道' }
export const WAREHOUSE_KIND = { OWN: '自有仓', OEM: '代工厂寄存仓', PLATFORM: '平台仓', THIRD_PARTY: '第三方仓', RETURN: '退货隔离区' }
export const STATUS_TAG = {
  DONE: 'success', SENT: 'primary', VOID: 'info', DISPUTED: 'danger', CONFIRMED: 'warning', INVOICED: 'warning', PAID: 'success', AWARDED: 'success',
  QUARANTINE: 'warning', PENDING_RELEASE: 'primary', RELEASED: 'success', REJECTED: 'danger', FROZEN: 'danger',
  OPEN: 'warning', PARTIAL: 'primary', RECEIVED: 'success',
  PLANNED: 'info', ISSUED: 'primary', COMPLETED: 'success',
  CLOSED: 'info', PENDING: 'warning', RESTOCK: 'success', SCRAP: 'danger',
  TRANSFER: 'primary', ADJUST: 'warning',
  SUBMITTED: 'warning', APPROVED: 'success', CANCELLED: 'info', ACTIVE: 'primary', REVIEW: 'warning', EFFECTIVE: 'success', SUPERSEDED: 'info', PROPOSED: 'warning', LAUNCH_REVIEW: 'warning', LAUNCHED: 'success', ASSESSED: 'warning', OBSOLETE: 'info', DRAFT: 'info',
  SHIPPED: 'success', FAILED: 'danger', ACCEPTED: 'success', RETURNED: 'danger'
}
export const COMPLAINT_CATEGORY = { ADVERSE: '不良反应', QUALITY: '质量问题', PACKAGE: '包装 / 标签', LOGISTICS: '物流 / 破损', OTHER: '其他' }
export const SEVERITY = { NORMAL: '一般', SERIOUS: '严重' }
export const COMPLAINT_CHANNELS = ['客服热线', '电商平台', '经销商', '私域社群', '监管部门转办', '其他']
export const OEM_STAGES = { CONFIRMED: '已接单', SCHEDULED: '已排产', PRODUCING: '生产中', TESTING: '检验中', NOTE: '备注' }
