<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>库存作业</h2>
        <p>仓间调拨、盘点调整和报废出库。每张单据的数量变化都写入库存流水和审计日志；报废须质量人员再次输入密码签批。退货隔离区的货请在“销售退货”里处置。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="inventory-ops" label="导出作业明细" />
        <el-button v-if="isQso" @click="openingOpen = true">期初库存导入</el-button>
        <el-button v-if="canMove" @click="open('TRANSFER')">仓间调拨</el-button>
        <el-button v-if="canMove" @click="open('ADJUST')">盘点调整</el-button>
        <el-button v-if="canScrap" type="danger" plain @click="open('SCRAP')">报废出库</el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card"><div class="muted small">累计库存作业单</div><div class="kpi-val mono">{{ docs.length }}</div></div>
      <div class="kpi-card"><div class="muted small">仓间调拨</div><div class="kpi-val mono">{{ docs.filter((x) => x.docType === 'TRANSFER').length }}</div></div>
      <div class="kpi-card"><div class="muted small">盘点调整</div><div class="kpi-val mono">{{ docs.filter((x) => x.docType === 'ADJUST').length }}</div></div>
      <div class="kpi-card"><div class="muted small">报废出库</div><div class="kpi-val mono">{{ docs.filter((x) => x.docType === 'SCRAP').length }}</div></div>
    </div>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="type" size="small">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="TRANSFER">调拨</el-radio-button>
            <el-radio-button value="ADJUST">盘点</el-radio-button>
            <el-radio-button value="SCRAP">报废</el-radio-button>
          </el-radio-group>
          <el-input v-model="t.keyword.value" placeholder="搜索单号、批号、物料" clearable />
        </div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table scrollbar-tabindex="0" v-else :data="t.paged.value" v-loading="loader.loading.value" empty-text="还没有库存作业单">
        <el-table-column label="单号" width="150"><template #default="{ row }"><span class="mono">{{ row.docNo }}</span></template></el-table-column>
        <el-table-column label="类型" width="100"><template #default="{ row }"><StatusTag :status="row.docType" :label="row.docTypeLabel" /></template></el-table-column>
        <el-table-column label="仓库" min-width="160">
          <template #default="{ row }">{{ row.fromWarehouseName }}<template v-if="row.toWarehouseName"> → {{ row.toWarehouseName }}</template></template>
        </el-table-column>
        <el-table-column label="明细" min-width="240">
          <template #default="{ row }">
            <div v-for="l in row.lines" :key="l.batchId" class="dl">
              <BatchNo :no="l.batchNo" /> <span class="small">{{ l.itemName }}</span>
              <span v-if="row.docType === 'ADJUST'" class="mono small" :class="Number(l.qty) < 0 ? 'neg' : 'pos'">
                {{ fmtQty(l.bookQty) }} → {{ fmtQty(Number(l.bookQty) + Number(l.qty)) }}（{{ Number(l.qty) > 0 ? '+' : '' }}{{ fmtQty(l.qty) }}）
              </span>
              <span v-else class="mono small">× {{ fmtQty(l.qty) }} {{ l.unit }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
        <el-table-column label="经办 / 签批" width="150">
          <template #default="{ row }">{{ row.createdBy }}<div v-if="row.signedByName" class="muted small">签批：{{ row.signedByName }}</div></template>
        </el-table-column>
        <el-table-column label="时间" width="140"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
      </el-table>
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <el-dialog v-model="d.visible" :title="TITLES[d.mode]" width="760px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item :label="d.mode === 'TRANSFER' ? '调出仓' : '作业仓'">
            <el-select id="op-from" v-model="d.fromId" style="width:100%" @change="loadStock">
              <el-option v-for="w in rd.stockWarehouses.value" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="d.mode === 'TRANSFER'" label="调入仓">
            <el-select id="op-to" v-model="d.toId" style="width:100%">
              <el-option v-for="w in rd.stockWarehouses.value" :key="w.id" :value="w.id" :label="w.name" :disabled="w.id === d.fromId" />
            </el-select>
          </el-form-item>
        </div>

        <!-- 盘点：把该仓账面库存列成盘点表，直接填实盘数 -->
        <template v-if="d.mode === 'ADJUST'">
          <div class="count-head">
            <span class="muted small">共 {{ stock.length }} 个批次在库，差异 {{ countDiffs.length }} 行</span>
            <el-input v-model="d.countFilter" size="small" placeholder="筛选物料或批号" clearable style="width:200px" />
          </div>
          <el-table :data="countRows" max-height="320" size="small" empty-text="该仓没有库存">
            <el-table-column label="物料 / 批号" :min-width="narrow ? 120 : 200">
              <template #default="{ row }">
                {{ row.itemName }} <BatchNo :no="row.batchNo" />
                <div v-if="narrow" class="muted small">账面 <span class="mono">{{ fmtQty(row.qty) }}</span></div>
              </template>
            </el-table-column>
            <!-- 手机上账面并到第一列，实盘不用横向滚动就能填 -->
            <el-table-column v-if="!narrow" label="账面" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span></template></el-table-column>
            <el-table-column label="实盘" :width="narrow ? 110 : 150">
              <template #default="{ row }">
                <el-input-number aria-label="实盘数量" v-model="counted[row.batchId]" :min="0" :controls="false" size="small" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="差异" :width="narrow ? 64 : 100" align="right">
              <template #default="{ row }">
                <span class="mono" :class="diffOf(row) < 0 ? 'neg' : diffOf(row) > 0 ? 'pos' : 'muted'">{{ diffOf(row) > 0 ? '+' : '' }}{{ fmtQty(diffOf(row)) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <!-- 调拨、报废：逐行选批次 -->
        <el-form-item v-else label="批次与数量">
          <div style="width:100%">
            <div v-for="(l, i) in d.lines" :key="i" class="line-row">
              <el-select aria-label="批次" v-model="l.batchId" placeholder="选择在库批次" filterable :disabled="!d.fromId">
                <el-option v-for="s in stock" :key="s.batchId" :value="s.batchId"
                           :label="`${s.itemName} · ${s.batchNo} · 在库 ${fmtQty(s.qty)} ${s.unit}（${s.statusLabel}）`" />
              </el-select>
              <el-input-number v-model="l.qty" :min="0" :controls="false" placeholder="数量" style="width:100%" />
              <el-button text :disabled="d.lines.length === 1" @click="d.lines.splice(i, 1)">删除</el-button>
            </div>
            <el-button size="small" :disabled="!d.fromId" @click="d.lines.push({ batchId: null, qty: null })">添加一行</el-button>
          </div>
        </el-form-item>

        <el-form-item :label="REASON_LABEL[d.mode]" style="margin-top:12px">
          <el-input v-model="d.reason" type="textarea" :rows="2" maxlength="300" show-word-limit :placeholder="REASON_HINT[d.mode]" />
        </el-form-item>
        <el-form-item v-if="d.mode === 'SCRAP'" label="签批密码（本人登录密码）">
          <el-input v-model="d.password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="d.visible = false">取消</el-button>
        <el-button :type="d.mode === 'SCRAP' ? 'danger' : 'primary'" :loading="d.saving" @click="submit">{{ SUBMIT[d.mode] }}</el-button>
      </template>
    </el-dialog>
    <ImportDialog v-model="openingOpen" kind="opening" @done="load" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ExportButton from '../components/ExportButton.vue'
import ImportDialog from '../components/ImportDialog.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const TITLES = { TRANSFER: '仓间调拨', ADJUST: '盘点调整', SCRAP: '报废出库' }
const SUBMIT = { TRANSFER: '确认调拨', ADJUST: '按实盘调整库存', SCRAP: '签批报废' }
const REASON_LABEL = { TRANSFER: '调拨原因', ADJUST: '盘点说明', SCRAP: '报废原因' }
const REASON_HINT = { TRANSFER: '如：调往代工厂寄存仓备料', ADJUST: '如：2026 年 11 月月度盘点，差异已复核', SCRAP: '如：包材外观破损，经检验不可使用' }

const auth = useAuth()
const rd = useRefData()
const docs = ref([])
const type = ref('')
const narrow = window.innerWidth < 640
const canMove = computed(() => auth.hasAny('ADMIN', 'WAREHOUSE'))
const canScrap = computed(() => auth.hasAny('QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const openingOpen = ref(false)

const loader = useLoader(async () => { docs.value = await http.get('/inventory-docs') })
const load = () => loader.run()
const t = usePaged(docs, {
  match: textMatch('docNo', 'reason', (r) => r.lines.map((l) => l.batchNo + l.itemName).join(' ')),
  extra: (r) => !type.value || r.docType === type.value
})

const d = reactive({ visible: false, mode: 'TRANSFER', saving: false, fromId: null, toId: null, reason: '', password: '', lines: [], countFilter: '' })
const stock = ref([])
const counted = reactive({})

const countRows = computed(() => {
  const kw = d.countFilter.trim().toLowerCase()
  return kw ? stock.value.filter((s) => (s.itemName + s.itemCode + s.batchNo).toLowerCase().includes(kw)) : stock.value
})
const diffOf = (row) => Number(counted[row.batchId] ?? row.qty) - Number(row.qty)
const countDiffs = computed(() => stock.value.filter((s) => diffOf(s) !== 0))

async function open(mode) {
  await rd.ready()
  const main = rd.ownWarehouses.value[0]
  Object.assign(d, { visible: true, mode, saving: false, fromId: main?.id || null, toId: null, reason: '', password: '', countFilter: '', lines: [{ batchId: null, qty: null }] })
  loadStock()
}

const stockReq = latestOnly()

async function loadStock() {
  const current = stockReq()
  stock.value = []
  Object.keys(counted).forEach((k) => delete counted[k])
  d.lines.forEach((l) => { l.batchId = null })
  if (!d.fromId) return
  const rows = await http.get('/stock', { params: { warehouseId: d.fromId } })
  // 切换仓库后旧仓库的响应才回来时要丢掉，否则按 A 仓的盘点表调整 B 仓，B 仓会凭空多出库存
  if (!current()) return
  stock.value = rows
  stock.value.forEach((s) => { counted[s.batchId] = Number(s.qty) })
}

async function submit() {
  let url
  let body
  if (d.mode === 'ADJUST') {
    if (!countDiffs.value.length) return ElMessage.warning('实盘数量与账面一致，无需调整')
    url = '/inventory-docs/adjust'
    body = { warehouseId: d.fromId, reason: d.reason, lines: countDiffs.value.map((s) => ({ batchId: s.batchId, countedQty: counted[s.batchId] })) }
  } else {
    const lines = d.lines.filter((l) => l.batchId && l.qty > 0)
    if (!lines.length) return ElMessage.warning('请至少填一行批次和数量')
    url = d.mode === 'TRANSFER' ? '/inventory-docs/transfer' : '/inventory-docs/scrap'
    body = d.mode === 'TRANSFER'
      ? { fromWarehouseId: d.fromId, toWarehouseId: d.toId, reason: d.reason, lines }
      : { warehouseId: d.fromId, reason: d.reason, password: d.password, lines }
  }
  d.saving = true
  try {
    const doc = await http.post(url, body)
    ElMessage.success(`${doc.docTypeLabel}单 ${doc.docNo} 已完成`)
    d.visible = false
    load()
  } finally {
    d.saving = false
  }
}

onMounted(() => Promise.all([load(), rd.load()]))
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.kpi-card { padding: 14px 16px; border-radius: 14px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); display: grid; gap: 4px; }
.kpi-val { font-size: 20px; font-weight: 600; }
.dl { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; line-height: 1.9; }
.neg { color: var(--el-color-danger); }
.pos { color: var(--el-color-success); }
.count-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
</style>
