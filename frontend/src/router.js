import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'
import AppLayout from './layout/AppLayout.vue'

const routes = [
  { path: '/login', name: 'login', component: () => import('./views/Login.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', name: 'dashboard', component: () => import('./views/Dashboard.vue'), meta: { title: '总览' } },
      { path: 'purchase', name: 'purchase', component: () => import('./views/Purchase.vue'), meta: { title: '采购入库' } },
      { path: 'production', name: 'production', component: () => import('./views/Production.vue'), meta: { title: '生产与委托' } },
      { path: 'quality', name: 'quality', component: () => import('./views/Quality.vue'), meta: { title: '质量放行' } },
      { path: 'stock', name: 'stock', component: () => import('./views/Stock.vue'), meta: { title: '库存' } },
      { path: 'sales', name: 'sales', component: () => import('./views/Sales.vue'), meta: { title: '销售出库' } },
      { path: 'trace', name: 'trace', component: () => import('./views/Trace.vue'), meta: { title: '批次追溯' } },
      { path: 'audit', name: 'audit', component: () => import('./views/Audit.vue'), meta: { title: '审计日志' } },
      { path: 'master', name: 'master', component: () => import('./views/Master.vue'), meta: { title: '主数据' } },
      { path: 'users', name: 'users', component: () => import('./views/Users.vue'), meta: { title: '账号与权限', roles: ['ADMIN'] } }
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
  if (to.meta.roles && !auth.hasAny(...to.meta.roles)) return { name: 'dashboard' }
  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '首页'} · 化妆品批次主线系统`
})

export default router
