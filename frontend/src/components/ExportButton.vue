<template>
  <el-button :icon="Download" :loading="busy" @click="run">导出 Excel</el-button>
</template>

<script setup>
import { ref } from 'vue'
import { Download } from '@element-plus/icons-vue'
import http from '../api'

// 导出在服务端生成，不受页面筛选和分页影响；每次导出都写审计日志
const props = defineProps({ report: { type: String, required: true } })
const busy = ref(false)

async function run() {
  busy.value = true
  try {
    const res = await http.get(`/reports/${props.report}`, { responseType: 'blob', fullResponse: true })
    const match = /filename\*=UTF-8''([^;]+)/i.exec(res.headers['content-disposition'] || '')
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    a.download = match ? decodeURIComponent(match[1]) : `${props.report}.xlsx`
    a.click()
    URL.revokeObjectURL(url)
  } finally {
    busy.value = false
  }
}
</script>
