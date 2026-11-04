<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>订货与召回</h2>
        <p>{{ auth.user?.partnerName }}：“可订数量”已按贵方的效期接收标准计算，只含满足要求的批次。订单提交后由品牌方审核，审核通过即按先到期先出发货，发货批号会显示在订单里。</p>
      </div>
      <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
    </div>

    <el-alert v-if="pendingRecalls.length" type="error" show-icon :closable="false"
              :title="`有 ${pendingRecalls.length} 条召回通知待确认：请立即停止销售相关批次，并确认收到。`">
      <el-button size="small" type="danger" plain style="margin-top:6px" @click="tab = 'recalls'">查看召回通知</el-button>
    </el-alert>

    <el-tabs v-model="tab" class="seg">
      <el-tab-pane label="订货" name="order" />
      <el-tab-pane :label="`我的订单（${orders.length}）`" name="orders" />
      <el-tab-pane :label="`召回通知${pendingRecalls.length ? `（${pendingRecalls.length} 待确认）` : ''}`" name="recalls" />
    </el-tabs>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />

    <!-- 订货 -->
    <el-card v-else-if="tab === 'order'" shadow="never" body-style="padding:0 0 8px">
      <el-table :data="catalog" v-loading="loader.loading.value" empty-text="暂时没有可订商品">
        <el-table-column label="商品" min-width="200"><template #default="{ row }">{{ row.name }}<div class="muted small mono">{{ row.code }}</div></template></el-table-column>
        <el-table-column prop="filingNo" label="备案编号" min-width="140" />
        <el-table-column label="可订数量" width="130" align="right">
          <template #default="{ row }"><span class="mono" :class="{ muted: !Number(row.available) }">{{ Number(row.available) ? fmtQty(row.available) : '暂无' }}</span> {{ row.unit }}</template>
        </el-table-column>
        <el-table-column label="订购数量" width="170">
          <template #default="{ row }">
            <el-input-number aria-label="订购数量" v-model="cart[row.itemId]" :min="0" :max="Number(row.available)" :precision="0" :disabled="!Number(row.available)" :controls="false" placeholder="0" style="width:100%" />
          </template>
        </el-table-column>
      </el-table>
      <div class="cart">
        <span class="muted small">已选 {{ cartLines.length }} 个品项，共 <b class="mono">{{ fmtQty(cartTotal) }}</b> 件</span>
        <el-date-picker v-model="requestedDate" type="date" value-format="YYYY-MM-DD" placeholder="期望到货日期（可选）" :disabled-date="(d) => d < startOfToday" />
        <el-input v-model="note" placeholder="备注（可选）" maxlength="300" style="max-width:260px" />
        <el-button type="primary" :disabled="!cartLines.length" :loading="saving" @click="submitOrder">提交订货单</el-button>
      </div>
    </el-card>

    <!-- 我的订单 -->
    <el-card v-else-if="tab === 'orders'" shadow="never" body-style="padding:0">
      <el-table :data="orders" v-loading="loader.loading.value" empty-text="还没有订单">
        <el-table-column label="订单号" width="150"><template #default="{ row }"><span class="mono">{{ row.orderNo }}</span><div class="muted small">{{ fmtTime(row.createdAt) }}</div></template></el-table-column>
        <el-table-column label="商品" min-width="180"><template #default="{ row }"><div v-for="l in row.lines" :key="l.itemId">{{ l.itemName }} × <span class="mono">{{ fmtQty(l.qty) }}</span></div></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
        <el-table-column label="发货批号" min-width="220">
          <template #default="{ row }">
            <div v-for="s in row.shipped" :key="s.batchNo" class="small"><span class="mono">{{ s.batchNo }}</span> {{ s.itemName }} × {{ fmtQty(s.qty) }} <span class="muted">效期 {{ s.expDate }}</span></div>
            <span v-if="row.reviewNote" class="small" style="color: var(--el-color-danger)">驳回原因：{{ row.reviewNote }}</span>
            <span v-if="!row.shipped.length && !row.reviewNote" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="100" fixed="right">
          <template #default="{ row }"><el-button v-if="row.status === 'SUBMITTED'" size="small" @click="cancel(row)">撤回</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 召回通知 -->
    <div v-else class="recalls">
      <el-empty v-if="!recalls.length" description="没有召回通知" />
      <section v-for="r in recalls" :key="r.noticeId" class="recall glass" :class="{ pending: !r.notifiedAt }">
        <div class="rh">
          <b>召回 {{ r.recallNo }}</b>
          <el-tag :type="r.notifiedAt ? 'success' : 'danger'" size="small" disable-transitions>{{ r.notifiedAt ? '已确认' : '待确认' }}</el-tag>
          <span class="muted small">{{ fmtTime(r.openedAt) }} 发起 · {{ r.statusLabel }}</span>
        </div>
        <div>{{ r.itemName }} · 批号 <span class="mono"><b>{{ r.batchNo }}</b></span> · 贵方收货 <span class="mono">{{ fmtQty(r.shippedQty) }}</span></div>
        <div class="small">原因：{{ r.reason }}</div>
        <div v-if="r.notifiedAt" class="muted small">{{ r.notifiedBy }} 于 {{ fmtTime(r.notifiedAt) }} 确认 · {{ r.note }}</div>
        <div v-else class="toolbar">
          <el-button type="danger" @click="ack(r)">确认收到，已停止销售</el-button>
          <span class="muted small">退货请联系品牌方销售登记“召回回收”。</span>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime } from '../utils/format'
import { useLoader } from '../utils/loader'

const auth = useAuth()
const tab = ref('order')
const catalog = ref([])
const orders = ref([])
const recalls = ref([])
const cart = reactive({})
const requestedDate = ref(null)
const note = ref('')
const saving = ref(false)
const startOfToday = new Date(new Date().setHours(0, 0, 0, 0))

const loader = useLoader(async () => {
  const [c, o, r] = await Promise.all([http.get('/portal/dealer/catalog'), http.get('/portal/dealer/orders'), http.get('/portal/dealer/recalls')])
  catalog.value = c
  orders.value = o
  recalls.value = r
  if (r.some((x) => !x.notifiedAt)) tab.value = 'recalls'
})
const load = () => loader.run()

const pendingRecalls = computed(() => recalls.value.filter((r) => !r.notifiedAt))
const cartLines = computed(() => Object.entries(cart).filter(([, q]) => q > 0).map(([itemId, qty]) => ({ itemId: Number(itemId), qty })))
const cartTotal = computed(() => cartLines.value.reduce((a, l) => a + l.qty, 0))

async function submitOrder() {
  saving.value = true
  try {
    const o = await http.post('/portal/dealer/orders', { requestedDate: requestedDate.value, note: note.value, lines: cartLines.value })
    ElMessage.success(`订货单 ${o.orderNo} 已提交，等待品牌方审核`)
    Object.keys(cart).forEach((k) => delete cart[k])
    note.value = ''
    requestedDate.value = null
    tab.value = 'orders'
    load()
  } finally {
    saving.value = false
  }
}

async function cancel(row) {
  try {
    await ElMessageBox.confirm(`撤回订货单 ${row.orderNo}？`, '撤回订单', { confirmButtonText: '撤回订单', cancelButtonText: '不撤回' })
  } catch {
    return
  }
  await http.post(`/portal/dealer/orders/${row.id}/cancel`)
  ElMessage.success('已撤回')
  load()
}

async function ack(r) {
  try {
    await ElMessageBox.confirm(`确认已收到召回 ${r.recallNo}，批号 ${r.batchNo} 已停止销售？`, '确认召回通知', { confirmButtonText: '确认收到', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  recalls.value = await http.post(`/portal/dealer/recalls/${r.noticeId}/ack`)
  ElMessage.success('已确认，品牌方会联系安排退回')
}

onMounted(load)
</script>

<style scoped>
.cart { display: flex; gap: 10px; align-items: center; justify-content: flex-end; flex-wrap: wrap; padding: 12px 16px 4px; }
.recalls { display: grid; gap: 12px; }
.recall { padding: 14px 18px; display: grid; gap: 6px; }
.recall.pending { border-color: color-mix(in srgb, var(--el-color-danger) 45%, transparent); }
.rh { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
</style>
