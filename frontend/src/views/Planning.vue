<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>补货建议</h2>
        <p>按近期出库算日均需求，用再订货点法给出成品建议生产 / 委托量；勾选后可按 BOM 展开算品牌供料的净采购量（扣在库、待检、在途和已计划未发料的占用）。生成的生产单、采购单都是草稿，走原有的全部校验。</p>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:14px 18px">
      <div class="params">
        <label>观察近 <el-input-number v-model="p.windowDays" :min="7" :max="365" :controls="false" size="small" /> 天出库</label>
        <label>交付周期 <el-input-number v-model="p.leadDays" :min="0" :max="180" :controls="false" size="small" /> 天</label>
        <label>安全库存 <el-input-number v-model="p.safetyDays" :min="0" :max="180" :controls="false" size="small" /> 天</label>
        <label>每次补够 <el-input-number v-model="p.coverDays" :min="0" :max="365" :controls="false" size="small" /> 天</label>
        <label><el-switch id="pl-forecast" v-model="p.useForecast" @change="load" /> 按未来 3 个月预测算日均</label>
        <el-button :loading="loader.loading.value" @click="load">重新计算</el-button>
      </div>
      <p class="muted small formula">再订货点 = 日均 × 交付周期 + 日均 × 安全天数；预计可用（可售 + 待放行 + 在制）低于再订货点时，补到“再订货点 + 日均 × 补够天数”，按 100 件取整。</p>
    </el-card>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="card-h">
          <span><b>成品</b> <span v-if="fgPlanValue" class="muted small">勾选计划的物料成本约 <b class="mono">{{ fmtMoney(fgPlanValue) }}</b></span></span>
          <span class="toolbar">
            <span class="muted small">已选 {{ chosen.length }} 个</span>
            <el-button :disabled="!chosen.length" :loading="m.loading" @click="calcMaterials">计算物料需求</el-button>
            <el-button v-if="canMo" type="primary" :disabled="!chosen.length" :loading="creatingMo" @click="createMos">生成生产 / 委托单草稿</el-button>
          </span>
        </div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table v-else ref="fgTable" :data="rows" v-loading="loader.loading.value" empty-text="没有在售成品" @selection-change="(s) => (chosen = s)">
        <el-table-column type="selection" width="44" />
        <el-table-column label="成品" min-width="170"><template #default="{ row }">{{ row.name }}<div class="muted small mono">{{ row.code }}</div></template></el-table-column>
        <el-table-column label="近 3 个月净出库（已扣退货）" width="220">
          <template #default="{ row }"><span v-for="mq in row.monthly" :key="mq.month" class="mq"><span class="muted small">{{ mq.month.slice(5) }}月</span> <span class="mono">{{ fmtQty(mq.qty) }}</span></span></template>
        </el-table-column>
        <el-table-column label="未来 3 个月预测" width="220">
          <template #default="{ row }">
            <template v-if="row.forecastMethod !== '无历史'"><span v-for="mq in row.forecast" :key="mq.month" class="mq"><span class="muted small">{{ mq.month.slice(5) }}月</span> <span class="mono">{{ fmtQty(mq.qty) }}</span></span></template>
            <div class="muted small">{{ row.forecastMethod }}</div>
          </template>
        </el-table-column>
        <el-table-column label="日均" width="80" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(Number(row.dailyDemand).toFixed(1)) }}</span></template></el-table-column>
        <el-table-column label="可售（临期）" width="120" align="right">
          <template #default="{ row }"><span class="mono">{{ fmtQty(row.available) }}</span><div v-if="Number(row.nearExpiry)" class="small warn">临期 {{ fmtQty(row.nearExpiry) }}</div></template>
        </el-table-column>
        <el-table-column label="待放行 / 在制" width="120" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.pendingRelease) }} / {{ fmtQty(row.wip) }}</span></template></el-table-column>
        <el-table-column label="可售天数" width="90" align="right"><template #default="{ row }"><span class="mono">{{ row.daysOfSupply ?? '—' }}</span></template></el-table-column>
        <el-table-column label="再订货点" width="95" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.reorderPoint) }}</span></template></el-table-column>
        <el-table-column label="判断" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.belowReorderPoint" type="danger" size="small" disable-transitions><el-icon class="ti"><WarningFilled /></el-icon>需补货</el-tag>
            <el-tag v-else-if="Number(row.dailyDemand) === 0" type="info" size="small" disable-transitions>无出库</el-tag>
            <el-tag v-else type="success" size="small" disable-transitions><el-icon class="ti"><CircleCheckFilled /></el-icon>充足</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="单位成本 / 指导价" width="130" align="right">
          <template #default="{ row }"><span class="mono">{{ fmtMoney(row.unitCost) }}</span><div v-if="row.listPrice" class="muted small mono">{{ fmtMoney(row.listPrice) }}</div></template>
        </el-table-column>
        <el-table-column label="计划数量" width="130">
          <template #default="{ row }"><el-input-number aria-label="建议生产数量" v-model="qty[row.itemId]" :min="0" :step="100" :precision="0" :controls="false" size="small" style="width:100%" /></template>
        </el-table-column>
        <el-table-column label="生产方" width="170">
          <template #default="{ row }">
            <el-select aria-label="生产方" v-model="factory[row.itemId]" size="small" placeholder="选择生产方">
              <el-option v-for="f in rd.factories.value" :key="f.id" :value="f.id" :label="f.name" :disabled="!f.usable" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="m.rows" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="card-h">
          <span><b>品牌供料物料需求</b> <span v-if="buyValue" class="muted small">采购金额约 <b class="mono">{{ fmtMoney(buyValue) }}</b></span></span>
          <span class="toolbar">
            <span class="muted small">需要采购 {{ toBuy.length }} 种，涉及 {{ supplierGroups.length }} 家供应商</span>
            <el-button v-if="canPo" type="primary" :disabled="!toBuy.length" :loading="creatingPo" @click="createPos">按供应商生成采购单</el-button>
          </span>
        </div>
      </template>
      <el-table :data="m.rows" empty-text="所选成品没有品牌供料物料">
        <el-table-column label="物料" min-width="160"><template #default="{ row }">{{ row.name }}<div class="muted small mono">{{ row.code }}</div></template></el-table-column>
        <el-table-column label="用于" min-width="150"><template #default="{ row }"><div v-for="u in row.usedBy" :key="u" class="small">{{ u }}</div></template></el-table-column>
        <el-table-column label="本次需求" width="105" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.gross) }}</span></template></el-table-column>
        <el-table-column label="已计划占用" width="105" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.openOrders) }}</span></template></el-table-column>
        <el-table-column label="在库 / 待检 / 在途" width="160" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.onHand) }} / {{ fmtQty(row.quarantine) }} / {{ fmtQty(row.onOrder) }}</span></template></el-table-column>
        <el-table-column label="协议价 / 标准成本" width="145" align="right">
          <template #default="{ row }">
            <template v-if="row.contractPrice != null">
              <el-tag size="small" type="success" disable-transitions style="margin-right:4px">协议价</el-tag><span class="mono">{{ fmtMoney(row.contractPrice) }}</span>
            </template>
            <span v-else class="mono">{{ fmtMoney(row.unitCost) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="采购数量" width="130">
          <template #default="{ row }"><el-input-number aria-label="建议采购数量" v-model="buy[row.itemId]" :min="0" :precision="row.itemType === 'PK' ? 0 : 4" :controls="false" size="small" style="width:100%" /> <span class="muted small">{{ row.unit }}</span></template>
        </el-table-column>
        <el-table-column label="供应商" width="190">
          <template #default="{ row }">
            <el-select aria-label="供应商" v-model="supplier[row.itemId]" size="small" placeholder="选择供应商">
              <el-option v-for="s in rd.suppliers.value" :key="s.id" :value="s.id" :label="s.name" :disabled="!s.usable" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue'
import http from '../api'
import LoadError from '../components/LoadError.vue'
import { useAuth } from '../stores/auth'
import { daysFromToday, fmtMoney, fmtQty } from '../utils/format'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'

const auth = useAuth()
const router = useRouter()
const rd = useRefData()
const p = reactive({ windowDays: 90, leadDays: 30, safetyDays: 15, coverDays: 60, useForecast: false })
const rows = ref([])
const chosen = ref([])
const fgTable = ref(null)
const qty = reactive({})
const factory = reactive({})
const canMo = computed(() => auth.hasAny('ADMIN', 'PRODUCTION'))
const canPo = computed(() => auth.hasAny('ADMIN', 'PURCHASE'))

// fresh：不用服务端的短时缓存。打开页面可以用缓存，用户主动计算、生成单据之后必须是最新结果
const loader = useLoader(async (fresh = false) => {
  rows.value = await http.get('/planning/finished-goods', { params: { ...p, fresh } })
  for (const r of rows.value) {
    qty[r.itemId] = Number(r.suggested)
    factory[r.itemId] = factory[r.itemId] ?? r.factoryId
  }
  // 默认勾上需要补货的
  await nextTick()
  rows.value.filter((r) => r.belowReorderPoint).forEach((r) => fgTable.value?.toggleRowSelection(r, true))
})
// 用户主动点“重新计算”、切换开关时要最新结果；打开页面可以用服务端的短时缓存
const load = () => loader.run(true)

const planLines = () => chosen.value.filter((r) => qty[r.itemId] > 0).map((r) => ({ fgItemId: r.itemId, qty: qty[r.itemId], factoryId: factory[r.itemId] }))

// ---------- 物料 ----------
const m = reactive({ loading: false, rows: null })
const buy = reactive({})
const supplier = reactive({})
const toBuy = computed(() => (m.rows || []).filter((r) => buy[r.itemId] > 0))
// 金额只算有成本的行；没有成本的物料不计入
const buyValue = computed(() => toBuy.value.reduce((a, r) => {
  const p = r.contractPrice ?? r.unitCost
  return a + (p != null ? Number(p) * buy[r.itemId] : 0)
}, 0))
const fgPlanValue = computed(() => chosen.value.reduce((a, r) => a + (r.unitCost != null ? Number(r.unitCost) * (qty[r.itemId] || 0) : 0), 0))
const supplierGroups = computed(() => [...new Set(toBuy.value.map((r) => supplier[r.itemId]).filter(Boolean))])

async function calcMaterials() {
  const lines = planLines()
  if (!lines.length) return ElMessage.warning('所选成品的计划数量都是 0')
  m.loading = true
  try {
    m.rows = await http.post('/planning/materials', { lines })
    for (const r of m.rows) {
      buy[r.itemId] = Number(r.suggested)
      supplier[r.itemId] = r.supplierUsable ? r.supplierId : null
    }
  } finally {
    m.loading = false
  }
}

// ---------- 生成草稿 ----------
const creatingMo = ref(false)
const creatingPo = ref(false)

async function createMos() {
  const lines = planLines()
  if (lines.some((l) => !l.factoryId)) return ElMessage.warning('请为每个勾选的成品选择生产方')
  if (!lines.length) return ElMessage.warning('所选成品的计划数量都是 0')
  creatingMo.value = true
  try {
    const nos = []
    for (const l of lines) {
      const mo = await http.post('/mfg-orders', { factoryId: l.factoryId, fgItemId: l.fgItemId, plannedQty: l.qty, plannedDate: daysFromToday(p.leadDays) })
      nos.push(mo.moNo)
    }
    ElMessage.success(`已生成 ${nos.join('、')}，到“生产与委托”里发料`)
    router.push('/production')
  } finally {
    creatingMo.value = false
  }
}

async function createPos() {
  if (toBuy.value.some((r) => !supplier[r.itemId])) return ElMessage.warning('请为每个要采购的物料选择供应商')
  creatingPo.value = true
  try {
    const nos = []
    for (const sid of supplierGroups.value) {
      const lines = toBuy.value.filter((r) => supplier[r.itemId] === sid).map((r) => ({
        itemId: r.itemId,
        qty: buy[r.itemId],
        unitPrice: r.supplierId === sid && r.contractPrice != null ? r.contractPrice : null,
        taxRate: r.supplierId === sid && r.contractTaxRate != null ? r.contractTaxRate : null
      }))
      const po = await http.post('/purchase-orders', { supplierId: sid, lines })
      nos.push(po.poNo)
    }
    ElMessage.success(`已生成采购单 ${nos.join('、')}`)
    router.push('/purchase')
  } finally {
    creatingPo.value = false
  }
}

onMounted(() => Promise.all([loader.run(false), rd.load()]))
</script>

<style scoped>
.params { display: flex; gap: 16px; flex-wrap: wrap; align-items: center; }
.params label { display: inline-flex; gap: 6px; align-items: center; font-size: 13px; color: var(--el-text-color-regular); }
.params .el-input-number { width: 72px; }
.formula { margin: 10px 0 0; }
.card-h { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; }
.mq { display: inline-block; margin-right: 8px; }
.warn { color: var(--el-color-warning); }
.ti { vertical-align: -2px; margin-right: 2px; }
</style>
