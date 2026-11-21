<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>上线检查</h2>
        <p>按上线切换规程（SOP-09）实时检查还缺什么。“必须处理”不解决主线走不通，比如没有质量安全负责人账号就没人能放行；“建议处理”不影响运行，但会影响数据质量或提醒。这里只是系统能查到的部分，盘点和备案资料的核对还要人工完成。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="golive-checklist" />
        <el-button @click="$router.push('/golive-print')">打印签字报告</el-button>
        <el-button :loading="loader.loading.value" @click="load">重新检查</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <template v-else-if="data">
      <el-alert v-if="data.blocking" type="error" :closable="false" show-icon :title="`还有 ${data.blocking} 项必须处理，系统还不能投入使用`" />
      <el-alert v-else-if="data.warnings" type="warning" :closable="false" show-icon :title="`必须处理的都已完成，还有 ${data.warnings} 项建议处理`" />
      <el-alert v-else type="success" :closable="false" show-icon title="系统能查到的项目都已就绪，可以按 SOP-09 做最后的人工核对" />

      <el-card v-for="g in groups" :key="g.name" shadow="never" body-style="padding:4px 18px 8px">
        <template #header><b>{{ g.name }}</b></template>
        <div v-for="c in g.checks" :key="c.title" class="row">
          <el-icon class="ic" :class="c.state"><component :is="ICONS[c.state]" /></el-icon>
          <div class="body">
            <div class="t"><b>{{ c.title }}</b> <el-tag v-if="c.state !== 'OK'" :type="c.state === 'BLOCK' ? 'danger' : 'warning'" size="small" disable-transitions>{{ c.state === 'BLOCK' ? '必须处理' : '建议处理' }}</el-tag></div>
            <div class="small" :class="{ muted: c.state === 'OK' }">{{ c.detail }}</div>
          </div>
          <el-button v-if="c.route && c.state !== 'OK'" size="small" @click="$router.push(c.route)">去处理</el-button>
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { CircleCheckFilled, CircleCloseFilled, WarningFilled } from '@element-plus/icons-vue'
import http from '../api'
import ExportButton from '../components/ExportButton.vue'
import LoadError from '../components/LoadError.vue'
import { useLoader } from '../utils/loader'

const ICONS = { OK: CircleCheckFilled, WARN: WarningFilled, BLOCK: CircleCloseFilled }
const data = ref(null)
const groups = computed(() => {
  const map = new Map()
  for (const c of data.value?.checks || []) {
    if (!map.has(c.group)) map.set(c.group, [])
    map.get(c.group).push(c)
  }
  return [...map.entries()].map(([name, checks]) => ({ name, checks }))
})
const loader = useLoader(async () => {
  data.value = await http.get('/golive')
})
const load = () => loader.run()
onMounted(load)
</script>

<style scoped>
.row { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-top: 1px solid var(--lg-stroke); }
.row:first-child { border-top: 0; }
.ic { font-size: 18px; flex: none; }
.ic.OK { color: var(--el-color-success); }
.ic.WARN { color: var(--el-color-warning); }
.ic.BLOCK { color: var(--el-color-danger); }
.body { flex: 1; min-width: 0; display: grid; gap: 2px; overflow-wrap: anywhere; }
.t { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
</style>
