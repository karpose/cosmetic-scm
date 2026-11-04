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
  VIEWER: '只读',
  OEM_PORTAL: '代工厂门户',
  DEALER: '经销商门户'
}

// 外部门户角色：只能用对应门户，不能和内部角色同时分配
export const PORTAL_ROLES = { OEM_PORTAL: { home: '/portal/oem', partnerKind: 'OEM' }, DEALER: { home: '/portal/dealer', partnerKind: 'CUSTOMER' } }
export const PASSWORD_RULE = '至少 8 位，大写字母、小写字母、数字、符号中至少三类，且不能包含账号名'

export const useAuth = defineStore('auth', {
  state: () => ({ user: null, token: localStorage.getItem(TOKEN_KEY) }),
  getters: {
    loggedIn: (s) => !!s.token,
    roles: (s) => s.user?.roles || [],
    // 门户账号的首页；内部账号为 null
    portalHome: (s) => (s.user?.roles || []).map((r) => PORTAL_ROLES[r]?.home).find(Boolean) || null
  },
  actions: {
    hasAny(...roles) {
      return roles.some((r) => this.roles.includes(r))
    },
    // 能上传附件：除只读外的内部岗位（与后端 AttachmentController 一致）
    canAttach() {
      return this.hasAny('ADMIN', 'MASTER_DATA', 'PURCHASE', 'WAREHOUSE', 'PRODUCTION', 'QA', 'QSO', 'SALES')
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
    async changePassword(oldPassword, newPassword) {
      // 改密后旧令牌失效，换用接口返回的新令牌
      const resp = await http.post('/auth/password', { oldPassword, newPassword })
      this.token = resp.token
      this.user = resp.user
      localStorage.setItem(TOKEN_KEY, resp.token)
    },
    logout() {
      this.token = null
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})
