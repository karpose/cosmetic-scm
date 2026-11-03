import { computed, reactive } from 'vue'
import http from '../api'

// 主数据下拉选项，各页面按需加载
export function useRefData() {
  const s = reactive({ items: [], partners: [], warehouses: [], loaded: false })

  async function load() {
    const [items, partners, warehouses] = await Promise.all([
      http.get('/items'), http.get('/partners'), http.get('/warehouses')
    ])
    Object.assign(s, { items, partners, warehouses, loaded: true })
  }

  const materials = computed(() => s.items.filter((i) => i.itemType !== 'FG' && i.active))
  const finished = computed(() => s.items.filter((i) => i.itemType === 'FG' && i.active))
  const suppliers = computed(() => s.partners.filter((p) => p.kind === 'SUPPLIER'))
  const factories = computed(() => s.partners.filter((p) => p.kind === 'OEM' || p.kind === 'OWN_FACTORY'))
  const customers = computed(() => s.partners.filter((p) => p.kind === 'CUSTOMER'))
  const ownWarehouses = computed(() => s.warehouses.filter((w) => w.kind === 'OWN'))
  const item = (id) => s.items.find((i) => i.id === id)

  return { s, load, materials, finished, suppliers, factories, customers, ownWarehouses, item }
}
