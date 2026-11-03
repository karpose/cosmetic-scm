import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
// 接口错误已由 api.js 弹出提示；页面里 try/finally 不再逐个 catch，这里统一吞掉，其它错误照常报出
app.config.errorHandler = (err) => {
  if (!err?.handled) console.error(err)
}
window.addEventListener('unhandledrejection', (e) => {
  if (e.reason?.handled) e.preventDefault()
})
app.use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')

// 跟随系统深色模式
const mq = window.matchMedia('(prefers-color-scheme: dark)')
const applyTheme = () => document.documentElement.classList.toggle('dark', mq.matches)
applyTheme()
mq.addEventListener('change', applyTheme)
