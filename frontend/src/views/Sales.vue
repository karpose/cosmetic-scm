<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>销售出库</h2>
        <p>出库时系统只分配已上市放行的批次，按“先到期先出”排序，并排除剩余效期达不到客户接收标准的批次。批号随出库单记录到客户，用于召回。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="shipments" />
        <el-button v-if="canCreate" type="primary" @click="openCreate">新建出库单</el-button>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:0 0 8px">
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
        <el-table-column label="品项" min-width="180">
          <template #default="{ row }"><div v-for="l in row.lines" :key="l.itemId">{{ l.itemName }} × {{ fmtQty(l.qty) }}</div></template>
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
            <div v-for="(l, i) in form.lines" :key="i" class="line-row">
              <el-select aria-label="成品" :id="`so-item-${i}`" v-model="l.itemId" placeholder="选择成品" filterable @change="preview">
                <el-option v-for="f in rd.finished.value" :key="f.id" :value="f.id" :label="`${f.code} ${f.name}`" />
              </el-select>
              <el-input-number :id="`so-qty-${i}`" v-model="l.qty" :min="0" :precision="0" :controls="false" placeholder="数量" style="width:100%" @change="preview" />
              <el-button text :disabled="form.lines.length === 1" @click="form.lines.splice(i, 1); preview()">删除</el-button>
            </div>
            <el-button size="small" @click="form.lines.push({ itemId: null, qty: null })">添加一行</el-button>
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
import { fmtQty } from '../utils/format'

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
const form = reactive({ visible: false, saving: false, customerId: null, warehouseId: null, lines: [] })

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
    lines: form.lines.filter((l) => l.itemId && l.qty > 0).map(({ itemId, qty }) => ({ itemId, qty }))
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

function openCreate() {
  const own = rd.ownWarehouses.value[0]
  Object.assign(form, { visible: true, customerId: null, warehouseId: own?.id || null, lines: [{ itemId: null, qty: null }] })
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
.preview { background: var(--el-fill-color-lighter); }
.pl { margin-top: 8px; line-height: 2; }
.pick { margin-right: 10px; }
</style>
