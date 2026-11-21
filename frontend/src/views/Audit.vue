<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>审计日志</h2>
        <p>每次新增、收货、发料、签批、出库、冻结和账号变更都会记录操作人、时间和原因。日志只增不改，系统不提供修改或删除入口。</p>
      </div>
      <ExportButton report="audit" :params="exportParams" />
    </div>
    <div class="stat-bar">
      <div class="stat-pill"><span class="muted small">当前范围记录</span><b class="mono">{{ t.total.value }}</b></div>
      <div class="stat-pill"><span class="muted small">涉及操作人</span><b class="mono">{{ operatorCount }}</b></div>
      <div class="stat-pill"><span class="muted small">涉及操作类型</span><b class="mono">{{ actions.length }}</b></div>
      <div class="stat-pill">
        <span class="muted small">价格隔离状态</span>
        <el-tag :type="canSeePrice ? 'success' : 'info'" size="small" disable-transitions>
          {{ canSeePrice ? '含涉价明细' : '涉价明细已自动遮盖' }}
        </el-tag>
      </div>
    </div>
    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input id="au-q" v-model="q" placeholder="按批号、单号、操作或账号搜索（回车查询）" clearable style="width:300px" @change="load" />
          <el-select aria-label="按操作筛选" v-model="action" placeholder="全部操作" clearable filterable>
            <el-option v-for="a in actions" :key="a" :value="a" :label="a" />
          </el-select>
          <el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:260px;flex:none" @change="load" />
          <span class="muted small">显示该范围内最近 300 条；导出按当前筛选条件与日期范围全量导出</span>
        </div>
      </template>
      <LoadError v-if="error" :message="error" :loading="loading" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loading" empty-text="没有记录">
        <el-table-column label="时间" width="150"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
        <el-table-column label="操作人" width="160"><template #default="{ row }">{{ row.displayName }} <span class="muted small">{{ row.username }}</span></template></el-table-column>
        <el-table-column prop="action" label="操作" width="130" />
        <el-table-column label="对象" width="200"><template #default="{ row }"><span class="mono small">{{ row.target }}</span></template></el-table-column>
        <el-table-column prop="detail" label="内容" min-width="240" />
        <el-table-column prop="reason" label="原因" min-width="180" />
      </el-table>
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>
  </div>
</template>

<script setup>
import ExportButton from '../components/ExportButton.vue'
import { computed, onMounted, ref } from 'vue'
import http from '../api'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import { useAuth } from '../stores/auth'
import { fmtTime } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { usePaged } from '../utils/table'

const auth = useAuth()
const canSeePrice = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'FINANCE'))
const q = ref('')
const action = ref('')
const dates = ref(null)
const range = computed(() => (dates.value ? { from: dates.value[0], to: dates.value[1] } : {}))
const exportParams = computed(() => ({
  ...range.value,
  ...(q.value.trim() ? { q: q.value.trim() } : {}),
  ...(action.value ? { action: action.value } : {})
}))
const rows = ref([])
const loading = ref(false)
const error = ref('')
const actions = computed(() => [...new Set(rows.value.map((r) => r.action))].sort())
const operatorCount = computed(() => new Set(rows.value.map((r) => r.username).filter(Boolean)).size)
const t = usePaged(rows, { extra: (r) => !action.value || r.action === action.value, pageSize: 50 })

const listReq = latestOnly()

async function load() {
  const current = listReq()
  loading.value = true
  error.value = ''
  try {
    const res = await http.get('/audit', { params: { limit: 300, ...range.value, ...(q.value ? { q: q.value } : {}) } })
    if (current()) rows.value = res
  } catch (e) {
    if (current()) error.value = e.message
  } finally {
    if (current()) loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.stat-bar { display: flex; gap: 12px; flex-wrap: wrap; }
.stat-pill { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 12px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); }
</style>
