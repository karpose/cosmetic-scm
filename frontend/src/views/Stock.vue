<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>库存</h2>
        <p>按批次、仓库查看结存。剩余效期低于 50% 标黄、低于 1/3 标红；出库分配时还会按客户的效期接收标准过滤。点击批号可查看追溯和库存流水。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="expiry-risk" label="导出效期风险" />
        <ExportButton report="stock" />
      </div>
    </div>

    <div v-if="risk" class="kpi-grid">
      <div class="kpi-card clickable" :class="{ warn: risk.expiredCount > 0, active: riskFilter === 'EXPIRED' }" @click="toggleRisk('EXPIRED')">
        <div class="muted small">已过期批次</div>
        <div class="kpi-val mono">{{ risk.expiredCount }}</div>
        <div v-if="risk.canSeeValue" class="muted small mono">货值 ¥{{ fmtAmt(risk.expiredValue) }}</div>
      </div>
      <div class="kpi-card clickable" :class="{ warn: risk.criticalCount > 0, active: riskFilter === 'CRITICAL' }" @click="toggleRisk('CRITICAL')">
        <div class="muted small">高危临期（剩余 &lt; 1/3）</div>
        <div class="kpi-val mono">{{ risk.criticalCount }}</div>
        <div v-if="risk.canSeeValue" class="muted small mono">货值 ¥{{ fmtAmt(risk.criticalValue) }}</div>
      </div>
      <div class="kpi-card clickable" :class="{ active: riskFilter === 'NEAR' }" @click="toggleRisk('NEAR')">
        <div class="muted small">临期预警（1/3 ~ 50%）</div>
        <div class="kpi-val mono">{{ risk.nearCount }}</div>
        <div v-if="risk.canSeeValue" class="muted small mono">货值 ¥{{ fmtAmt(risk.nearValue) }}</div>
      </div>
      <div class="kpi-card clickable" :class="{ active: riskFilter === 'DUE_90D' }" @click="toggleRisk('DUE_90D')">
        <div class="muted small">90 天内到期</div>
        <div class="kpi-val mono">{{ risk.due90dCount }}</div>
        <div v-if="risk.canSeeValue" class="muted small mono">货值 ¥{{ fmtAmt(risk.due90dValue) }}</div>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="toolbar">
          <el-select aria-label="按类型筛选" id="st-type" v-model="f.type" placeholder="全部类型" clearable style="width:120px" @change="load">
            <el-option value="FG" label="成品" /><el-option value="RM" label="原料" /><el-option value="PK" label="包材" />
          </el-select>
          <el-select aria-label="按仓库筛选" id="st-wh" v-model="f.warehouseId" placeholder="全部仓库" clearable style="width:160px" @change="load">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.kind === 'RETURN' ? `${w.name}（待处置退货）` : w.name" />
          </el-select>
          <el-select aria-label="按效期风险筛选" v-model="riskFilter" placeholder="全部效期状态" clearable style="width:150px">
            <el-option value="EXPIRED" label="已过期" />
            <el-option value="CRITICAL" label="高危 (<1/3)" />
            <el-option value="NEAR" label="临期 (1/3~50%)" />
            <el-option value="DUE_90D" label="90 天内到期" />
          </el-select>
          <el-input id="st-q" v-model="f.q" placeholder="搜索物料或批号" clearable style="width:220px" @change="load" />
          <el-checkbox id="st-zero" v-model="f.includeZero" @change="load">显示已用完批次</el-checkbox>
          <el-button size="small" :disabled="!picked.length" @click="printLabels">打印标签（{{ picked.length }}）</el-button>
        </div>
      </template>
      <LoadError v-if="error" :message="error" :loading="loading" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loading" empty-text="没有符合条件的库存" @selection-change="(s) => (picked = s)">
        <el-table-column type="selection" width="40" />
        <el-table-column label="物料" min-width="180"><template #default="{ row }">{{ row.itemName }} <span class="muted small mono">{{ row.itemCode }}</span></template></el-table-column>
        <el-table-column label="类型" width="70"><template #default="{ row }">{{ ITEM_TYPE[row.itemType] }}</template></el-table-column>
        <el-table-column label="批号" width="150"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
        <el-table-column prop="warehouseName" label="仓库" width="120" />
        <el-table-column label="数量" width="130" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }} {{ row.unit }}</span></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
        <el-table-column label="有效期至" width="110"><template #default="{ row }"><span class="mono">{{ fmtDate(row.expDate) }}</span></template></el-table-column>
        <el-table-column label="剩余效期" width="130"><template #default="{ row }"><ShelfBar :ratio="row.shelfRatio" /></template></el-table-column>
      </el-table>
      <ListCapHint :count="rows.length" :cap="5000" />
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import ShelfBar from '../components/ShelfBar.vue'
import StatusTag from '../components/StatusTag.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import { ITEM_TYPE, fmtDate, fmtQty } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { usePaged } from '../utils/table'

const f = reactive({ type: '', warehouseId: null, q: '', includeZero: false })
const rows = ref([])
const risk = ref(null)
const riskFilter = ref('')
const warehouses = ref([])
const loading = ref(false)
const error = ref('')
const riskMap = computed(() => {
  const m = new Map()
  for (const r of risk.value?.riskRows || []) m.set(`${r.batchNo}|${r.warehouseName}`, r.riskLevel)
  return m
})
const t = usePaged(rows, {
  pageSize: 50,
  extra: (r) => !riskFilter.value || riskMap.value.get(`${r.batchNo}|${r.warehouseName}`) === riskFilter.value
})
const router = useRouter()
const picked = ref([])
// 同一批次在多个仓库时只打一次
const printLabels = () => router.push({ path: '/labels', query: { batches: [...new Set(picked.value.map((r) => r.batchNo))].join(',') } })
const toggleRisk = (level) => { riskFilter.value = riskFilter.value === level ? '' : level }
const fmtAmt = (v) => (v == null ? '0.00' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))

const listReq = latestOnly()

async function load() {
  const current = listReq()
  loading.value = true
  error.value = ''
  try {
    const params = { includeZero: f.includeZero }
    if (f.type) params.type = f.type
    if (f.warehouseId) params.warehouseId = f.warehouseId
    if (f.q) params.q = f.q
    const [res, rk] = await Promise.all([
      http.get('/stock', { params }),
      http.get('/analytics/expiry-risk')
    ])
    if (current()) {
      rows.value = res
      risk.value = rk
    }
  } catch (e) {
    if (current()) error.value = e.message
  } finally {
    if (current()) loading.value = false
  }
}

onMounted(async () => {
  warehouses.value = await http.get('/warehouses')
  load()
})
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
.kpi-card { padding: 14px 16px; border-radius: 14px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); display: grid; gap: 4px; transition: border-color .15s; }
.kpi-card.clickable { cursor: pointer; }
.kpi-card.warn { border-color: var(--el-color-danger-light-5); }
.kpi-card.active { border-color: var(--el-color-primary); box-shadow: 0 0 0 1px var(--el-color-primary-light-5); }
.kpi-val { font-size: 20px; font-weight: 600; }
</style>
