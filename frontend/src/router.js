import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuth } from './stores/auth'
import AppLayout from './layout/AppLayout.vue'

const routes = [
  { path: '/login', name: 'login', component: () => import('./views/Login.vue'), meta: { public: true, title: '登录' } },
  // 批次标签打印页：不套主布局，白底便于打印；需要登录
  { path: '/batch-record', name: 'batch-record', component: () => import('./views/BatchRecordPrint.vue'), meta: { title: '批生产记录' } },
  { path: '/labels', name: 'labels', component: () => import('./views/Labels.vue'), meta: { title: '批次标签' } },
  // 消费者扫码页：不需要登录，二维码里的地址是 /v/防伪码
  { path: '/v/:code?', name: 'verify', component: () => import('./views/Verify.vue'), meta: { public: true, title: '正品查询' } },
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', name: 'dashboard', component: () => import('./views/Dashboard.vue'), meta: { title: '总览' } },
      { path: 'analytics', name: 'analytics', component: () => import('./views/Analytics.vue'), meta: { title: '经营看板' } },
      { path: 'planning', name: 'planning', component: () => import('./views/Planning.vue'), meta: { title: '补货建议' } },
      { path: 'docs', name: 'docs', component: () => import('./views/Docs.vue'), meta: { title: '受控文件' } },
      { path: 'changes', name: 'changes', component: () => import('./views/Changes.vue'), meta: { title: '变更控制' } },
      { path: 'npd', name: 'npd', component: () => import('./views/Npd.vue'), meta: { title: '新品立项' } },
      { path: 'specs', name: 'specs', component: () => import('./views/Specs.vue'), meta: { title: '质量标准' } },
      { path: 'stability', name: 'stability', component: () => import('./views/Stability.vue'), meta: { title: '稳定性试验' } },
      { path: 'compliance', name: 'compliance', component: () => import('./views/Compliance.vue'), meta: { title: '合规资料' } },
      { path: 'supplier-returns', name: 'supplier-returns', component: () => import('./views/SupplierReturns.vue'), meta: { title: '退供' } },
      { path: 'sourcing', name: 'sourcing', component: () => import('./views/Sourcing.vue'), meta: { title: '询价比价', roles: ['ADMIN', 'PURCHASE', 'FINANCE'] } },
      { path: 'settlement', name: 'settlement', component: () => import('./views/Settlement.vue'), meta: { title: '对账结算', roles: ['ADMIN', 'PURCHASE', 'FINANCE'] } },
      { path: 'serials', name: 'serials', component: () => import('./views/Serials.vue'), meta: { title: '一物一码' } },
      { path: 'qms', name: 'qms', component: () => import('./views/Qms.vue'), meta: { title: '质量体系' } },
      { path: 'formulas', name: 'formulas', component: () => import('./views/Formulas.vue'), meta: { title: '配方与备案' } },
      { path: 'notifications', name: 'notifications', component: () => import('./views/Notifications.vue'), meta: { title: '消息提醒', roles: ['ADMIN'] } },
      { path: 'golive', name: 'golive', component: () => import('./views/GoLive.vue'), meta: { title: '上线检查', roles: ['ADMIN', 'MASTER_DATA', 'QA', 'QSO'] } },
      { path: 'scan', name: 'scan', component: () => import('./views/Scan.vue'), meta: { title: '扫码作业' } },
      { path: 'purchase', name: 'purchase', component: () => import('./views/Purchase.vue'), meta: { title: '采购入库' } },
      { path: 'production', name: 'production', component: () => import('./views/Production.vue'), meta: { title: '生产与委托' } },
      { path: 'quality', name: 'quality', component: () => import('./views/Quality.vue'), meta: { title: '质量放行' } },
      { path: 'stock', name: 'stock', component: () => import('./views/Stock.vue'), meta: { title: '库存' } },
      { path: 'inventory-ops', name: 'inventory-ops', component: () => import('./views/InventoryOps.vue'), meta: { title: '库存作业' } },
      { path: 'sales', name: 'sales', component: () => import('./views/Sales.vue'), meta: { title: '销售出库' } },
      { path: 'returns', name: 'returns', component: () => import('./views/Returns.vue'), meta: { title: '销售退货' } },
      { path: 'trace', name: 'trace', component: () => import('./views/Trace.vue'), meta: { title: '批次追溯' } },
      { path: 'quality-events', name: 'quality-events', component: () => import('./views/QualityEvents.vue'), meta: { title: '投诉与召回' } },
      { path: 'audit', name: 'audit', component: () => import('./views/Audit.vue'), meta: { title: '审计日志' } },
      { path: 'master', name: 'master', component: () => import('./views/Master.vue'), meta: { title: '主数据' } },
      { path: 'users', name: 'users', component: () => import('./views/Users.vue'), meta: { title: '账号与权限', roles: ['ADMIN'] } },
      { path: 'dealer-orders', name: 'dealer-orders', component: () => import('./views/DealerOrders.vue'), meta: { title: '经销商订单' } },
      { path: 'integration', name: 'integration', component: () => import('./views/Integration.vue'), meta: { title: '电商对接' } },
      { path: 'portal/oem', name: 'portal-oem', component: () => import('./views/PortalOem.vue'), meta: { title: '代工厂门户', portal: 'OEM_PORTAL' } },
      { path: 'portal/dealer', name: 'portal-dealer', component: () => import('./views/PortalDealer.vue'), meta: { title: '经销商门户', portal: 'DEALER' } },
      { path: 'portal/supplier', name: 'portal-supplier', component: () => import('./views/PortalSupplier.vue'), meta: { title: '供应商门户', portal: 'SUPPLIER_PORTAL' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(async (to) => {
  const auth = useAuth()
  if (to.meta.public) return true
  if (!auth.loggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      auth.logout()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  // 门户账号只能进自己的门户；内部账号进不了门户页
  if (auth.portalHome) {
    if (to.meta.portal && auth.hasAny(to.meta.portal)) return true
    return auth.portalHome
  }
  if (to.meta.portal) return { name: 'dashboard' }
  if (to.meta.roles && !auth.hasAny(...to.meta.roles)) return { name: 'dashboard' }
  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '首页'} · 化妆品批次主线系统`
})

// 升级后，开着的旧页面里记的文件名已经不存在，点菜单会下载失败、毫无反应。
// 刷新一次就拿到新版本；刚刷新过又失败说明是网络问题，只提示，不反复刷新
const CHUNK_ERROR = /dynamically imported module|Importing a module script failed|Unable to preload CSS/i
const RELOAD_KEY = 'scm_chunk_reload_at'

router.onError((err, to) => {
  if (!CHUNK_ERROR.test(err?.message || '')) return
  const last = Number(sessionStorage.getItem(RELOAD_KEY) || 0)
  if (Date.now() - last > 30000) {
    sessionStorage.setItem(RELOAD_KEY, String(Date.now()))
    window.location.assign(to.fullPath)
  } else {
    ElMessage.error('页面加载失败，请检查网络后刷新重试')
  }
})

export default router
