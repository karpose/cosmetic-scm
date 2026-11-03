export function fmtQty(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
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

export function today() {
  const d = new Date()
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export const ITEM_TYPE = { RM: '原料', PK: '包材', FG: '成品' }
export const PARTNER_KIND = { SUPPLIER: '物料供应商', OEM: '受托生产企业', OWN_FACTORY: '自有工厂', CUSTOMER: '客户渠道' }
export const WAREHOUSE_KIND = { OWN: '自有仓', OEM: '代工厂寄存仓', PLATFORM: '平台仓', THIRD_PARTY: '第三方仓' }
export const STATUS_TAG = {
  QUARANTINE: 'warning', PENDING_RELEASE: 'primary', RELEASED: 'success', REJECTED: 'danger', FROZEN: 'danger',
  OPEN: 'warning', PARTIAL: 'primary', RECEIVED: 'success',
  PLANNED: 'info', ISSUED: 'primary', COMPLETED: 'success'
}
