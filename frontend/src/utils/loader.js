import { ref } from 'vue'

/** 统一的加载状态：loading、error（失败原因）和带重试的 run。错误提示已由 api.js 弹出，这里只保留给页面展示。 */
export function useLoader(fn) {
  const loading = ref(false)
  const error = ref('')

  async function run(...args) {
    loading.value = true
    error.value = ''
    try {
      return await fn(...args)
    } catch (e) {
      error.value = e?.message || '加载失败'
      return undefined
    } finally {
      loading.value = false
    }
  }

  return { loading, error, run }
}
