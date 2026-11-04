import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from './router'

export const TOKEN_KEY = 'scm_token'

const http = axios.create({ baseURL: '/api', timeout: 30000 })

http.interceptors.request.use((cfg) => {
  // public：消费者扫码等公开接口不带令牌，过期令牌也就不会把它挡成 401
  const token = cfg.public ? null : localStorage.getItem(TOKEN_KEY)
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

// 统一取 data；后端错误统一为 { message }，直接提示给用户
http.interceptors.response.use(
  (resp) => resp.data,
  (err) => {
    const status = err.response?.status
    const msg = err.response?.data?.message || (status ? `请求失败（${status}）` : '无法连接服务器，请检查网络或后端是否已启动')
    const isLogin = err.config?.url?.endsWith('/auth/login')
    if (status === 401 && !isLogin && !err.config?.public) {
      localStorage.removeItem(TOKEN_KEY)
      const current = router.currentRoute.value
      if (current.name !== 'login') router.push({ name: 'login', query: { redirect: current.fullPath } })
    }
    if (!err.config?.silent) ElMessage.error(msg)
    // handled：已经提示过用户，main.js 的全局错误处理不再重复报到控制台
    return Promise.reject(Object.assign(new Error(msg), { handled: true }))
  }
)

export default http
