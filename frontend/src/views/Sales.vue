<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>销售出库</h2>
        <p>出库时系统只分配已上市放行的批次，按“先到期先出”排序，并排除剩余效期达不到客户接收标准的批次。批号随出库单记录到客户，用于召回。</p>
      </div>
      <div class="toolbar">
        <ExportButton :report="activeTab === 'prices' ? 'customer-prices' : 'shipments'" />
        <el-button v-if="canCreate && activeTab === 'orders'" type="primary" @click="openCreate">新建出库单</el-button>
        <el-button v-if="canPrice && activeTab === 'prices'" type="primary" @click="openPriceCreate">新增客户协议价</el-button>
      </div>
    </div>

    <el-tabs v-if="canPrice" v-model="activeTab" class="seg" @tab-change="onTabChange">
      <el-tab-pane label="销售出库单" name="orders" />
      <el-tab-pane label="客户协议价" name="prices" />
    </el-tabs>

    <el-card v-if="activeTab === 'prices'" shadow="never" body-style="padding:0 0 8px">
      <el-table :data="cp.rows" v-loading="cp.loading" empty-text="还没有维护客户协议价">
        <el-table-column label="客户" min-width="160"><template #default="{ row }">{{ row.customerName }}<div class="muted small mono">{{ row.customerCode }}</div></template></el-table-column>
        <el-table-column label="成品" min-width="170"><template #default="{ row }">{{ row.itemName }}<div class="muted small mono">{{ row.itemCode }}</div></template></el-table-column>
        <el-table-column label="含税供货价" width="120" align="right"><template #default="{ row }"><span class="mono">¥{{ fmtQty(row.unitPrice) }}</span></template></el-table-column>
        <el-table-column label="税率" width="80" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.taxRate) }}%</span></template></el-table-column>
        <el-table-column label="有效期" width="200"><template #default="{ row }"><span class="mono">{{ row.validFrom }} ~ {{ row.validTo || '长期' }}</span></template></el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="!row.active ? 'info' : row.effectiveToday ? 'success' : 'warning'" disable-transitions>{{ !row.active ? '已停用' : row.effectiveToday ? '生效中' : '未生效/过期' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="120" />
        <el-table-column label="" width="90" fixed="right">
          <template #default="{ row }"><el-button size="small" text @click="toggleCp(row)">{{ row.active ? '停用' : '启用' }}</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="t.keyword.value" placeholder="搜索单号、客户、批号" clearable />
          <el-select aria-label="按客户筛选" v-model="filterBy" placeholder="全部客户" clearable filterable>
            <el-option v-for="o in filterOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </div>
      </template>
      <LoadError v-if="loadError" :message="loadError" :loading="loading" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loading" empty-text="还没有出库单">
        <el-table-column label="单号" width="150"><template #default="{ row }"><span class="mono">{{ row.soNo }}</span></template></el-table-column>
        <el-table-column prop="customerName" label="客户" min-width="160" />
        <el-table-column prop="channel" label="渠道" width="120" />
        <el-table-column prop="warehouseName" label="发货仓" width="110" />
        <el-table-column label="品项" min-width="210">
          <template #default="{ row }">
            <div v-for="l in row.lines" :key="l.itemId">
              {{ l.itemName }} × {{ fmtQty(l.qty) }}
              <span v-if="l.unitPrice != null" class="muted small mono"> · ¥{{ fmtQty(l.unitPrice) }}（¥{{ fmtMoney(l.amount) }}）</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="出库批次" min-width="180">
          <template #default="{ row }"><div v-for="a in row.allocations" :key="a.batchId"><BatchNo :no="a.batchNo" /> <span class="mono small">{{ fmtQty(a.qty) }}</span></div></template>
        </el-table-column>
        <el-table-column label="日期" width="110"><template #default="{ row }"><span class="mono">{{ row.orderDate }}</span></template></el-table-column>
      </el-table>
      <ListCapHint :count="orders.length" />
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <el-dialog v-model="form.visible" title="新建出库单" width="720px">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="客户 / 渠道">
            <el-select id="so-customer" v-model="form.customerId" placeholder="选择客户" filterable style="width:100%" @change="preview">
              <el-option v-for="c in rd.customers.value" :key="c.id" :value="c.id" :label="`${c.name}${c.minShelfPct ? `（效期 ≥ ${c.minShelfPct}%）` : ''}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="发货仓">
            <el-select id="so-wh" v-model="form.warehouseId" style="width:100%" @change="preview">
              <el-option v-for="w in rd.stockWarehouses.value" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="品项">
          <div style="width:100%">
            <div v-for="(l, i) in form.lines" :key="i" class="line-row" :class="{ priced: canPrice }">
              <el-select aria-label="成品" :id="`so-item-${i}`" v-model="l.itemId" placeholder="选择成品" filterable @change="preview">
                <el-option v-for="f in rd.finished.value" :key="f.id" :value="f.id" :label="`${f.code} ${f.name}`" />
              </el-select>
              <el-input-number :id="`so-qty-${i}`" v-model="l.qty" :min="0" :precision="0" :controls="false" placeholder="数量" style="width:100%" @change="preview" />
              <el-input-number v-if="canPrice" :id="`so-price-${i}`" v-model="l.unitPrice" :min="0" :precision="4" :controls="false" placeholder="含税单价(选填)" style="width:100%" />
              <el-input-number v-if="canPrice" :id="`so-tax-${i}`" v-model="l.taxRate" :min="0" :max="100" :precision="2" :controls="false" :placeholder="`税率% (默认${defaultTaxRate})`" style="width:100%" />
              <el-button text :disabled="form.lines.length === 1" @click="form.lines.splice(i, 1); preview()">删除</el-button>
            </div>
            <el-button size="small" @click="form.lines.push({ itemId: null, qty: null, unitPrice: null, taxRate: null })">添加一行</el-button>
          </div>
        </el-form-item>
        <el-card shadow="never" class="preview">
          <template v-if="plan">
            <div class="muted small">{{ plan.customerName }} 要求剩余效期不低于 {{ plan.minShelfPct }}%</div>
            <div v-for="l in plan.lines" :key="l.itemId" class="pl">
              <b>{{ l.itemName }}</b> × {{ fmtQty(l.qty) }}：
              <span v-for="p in l.picks" :key="p.batchId + '-' + p.warehouseId" class="pick">
                <BatchNo :no="p.batchNo" /> <span class="mono small">{{ fmtQty(p.qty) }}</span> <span class="muted small">效期 {{ p.expDate }}</span>
              </span>
              <span v-if="!l.picks.length" class="muted">无可用批次</span>
              <el-tag :type="Number(l.shortQty) > 0 ? 'danger' : 'success'" size="small" disable-transitions>
                {{ Number(l.shortQty) > 0 ? `缺 ${fmtQty(l.shortQty)}` : '可发' }}
              </el-tag>
              <span v-if="Number(l.excludedQty) > 0" class="muted small">（另有 {{ fmtQty(l.excludedQty) }} 件因效期不达标被排除）</span>
            </div>
          </template>
          <span v-else class="muted small">填写客户和品项后，这里显示系统按先到期先出分配的批次。</span>
        </el-card>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" :loading="form.saving" :disabled="!plan || !plan.fulfilled" @click="submit">确认出库</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cpForm.visible" title="新增客户协议价" width="480px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="客户">
          <el-select v-model="cpForm.customerId" filterable placeholder="选择客户" style="width:100%">
            <el-option v-for="c in rd.customers.value" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="成品">
          <el-select v-model="cpForm.itemId" filterable placeholder="选择成品" style="width:100%">
            <el-option v-for="f in rd.finished.value" :key="f.id" :value="f.id" :label="`${f.code} ${f.name}`" />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="含税供货价"><el-input-number v-model="cpForm.unitPrice" :min="0" :precision="4" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="税率 %（留空取客户默认）"><el-input-number v-model="cpForm.taxRate" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" /></el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="生效日期"><el-date-picker v-model="cpForm.validFrom" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="失效日期（留空长期有效）"><el-date-picker v-model="cpForm.validTo" type="date" value-format="YYYY-MM-DD" style="width:100%" clearable /></el-form-item>
        </div>
        <el-form-item label="备注（选填）"><el-input v-model="cpForm.note" maxlength="300" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cpForm.visible = false">取消</el-button>
        <el-button type="primary" :loading="cpForm.saving" @click="submitCp">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import BatchNo from '../components/BatchNo.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'
import { fmtMoney, fmtQty } from '../utils/format'

const auth = useAuth()
const rd = useRefData()
const orders = ref([])
const loading = ref(false)
const loadError = ref('')
const filterBy = ref('')
const filterOptions = computed(() => [...new Map(orders.value.map((r) => [r.customerId, r.customerName])).entries()]
  .map(([value, label]) => ({ value, label })))
const t = usePaged(orders, {
  match: textMatch('soNo', 'customerName', 'channel', (r) => r.allocations.map((a) => a.batchNo + a.itemName).join(' ')),
  extra: (r) => !filterBy.value || r.customerId === filterBy.value
})
const plan = ref(null)
const canCreate = computed(() => auth.hasAny('ADMIN', 'SALES', 'WAREHOUSE'))
const canPrice = computed(() => auth.hasAny('ADMIN', 'SALES', 'FINANCE'))
const activeTab = ref('orders')
const cp = reactive({ loading: false, rows: [] })
const cpForm = reactive({ visible: false, saving: false, customerId: null, itemId: null, unitPrice: null, taxRate: null, validFrom: null, validTo: null, note: '' })
async function loadCp() {
  cp.loading = true
  try { cp.rows = await http.get('/customer-prices') } finally { cp.loading = false }
}
function onTabChange(name) { if (name === 'prices') loadCp() }
async function openPriceCreate() {
  await rd.ready()
  Object.assign(cpForm, { visible: true, saving: false, customerId: null, itemId: null, unitPrice: null, taxRate: null, validFrom: null, validTo: null, note: '' })
}
async function submitCp() {
  if (!cpForm.customerId || !cpForm.itemId || !(cpForm.unitPrice > 0)) return ElMessage.warning('请填写客户、成品和含税供货价')
  cpForm.saving = true
  try {
    await http.post('/customer-prices', { customerId: cpForm.customerId, itemId: cpForm.itemId, unitPrice: cpForm.unitPrice, taxRate: cpForm.taxRate, validFrom: cpForm.validFrom || null, validTo: cpForm.validTo || null, note: cpForm.note })
    ElMessage.success('客户协议价已保存')
    cpForm.visible = false
    loadCp()
  } finally { cpForm.saving = false }
}
async function toggleCp(row) {
  await http.post(`/customer-prices/${row.id}/active`, null, { params: { active: !row.active } })
  ElMessage.success(row.active ? '已停用' : '已启用')
  loadCp()
}
const form = reactive({ visible: false, saving: false, customerId: null, warehouseId: null, lines: [] })
const defaultTaxRate = computed(() => rd.customers.value.find((c) => c.id === form.customerId)?.defaultTaxRate ?? 13)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    orders.value = await http.get('/sales-orders')
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
}

function payload() {
  return {
    customerId: form.customerId,
    warehouseId: form.warehouseId,
    lines: form.lines.filter((l) => l.itemId && l.qty > 0).map(({ itemId, qty, unitPrice, taxRate }) => ({
      itemId, qty, unitPrice: canPrice.value && unitPrice > 0 ? unitPrice : null, taxRate: canPrice.value && unitPrice > 0 ? taxRate : null
    }))
  }
}

let seq = 0
async function preview() {
  const body = payload()
  if (!body.customerId || !body.warehouseId || !body.lines.length) {
    plan.value = null
    return
  }
  const mine = ++seq
  try {
    const p = await http.post('/sales-orders/preview', body, { silent: true })
    if (mine === seq) plan.value = p
  } catch {
    if (mine === seq) plan.value = null
  }
}

async function openCreate() {
  await rd.ready()
  const own = rd.ownWarehouses.value[0]
  Object.assign(form, { visible: true, customerId: null, warehouseId: own?.id || null, lines: [{ itemId: null, qty: null, unitPrice: null, taxRate: null }] })
  plan.value = null
}

async function submit() {
  form.saving = true
  try {
    const so = await http.post('/sales-orders', payload())
    ElMessage.success(`出库单 ${so.soNo} 已完成`)
    form.visible = false
    load()
  } finally {
    form.saving = false
  }
}

onMounted(() => Promise.all([load(), rd.load()]))
</script>

<style scoped>
.line-row.priced { display: grid; grid-template-columns: 2fr 1fr 1.2fr 1.1fr auto; gap: 8px; }
.preview { background: var(--el-fill-color-lighter); }
.pl { margin-top: 8px; line-height: 2; }
.pick { margin-right: 10px; }
</style>
