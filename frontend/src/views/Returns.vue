<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>销售退货</h2>
        <p>客户退回的成品先进入退货隔离区，不参与先到期先出分配；再由质量人员逐行签批“转可售”或“报废”。召回回收的货也在这里登记并关联召回单，计入召回进度，且只能报废。</p>
      </div>
      <el-button v-if="canCreate" type="primary" @click="openCreate()">登记退货</el-button>
    </div>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="state" size="small">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="PENDING">待处置</el-radio-button>
            <el-radio-button value="DONE">已处置</el-radio-button>
          </el-radio-group>
          <el-input v-model="t.keyword.value" placeholder="搜索退货单、客户、批号" clearable />
        </div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loader.loading.value" empty-text="还没有退货">
        <el-table-column label="退货单" width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.returnNo }}</span>
            <div v-if="row.recallNo"><el-tag type="danger" size="small" disable-transitions>召回 {{ row.recallNo }}</el-tag></div>
          </template>
        </el-table-column>
        <el-table-column prop="customerName" label="客户" min-width="150" />
        <el-table-column label="退回批次与处置" min-width="360">
          <template #default="{ row }">
            <div v-for="l in row.lines" :key="l.id" class="rl">
              <BatchNo :no="l.batchNo" />
              <span class="small">{{ l.itemName }} × <span class="mono">{{ fmtQty(l.qty) }}</span></span>
              <StatusTag :status="l.disposition" :label="l.dispositionLabel" />
              <span v-if="l.disposition !== 'PENDING'" class="muted small">
                {{ l.targetWarehouseName ? `→ ${l.targetWarehouseName}` : '' }} {{ l.disposedByName }} · {{ l.dispositionNote }}
              </span>
              <el-button v-else-if="canDispose" size="small" type="primary" plain @click="openDispose(row, l)">处置</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="退货原因" min-width="160" show-overflow-tooltip />
        <el-table-column label="入库" width="140">
          <template #default="{ row }"><span class="mono small">{{ row.returnDate }}</span><div class="muted small">{{ row.warehouseName }} · {{ row.createdBy }}</div></template>
        </el-table-column>
      </el-table>
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <!-- 登记退货 -->
    <el-dialog v-model="c.visible" title="登记退货" width="720px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="客户">
            <el-select id="rt-customer" v-model="c.customerId" filterable placeholder="选择客户" style="width:100%" @change="loadReturnable">
              <el-option v-for="cu in rd.customers.value" :key="cu.id" :value="cu.id" :label="cu.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="关联召回（召回回收时选择）">
            <el-select v-model="c.recallId" clearable placeholder="不关联" style="width:100%" @change="onRecallChange">
              <el-option v-for="r in openRecalls" :key="r.id" :value="r.id" :label="`${r.recallNo} · ${r.itemName} ${r.batchNo}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="入退货隔离区">
            <el-select v-model="c.warehouseId" style="width:100%">
              <el-option v-for="w in rd.returnWarehouses.value" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
        </div>
        <el-alert v-if="!rd.returnWarehouses.value.length && rd.s.loaded" type="warning" :closable="false" show-icon
                  title="还没有退货隔离区，请先在主数据里新增一个类型为“退货隔离区”的仓库。" />
        <el-form-item label="退回批次">
          <div style="width:100%">
            <div v-for="(l, i) in c.lines" :key="i" class="line-row">
              <el-select aria-label="退货批次" v-model="l.batchId" :loading="returnableLoading" filterable :placeholder="c.customerId ? '选择发给该客户的批次' : '先选客户'" :disabled="!c.customerId">
                <el-option v-for="b in returnableOptions" :key="b.batchId" :value="b.batchId" :disabled="Number(b.returnableQty) <= 0"
                           :label="`${b.itemName} · ${b.batchNo} · 已发 ${fmtQty(b.shippedQty)}，可退 ${fmtQty(b.returnableQty)}`" />
              </el-select>
              <el-input-number v-model="l.qty" :min="0" :controls="false" placeholder="数量" style="width:100%" />
              <el-button text :disabled="c.lines.length === 1" @click="c.lines.splice(i, 1)">删除</el-button>
            </div>
            <el-button size="small" :disabled="!c.customerId" @click="c.lines.push({ batchId: null, qty: null })">添加一行</el-button>
            <p v-if="c.customerId && !returnable.length" class="muted small">该客户还没有出库记录，无可退批次。</p>
          </div>
        </el-form-item>
        <el-form-item label="退货原因">
          <el-input v-model="c.reason" type="textarea" :rows="2" maxlength="300" show-word-limit placeholder="如：物流外箱破损，客户拒收" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="c.visible = false">取消</el-button>
        <el-button type="primary" :loading="c.saving" :disabled="returnableLoading" @click="submitCreate">确认退货入库</el-button>
      </template>
    </el-dialog>

    <!-- 质量处置 -->
    <el-dialog v-model="p.visible" title="退货处置" width="520px" :close-on-click-modal="false">
      <template v-if="p.line">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="退货单">{{ p.ret.returnNo }} · {{ p.ret.customerName }}</el-descriptions-item>
          <el-descriptions-item label="批次"><BatchNo :no="p.line.batchNo" /> {{ p.line.itemName }} × {{ fmtQty(p.line.qty) }} {{ p.line.unit }}</el-descriptions-item>
          <el-descriptions-item label="批次状态">{{ p.line.batchStatusLabel }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-position="top" style="margin-top:14px" @submit.prevent>
          <el-form-item label="处置结论">
            <el-radio-group v-model="p.disposition">
              <el-radio value="RESTOCK" :disabled="!restockAllowed">转可售</el-radio>
              <el-radio value="SCRAP">报废</el-radio>
            </el-radio-group>
            <div v-if="!restockAllowed" class="muted small" style="width:100%">{{ p.ret.recallId ? '召回回收的货只能报废。' : `批次${p.line.batchStatusLabel}，只能报废。` }}</div>
          </el-form-item>
          <el-form-item v-if="p.disposition === 'RESTOCK'" label="转入自有仓">
            <el-select v-model="p.targetId" style="width:100%">
              <el-option v-for="w in rd.ownWarehouses.value" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="处置说明">
            <el-input v-model="p.note" type="textarea" :rows="2" maxlength="300" :placeholder="p.disposition === 'RESTOCK' ? '如：外观、密封完好，复检合格' : '如：外包装破损，按不合格品销毁'" />
          </el-form-item>
          <el-form-item label="签批密码（本人登录密码）">
            <el-input v-model="p.password" type="password" show-password autocomplete="current-password" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="p.visible = false">取消</el-button>
        <el-button :type="p.disposition === 'SCRAP' ? 'danger' : 'primary'" :loading="p.saving" @click="submitDispose">
          {{ p.disposition === 'SCRAP' ? '签批报废' : '签批转可售' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const rd = useRefData()
const list = ref([])
const recalls = ref([])
const state = ref('')
const canCreate = computed(() => auth.hasAny('ADMIN', 'SALES', 'WAREHOUSE'))
const canDispose = computed(() => auth.hasAny('QA', 'QSO'))

const loader = useLoader(async () => {
  const [rs, rc] = await Promise.all([http.get('/sales-returns'), http.get('/recalls')])
  list.value = rs
  recalls.value = rc
})
const load = () => loader.run()
const t = usePaged(list, {
  match: textMatch('returnNo', 'customerName', 'reason', 'recallNo', (r) => r.lines.map((l) => l.batchNo + l.itemName).join(' ')),
  extra: (r) => !state.value || (state.value === 'PENDING' ? r.pendingLines > 0 : r.pendingLines === 0)
})
const openRecalls = computed(() => recalls.value.filter((r) => r.status === 'OPEN'))

// ---------- 登记退货 ----------
const c = reactive({ visible: false, saving: false, customerId: null, recallId: null, warehouseId: null, reason: '', lines: [] })
const returnable = ref([])
const recallOf = (id) => recalls.value.find((r) => r.id === id)
const returnableOptions = computed(() => {
  const r = recallOf(c.recallId)
  return r ? returnable.value.filter((b) => b.batchId === r.batchId) : returnable.value
})

async function openCreate(prefill = {}) {
  await rd.ready()
  Object.assign(c, {
    visible: true, saving: false, customerId: prefill.customerId || null, recallId: prefill.recallId || null,
    warehouseId: rd.returnWarehouses.value[0]?.id || null,
    reason: prefill.recallId ? `召回回收（${recallOf(prefill.recallId)?.recallNo || ''}）` : '',
    lines: [{ batchId: null, qty: null }]
  })
  returnable.value = []
  if (c.customerId) loadReturnable()
}

const returnableReq = latestOnly()
// 可退批次到手之前不能提交：批次还是空的，点了只会提示“请至少填一行”，响应回来又自动填上，看不出刚才为什么没成
const returnableLoading = ref(false)

async function loadReturnable() {
  const current = returnableReq()
  c.lines.forEach((l) => { l.batchId = null })
  returnableLoading.value = !!c.customerId
  try {
    const rows = c.customerId ? await http.get('/sales-returns/returnable', { params: { customerId: c.customerId } }) : []
    if (!current()) return
    returnable.value = rows
    onRecallChange()
  } finally {
    if (current()) returnableLoading.value = false
  }
}

function onRecallChange() {
  const r = recallOf(c.recallId)
  if (r && returnableOptions.value.length === 1) c.lines[0].batchId = r.batchId
}

async function submitCreate() {
  const lines = c.lines.filter((l) => l.batchId && l.qty > 0)
  if (!lines.length) return ElMessage.warning('请至少填一行批次和数量')
  c.saving = true
  try {
    const r = await http.post('/sales-returns', { customerId: c.customerId, warehouseId: c.warehouseId, recallId: c.recallId, reason: c.reason, lines })
    ElMessage.success(`退货单 ${r.returnNo} 已入隔离区，等待质量处置`)
    c.visible = false
    load()
  } finally {
    c.saving = false
  }
}

// ---------- 处置 ----------
const p = reactive({ visible: false, saving: false, ret: null, line: null, disposition: 'SCRAP', targetId: null, note: '', password: '' })
const restockAllowed = computed(() => p.line && p.line.batchStatusLabel === '已放行' && !p.ret.recallId)

async function openDispose(ret, line) {
  await rd.ready()
  Object.assign(p, { visible: true, saving: false, ret, line, note: '', password: '', targetId: rd.ownWarehouses.value[0]?.id || null })
  p.disposition = restockAllowed.value ? 'RESTOCK' : 'SCRAP'
}

async function submitDispose() {
  p.saving = true
  try {
    await http.post(`/sales-returns/lines/${p.line.id}/dispose`, {
      disposition: p.disposition, targetWarehouseId: p.disposition === 'RESTOCK' ? p.targetId : null, note: p.note, password: p.password
    })
    ElMessage.success(p.disposition === 'RESTOCK' ? '已转可售' : '已报废出库')
    p.visible = false
    load()
  } finally {
    p.saving = false
  }
}

onMounted(async () => {
  await Promise.all([load(), rd.load()])
  // 从召回页“登记回收”跳转过来时，自动打开并带上客户和召回单
  const { customerId, recallId } = route.query
  if (customerId && canCreate.value) {
    openCreate({ customerId: Number(customerId), recallId: recallId ? Number(recallId) : null })
    router.replace({ query: {} })
  }
})
</script>

<style scoped>
.rl { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding: 3px 0; }
</style>
