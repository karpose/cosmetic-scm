<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>退供</h2>
        <p>质量签批后登记退货物流。供应商可在门户确认收货，未启用门户的由采购凭回单代录签收。</p>
      </div>
      <div class="toolbar">
        <ExportButton :report="activeTab === 'closure' ? 'supplier-return-closure' : 'supplier-returns'" :label="activeTab === 'closure' ? '导出闭环监控' : '导出台账'" />
        <el-button v-if="canMake" type="primary" @click="openCreate">发起退供</el-button>
      </div>
    </div>

    <div v-if="summary" class="kpi-grid">
      <div class="kpi-card" :class="{ warn: summary.pendingSignCount > 0 }">
        <div class="muted small">待质量签批</div>
        <div class="kpi-val mono">{{ summary.pendingSignCount }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.pendingShipCount > 0 }">
        <div class="muted small">待登记退货物流</div>
        <div class="kpi-val mono">{{ summary.pendingShipCount }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.awaitingReceiptCount > 0 }">
        <div class="muted small">已发出待签收（需代录）</div>
        <div class="kpi-val mono">{{ summary.awaitingReceiptCount }} <span class="muted small">（{{ summary.offlineReceiptPendingCount }}）</span></div>
      </div>
      <div class="kpi-card">
        <div class="muted small">已完成签收 / 累计单数</div>
        <div class="kpi-val mono">{{ summary.completedCount }} <span class="muted small">/ {{ summary.totalCount }}</span></div>
      </div>
    </div>

    <el-tabs v-if="!selectedNo" v-model="activeTab" class="seg">
      <el-tab-pane label="退供台账" name="list" />
      <el-tab-pane :label="`闭环监控（${closureBoard?.totalOpenCount ?? 0}）`" name="closure" />
    </el-tabs>

    <el-card v-if="!selectedNo && activeTab === 'closure'" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="closureFilter" size="small">
            <el-radio-button value="ALL">全部未闭环 ({{ closureBoard?.totalOpenCount ?? 0 }})</el-radio-button>
            <el-radio-button value="OVERDUE">超期滞留 ({{ closureBoard?.overdueCount ?? 0 }})</el-radio-button>
            <el-radio-button value="PENDING_SIGN">待签批 ({{ closureBoard?.pendingSignCount ?? 0 }})</el-radio-button>
            <el-radio-button value="PENDING_SHIP">待登记物流 ({{ closureBoard?.pendingShipCount ?? 0 }})</el-radio-button>
            <el-radio-button value="RECEIPT">待签收 / 代录 ({{ closureBoard?.awaitingReceiptCount ?? 0 }})</el-radio-button>
          </el-radio-group>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
        <div v-if="closureBoard" class="muted small" style="margin-top:8px">
          未闭环退供单 <b class="mono">{{ closureBoard.totalOpenCount }}</b> 笔 · 超期滞留 <b class="mono" :class="{ late: closureBoard.overdueCount > 0 }">{{ closureBoard.overdueCount }}</b> 笔 · 待签批 <b class="mono">{{ closureBoard.pendingSignCount }}</b> · 待发运 <b class="mono">{{ closureBoard.pendingShipCount }}</b> · 待签收 <b class="mono">{{ closureBoard.awaitingReceiptCount }}</b>（其中需采购代录 <b class="mono">{{ closureBoard.offlineReceiptPendingCount }}</b>）
        </div>
      </template>
      <el-table :data="filteredClosureRows" v-loading="loader.loading.value" size="small" empty-text="当前无未闭环的退供单">
        <el-table-column label="风险分级" width="130" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.overdue ? 'danger' : row.stage === 'PENDING_SIGN' ? 'warning' : row.stage === 'PENDING_SHIP' ? 'primary' : 'warning'" disable-transitions>{{ row.riskLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="stageLabel" label="当前环节" width="120" />
        <el-table-column label="退供单 / 供应商" min-width="165">
          <template #default="{ row }"><span class="mono">{{ row.returnNo }}</span><div class="muted small">{{ row.supplierName }}</div></template>
        </el-table-column>
        <el-table-column label="物料 / 批号" min-width="180">
          <template #default="{ row }">{{ row.itemName }} · {{ fmtQty(row.qty) }} {{ row.unit }}<div class="muted small mono">{{ row.batchNo }}<template v-if="row.trackingNo"> · {{ row.carrier }} {{ row.trackingNo }}</template></div></template>
        </el-table-column>
        <el-table-column label="滞留" width="80" align="right">
          <template #default="{ row }"><span class="mono" :class="{ late: row.overdue }">{{ row.agingDays }} 天</span></template>
        </el-table-column>
        <el-table-column prop="recommendedAction" label="闭环处置建议" min-width="220" />
        <el-table-column label="" width="120" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="row.stage === 'PENDING_SIGN' && canSign" size="small" type="primary" @click="openApproveById(row.id)">签批</el-button>
            <el-button v-else-if="row.stage === 'PENDING_SHIP' && canMake" size="small" @click="openShipById(row.id)">登记物流</el-button>
            <el-button v-else-if="row.offlineReceiptPending && canRecordReceipt" size="small" type="primary" @click="openReceiptById(row.id)">代录签收</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div v-if="selectedNo" class="filters">
          <span class="mono">{{ selectedNo }}</span>
          <RouterLink to="/supplier-returns">查看全部退供单</RouterLink>
        </div>
        <div v-else class="filters"><el-input v-model="t.keyword.value" placeholder="搜索单号、供应商、批号、物料、运单或回单号" clearable /></div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loader.loading.value" :empty-text="selectedNo ? '没有找到这张退供单' : '还没有退供'" scrollbar-tabindex="0">
        <el-table-column label="退供单" width="150"><template #default="{ row }"><span class="mono">{{ row.returnNo }}</span><div class="muted small mono">{{ fmtTime(row.createdAt) }}</div></template></el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="150" />
        <el-table-column label="批次和数量" min-width="230">
          <template #default="{ row }">{{ row.itemName }} <BatchNo :no="row.batchNo" /><div class="muted small">{{ fmtQty(row.qty) }} {{ row.unit }} · {{ row.warehouseName }} · 供应商批号 <span class="mono">{{ row.extBatchNo }}</span></div></template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="140" />
        <el-table-column label="退货物流与签收" min-width="190">
          <template #default="{ row }">
            <div v-if="row.status === 'DONE'" class="small">
              <div v-if="row.shippedAt">{{ row.carrier }} <span class="mono">{{ row.trackingNo }}</span></div>
              <el-tag v-if="row.supplierConfirmedAt" size="small" type="success" disable-transitions>{{ row.receiptSource === 'OFFLINE' ? '采购已代录签收' : '供应商已签收' }}</el-tag>
              <el-tag v-else-if="row.offlineReceiptPending" size="small" type="warning" disable-transitions>待代录签收</el-tag>
              <el-tag v-else-if="row.shippedAt" size="small" type="warning" disable-transitions>已发出待签收</el-tag>
              <el-tag v-else size="small" type="info" disable-transitions>待登记退货物流</el-tag>
              <div v-if="row.supplierConfirmedAt" class="muted small">
                <div v-if="row.receiptSource === 'OFFLINE'">签收 {{ row.receivedOn }} · 凭证 <span class="mono">{{ row.receiptReference }}</span></div>
                {{ fmtTime(row.supplierConfirmedAt) }}<span v-if="row.supplierNote"> · {{ row.supplierNote }}</span>
              </div>
            </div>
            <span v-else class="muted small">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.signedBy" class="muted small">{{ row.signedBy }}</div><div v-if="row.cancelReason" class="muted small">{{ row.cancelReason }}</div></template></el-table-column>
        <el-table-column label="" width="170" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING' && canSign" size="small" type="primary" @click="openApprove(row)">签批</el-button>
            <el-button v-if="row.status === 'PENDING' && canMake" size="small" text @click="cancel(row)">取消</el-button>
            <el-button v-if="row.status === 'DONE' && !row.supplierConfirmedAt && canMake" size="small" @click="openShip(row)">{{ row.trackingNo ? '改物流' : '登记物流' }}</el-button>
            <el-button v-if="row.offlineReceiptPending && canRecordReceipt" size="small" type="primary" @click="openReceipt(row)">代录签收</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListCapHint v-if="!selectedNo" :count="rows.length" />
      <ListPager v-if="!selectedNo" v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" title="发起退供" width="760px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="供应商">
          <el-select id="rs-supplier" v-model="cr.supplierId" filterable placeholder="选择供应商" style="width:100%" @change="loadCandidates">
            <el-option v-for="p in rd.suppliers.value" :key="p.id" :value="p.id" :label="p.name" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="cr.supplierId" label="退哪个批次">
          <el-select id="rs-batch" v-model="cr.pick" filterable placeholder="选择在库批次" style="width:100%" :no-data-text="'这家供应商没有在库的来料批次'" @change="onPick">
            <el-option v-for="(c, i) in cr.candidates" :key="i" :value="i" :disabled="Number(c.available) <= 0"
                       :label="`${c.itemName} · ${c.batchNo} · ${c.statusLabel} · ${c.warehouseName} · 可退 ${fmtQty(c.available)} ${c.unit}`" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="picked" :label="`退货数量（最多 ${fmtQty(picked.available)} ${picked.unit}）`">
          <el-input-number id="rs-qty" v-model="cr.qty" :min="0" :max="Number(picked.available)" :precision="4" :controls="false" style="width:100%" />
        </el-form-item>
        <el-form-item label="退货原因"><el-input id="rs-reason" v-model="cr.reason" type="textarea" :rows="2" maxlength="300" show-word-limit placeholder="如：来料检验不合格，COA 编号……" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" @click="submitCreate">提交质量签批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ap.visible" :title="`签批退供：${ap.row?.returnNo || ''}`" width="480px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p style="margin-top:0">{{ ap.row?.supplierName }}：{{ ap.row?.itemName }} <span class="mono">{{ ap.row?.batchNo }}</span> × {{ fmtQty(ap.row?.qty) }} {{ ap.row?.unit }}。签批后立即扣库存。</p>
        <p class="muted small">原因：{{ ap.row?.reason }}</p>
        <p v-if="ap.row?.createdBy === auth.user?.username" class="late small">这张单是您发起的，要由另一位质量人员签批。</p>
        <el-form-item label="签批密码"><el-input id="rs-password" v-model="ap.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ap.visible = false">取消</el-button>
        <el-button type="primary" :loading="ap.saving" @click="submitApprove">签批并扣库存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="sh.visible" :title="`登记退货物流：${sh.row?.returnNo || ''}`" width="460px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="承运商 / 退回方式">
          <el-input id="rs-carrier" v-model="sh.carrier" maxlength="60" placeholder="如：顺丰速运、德邦物流、供应商自提" />
        </el-form-item>
        <el-form-item label="运单号 / 车牌交接单号">
          <el-input id="rs-tracking" v-model="sh.trackingNo" maxlength="60" placeholder="填写物流单号或交接单号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sh.visible = false">取消</el-button>
        <el-button type="primary" :loading="sh.saving" @click="submitShip">保存物流信息</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rc.visible" :title="`代录签收：${rc.row?.returnNo || ''}`" width="480px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small">{{ rc.row?.supplierName }} · 运单或交接单号 {{ rc.row?.trackingNo }}。请按收到的回单填写。</p>
        <el-form-item label="实际签收日期">
          <el-date-picker id="rs-received-on" v-model="rc.receivedOn" type="date" value-format="YYYY-MM-DD" :disabled-date="(d) => d > startOfBusinessDay()" style="width:100%" />
        </el-form-item>
        <el-form-item label="回单或签收凭证编号">
          <el-input id="rs-receipt-reference" v-model="rc.receiptReference" maxlength="80" />
        </el-form-item>
        <el-form-item label="签收备注（选填）">
          <el-input id="rs-receipt-note" v-model="rc.note" type="textarea" :rows="2" maxlength="300" />
        </el-form-item>
        <el-form-item label="签批密码">
          <el-input id="rs-receipt-password" v-model="rc.password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rc.visible = false">取消</el-button>
        <el-button type="primary" :loading="rc.saving" @click="submitReceipt">登记签收</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const route = useRoute()
const rd = useRefData()
const canMake = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'WAREHOUSE'))
const canSign = computed(() => auth.hasAny('QA', 'QSO'))
const canRecordReceipt = computed(() => auth.hasAny('ADMIN', 'PURCHASE'))

const activeTab = ref(route.query.tab === 'closure' ? 'closure' : 'list')
const rows = ref([])
const summary = ref(null)
const closureBoard = ref(null)
const closureFilter = ref('ALL')
const filteredClosureRows = computed(() => {
  const list = closureBoard.value?.rows || []
  if (closureFilter.value === 'ALL') return list
  if (closureFilter.value === 'OVERDUE') return list.filter((r) => r.overdue)
  if (closureFilter.value === 'RECEIPT') return list.filter((r) => r.stage === 'AWAITING_RECEIPT' || r.stage === 'OFFLINE_RECEIPT')
  return list.filter((r) => r.stage === closureFilter.value)
})
const selectedNo = computed(() => typeof route.query.no === 'string' ? route.query.no : '')
const listRequest = latestOnly()
const loader = useLoader(async () => {
  const current = listRequest()
  const [data, sum, cb] = await Promise.all([
    http.get('/supplier-returns', { params: { returnNo: selectedNo.value || undefined } }),
    http.get('/supplier-returns/summary'),
    http.get('/supplier-returns/closure-board')
  ])
  if (current()) {
    rows.value = data
    summary.value = sum
    closureBoard.value = cb
  }
})
const openApproveById = (id) => { const r = rows.value.find((x) => x.id === id); if (r) openApprove(r) }
const openShipById = (id) => { const r = rows.value.find((x) => x.id === id); if (r) openShip(r) }
const openReceiptById = (id) => { const r = rows.value.find((x) => x.id === id); if (r) openReceipt(r) }
const load = () => loader.run()
const t = usePaged(rows, { match: textMatch('returnNo', 'supplierName', 'batchNo', 'extBatchNo', 'itemName', 'trackingNo', 'receiptReference') })
watch(selectedNo, () => { rows.value = []; t.keyword.value = ''; t.page.value = 1; load() })
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { load(); rd.load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const candidatesReq = latestOnly()
const cr = reactive({ visible: false, saving: false, supplierId: null, candidates: [], pick: null, qty: undefined, reason: '' })
const picked = computed(() => (cr.pick === null ? null : cr.candidates[cr.pick]))
async function openCreate() {
  await rd.ready()
  Object.assign(cr, { visible: true, saving: false, supplierId: null, candidates: [], pick: null, qty: undefined, reason: '' })
}
async function loadCandidates() {
  const current = candidatesReq()
  cr.candidates = []
  cr.pick = null
  cr.qty = undefined
  const res = await http.get('/supplier-returns/candidates', { params: { supplierId: cr.supplierId } })
  if (current()) cr.candidates = res
}
function onPick() {
  cr.qty = picked.value ? Number(picked.value.available) : undefined
}
async function submitCreate() {
  if (!picked.value) return ElMessage.warning('请选择批次')
  if (!(cr.qty > 0)) return ElMessage.warning('请填写退货数量')
  if (!cr.reason.trim()) return ElMessage.warning('请写明退货原因')
  cr.saving = true
  try {
    const r = await http.post('/supplier-returns', { batchId: picked.value.batchId, warehouseId: picked.value.warehouseId, qty: cr.qty, reason: cr.reason })
    ElMessage.success(`退供单 ${r.returnNo} 已提交，等质量签批`)
    cr.visible = false
    load()
  } finally {
    cr.saving = false
  }
}

const ap = reactive({ visible: false, saving: false, row: null, password: '' })
function openApprove(row) {
  Object.assign(ap, { visible: true, saving: false, row, password: '' })
}
async function submitApprove() {
  if (!ap.password) return ElMessage.warning('请输入签批密码')
  ap.saving = true
  try {
    await http.post(`/supplier-returns/${ap.row.id}/approve`, { password: ap.password })
    ElMessage.success('已签批，库存已扣减')
    ap.visible = false
    load()
  } finally {
    ap.saving = false
  }
}

const sh = reactive({ visible: false, saving: false, row: null, carrier: '', trackingNo: '' })
function openShip(row) {
  Object.assign(sh, { visible: true, saving: false, row, carrier: row.carrier || '', trackingNo: row.trackingNo || '' })
}
async function submitShip() {
  if (!sh.carrier.trim() || !sh.trackingNo.trim()) return ElMessage.warning('请填写承运商和物流单号')
  sh.saving = true
  try {
    await http.post(`/supplier-returns/${sh.row.id}/ship`, { carrier: sh.carrier, trackingNo: sh.trackingNo })
    ElMessage.success('退货物流已登记')
    sh.visible = false
    load()
  } finally {
    sh.saving = false
  }
}

const rc = reactive({ visible: false, saving: false, row: null, receivedOn: null, receiptReference: '', note: '', password: '' })
function openReceipt(row) {
  Object.assign(rc, { visible: true, saving: false, row, receivedOn: today(), receiptReference: '', note: '', password: '' })
}
async function submitReceipt() {
  if (!rc.receivedOn || !rc.receiptReference.trim() || !rc.password) return ElMessage.warning('请填写签收日期、凭证编号和签批密码')
  rc.saving = true
  try {
    await http.post(`/supplier-returns/${rc.row.id}/offline-receipt`, {
      receivedOn: rc.receivedOn, receiptReference: rc.receiptReference, note: rc.note, password: rc.password
    })
    ElMessage.success('线下签收已登记')
    rc.visible = false
    load()
  } finally {
    rc.saving = false
  }
}

async function cancel(row) {
  let reason
  try {
    reason = (await ElMessageBox.prompt('写明原因。', `取消退供 ${row.returnNo}`,
      { confirmButtonText: '取消退供', cancelButtonText: '不取消', inputValidator: (v) => !!v?.trim() || '请填写取消原因' })).value
  } catch { return }
  await http.post(`/supplier-returns/${row.id}/cancel`, { reason })
  ElMessage.success('退供已取消')
  load()
}
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
.kpi-card { padding: 14px 16px; border-radius: 14px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); display: grid; gap: 4px; }
.kpi-card.warn { border-color: var(--el-color-danger-light-5); }
.kpi-val { font-size: 20px; font-weight: 600; }
.late { color: var(--el-color-danger); }
</style>
