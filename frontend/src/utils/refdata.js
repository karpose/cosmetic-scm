import { computed, reactive } from 'vue'
import http from '../api'

// 主数据下拉选项，各页面按需加载
export function useRefData() {
  const s = reactive({ items: [], partners: [], warehouses: [], loaded: false })

  let pending = null

  function load() {
    pending = (async () => {
      const [items, partners, warehouses] = await Promise.all([
        http.get('/items'), http.get('/partners'), http.get('/warehouses')
      ])
      Object.assign(s, { items, partners, warehouses, loaded: true })
    })()
    return pending
  }

  // 等基础数据到位。弹窗要用它设默认值：网络慢时用户可能先点开弹窗，默认仓库就一直是空的。
  // 加载失败时接口层已提示，这里照常往下走，让用户自己选
  async function ready() {
    if (s.loaded) return
    await (pending || load()).catch(() => {})
  }

  const materials = computed(() => s.items.filter((i) => i.itemType !== 'FG' && i.active))
  const finished = computed(() => s.items.filter((i) => i.itemType === 'FG' && i.active))
  const suppliers = computed(() => s.partners.filter((p) => p.kind === 'SUPPLIER'))
  const factories = computed(() => s.partners.filter((p) => p.kind === 'OEM' || p.kind === 'OWN_FACTORY'))
  const customers = computed(() => s.partners.filter((p) => p.kind === 'CUSTOMER'))
  const ownWarehouses = computed(() => s.warehouses.filter((w) => w.kind === 'OWN'))
  // 除退货隔离区以外的仓库：调拨、盘点、出库可选；退货隔离区的货只能走退货处置
  const stockWarehouses = computed(() => s.warehouses.filter((w) => w.kind !== 'RETURN'))
  const returnWarehouses = computed(() => s.warehouses.filter((w) => w.kind === 'RETURN'))
  const item = (id) => s.items.find((i) => i.id === id)

  return { s, load, ready, materials, finished, suppliers, factories, customers, ownWarehouses, stockWarehouses, returnWarehouses, item }
}
