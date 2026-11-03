import { defineStore } from 'pinia'
import http, { TOKEN_KEY } from '../api'

export const ROLE_LABELS = {
  ADMIN: '系统管理员',
  MASTER_DATA: '主数据管理',
  PURCHASE: '采购',
  WAREHOUSE: '仓库',
  PRODUCTION: '生产计划',
  QA: '质量检验',
  QSO: '质量安全负责人',
  SALES: '销售',
  VIEWER: '只读'
}

export const useAuth = defineStore('auth', {
  state: () => ({ user: null, token: localStorage.getItem(TOKEN_KEY) }),
  getters: {
    loggedIn: (s) => !!s.token,
    roles: (s) => s.user?.roles || []
  },
  actions: {
    hasAny(...roles) {
      return roles.some((r) => this.roles.includes(r))
    },
    async login(username, password) {
      const resp = await http.post('/auth/login', { username, password })
      this.token = resp.token
      this.user = resp.user
      localStorage.setItem(TOKEN_KEY, resp.token)
    },
    async fetchMe() {
      this.user = await http.get('/auth/me', { silent: true })
    },
    logout() {
      this.token = null
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})
