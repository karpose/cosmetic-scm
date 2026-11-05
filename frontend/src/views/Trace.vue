<template>
  <div class="page">
    <div class="page-head no-print">
      <div>
        <h2>批次追溯</h2>
        <p>输入任一批号：向上查到原料批次、供应商和生产方，向下查到成品批次和收货客户。发现问题时可在这里一键冻结受影响批次。</p>
      </div>
    </div>

    <el-card shadow="never" class="no-print">
      <form class="toolbar" @submit.prevent="go">
        <el-input id="trace-no" v-model="input" placeholder="输入系统批号，或供应商、受托厂的批号" clearable class="mono" style="flex:1 1 260px" />
        <el-button type="primary" native-type="submit" :loading="loading">追溯</el-button>
      </form>
    </el-card>

    <!-- 查的不是系统批号，而是外部批号时：列出对应的系统批次 -->
    <el-card v-if="hits.length" shadow="never" class="no-print">
      <template #header>“{{ searched }}”不是系统批号，找到这些匹配的批次</template>
      <el-table :data="hits" empty-text="没有匹配">
        <el-table-column label="系统批号" min-width="170">
          <template #default="{ row }"><el-button link type="primary" @click="openHit(row)">{{ row.batchNo }}</el-button></template>
        </el-table-column>
        <el-table-column label="物料" min-width="180"><template #default="{ row }">{{ row.itemName }}<div class="muted small mono">{{ row.itemCode }}</div></template></el-table-column>
        <el-table-column prop="matchLabel" label="匹配方式" min-width="220" />
        <el-table-column prop="statusLabel" label="状态" width="110" />
      </el-table>
    </el-card>

    <template v-if="t">
      <div class="print-only print-head">
        <h2>批次追溯报告</h2>
        <p class="small">打印时间 {{ printedAt }} · 打印人 {{ auth.user?.displayName }}</p>
      </div>
      <el-alert v-if="t.source.type === '期初导入'" type="warning" show-icon :closable="false"
                title="这是期初导入的批次：上线前的领用和发货记录不在系统里，下面的流向可能不完整，请到旧系统或台账核对。" />
      <el-card shadow="never">
        <template #header>
          <div class="head">
            <span><b>{{ t.batch.itemName }}</b> <BatchNo :no="t.batch.batchNo" /></span>
            <span class="toolbar">
              <StatusTag :status="t.batch.status" :label="t.batch.statusLabel" />
              <span class="muted small">追溯用时 {{ elapsedText }} 毫秒</span>
              <el-button class="no-print" size="small" @click="printReport">打印追溯报告</el-button>
              <el-button class="no-print" size="small" @click="$router.push({ path: '/labels', query: { batches: t.batch.batchNo } })">打印标签</el-button>
            </span>
          </div>
        </template>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="类型">{{ ITEM_TYPE[t.batch.itemType] }}{{ t.filingNo ? ` · 备案编号 ${t.filingNo}` : '' }}</el-descriptions-item>
          <el-descriptions-item label="生产 / 有效期"><span class="mono">{{ fmtDate(t.batch.mfgDate) }} → {{ fmtDate(t.batch.expDate) }}</span> <ShelfBar :ratio="t.batch.shelfRatio" /></el-descriptions-item>
          <el-descriptions-item label="来源">{{ t.source.type }} <span class="mono">{{ t.source.no }}</span>{{ t.source.moTypeLabel ? `（${t.source.moTypeLabel}）` : '' }}</el-descriptions-item>
          <el-descriptions-item :label="t.batch.itemType === 'FG' ? '生产方' : '供应商'">
            {{ t.source.partnerName || '—' }}<span v-if="t.source.licenseNo" class="muted small"> · {{ t.source.licenseNo }}</span>
            <span v-if="t.source.extBatchNo" class="mono small"> · 外部批号 {{ t.source.extBatchNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="当前库存">
            <span v-if="!t.stock.length">0</span>
            <span v-for="s in t.stock" :key="s.warehouseName">{{ s.warehouseName }} {{ fmtQty(s.qty) }}　</span>
          </el-descriptions-item>
          <el-descriptions-item label="签批记录">
            <div v-for="d in t.decisions" :key="d.id" class="small">
              {{ d.stageLabel }} · {{ d.decisionLabel }} · {{ d.signedByName }} · <span class="mono">{{ fmtTime(d.signedAt) }}</span>
              <span v-if="d.retainQty"> · 留样 {{ d.retainQty }} 件，保存至 {{ d.retainUntil }}</span>
            </div>
            <span v-if="!t.decisions.length" class="muted">—</span>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <div class="grid2">
        <section>
          <h4 class="col-h">向上追溯（来源）</h4>
          <div v-if="t.batch.itemType !== 'FG'" class="node">
            <div class="muted small">采购单</div>
            <div class="mono">{{ t.source.no }}</div>
            <div class="small">{{ t.source.partnerName }} · 来料批号 {{ t.source.extBatchNo || '—' }}</div>
          </div>
          <template v-else>
            <div class="node">
              <div class="muted small">{{ t.source.moTypeLabel || '生产单' }}</div>
              <div class="mono">{{ t.source.no }}</div>
              <div class="small">{{ t.source.partnerName }}{{ t.source.licenseNo ? ` · ${t.source.licenseNo}` : '' }}</div>
            </div>
            <div v-for="(u, i) in t.upstream" :key="i" class="node">
              <div>{{ u.itemName }} <BatchNo v-if="u.batchNo" :no="u.batchNo" /> <span class="mono small">{{ fmtQty(u.qty) }} {{ u.unit }}</span></div>
              <div class="muted small">
                {{ u.supplyLabel }}
                <template v-if="u.batchNo"> · {{ u.supplierName }} · 来料批号 {{ u.extBatchNo || '—' }} · {{ u.sourceNo }}</template>
                <template v-else> · 原料批号 <span class="mono">{{ u.extBatchNo }}</span></template>
              </div>
            </div>
          </template>
        </section>
        <section>
          <h4 class="col-h">向下追溯（去向）</h4>
          <div v-for="m in t.downstreamOrders" :key="m.moNo" class="node">
            <div class="muted small">{{ m.moTypeLabel }} <span class="mono">{{ m.moNo }}</span> · 用量 {{ fmtQty(m.usedQty) }} {{ t.batch.unit }} · {{ m.statusLabel }}</div>
            <div><BatchNo v-if="m.outBatchNo" :no="m.outBatchNo" /><span v-else class="muted">尚未完工</span> {{ m.fgName }}</div>
          </div>
          <div v-if="t.batch.itemType !== 'FG' && !t.downstreamOrders.length" class="muted small">该批次尚未用于生产。</div>
          <div v-for="f in t.finishedBatches" :key="f.id" class="node" :class="{ hl: f.batchNo === t.batch.batchNo }">
            <div><BatchNo :no="f.batchNo" /> {{ f.itemName }} <StatusTag :status="f.status" :label="f.statusLabel" /></div>
            <div class="muted small">入库 {{ fmtQty(f.qtyIn) }} · 在库 {{ fmtQty(f.onHand) }} · 已出库 {{ fmtQty(shippedOf(f.batchNo)) }}</div>
          </div>
        </section>
      </div>

      <el-card shadow="never">
        <AttachmentList owner-type="BATCH" :owner-id="t.batch.id" :can-upload="auth.canAttach()"
                        title="批次附件（COA、检验报告、批生产记录）" />
      </el-card>

      <el-card shadow="never" body-style="padding:0">
        <template #header>
          <div class="head">
            <b>已发往的客户</b>
            <span class="toolbar">
              <span class="muted small">{{ customerCount }} 个客户</span>
              <el-button v-if="t.affectedBatchNos.length && canFreeze" size="small" type="danger" plain @click="openFreeze">
                冻结受影响批次（{{ t.affectedBatchNos.length }}）
              </el-button>
            </span>
          </div>
        </template>
        <el-table :data="t.shipments" empty-text="这些批次还没有出库记录">
          <el-table-column prop="customerName" label="客户" min-width="160" />
          <el-table-column prop="channel" label="渠道" width="120" />
          <el-table-column label="批号" width="150"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
          <el-table-column label="数量" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span></template></el-table-column>
          <el-table-column label="出库单" width="140"><template #default="{ row }"><span class="mono">{{ row.soNo }}</span></template></el-table-column>
          <el-table-column label="日期" width="110"><template #default="{ row }"><span class="mono">{{ row.orderDate }}</span></template></el-table-column>
          <el-table-column label="联系方式" min-width="180"><template #default="{ row }">{{ [row.contact, row.address].filter(Boolean).join(' · ') }}</template></el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="t.returns.length || t.recalls.length" shadow="never" body-style="padding:0">
        <template #header>
          <div class="head">
            <b>退回与召回</b>
            <span class="toolbar">
              <router-link v-for="r in t.recalls" :key="r.id" :to="{ path: '/quality-events', query: { tab: 'recalls', recall: r.id } }">
                <el-tag type="danger" size="small" disable-transitions>召回 {{ r.recallNo }} · {{ r.statusLabel }}</el-tag>
              </router-link>
            </span>
          </div>
        </template>
        <el-table :data="t.returns" empty-text="还没有退回记录">
          <el-table-column prop="customerName" label="客户" min-width="160" />
          <el-table-column label="批号" width="150"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
          <el-table-column label="数量" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span></template></el-table-column>
          <el-table-column label="退货单" width="140"><template #default="{ row }"><span class="mono">{{ row.returnNo }}</span></template></el-table-column>
          <el-table-column label="类型" width="100"><template #default="{ row }">{{ row.recallId ? '召回回收' : '客户退货' }}</template></el-table-column>
          <el-table-column prop="dispositionLabel" label="处置" width="100" />
          <el-table-column label="日期" width="110"><template #default="{ row }"><span class="mono">{{ row.returnDate }}</span></template></el-table-column>
        </el-table>
      </el-card>
    </template>

    <el-dialog v-model="fz.visible" title="冻结受影响批次" width="480px">
      <p style="margin-top:0">冻结后这些批次的在库数量不能再发料或出库，直到质量部门处理。</p>
      <div class="toolbar" style="margin-bottom:12px"><span v-for="n in t?.affectedBatchNos || []" :key="n" class="mono tagno">{{ n }}</span></div>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="冻结原因"><el-input id="fz-reason" v-model="fz.reason" type="textarea" :rows="2" placeholder="如：消费者投诉过敏，待调查" /></el-form-item>
        <el-form-item label="签批密码"><el-input id="fz-password" v-model="fz.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fz.visible = false">取消</el-button>
        <el-button type="danger" :loading="fz.saving" @click="submitFreeze">冻结</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ShelfBar from '../components/ShelfBar.vue'
import StatusTag from '../components/StatusTag.vue'
import AttachmentList from '../components/AttachmentList.vue'
import { useAuth } from '../stores/auth'
import { ITEM_TYPE, fmtDate, fmtQty, fmtTime } from '../utils/format'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const input = ref(route.query.no || '')
const t = ref(null)
const hits = ref([])
const searched = ref('')
const printedAt = ref('')
const loading = ref(false)
const canFreeze = computed(() => auth.hasAny('QA', 'QSO'))
const elapsedText = computed(() => (t.value && t.value.elapsedMs >= 1 ? String(t.value.elapsedMs) : '不到 1'))
const customerCount = computed(() => new Set((t.value?.shipments || []).map((s) => s.customerName)).size)
const fz = reactive({ visible: false, saving: false, reason: '', password: '' })

function shippedOf(batchNo) {
  return (t.value?.shipments || []).filter((s) => s.batchNo === batchNo).reduce((a, s) => a + Number(s.qty), 0)
}

function go() {
  const no = input.value.trim()
  if (no) router.push({ name: 'trace', query: { no } })
}

async function searchExternal(no) {
  searched.value = no
  hits.value = await http.get('/trace/search', { params: { q: no } })
  if (!hits.value.length) ElMessage.error(`没有找到批号 ${no}，也没有匹配的供应商或受托厂批号`)
}

function openHit(row) {
  input.value = row.batchNo
  go()
}

function printReport() {
  printedAt.value = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16)
  nextTick(() => window.print())
}

async function load(no) {
  if (!no) {
    t.value = null
    return
  }
  loading.value = true
  hits.value = []
  try {
    t.value = await http.get(`/trace/${encodeURIComponent(no)}`, { silent: true })
  } catch (e) {
    t.value = null
    if (e.status === 404) await searchExternal(no)
    else if (e.status) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

watch(() => route.query.no, (no) => {
  input.value = no || ''
  load(no)
}, { immediate: true })

function openFreeze() {
  Object.assign(fz, { visible: true, reason: '', password: '' })
}

async function submitFreeze() {
  if (!fz.reason.trim() || !fz.password) return ElMessage.warning('请填写冻结原因和签批密码')
  fz.saving = true
  try {
    const res = await http.post('/quality/freeze', { batchNos: t.value.affectedBatchNos, reason: fz.reason, password: fz.password })
    ElMessage.success(`已冻结 ${res.frozen.length} 个批次`)
    fz.visible = false
    load(t.value.batch.batchNo)
  } finally {
    fz.saving = false
  }
}
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; }
.grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.col-h { margin: 0 0 8px; font-size: 13px; color: var(--el-text-color-secondary); font-weight: 500; }
.node { border: 1px solid var(--lg-stroke); border-radius: 14px; padding: 10px 14px; display: grid; gap: 4px; background: var(--lg-surface); -webkit-backdrop-filter: var(--lg-blur); backdrop-filter: var(--lg-blur); box-shadow: var(--lg-highlight), var(--lg-shadow); }
.node + .node { margin-top: 8px; }
.node.hl { border-color: color-mix(in srgb, var(--el-color-primary) 55%, transparent); background: var(--lg-tint); }
.tagno { font-size: 12px; padding: 1px 8px; border: 1px solid var(--el-border-color); border-radius: 999px; }
@media (max-width: 900px) { .grid2 { grid-template-columns: minmax(0, 1fr); } }
</style>
