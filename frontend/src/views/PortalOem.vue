<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>委托生产协同</h2>
        <p>{{ auth.user?.partnerName }}：这里列出品牌方委托给本厂的生产单。请及时回传接单、排产和生产进度；生产完成、出厂检验合格后提交完工申报，品牌方核对后入库。</p>
      </div>
      <div class="toolbar">
        <el-input v-if="tab !== 'statements'" v-model="keyword" size="default" placeholder="搜索委托单号、成品名称" clearable style="width:220px" />
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card" @click="tab = 'active'">
        <div class="kpi-label">进行中委托单</div>
        <div class="kpi-val mono">{{ active.length }} <span class="kpi-unit">/ {{ orders.length }}</span></div>
        <div class="kpi-sub">已完成/取消 {{ finished.length }} 单</div>
      </div>
      <div class="kpi-card" :class="{ warn: unconfirmedCount > 0 }" @click="tab = 'active'">
        <div class="kpi-label">待确认接单</div>
        <div class="kpi-val mono">{{ unconfirmedCount }}</div>
        <div class="kpi-sub">尚未回传首条接单进度</div>
      </div>
      <div class="kpi-card" :class="{ warn: pendingReviewCount > 0 }" @click="tab = 'active'">
        <div class="kpi-label">完工申报待核对</div>
        <div class="kpi-val mono">{{ pendingReviewCount }}</div>
        <div class="kpi-sub">已提交品牌方等待核对入库</div>
      </div>
      <div class="kpi-card" :class="{ warn: pendingStatementCount > 0 }" @click="tab = 'statements'">
        <div class="kpi-label">待确认委外对账单</div>
        <div class="kpi-val mono">{{ pendingStatementCount }}</div>
        <div class="kpi-sub">共 {{ statements.length }} 张加工费对账单</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="seg">
      <el-tab-pane :label="`进行中（${active.length}）`" name="active" />
      <el-tab-pane :label="`已结束（${finished.length}）`" name="done" />
      <el-tab-pane :label="`委外对账（${statements.length}）`" name="statements" />
    </el-tabs>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <template v-else-if="tab === 'statements'">
      <el-empty v-if="!statements.length && !loader.loading.value" description="还没有委外加工费对账单" />
      <div v-else class="cards">
        <section v-for="s in statements" :key="s.id" class="order glass">
          <header class="oh">
            <div>
              <div class="mono muted small">{{ s.statementNo }} · 截止 {{ s.periodTo }} · 到期 {{ s.dueDate }}</div>
              <h3>合计 <span class="mono">¥{{ fmtMoney(s.totalAmount) }}</span> <span class="muted small">（已结 ¥{{ fmtMoney(s.paidAmount) }}）</span></h3>
            </div>
            <div class="tags">
              <StatusTag :status="s.status" :label="s.statusLabel" />
            </div>
          </header>
          <el-table :data="s.lines" size="small">
            <el-table-column prop="poNo" label="委托单号" width="150" />
            <el-table-column prop="batchNo" label="产出批号" width="150" />
            <el-table-column prop="itemName" label="成品" min-width="140" />
            <el-table-column label="数量" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span></template></el-table-column>
            <el-table-column label="加工费" width="95" align="right"><template #default="{ row }"><span class="mono">¥{{ fmtQty(row.unitPrice) }}</span></template></el-table-column>
            <el-table-column label="税率" width="75" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.taxRate) }}%</span></template></el-table-column>
            <el-table-column label="含税金额" width="110" align="right"><template #default="{ row }"><span class="mono">¥{{ fmtMoney(row.amount) }}</span></template></el-table-column>
          </el-table>
          <footer class="toolbar" style="margin-top:8px">
            <el-button v-if="s.status === 'SENT'" type="primary" size="small" @click="confirmStatement(s)">确认对账</el-button>
            <el-button v-if="s.status === 'SENT'" size="small" @click="disputeStatement(s)">提出异议</el-button>
            <el-button v-if="s.status === 'CONFIRMED'" type="primary" size="small" @click="openStInvoice(s)">登记加工费发票</el-button>
            <span v-if="s.invoiceNo" class="muted small mono">发票：{{ s.invoiceNo }}（{{ s.invoiceDate }}）</span>
          </footer>
        </section>
      </div>
    </template>
    <el-empty v-else-if="!shown.length && !loader.loading.value" :description="tab === 'active' ? '目前没有进行中的委托单' : '还没有已结束的委托单'" />

    <div v-else class="cards">
      <section v-for="o in shown" :key="o.id" class="order glass">
        <header class="oh">
          <div>
            <div class="mono muted small">{{ o.moNo }}</div>
            <h3>{{ o.fgName }} <span class="mono">× {{ fmtQty(o.plannedQty) }}</span></h3>
            <div class="muted small">计划完工 {{ o.plannedDate || '—' }}<template v-if="o.promiseDate"> · 本厂承诺交期 <b>{{ o.promiseDate }}</b></template></div>
          </div>
          <div class="tags">
            <StatusTag :status="o.status" :label="o.status === 'PLANNED' ? '待品牌方发料' : o.statusLabel" />
            <el-tag type="info" size="small" disable-transitions>{{ o.stageLabel }}</el-tag>
            <StatusTag v-if="latestReport(o)" :status="latestReport(o).status" :label="`申报${latestReport(o).statusLabel}`" />
          </div>
        </header>

        <el-alert v-if="latestReport(o)?.status === 'RETURNED'" type="error" :closable="false" show-icon
                  :title="`品牌方退回了申报：${latestReport(o).reviewNote}`" description="请核对后重新申报。" />

        <div class="grid">
          <div>
            <h4>物料</h4>
            <div v-for="r in o.requirements" :key="r.itemId" class="mat">
              <span>{{ r.itemName }} <span class="muted small mono">{{ fmtQty(r.requiredQty) }} {{ r.unit }}</span></span>
              <span v-if="r.supplyMode === 'BRAND'" class="small">
                <el-tag size="small" disable-transitions>品牌供料</el-tag>
                <template v-if="o.status !== 'PLANNED'"> <span v-for="p in r.picks" :key="p.batchId" class="mono muted"> {{ p.batchNo }}</span></template>
                <span v-else class="muted"> 待发料</span>
              </span>
              <el-tag v-else size="small" type="warning" disable-transitions>本厂代采，申报时登记批号</el-tag>
            </div>
          </div>
          <div>
            <h4>进度</h4>
            <el-timeline v-if="o.progress.length" class="tl">
              <el-timeline-item v-for="p in o.progress.slice(-4)" :key="p.id" :timestamp="fmtTime(p.createdAt)" placement="top">
                <b>{{ p.stageLabel }}</b><span v-if="p.promiseDate" class="muted small"> · 交期 {{ p.promiseDate }}</span>
                <div v-if="p.note" class="small">{{ p.note }}</div>
              </el-timeline-item>
            </el-timeline>
            <p v-else class="muted small">还没有回传进度，请先“确认接单”。</p>
          </div>
        </div>

        <AttachmentList portal owner-type="MFG_ORDER" :owner-id="o.id" :can-upload="!isClosed(o)"
                        title="附件（出厂检验报告、批生产记录）" empty-text="申报时请上传出厂检验报告扫描件" />

        <footer v-if="!isClosed(o)" class="toolbar">
          <el-button @click="openProgress(o)">{{ o.progress.length ? '回传进度' : '确认接单' }}</el-button>
          <el-button type="primary" :disabled="!canReport(o)" @click="openReport(o)">申报完工与出厂放行</el-button>
          <span v-if="o.status === 'PLANNED'" class="muted small">品牌方发料后才能申报完工</span>
          <span v-else-if="latestReport(o)?.status === 'SUBMITTED'" class="muted small">申报已提交，等品牌方核对入库</span>
        </footer>
        <footer v-else class="muted small">已入库，品牌方成品批号 <span class="mono">{{ o.outBatchNo }}</span></footer>
      </section>
    </div>

    <!-- 回传进度 -->
    <el-dialog v-model="pg.visible" :title="`回传进度：${pg.order?.moNo || ''}`" width="480px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="当前进度">
          <el-radio-group v-model="pg.stage">
            <el-radio-button v-for="(v, k) in OEM_STAGES" :key="k" :value="k">{{ v }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="承诺交期（可选）"><el-date-picker v-model="pg.promiseDate" type="date" value-format="YYYY-MM-DD" :disabled-date="(d) => d < startOfToday" style="width:100%" /></el-form-item>
        <el-form-item :label="pg.stage === 'NOTE' ? '备注' : '说明（可选）'"><el-input v-model="pg.note" type="textarea" :rows="2" maxlength="300" placeholder="如：排在 3 号灌装线，预计周四开始灌装" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pg.visible = false">取消</el-button>
        <el-button type="primary" :loading="pg.saving" @click="submitProgress">回传给品牌方</el-button>
      </template>
    </el-dialog>

    <!-- 登记委外加工费发票 -->
    <el-dialog v-model="stInv.visible" :title="`登记加工费发票：${stInv.s?.statementNo || ''}`" width="460px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="发票号"><el-input v-model="stInv.invoiceNo" maxlength="60" /></el-form-item>
        <el-form-item label="开票日期"><el-date-picker v-model="stInv.invoiceDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="税率 %"><el-input-number v-model="stInv.taxRate" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" /></el-form-item>
        <el-form-item label="含税金额"><el-input-number v-model="stInv.amount" :precision="2" :controls="false" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stInv.visible = false">取消</el-button>
        <el-button type="primary" :loading="stInv.saving" @click="submitStInvoice">提交发票</el-button>
      </template>
    </el-dialog>

    <!-- 申报完工 -->
    <el-dialog v-model="rp.visible" :title="`完工与出厂放行申报：${rp.order?.moNo || ''}`" width="700px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item :label="`实际产量（计划 ${fmtQty(rp.order?.plannedQty)}）`"><el-input-number v-model="rp.actualQty" :min="1" :precision="0" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="生产日期"><el-date-picker v-model="rp.mfgDate" type="date" value-format="YYYY-MM-DD" :disabled-date="(d) => d > new Date()" style="width:100%" /></el-form-item>
          <el-form-item label="本厂成品批号"><el-input v-model="rp.extBatchNo" maxlength="60" /></el-form-item>
          <el-form-item label="出厂检验报告编号"><el-input v-model="rp.releaseReportNo" maxlength="60" /></el-form-item>
          <el-form-item label="质量放行人"><el-input v-model="rp.releasedBy" maxlength="64" placeholder="本厂质量负责人姓名" /></el-form-item>
        </div>
        <el-form-item v-if="rpOutOfRange" label="物料平衡说明（实际产量超出计划的 95%–102%）">
          <el-input v-model="rp.balanceNote" type="textarea" :rows="2" maxlength="300" placeholder="说明偏差原因，例如灌装损耗、取样量" />
        </el-form-item>
        <el-form-item v-if="rp.materials.length" label="本厂代采原料：登记实际使用的原料批号和用量">
          <div style="width:100%">
            <div v-for="m in rp.materials" :key="m.itemId" class="line-row">
              <span>{{ m.itemName }} <span class="muted small">理论 {{ fmtQty(m.requiredQty) }} {{ m.unit }}</span></span>
              <el-input v-model="m.extBatchNo" placeholder="原料批号" maxlength="60" />
              <el-input-number aria-label="实际用量" v-model="m.qty" :min="0" :precision="4" :controls="false" />
            </div>
          </div>
        </el-form-item>
        <el-checkbox v-model="rp.confirm">本厂已完成出厂检验并合格，批生产记录已审核，同意放行</el-checkbox>
      </el-form>
      <template #footer>
        <el-button @click="rp.visible = false">取消</el-button>
        <el-button type="primary" :loading="rp.saving" :disabled="!rp.confirm" @click="submitReport">提交申报</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import AttachmentList from '../components/AttachmentList.vue'
import { useAuth } from '../stores/auth'
import { fmtMoney, OEM_STAGES, fmtQty, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { useLoader } from '../utils/loader'

const auth = useAuth()
const orders = ref([])
const statements = ref([])
const tab = ref('active')
const keyword = ref('')
const loader = useLoader(async () => {
  const [o, s] = await Promise.all([http.get('/portal/oem/orders'), http.get('/portal/oem/statements')])
  orders.value = o
  statements.value = s
})
const load = () => loader.run()

const isClosed = (o) => o.status === 'COMPLETED' || o.status === 'CANCELLED'
const active = computed(() => orders.value.filter((o) => !isClosed(o)))
const finished = computed(() => orders.value.filter(isClosed))
const latestReport = (o) => o.reports[o.reports.length - 1] || null
const unconfirmedCount = computed(() => active.value.filter((o) => !o.progress.length).length)
const pendingReviewCount = computed(() => active.value.filter((o) => latestReport(o)?.status === 'SUBMITTED').length)
const pendingStatementCount = computed(() => statements.value.filter((s) => s.status === 'SENT' || s.status === 'CONFIRMED').length)
const shown = computed(() => {
  const base = tab.value === 'active' ? active.value : finished.value
  const q = keyword.value.trim().toLowerCase()
  return q ? base.filter((o) => (o.moNo || '').toLowerCase().includes(q) || (o.fgName || '').toLowerCase().includes(q)) : base
})
const canReport = (o) => o.status === 'ISSUED' && latestReport(o)?.status !== 'SUBMITTED'
const startOfToday = startOfBusinessDay()

// ---------- 回传进度 ----------
const pg = reactive({ visible: false, saving: false, order: null, stage: 'CONFIRMED', promiseDate: null, note: '' })
const NEXT = { CONFIRMED: 'SCHEDULED', SCHEDULED: 'PRODUCING', PRODUCING: 'TESTING', TESTING: 'TESTING' }

function openProgress(o) {
  Object.assign(pg, { visible: true, saving: false, order: o, stage: o.stage ? NEXT[o.stage] : 'CONFIRMED', promiseDate: o.promiseDate, note: '' })
}

async function submitProgress() {
  pg.saving = true
  try {
    await http.post(`/portal/oem/orders/${pg.order.id}/progress`, { stage: pg.stage, promiseDate: pg.promiseDate, note: pg.note })
    ElMessage.success('已回传给品牌方')
    pg.visible = false
    load()
  } finally {
    pg.saving = false
  }
}

// ---------- 申报完工 ----------
const rp = reactive({ visible: false, saving: false, order: null, actualQty: null, mfgDate: today(), extBatchNo: '', releaseReportNo: '', releasedBy: '', balanceNote: '', materials: [], confirm: false })
const rpOutOfRange = computed(() => {
  if (!rp.order || !rp.actualQty) return false
  const r = rp.actualQty / Number(rp.order.plannedQty)
  return r < 0.95 || r > 1.02
})

function openReport(o) {
  const last = latestReport(o)
  Object.assign(rp, {
    visible: true, saving: false, order: o, confirm: false,
    actualQty: Number(last?.actualQty ?? o.plannedQty), mfgDate: last?.mfgDate || today(), extBatchNo: last?.extBatchNo || '',
    releaseReportNo: last?.releaseReportNo || '', releasedBy: last?.releasedBy || '', balanceNote: last?.balanceNote || '',
    materials: o.requirements.filter((r) => r.supplyMode === 'OEM').map((r) => {
      const prev = last?.materials.find((m) => m.itemId === r.itemId)
      return { itemId: r.itemId, itemName: r.itemName, unit: r.unit, requiredQty: r.requiredQty, extBatchNo: prev?.extBatchNo || '', qty: Number(prev?.qty ?? r.requiredQty) }
    })
  })
}

async function submitReport() {
  if (rpOutOfRange.value && !rp.balanceNote.trim()) return ElMessage.warning('请填写物料平衡说明')
  if (rp.materials.some((m) => !m.extBatchNo.trim())) return ElMessage.warning('请登记每种代采原料的批号')
  rp.saving = true
  try {
    const { actualQty, mfgDate, extBatchNo, releaseReportNo, releasedBy, balanceNote } = rp
    await http.post(`/portal/oem/orders/${rp.order.id}/report`, {
      actualQty, mfgDate, extBatchNo, releaseReportNo, releasedBy, balanceNote,
      materials: rp.materials.map(({ itemId, extBatchNo: b, qty }) => ({ itemId, extBatchNo: b, qty }))
    })
    ElMessage.success('申报已提交，品牌方核对后入库')
    rp.visible = false
    load()
  } finally {
    rp.saving = false
  }
}

async function confirmStatement(s) {
  await http.post(`/portal/oem/statements/${s.id}/confirm`)
  ElMessage.success('已确认委外对账单，请登记加工费发票')
  load()
}
async function disputeStatement(s) {
  let note
  try {
    note = (await ElMessageBox.prompt('请写明异议原因，品牌方采购会跟进核对。', `对账异议 ${s.statementNo}`,
      { confirmButtonText: '提交异议', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '请填写异议内容' })).value
  } catch { return }
  await http.post(`/portal/oem/statements/${s.id}/dispute`, { note })
  ElMessage.success('已提交异议')
  load()
}
const stInv = reactive({ visible: false, saving: false, s: null, invoiceNo: '', invoiceDate: today(), taxRate: 6, amount: 0 })
function openStInvoice(s) {
  Object.assign(stInv, { visible: true, saving: false, s, invoiceNo: '', invoiceDate: today(), taxRate: Number(s.taxBreakdown?.[0]?.taxRate ?? 6), amount: Number(s.totalAmount) })
}
async function submitStInvoice() {
  if (!stInv.invoiceNo.trim() || !stInv.invoiceDate) return ElMessage.warning('请填写发票号与开票日期')
  stInv.saving = true
  try {
    await http.post(`/portal/oem/statements/${stInv.s.id}/invoices`, {
      invoices: [{ invoiceNo: stInv.invoiceNo, invoiceType: stInv.amount < 0 ? 'RED' : 'BLUE', invoiceDate: stInv.invoiceDate, taxRate: stInv.taxRate, amount: stInv.amount }]
    })
    ElMessage.success('加工费发票已提交，等待品牌方财务审批')
    stInv.visible = false
    load()
  } finally { stInv.saving = false }
}

onMounted(load)
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
.kpi-card { background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 12px 14px; cursor: pointer; transition: border-color 0.15s; }
.kpi-card:hover { border-color: var(--el-color-primary-light-5); }
.kpi-card.warn { border-color: var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); }
.kpi-label { font-size: 12px; color: var(--el-text-color-secondary); }
.kpi-val { font-size: 22px; font-weight: 700; margin: 4px 0 2px; line-height: 1.2; }
.kpi-unit { font-size: 12px; font-weight: 400; color: var(--el-text-color-secondary); }
.kpi-sub { font-size: 12px; color: var(--el-text-color-secondary); }
.cards { display: grid; gap: 16px; }
@media (max-width: 900px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.order { padding: 18px 20px; display: grid; gap: 14px; }
.oh { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: flex-start; }
.oh h3 { margin: 4px 0; font-size: 18px; letter-spacing: -0.01em; }
.tags { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
h4 { margin: 0 0 8px; font-size: 13px; color: var(--el-text-color-secondary); font-weight: 600; }
.mat { display: flex; justify-content: space-between; gap: 8px; padding: 6px 0; border-bottom: 1px solid var(--lg-hairline); flex-wrap: wrap; }
.mat:last-child { border-bottom: 0; }
.tl { padding-left: 2px; }
footer { padding-top: 4px; }
@media (max-width: 760px) { .grid { grid-template-columns: minmax(0, 1fr); } }
</style>
