<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>总览</h2>
        <p>一期主线：物料入库后先检验放行，生产完工后由品牌方上市放行，出库按先到期先出分配批次，全程可按批号追溯。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-if="data" class="line">
      <router-link v-for="s in stages" :key="s.k" class="stage" :class="{ hot: s.hot }" :to="s.to">
        <div class="k">{{ s.k }}</div>
        <div class="v mono">{{ s.v }}</div>
        <div class="d">{{ s.d }}</div>
      </router-link>
    </div>

    <div v-if="data" class="grid2">
      <el-card shadow="never">
        <template #header><div class="card-h"><b>待办</b><span class="muted small">{{ data.todos.length }} 项</span></div></template>
        <el-empty v-if="!data.todos.length" description="没有待办事项" :image-size="60" />
        <ul v-else class="list">
          <li v-for="(t, i) in data.todos" :key="i">
            <span class="t">
              <el-tag :type="tagType(t.level)" size="small" disable-transitions>{{ t.label }}</el-tag>
              <BatchNo v-if="isBatch(t)" :no="t.ref" />
              <span v-else class="mono small">{{ t.ref }}</span>
              <span>{{ t.title }}</span>
              <span class="muted small">{{ t.detail }}</span>
            </span>
            <el-button size="small" @click="$router.push(t.route)">去处理</el-button>
          </li>
        </ul>
      </el-card>
      <el-card shadow="never">
        <template #header><div class="card-h"><b>最近操作</b><router-link class="small" to="/audit">全部审计日志</router-link></div></template>
        <el-empty v-if="!data.recent.length" description="还没有操作记录" :image-size="60" />
        <ul v-else class="list">
          <li v-for="a in data.recent" :key="a.id">
            <span class="t">
              <span class="mono small muted">{{ fmtTime(a.createdAt) }}</span>
              <span>{{ a.displayName || a.username }}</span>
              <b>{{ a.action }}</b>
              <span class="mono small">{{ a.target }}</span>
            </span>
          </li>
        </ul>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import { fmtTime } from '../utils/format'

const data = ref(null)
const loading = ref(false)

const stages = computed(() => {
  const c = data.value.counts
  return [
    { k: '采购未收货', v: c.openPurchaseOrders, d: '张采购单', to: '/purchase' },
    { k: '物料待检', v: c.quarantine, d: '个批次', to: '/quality', hot: c.quarantine > 0 },
    { k: '可用物料', v: c.availableMaterialBatches, d: '个批次', to: '/stock' },
    { k: '生产中', v: c.wipOrders, d: '张生产 / 委托单', to: '/production' },
    { k: '待上市放行', v: c.pendingRelease, d: '个成品批次', to: '/quality', hot: c.pendingRelease > 0 },
    { k: '近 30 天出库', v: c.shipped30d, d: '张出库单', to: '/sales' }
  ]
})

const tagType = (level) => ({ warn: 'warning', info: 'primary', bad: 'danger', muted: 'info' }[level] || 'info')
const isBatch = (t) => ['QC', 'RELEASE', 'NEAR_EXPIRY'].includes(t.kind)

async function load() {
  loading.value = true
  try {
    data.value = await http.get('/dashboard')
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.line { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-bg-color); overflow: hidden; }
.stage { padding: 14px; border-right: 1px solid var(--el-border-color-lighter); text-decoration: none; color: inherit; min-width: 0; }
.stage:last-child { border-right: 0; }
.stage:hover { background: var(--el-fill-color-light); }
.stage .k, .stage .d { font-size: 12px; color: var(--el-text-color-secondary); }
.stage .v { font-size: 26px; font-weight: 600; margin: 4px 0 2px; }
.stage.hot .v { color: var(--el-color-primary); }
.grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.card-h { display: flex; justify-content: space-between; align-items: center; }
.list { list-style: none; margin: -8px -20px; padding: 0; }
.list li { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 10px 20px; border-bottom: 1px solid var(--el-border-color-lighter); flex-wrap: wrap; }
.list li:last-child { border-bottom: 0; }
.t { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; min-width: 0; }
@media (max-width: 900px) {
  .line { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .stage:nth-child(3) { border-right: 0; }
  .stage:nth-child(-n+3) { border-bottom: 1px solid var(--el-border-color-lighter); }
  .grid2 { grid-template-columns: minmax(0, 1fr); }
}
</style>
