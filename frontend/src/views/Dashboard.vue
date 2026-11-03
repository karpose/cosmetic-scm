<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>总览</h2>
        <p>一期主线：物料入库后先检验放行，生产完工后由品牌方上市放行，出库按先到期先出分配批次，全程可按批号追溯。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <LoadError v-if="error" :message="error" :loading="loading" @retry="load" />

    <div v-if="data" class="tiles">
      <router-link v-for="s in stages" :key="s.k" class="tile glass" :class="[s.tone, { hot: s.hot }]" :to="s.to">
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
import LoadError from '../components/LoadError.vue'
import { fmtTime } from '../utils/format'

const data = ref(null)
const loading = ref(false)
const error = ref('')

const stages = computed(() => {
  const c = data.value.counts
  return [
    { k: '采购未收货', v: c.openPurchaseOrders, d: '张采购单', to: '/purchase' },
    { k: '物料待检', v: c.quarantine, d: '个批次', to: '/quality', hot: c.quarantine > 0 },
    { k: '可用物料', v: c.availableMaterialBatches, d: '个批次', to: '/stock' },
    { k: '生产中', v: c.wipOrders, d: '张生产 / 委托单', to: '/production' },
    { k: '待上市放行', v: c.pendingRelease, d: '个成品批次', to: '/quality', hot: c.pendingRelease > 0 },
    { k: '近 30 天出库', v: c.shipped30d, d: '张出库单', to: '/sales' },
    { k: '投诉处理中', v: c.openComplaints, d: '件', to: '/quality-events', hot: c.openComplaints > 0, tone: 'warn' },
    { k: '召回进行中', v: c.openRecalls, d: '个', to: '/quality-events?tab=recalls', hot: c.openRecalls > 0, tone: 'bad' },
    { k: '退货待处置', v: c.pendingReturns, d: '行', to: '/returns', hot: c.pendingReturns > 0, tone: 'warn' }
  ]
})

const tagType = (level) => ({ warn: 'warning', info: 'primary', bad: 'danger', muted: 'info' }[level] || 'info')
const isBatch = (t) => ['QC', 'RELEASE', 'NEAR_EXPIRY', 'RECALL'].includes(t.kind)

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await http.get('/dashboard')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; }
.tile { padding: 14px 16px 12px; text-decoration: none; color: inherit; min-width: 0; border-radius: 18px; transition: transform .2s ease, box-shadow .2s ease; }
.tile:hover { transform: translateY(-2px); box-shadow: var(--lg-highlight), var(--lg-shadow-pop); }
.tile .k, .tile .d { font-size: 12px; color: var(--el-text-color-secondary); }
.tile .v { font-size: 30px; font-weight: 650; margin: 6px 0 0; letter-spacing: -0.02em; }
.tile.hot .v { color: var(--el-color-primary); }
.tile.warn.hot .v { color: var(--el-color-warning); }
.tile.bad.hot .v { color: var(--el-color-danger); }
.tile.hot::before { content: ""; position: absolute; top: 14px; right: 14px; width: 8px; height: 8px; border-radius: 50%; background: currentColor; color: var(--el-color-primary); box-shadow: 0 0 0 4px var(--lg-tint); }
.tile.warn.hot::before { color: var(--el-color-warning); }
.tile.bad.hot::before { color: var(--el-color-danger); }
.grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.card-h { display: flex; justify-content: space-between; align-items: center; }
.list { list-style: none; margin: -8px -20px; padding: 0; }
.list li { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 11px 20px; border-bottom: 1px solid var(--lg-hairline); flex-wrap: wrap; transition: background .2s ease; }
.list li:hover { background: var(--lg-fill); }
.list li:last-child { border-bottom: 0; }
.t { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; min-width: 0; }
@media (max-width: 900px) { .grid2 { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 520px) { .tiles { grid-template-columns: repeat(2, minmax(0, 1fr)); } .tile .v { font-size: 24px; } }
</style>
