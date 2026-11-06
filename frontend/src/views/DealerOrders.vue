<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>经销商订单</h2>
        <p>经销商在订货门户提交的订单。审核通过时系统按先到期先出、并按该经销商的效期接收标准分配批次，直接生成销售出库单；库存不够时不能通过。</p>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="state" size="small">
            <el-radio-button value="SUBMITTED">待审核</el-radio-button>
            <el-radio-button value="">全部</el-radio-button>
          </el-radio-group>
          <el-input v-model="t.keyword.value" placeholder="搜索订单号、经销商、商品" clearable />
        </div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loader.loading.value" empty-text="没有符合条件的订单">
        <el-table-column label="订单号" width="150"><template #default="{ row }"><span class="mono">{{ row.orderNo }}</span><div class="muted small">{{ fmtTime(row.createdAt) }}</div></template></el-table-column>
        <el-table-column label="经销商" min-width="150"><template #default="{ row }">{{ row.customerName }}<div class="muted small">效期 ≥ {{ row.minShelfPct }}%</div></template></el-table-column>
        <el-table-column label="商品" min-width="180"><template #default="{ row }"><div v-for="l in row.lines" :key="l.itemId">{{ l.itemName }} × <span class="mono">{{ fmtQty(l.qty) }}</span></div></template></el-table-column>
        <el-table-column label="期望到货 / 备注" min-width="150"><template #default="{ row }"><span class="mono">{{ row.requestedDate || '—' }}</span><div class="muted small">{{ row.note }}</div></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
        <el-table-column label="审核结果" min-width="200">
          <template #default="{ row }">
            <template v-if="row.soNo"><span class="mono">{{ row.soNo }}</span><div v-for="s in row.shipped" :key="s.batchNo" class="small"><BatchNo :no="s.batchNo" /> × {{ fmtQty(s.qty) }}</div></template>
            <span v-else-if="row.reviewNote" class="small">{{ row.reviewNote }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="170" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'SUBMITTED' && canReview">
              <el-button size="small" type="primary" @click="openApprove(row)">审核发货</el-button>
              <el-button size="small" @click="reject(row)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <el-dialog v-model="ap.visible" :title="`审核发货：${ap.order?.orderNo || ''}`" width="640px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="发货仓">
          <el-select v-model="ap.warehouseId" style="width:100%" @change="preview">
            <el-option v-for="w in rd.ownWarehouses.value" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="ap.plan" class="plan glass">
        <div class="muted small">{{ ap.plan.customerName }} 要求剩余效期不低于 {{ ap.plan.minShelfPct }}%</div>
        <div v-for="l in ap.plan.lines" :key="l.itemId" class="pl">
          <b>{{ l.itemName }}</b> × {{ fmtQty(l.qty) }}：
          <span v-for="p in l.picks" :key="p.batchId" class="pick"><BatchNo :no="p.batchNo" /> <span class="mono small">{{ fmtQty(p.qty) }}</span></span>
          <el-tag :type="Number(l.shortQty) > 0 ? 'danger' : 'success'" size="small" disable-transitions>{{ Number(l.shortQty) > 0 ? `缺 ${fmtQty(l.shortQty)}` : '可发' }}</el-tag>
        </div>
      </div>
      <template #footer>
        <el-button @click="ap.visible = false">取消</el-button>
        <el-button type="primary" :loading="ap.saving" :disabled="!ap.plan?.fulfilled" @click="approve">审核通过并出库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime } from '../utils/format'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const rd = useRefData()
const list = ref([])
const state = ref('SUBMITTED')
const canReview = computed(() => auth.hasAny('ADMIN', 'SALES'))
const loader = useLoader(async () => { list.value = await http.get('/dealer-orders') })
const load = () => loader.run()
const t = usePaged(list, {
  match: textMatch('orderNo', 'customerName', 'note', (r) => r.lines.map((l) => l.itemName).join(' ')),
  extra: (r) => !state.value || r.status === state.value
})

const ap = reactive({ visible: false, saving: false, order: null, warehouseId: null, plan: null })

async function openApprove(row) {
  await rd.ready()
  Object.assign(ap, { visible: true, saving: false, order: row, warehouseId: rd.ownWarehouses.value[0]?.id || null, plan: null })
  preview()
}

async function preview() {
  ap.plan = null
  if (!ap.warehouseId) return
  ap.plan = await http.post('/sales-orders/preview', {
    customerId: ap.order.customerId, warehouseId: ap.warehouseId,
    lines: ap.order.lines.map((l) => ({ itemId: l.itemId, qty: l.qty }))
  }, { silent: true }).catch(() => null)
}

async function approve() {
  ap.saving = true
  try {
    const o = await http.post(`/dealer-orders/${ap.order.id}/approve`, { warehouseId: ap.warehouseId })
    ElMessage.success(`已通过，出库单 ${o.soNo}`)
    ap.visible = false
    load()
  } finally {
    ap.saving = false
  }
}

async function reject(row) {
  let note
  try {
    note = (await ElMessageBox.prompt('驳回原因会显示给经销商。', `驳回 ${row.orderNo}`, {
      confirmButtonText: '驳回订单', cancelButtonText: '取消', inputPlaceholder: '如：该品项本月配额已满',
      inputValidator: (v) => !!v?.trim() || '请写明驳回原因'
    })).value
  } catch {
    return
  }
  await http.post(`/dealer-orders/${row.id}/reject`, { note })
  ElMessage.success('已驳回')
  load()
}

onMounted(() => Promise.all([load(), rd.load()]))
</script>

<style scoped>
.plan { padding: 12px 16px; border-radius: 14px; }
.pl { margin-top: 8px; line-height: 2; }
.pick { margin-right: 10px; }
</style>
