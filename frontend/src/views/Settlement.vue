<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>对账结算</h2>
        <p>只结算检验放行的来料批次，按采购单单价算金额。供应商确认后登记发票，发票金额要和对账单一致，财务审批后登记付款。这里只做登记，不实际付款，也不代替财务账。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="statements" />
        <el-button v-if="canBuy" type="primary" @click="openCreate">生成对账单</el-button>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="t.keyword.value" placeholder="搜索单号、供应商、发票号" clearable />
          <el-select aria-label="按状态筛选" v-model="filterBy" placeholder="全部状态" clearable>
            <el-option v-for="o in filterOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loader.loading.value" empty-text="还没有对账单">
        <el-table-column label="对账单" width="150"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row)">{{ row.statementNo }}</a><div class="muted small mono">截止 {{ row.periodTo }}</div></template></el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="160" />
        <el-table-column label="合计" width="120" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.totalAmount) }}</span></template></el-table-column>
        <el-table-column label="未付" width="120" align="right"><template #default="{ row }"><span class="mono">{{ row.status === 'CANCELLED' ? '—' : fmtMoney(row.unpaid) }}</span></template></el-table-column>
        <el-table-column label="发票" width="150"><template #default="{ row }"><span class="mono">{{ row.invoiceNo || '—' }}</span><div v-if="row.invoiceDate" class="muted small mono">{{ row.invoiceDate }}</div></template></el-table-column>
        <el-table-column label="状态" min-width="150"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.status === 'DISPUTED'" class="late small">{{ row.disputeNote }}</div><div v-if="row.cancelReason" class="muted small">{{ row.cancelReason }}</div></template></el-table-column>
        <el-table-column label="" width="200" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="row.status === 'CONFIRMED' && canBuy" size="small" @click="openInvoice(row)">代登记发票</el-button>
            <el-button v-if="row.status === 'INVOICED' && canFinance" size="small" type="primary" @click="openApprove(row)">审批</el-button>
            <el-button v-if="row.status === 'APPROVED' && canFinance" size="small" type="primary" @click="openPay(row)">登记付款</el-button>
            <el-button v-if="canVoid(row) && canBuy" size="small" text @click="voidStatement(row)">作废</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListCapHint :count="rows.length" />
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" title="生成对账单" width="820px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="供应商">
            <el-select id="st-supplier" v-model="cr.supplierId" filterable placeholder="选择供应商" style="width:100%" @change="loadPreview">
              <el-option v-for="p in rd.suppliers.value" :key="p.id" :value="p.id" :label="p.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="对账截止日期（这天及以前入库的）"><el-date-picker id="st-period" v-model="cr.periodTo" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" @change="loadPreview" /></el-form-item>
        </div>
        <el-alert v-if="cr.preview?.missingPrice.length" type="warning" :closable="false" show-icon
                  :title="`${cr.preview.missingPrice.length} 个批次的采购单没有单价，不会进这张对账单。请先到采购入库录入单价：${missingText}`" />
        <el-table v-if="cr.preview" :data="cr.preview.lines" size="small" max-height="260" empty-text="这段时间没有可结算的批次（只结算检验放行的来料）" scrollbar-tabindex="0">
          <el-table-column label="批号" width="150"><template #default="{ row }"><span class="mono">{{ row.batchNo }}</span><div class="muted small mono">{{ row.poNo }}</div></template></el-table-column>
          <el-table-column prop="itemName" label="物料" min-width="120" />
          <el-table-column label="收货" width="100"><template #default="{ row }"><span class="mono">{{ row.receivedOn }}</span></template></el-table-column>
          <el-table-column label="数量" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span></template></el-table-column>
          <el-table-column label="含税单价" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.unitPrice) }}</span></template></el-table-column>
          <el-table-column label="含税金额" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template></el-table-column>
        </el-table>
        <div v-if="cr.preview?.lines.length" class="form-grid" style="margin-top:12px">
          <el-form-item label="调整金额（扣款填负数，选填）"><el-input-number id="st-adjust" v-model="cr.adjustment" :precision="2" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="调整原因"><el-input id="st-adjust-note" v-model="cr.adjustmentNote" maxlength="300" /></el-form-item>
        </div>
        <p v-if="cr.preview?.lines.length" class="total">合计 <b class="mono">¥{{ fmtMoney(previewTotal) }}</b> <span class="muted small">货款 ¥{{ fmtMoney(cr.preview.goodsAmount) }}，其中税额 ¥{{ fmtMoney(cr.preview.taxAmount) }}</span></p>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" :disabled="!cr.preview?.lines.length" @click="submitCreate">生成并发给供应商</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dt.visible" :title="`对账单：${dt.s?.statementNo || ''}`" width="820px">
      <template v-if="dt.s">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="供应商">{{ dt.s.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="dt.s.status" :label="dt.s.statusLabel" /></el-descriptions-item>
          <el-descriptions-item label="含税货款"><span class="mono">{{ fmtMoney(dt.s.goodsAmount) }}</span>（税额 <span class="mono">{{ fmtMoney(dt.s.taxAmount) }}</span>）</el-descriptions-item>
          <el-descriptions-item label="调整"><span class="mono">{{ fmtMoney(dt.s.adjustment) }}</span> {{ dt.s.adjustmentNote || '' }}</el-descriptions-item>
          <el-descriptions-item label="合计"><b class="mono">{{ fmtMoney(dt.s.totalAmount) }}</b></el-descriptions-item>
          <el-descriptions-item label="已付 / 未付"><span class="mono">{{ fmtMoney(dt.s.paidAmount) }} / {{ fmtMoney(dt.s.unpaid) }}</span></el-descriptions-item>
          <el-descriptions-item label="制单">{{ dt.s.createdBy }} · {{ fmtTime(dt.s.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="供应商确认">{{ dt.s.confirmedBy ? `${dt.s.confirmedBy} · ${fmtTime(dt.s.confirmedAt)}` : '—' }}</el-descriptions-item>
          <el-descriptions-item label="发票">{{ dt.s.invoiceNo ? `${dt.s.invoiceNo} · ${dt.s.invoiceDate} · ${fmtMoney(dt.s.invoiceAmount)}` : '—' }}</el-descriptions-item>
          <el-descriptions-item label="审批">{{ dt.s.approvedBy ? `${dt.s.approvedBy} · ${fmtTime(dt.s.approvedAt)}` : '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="dt.s.lines" size="small" max-height="240" style="margin-top:12px" scrollbar-tabindex="0">
          <el-table-column label="批号" width="150"><template #default="{ row }"><span class="mono">{{ row.batchNo }}</span><div class="muted small mono">{{ row.extBatchNo }}</div></template></el-table-column>
          <el-table-column prop="poNo" label="采购单" width="130" />
          <el-table-column prop="itemName" label="物料" min-width="120" />
          <el-table-column label="数量" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span></template></el-table-column>
          <el-table-column label="单价" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.unitPrice) }}</span></template></el-table-column>
          <el-table-column label="金额" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template></el-table-column>
        </el-table>
        <div v-if="dt.s.payments.length" style="margin-top:12px">
          <b class="small">付款记录</b>
          <div v-for="(p, i) in dt.s.payments" :key="i" class="small"><span class="mono">{{ p.payDate }} · {{ fmtMoney(p.amount) }}</span><span class="muted"> · {{ p.referenceNo || '无凭证号' }} · {{ p.createdBy }}</span></div>
        </div>
        <AttachmentList owner-type="SUPPLIER_STATEMENT" :owner-id="dt.s.id" :can-upload="auth.canAttach()" title="附件（发票扫描件）" style="margin-top:12px" />
      </template>
    </el-dialog>

    <el-dialog v-model="iv.visible" :title="`代登记发票：${iv.s?.statementNo || ''}`" width="460px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">供应商没开门户时用。发票金额要和对账单合计一致。</p>
        <el-form-item label="发票号"><el-input id="si-no" v-model="iv.invoiceNo" maxlength="60" /></el-form-item>
        <el-form-item label="开票日期"><el-date-picker id="si-date" v-model="iv.invoiceDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
        <el-form-item label="发票金额（含税）"><el-input-number id="si-amount" v-model="iv.amount" :min="0" :precision="2" :controls="false" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="iv.visible = false">取消</el-button>
        <el-button type="primary" :loading="iv.saving" @click="submitInvoice">登记发票</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ap.visible" :title="`审批：${ap.s?.statementNo || ''}`" width="440px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p style="margin-top:0">{{ ap.s?.supplierName }}，合计 <b class="mono">¥{{ fmtMoney(ap.s?.totalAmount) }}</b>，发票 <span class="mono">{{ ap.s?.invoiceNo }}</span>。</p>
        <p v-if="ap.s?.createdBy === auth.user?.username" class="late small">这张对账单是您生成的，审批要由另一位财务签批。</p>
        <el-form-item label="签批密码"><el-input id="ap-password" v-model="ap.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ap.visible = false">取消</el-button>
        <el-button type="primary" :loading="ap.saving" @click="submitApprove">签批审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pa.visible" :title="`登记付款：${pa.s?.statementNo || ''}`" width="460px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">只做登记，实际付款在银行或财务系统完成。未付 ¥{{ fmtMoney(pa.s?.unpaid) }}。</p>
        <el-form-item label="付款日期"><el-date-picker id="pa-date" v-model="pa.payDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
        <el-form-item label="付款金额"><el-input-number id="pa-amount" v-model="pa.amount" :min="0" :precision="2" :controls="false" style="width:100%" /></el-form-item>
        <el-form-item label="付款凭证号（选填）"><el-input id="pa-ref" v-model="pa.referenceNo" maxlength="60" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pa.visible = false">取消</el-button>
        <el-button type="primary" :loading="pa.saving" @click="submitPay">登记付款</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtMoney, fmtQty, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const rd = useRefData()
const canBuy = computed(() => auth.hasAny('ADMIN', 'PURCHASE'))
const canFinance = computed(() => auth.hasAny('ADMIN', 'FINANCE'))
const rows = ref([])
const filterBy = ref('')
const loader = useLoader(async () => { rows.value = await http.get('/statements') })
const load = () => loader.run()
const filterOptions = computed(() => [...new Map(rows.value.map((r) => [r.status, r.statusLabel])).entries()].map(([value, label]) => ({ value, label })))
const t = usePaged(rows, { match: textMatch('statementNo', 'supplierName', 'invoiceNo'), extra: (r) => !filterBy.value || r.status === filterBy.value })
const startOfToday = startOfBusinessDay()
const canVoid = (s) => ['SENT', 'DISPUTED', 'CONFIRMED', 'INVOICED'].includes(s.status)
onMounted(() => { load(); rd.load() })
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const previewReq = latestOnly()
const cr = reactive({ visible: false, saving: false, supplierId: null, periodTo: null, preview: null, adjustment: undefined, adjustmentNote: '' })
// 只列前几个：真实数据可能几百个批次，全列出来会撑满屏幕
const missingText = computed(() => {
  const list = cr.preview?.missingPrice || []
  return list.slice(0, 5).map((m) => m.poNo + ' ' + m.itemName).join('、') + (list.length > 5 ? ` 等 ${list.length} 个` : '')
})
const previewTotal = computed(() => Number(cr.preview?.goodsAmount || 0) + Number(cr.adjustment || 0))
async function openCreate() {
  await rd.ready()
  Object.assign(cr, { visible: true, saving: false, supplierId: null, periodTo: today(), preview: null, adjustment: undefined, adjustmentNote: '' })
}
async function loadPreview() {
  const current = previewReq()
  cr.preview = null
  if (!cr.supplierId || !cr.periodTo) return
  const res = await http.get('/statements/preview', { params: { supplierId: cr.supplierId, periodTo: cr.periodTo } })
  if (current()) cr.preview = res
}
async function submitCreate() {
  if (cr.adjustment && !cr.adjustmentNote.trim()) return ElMessage.warning('有调整金额时要写明原因')
  cr.saving = true
  try {
    const s = await http.post('/statements', { supplierId: cr.supplierId, periodTo: cr.periodTo, adjustment: cr.adjustment || 0, adjustmentNote: cr.adjustmentNote })
    ElMessage.success(`对账单 ${s.statementNo} 已生成，等供应商确认`)
    cr.visible = false
    load()
  } finally {
    cr.saving = false
  }
}

const dt = reactive({ visible: false, s: null })
function openDetail(s) {
  Object.assign(dt, { visible: true, s })
}

async function voidStatement(s) {
  let reason
  try {
    reason = (await ElMessageBox.prompt('作废后这些批次可以重新结算。', `作废 ${s.statementNo}`, { confirmButtonText: '作废', cancelButtonText: '不作废', inputValidator: (v) => !!v?.trim() || '请填写作废原因' })).value
  } catch {
    return
  }
  await http.post(`/statements/${s.id}/cancel`, { reason })
  ElMessage.success('对账单已作废')
  load()
}

const iv = reactive({ visible: false, saving: false, s: null, invoiceNo: '', invoiceDate: null, amount: undefined })
function openInvoice(s) {
  Object.assign(iv, { visible: true, saving: false, s, invoiceNo: '', invoiceDate: today(), amount: Number(s.totalAmount) })
}
async function submitInvoice() {
  if (!iv.invoiceNo.trim() || !iv.invoiceDate || !(iv.amount > 0)) return ElMessage.warning('请填写发票号、日期和金额')
  iv.saving = true
  try {
    await http.post(`/statements/${iv.s.id}/invoice`, { invoiceNo: iv.invoiceNo, invoiceDate: iv.invoiceDate, amount: iv.amount })
    ElMessage.success('发票已登记，等财务审批')
    iv.visible = false
    load()
  } finally {
    iv.saving = false
  }
}

const ap = reactive({ visible: false, saving: false, s: null, password: '' })
function openApprove(s) {
  Object.assign(ap, { visible: true, saving: false, s, password: '' })
}
async function submitApprove() {
  if (!ap.password) return ElMessage.warning('请输入签批密码')
  ap.saving = true
  try {
    await http.post(`/statements/${ap.s.id}/approve`, { password: ap.password })
    ElMessage.success('已审批，可以付款')
    ap.visible = false
    load()
  } finally {
    ap.saving = false
  }
}

const pa = reactive({ visible: false, saving: false, s: null, payDate: null, amount: undefined, referenceNo: '' })
function openPay(s) {
  Object.assign(pa, { visible: true, saving: false, s, payDate: today(), amount: Number(s.unpaid), referenceNo: '' })
}
async function submitPay() {
  if (!pa.payDate || !(pa.amount > 0)) return ElMessage.warning('请填写付款日期和金额')
  pa.saving = true
  try {
    await http.post(`/statements/${pa.s.id}/pay`, { payDate: pa.payDate, amount: pa.amount, referenceNo: pa.referenceNo })
    ElMessage.success('付款已登记')
    pa.visible = false
    load()
  } finally {
    pa.saving = false
  }
}
</script>

<style scoped>
.late { color: var(--el-color-danger); }
.total { margin: 4px 0 0; font-size: 15px; }
</style>
