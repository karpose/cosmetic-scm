<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>采购入库</h2>
        <p>只能向合格名录内、资质在有效期内的供应商下单；禁用原料不能采购。收货后物料批次进入“待检”，检验放行前不能发料。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="purchases" />
        <el-button v-if="canCreate" type="primary" @click="openCreate">新建采购单</el-button>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="t.keyword.value" placeholder="搜索单号、供应商、物料" clearable />
          <el-select v-model="filterBy" placeholder="全部状态" clearable filterable>
            <el-option v-for="o in filterOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </div>
      </template>
      <LoadError v-if="loadError" :message="loadError" :loading="loading" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loading" empty-text="还没有采购单">
        <el-table-column label="单号" width="150"><template #default="{ row }"><span class="mono">{{ row.poNo }}</span></template></el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="160" />
        <el-table-column label="日期" width="110"><template #default="{ row }"><span class="mono">{{ row.orderDate }}</span></template></el-table-column>
        <el-table-column label="物料（已收 / 订购）" min-width="260">
          <template #default="{ row }">
            <div v-for="l in row.lines" :key="l.id">
              {{ l.itemName }} <span class="mono muted small">{{ fmtQty(l.receivedQty) }} / {{ fmtQty(l.qty) }} {{ l.unit }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.closeReason" class="muted small" :title="row.closedBy">{{ row.closeReason }}</div></template></el-table-column>
        <el-table-column label="" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="isOpen(row) && canReceive" size="small" @click="openReceive(row)">收货</el-button>
            <el-button v-if="isOpen(row) && canCreate" size="small" text @click="closeOrder(row)">{{ row.status === 'PARTIAL' ? '关闭' : '取消' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListCapHint :count="orders.length" />
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <!-- 新建采购单 -->
    <el-dialog v-model="create.visible" title="新建采购单" width="640px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="供应商">
          <el-select id="po-supplier" v-model="create.supplierId" placeholder="选择供应商" filterable style="width:100%">
            <el-option v-for="p in rd.suppliers.value" :key="p.id" :value="p.id" :disabled="!p.usable"
                       :label="p.name + (p.usable ? '' : (p.qualified ? '（资质已过期）' : '（未进合格名录）'))" />
          </el-select>
          <div class="muted small">灰色选项不在合格名录或资质已过期，不能下单。</div>
        </el-form-item>
        <el-form-item label="物料与数量">
          <div style="width:100%">
            <div v-for="(l, i) in create.lines" :key="i" class="line-row">
              <el-select :id="`po-item-${i}`" v-model="l.itemId" placeholder="选择物料" filterable>
                <el-option v-for="m in rd.materials.value" :key="m.id" :value="m.id" :disabled="m.banned"
                           :label="`${m.code} ${m.name}${m.banned ? '（禁用原料）' : ''}`" />
              </el-select>
              <el-input-number :id="`po-qty-${i}`" v-model="l.qty" :min="0" :precision="4" :controls="false" placeholder="数量" style="width:100%" />
              <el-button text :disabled="create.lines.length === 1" @click="create.lines.splice(i, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="create.lines.push({ itemId: null, qty: null })">添加一行</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="create.visible = false">取消</el-button>
        <el-button type="primary" :loading="create.saving" @click="submitCreate">保存采购单</el-button>
      </template>
    </el-dialog>

    <!-- 收货 -->
    <el-dialog v-model="recv.visible" :title="`收货：${recv.po?.poNo || ''}`" width="860px">
      <p class="muted small" style="margin-top:0">供应商：{{ recv.po?.supplierName }}。收货后系统生成内部批号，批次状态为“待检”。有效期留空时按物料保质期推算。</p>
      <el-card v-for="l in recv.lines" :key="l.poLineId" shadow="never" style="margin-bottom:12px">
        <div style="margin-bottom:8px"><b>{{ l.itemName }}</b> <span class="muted small">{{ l.itemCode }} · 未收 {{ fmtQty(l.remaining) }} {{ l.unit }}{{ l.shelfMonths ? ` · 保质期 ${l.shelfMonths} 个月` : '' }}</span></div>
        <div class="form-grid">
          <el-form-item label="本次收货数量"><el-input-number :id="`rv-qty-${l.poLineId}`" v-model="l.qty" :min="0" :max="Number(l.remaining)" :precision="4" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="供应商批号"><el-input :id="`rv-ext-${l.poLineId}`" v-model="l.extBatchNo" placeholder="来料标签上的批号" /></el-form-item>
          <el-form-item label="生产日期"><el-date-picker :id="`rv-mfg-${l.poLineId}`" v-model="l.mfgDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="有效期至"><el-date-picker :id="`rv-exp-${l.poLineId}`" v-model="l.expDate" type="date" value-format="YYYY-MM-DD" placeholder="可留空" style="width:100%" /></el-form-item>
          <el-form-item label="入库仓">
            <el-select :id="`rv-wh-${l.poLineId}`" v-model="l.warehouseId" style="width:100%">
              <el-option v-for="w in rd.ownWarehouses.value" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
        </div>
      </el-card>
      <template #footer>
        <el-button @click="recv.visible = false">取消</el-button>
        <el-button type="primary" :loading="recv.saving" @click="submitReceive">确认收货</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'
import { fmtQty, today } from '../utils/format'

const auth = useAuth()
const rd = useRefData()
const orders = ref([])
const loading = ref(false)
const loadError = ref('')
const filterBy = ref('')
const filterOptions = computed(() => [...new Map(orders.value.map((r) => [r.status, r.statusLabel])).entries()]
  .map(([value, label]) => ({ value, label })))
const t = usePaged(orders, {
  match: textMatch('poNo', 'supplierName', (r) => r.lines.map((l) => l.itemName + l.itemCode).join(' ')),
  extra: (r) => !filterBy.value || r.status === filterBy.value
})
const canCreate = computed(() => auth.hasAny('ADMIN', 'PURCHASE'))
const canReceive = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'WAREHOUSE'))
const isOpen = (row) => row.status === 'OPEN' || row.status === 'PARTIAL'

async function closeOrder(row) {
  const partial = row.status === 'PARTIAL'
  let reason
  try {
    reason = (await ElMessageBox.prompt(
      partial ? '已收的批次不受影响；没到的余量不再收货，也不再计入补货建议的在途。' : '这张单还没收过货，取消后不能恢复，记录会保留。',
      `${partial ? '关闭' : '取消'} ${row.poNo}`,
      { inputPlaceholder: '原因', confirmButtonText: partial ? '关闭采购单' : '取消采购单', cancelButtonText: '返回', inputValidator: (v) => !!v?.trim() || '请填写原因' }
    )).value
  } catch {
    return
  }
  await http.post(`/purchase-orders/${row.id}/close`, { reason })
  ElMessage.success(partial ? '已关闭' : '已取消')
  load()
}

const create = reactive({ visible: false, saving: false, supplierId: null, lines: [] })
const recv = reactive({ visible: false, saving: false, po: null, lines: [] })

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    orders.value = await http.get('/purchase-orders')
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(create, { visible: true, supplierId: null, lines: [{ itemId: null, qty: null }] })
}

async function submitCreate() {
  const lines = create.lines.filter((l) => l.itemId || l.qty)
  if (!create.supplierId) return ElMessage.warning('请选择供应商')
  if (!lines.length || lines.some((l) => !l.itemId || !(l.qty > 0))) return ElMessage.warning('请把每一行的物料和数量填完整')
  create.saving = true
  try {
    const po = await http.post('/purchase-orders', { supplierId: create.supplierId, lines })
    ElMessage.success(`采购单 ${po.poNo} 已保存`)
    create.visible = false
    load()
  } finally {
    create.saving = false
  }
}

function openReceive(po) {
  const wh = rd.ownWarehouses.value[0]?.id || null
  recv.po = po
  recv.lines = po.lines.filter((l) => Number(l.remaining) > 0).map((l) => ({
    poLineId: l.id, itemName: l.itemName, itemCode: l.itemCode, unit: l.unit, shelfMonths: l.shelfMonths,
    remaining: l.remaining, qty: Number(l.remaining), extBatchNo: '', mfgDate: today(), expDate: null, warehouseId: wh
  }))
  recv.visible = true
}

async function submitReceive() {
  const lines = recv.lines.filter((l) => l.qty > 0)
  if (!lines.length) return ElMessage.warning('没有填写收货数量')
  if (lines.some((l) => !l.extBatchNo.trim())) return ElMessage.warning('请填写每一行的供应商批号')
  recv.saving = true
  try {
    const res = await http.post(`/purchase-orders/${recv.po.id}/receipts`, {
      lines: lines.map(({ poLineId, qty, extBatchNo, mfgDate, expDate, warehouseId }) => ({ poLineId, qty, extBatchNo, mfgDate, expDate, warehouseId }))
    })
    ElMessage.success(`已收货，生成待检批次 ${res.batchNos.join('、')}`)
    recv.visible = false
    load()
  } finally {
    recv.saving = false
  }
}

onMounted(() => Promise.all([load(), rd.load()]))
</script>
