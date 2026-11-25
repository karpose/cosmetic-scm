<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>销售出库</h2>
        <p>出库时系统只分配已上市放行的批次，按“先到期先出”排序，并排除剩余效期达不到客户接收标准的批次。批号随出库单记录到客户，用于召回。</p>
      </div>
      <div class="toolbar">
        <ExportButton v-if="activeTab === 'records'" report="sales-records" :params="{ from: sr.from, to: sr.to }" label="导出销售记录" />
        <ExportButton v-else :report="activeTab === 'prices' ? 'customer-prices' : 'shipments'" />
        <el-button v-if="canCreate && activeTab === 'orders'" type="primary" @click="openCreate">新建出库单</el-button>
        <el-button v-if="canPrice && activeTab === 'prices'" type="primary" @click="openPriceCreate">新增客户协议价</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="seg" @tab-change="onTabChange">
      <el-tab-pane label="销售出库单" name="orders" />
      <el-tab-pane label="产品销售记录" name="records" />
      <el-tab-pane v-if="canPrice" label="客户协议价" name="prices" />
    </el-tabs>

    <template v-if="activeTab === 'records'">
      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header>
          <div class="filters">
            <el-date-picker v-model="sr.range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"
                            :clearable="false" aria-label="销售日期范围" @change="loadRecords" />
            <el-checkbox v-model="sr.onlyGaps">只看不全的</el-checkbox>
            <span v-if="sr.data" class="muted small">{{ sr.data.orders }} 张出库单、{{ sr.data.rows.length }} 行<template v-if="sr.data.incompleteRows">，<span class="warn">{{ sr.data.incompleteRows }} 行不全</span></template></span>
          </div>
          <p class="muted small rec-hint">按《化妆品生产质量管理规范》第五十八条：产品名称、注册证或备案编号、使用期限（限期使用日期）、净含量、数量、销售日期、价格，以及购买者名称、地址和联系方式。购买者和产品信息是出库时记下的；价格取出库单价，出库时没定价的取对账单上的价<template v-if="sr.data && !sr.data.priceVisible">，价格只有销售、财务和管理员能看</template>。最多查一年。</p>
          <el-alert v-if="sr.data?.gapSummary.length" type="warning" :closable="false" show-icon :title="`缺的内容：${sr.data.gapSummary.join('，')}`"
                    description="购买者地址、联系方式和成品净含量到“主数据”补；价格在对账时补；以后的出库会自动带上。" />
        </template>
        <LoadError v-if="sr.error" :message="sr.error" :loading="sr.loading" @retry="loadRecords" />
        <el-table v-else :data="srRows" v-loading="sr.loading" empty-text="这段时间没有销售出库" class="rec-table">
          <el-table-column label="销售日期" width="110" fixed><template #default="{ row }"><span class="mono">{{ row.saleDate }}</span><div class="muted small mono">{{ row.soNo }}</div></template></el-table-column>
          <el-table-column label="产品" min-width="170"><template #default="{ row }">{{ row.itemName }}<div class="muted small">{{ row.filingNo || '没有备案编号' }}</div></template></el-table-column>
          <el-table-column label="批号和使用期限" min-width="160"><template #default="{ row }"><BatchNo :no="row.batchNo" /><div class="muted small">{{ row.expDate ? `限期使用 ${row.expDate}` : '没有限期使用日期' }}</div></template></el-table-column>
          <el-table-column label="净含量" width="90"><template #default="{ row }">{{ row.netContent || '—' }}</template></el-table-column>
          <el-table-column label="数量" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span> <span class="muted small">{{ row.unit }}</span></template></el-table-column>
          <el-table-column v-if="sr.data?.priceVisible" label="含税单价" width="120" align="right">
            <template #default="{ row }"><template v-if="row.unitPrice != null"><span class="mono">¥{{ fmtQty(row.unitPrice) }}</span><div class="muted small">{{ row.priceSource }} · ¥{{ fmtMoney(row.amount) }}</div></template><span v-else class="muted">—</span></template>
          </el-table-column>
          <el-table-column label="购买者" min-width="220"><template #default="{ row }">{{ row.buyerName }}<div class="muted small">{{ row.buyerAddress || '没有地址' }} · {{ row.buyerContact || '没有联系方式' }}</div><div v-if="row.platformOrderNo" class="muted small mono">{{ row.platformOrderNo }}</div></template></el-table-column>
          <el-table-column label="核对" min-width="150"><template #default="{ row }"><el-tag v-if="!row.gaps.length" type="success" size="small" disable-transitions>齐全</el-tag><span v-else class="warn small">{{ row.gaps.join('、') }}</span></template></el-table-column>
        </el-table>
      </el-card>
    </template>

    <div v-if="activeTab === 'orders' && summary" class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">近 {{ summary.windowDays }} 天出库单</div>
        <div class="kpi-val mono">{{ summary.ordersInWindow }} <span class="kpi-unit">单</span></div>
        <div class="kpi-sub">累计历史出库 {{ summary.totalOrdersAll }} 单</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">近 {{ summary.windowDays }} 天出库件数</div>
        <div class="kpi-val mono">{{ fmtQty(summary.unitsInWindow) }}</div>
        <div class="kpi-sub">覆盖 {{ summary.activeCustomersInWindow }} 家活跃客户</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">近 {{ summary.windowDays }} 天出库金额</div>
        <div class="kpi-val mono">{{ summary.amountInWindow != null ? fmtMoney(summary.amountInWindow) : '—' }}</div>
        <div class="kpi-sub">{{ summary.amountInWindow != null ? '已维护单价行含税合计' : '金额按角色权限隐藏' }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">主要出库渠道</div>
        <div class="kpi-val">{{ summary.byChannel.length ? summary.byChannel[0].channel : '—' }}</div>
        <div class="kpi-sub">
          <template v-if="summary.byChannel.length">
            {{ summary.byChannel.slice(0, 2).map((c) => `${c.channel} ${fmtQty(c.totalQty)}`).join(' · ') }}
          </template>
          <template v-else>近 {{ summary.windowDays }} 天暂无出库</template>
        </div>
      </div>
    </div>

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

    <el-card v-else-if="activeTab === 'orders'" shadow="never" body-style="padding:0 0 8px">
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
        <el-table-column label="销售记录" width="110">
          <template #default="{ row }">
            <el-tooltip v-if="row.recordGaps.length" :content="row.recordGaps.join('、')" placement="top"><el-tag type="warning" size="small" disable-transitions tabindex="0">不全</el-tag></el-tooltip>
            <span v-else class="muted small">齐全</span>
          </template>
        </el-table-column>
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
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import BatchNo from '../components/BatchNo.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'
import { daysFromToday, fmtMoney, fmtQty, today } from '../utils/format'
import { latestOnly } from '../utils/latest'

const auth = useAuth()
const rd = useRefData()
const orders = ref([])
const summary = ref(null)
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
const route = useRoute()
const activeTab = ref(route.query.tab === 'records' || (route.query.tab === 'prices' && canPrice.value) ? route.query.tab : 'orders')
// 产品销售记录
const sr = reactive({ range: [daysFromToday(-29), today()], onlyGaps: false, loading: false, error: '', data: null, get from() { return this.range[0] }, get to() { return this.range[1] } })
const srRows = computed(() => (sr.data?.rows || []).filter((r) => !sr.onlyGaps || r.gaps.length))
const srReq = latestOnly()
async function loadRecords() {
  const current = srReq()
  sr.loading = true
  sr.error = ''
  try {
    const d = await http.get('/sales-orders/records', { params: { from: sr.from, to: sr.to } })
    if (current()) sr.data = d
  } catch (e) {
    if (current()) sr.error = e.message
  } finally {
    if (current()) sr.loading = false
  }
}
const cp = reactive({ loading: false, rows: [] })
const cpForm = reactive({ visible: false, saving: false, customerId: null, itemId: null, unitPrice: null, taxRate: null, validFrom: null, validTo: null, note: '' })
async function loadCp() {
  cp.loading = true
  try { cp.rows = await http.get('/customer-prices') } finally { cp.loading = false }
}
function onTabChange(name) {
  if (name === 'prices') loadCp()
  if (name === 'records') loadRecords()
}
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
    const [list, sum] = await Promise.all([
      http.get('/sales-orders'),
      http.get('/sales-orders/summary', { silent: true }).catch(() => null)
    ])
    orders.value = list
    summary.value = sum
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
    if (so.recordGaps?.length) ElMessage.warning({ message: `出库单 ${so.soNo} 已完成，但销售记录缺：${so.recordGaps.join('、')}。请到主数据补齐，以后的出库会自动带上`, duration: 8000 })
    else ElMessage.success(`出库单 ${so.soNo} 已完成`)
    form.visible = false
    load()
  } finally {
    form.saving = false
  }
}

onMounted(() => {
  if (activeTab.value !== 'orders') onTabChange(activeTab.value)
  return Promise.all([load(), rd.load()])
})
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
.kpi-card { background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 12px 14px; }
.kpi-label { font-size: 12px; color: var(--el-text-color-secondary); }
.kpi-val { font-size: 22px; font-weight: 700; margin: 4px 0 2px; line-height: 1.2; }
.kpi-unit { font-size: 12px; font-weight: 400; color: var(--el-text-color-secondary); }
.kpi-sub { font-size: 12px; color: var(--el-text-color-secondary); }
.line-row.priced { display: grid; grid-template-columns: 2fr 1fr 1.2fr 1.1fr auto; gap: 8px; }
.preview { background: var(--el-fill-color-lighter); }
.pl { margin-top: 8px; line-height: 2; }
.pick { margin-right: 10px; }
.warn { color: var(--el-color-danger); }
.rec-hint { margin: 8px 0 0; line-height: 1.6; }
@media (max-width: 900px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
