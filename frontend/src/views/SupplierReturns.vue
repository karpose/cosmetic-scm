<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>退供</h2>
        <p>把来料退回供应商。采购或仓库发起，质量签批后才扣库存（签批人不能是发起人），签批后登记退货物流，供应商在门户确认收货。结算前退的自动减结算数量，结算后退的在下一张对账单冲减。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="supplier-returns" />
        <el-button v-if="canMake" type="primary" @click="openCreate">发起退供</el-button>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters"><el-input v-model="t.keyword.value" placeholder="搜索单号、供应商、批号、物料、运单号" clearable /></div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loader.loading.value" empty-text="还没有退供">
        <el-table-column label="退供单" width="150"><template #default="{ row }"><span class="mono">{{ row.returnNo }}</span><div class="muted small mono">{{ fmtTime(row.createdAt) }}</div></template></el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="150" />
        <el-table-column label="批次和数量" min-width="230">
          <template #default="{ row }">{{ row.itemName }} <BatchNo :no="row.batchNo" /><div class="muted small">{{ fmtQty(row.qty) }} {{ row.unit }} · {{ row.warehouseName }} · 供应商批号 <span class="mono">{{ row.extBatchNo }}</span></div></template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="140" />
        <el-table-column label="退货物流与签收" min-width="190">
          <template #default="{ row }">
            <div v-if="row.trackingNo" class="small">
              <span>{{ row.carrier }} <span class="mono">{{ row.trackingNo }}</span></span>
              <div class="muted small">
                <el-tag v-if="row.supplierConfirmedAt" size="small" type="success" disable-transitions>供应商已签收</el-tag>
                <el-tag v-else size="small" type="warning" disable-transitions>已发出待签收</el-tag>
                <span v-if="row.supplierNote"> · {{ row.supplierNote }}</span>
              </div>
            </div>
            <span v-else class="muted small">{{ row.status === 'DONE' ? '待登记退货物流' : '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.signedBy" class="muted small">{{ row.signedBy }}</div><div v-if="row.cancelReason" class="muted small">{{ row.cancelReason }}</div></template></el-table-column>
        <el-table-column label="" width="170" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING' && canSign" size="small" type="primary" @click="openApprove(row)">签批</el-button>
            <el-button v-if="row.status === 'PENDING' && canMake" size="small" text @click="cancel(row)">取消</el-button>
            <el-button v-if="row.status === 'DONE' && !row.supplierConfirmedAt && canMake" size="small" @click="openShip(row)">{{ row.trackingNo ? '改物流' : '登记物流' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListCapHint :count="rows.length" />
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
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
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const rd = useRefData()
const canMake = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'WAREHOUSE'))
const canSign = computed(() => auth.hasAny('QA', 'QSO'))

const rows = ref([])
const loader = useLoader(async () => { rows.value = await http.get('/supplier-returns') })
const load = () => loader.run()
const t = usePaged(rows, { match: textMatch('returnNo', 'supplierName', 'batchNo', 'extBatchNo', 'itemName', 'trackingNo') })
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
.late { color: var(--el-color-danger); }
</style>
