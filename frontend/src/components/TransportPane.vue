<template>
  <div>
    <p class="muted small intro">
      对应检查要点委托生产版序号 20（GMP 第五十九条）：产品的运输条件要符合产品标签的要求。质量部门给成品设运输条件（系统不内置），
      销售发运和受托厂成品到仓各登记一条运输记录，一条可以包含同一车或同一批快递揽收的多张单据。超出温度范围、要求记录温度却没记录、到货异常的自动生成偏差；
      设了条件的成品出库或完工 {{ missing.graceDays }} 天后还没登记的进总览待办。
    </p>
    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <template v-else>
      <div class="toolbar-row">
        <el-button v-if="canRecord" type="primary" @click="openCreate()">登记运输记录</el-button>
        <el-button v-if="canReq" @click="openReq()">设定运输条件</el-button>
        <ExportButton report="transport-records" :params="{ from: range[0], to: range[1] }" :label="`导出 ${range[0]} 至 ${range[1]} 的运输记录`" />
      </div>

      <el-card shadow="never" class="block" body-style="padding:0 0 8px">
        <template #header>待登记（{{ missing.rows.length }}）</template>
        <p v-if="!missing.activeReqs" class="muted small pad">还没有给成品设运输条件。设了条件以后，这些成品的出库和到仓都要登记运输记录。</p>
        <el-table v-else :data="missing.rows" empty-text="设了运输条件的成品，出库和到仓都已登记运输记录" scrollbar-tabindex="0">
          <el-table-column label="日期" width="120">
            <template #default="{ row }"><span class="mono">{{ row.date }}</span>
              <div><el-tag :type="row.overdue ? 'warning' : 'info'" size="small" disable-transitions>{{ row.overdue ? `超过 ${missing.graceDays} 天` : '登记期限内' }}</el-tag></div>
            </template>
          </el-table-column>
          <el-table-column label="待登记" min-width="220">
            <template #default="{ row }">
              <b>{{ row.directionLabel }}</b> · {{ row.place || '—' }}
              <div class="small">{{ row.docNo ? row.docNo : `${row.count} 张出库单` }}</div>
            </template>
          </el-table-column>
          <el-table-column v-if="canRecord" label="" width="90" :fixed="narrow ? false : 'right'">
            <template #default="{ row }"><el-button size="small" text type="primary" @click="openCreate(row)">登记</el-button></template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="block" body-style="padding:0 0 8px">
        <template #header>运输条件（{{ reqs.length }}）</template>
        <el-table :data="reqs" empty-text="还没有设运输条件：请质量部门按产品标签的贮存条件给成品设定" scrollbar-tabindex="0">
          <el-table-column label="成品" min-width="180">
            <template #default="{ row }">
              <b>{{ row.itemName }}</b> <el-tag v-if="!row.active" size="small" type="info" disable-transitions>已停用</el-tag>
              <div class="muted small mono">{{ row.itemCode }}</div>
              <div v-if="narrow" class="small">{{ row.text }}</div>
            </template>
          </el-table-column>
          <el-table-column v-if="!narrow" label="运输条件" min-width="220" prop="text" />
          <el-table-column label="从哪天起登记" width="130">
            <template #default="{ row }"><span class="mono">{{ row.effectiveFrom }}</span></template>
          </el-table-column>
          <el-table-column v-if="canReq" label="" width="80" :fixed="narrow ? false : 'right'">
            <template #default="{ row }"><el-button size="small" text @click="openReq(row)">修改</el-button></template>
          </el-table-column>
        </el-table>
      </el-card>

      <div class="filters">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"
                        aria-label="发运日期范围" :clearable="false" :disabled-date="(d) => d > endOfToday" style="width:260px" @change="loadRecords" />
      </div>
      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header>运输记录{{ recs ? `（${recs.total}）` : '' }}</template>
        <el-table v-loading="recLoading" :data="recs?.rows || []" empty-text="这段时间没有运输记录" scrollbar-tabindex="0">
          <el-table-column label="记录" min-width="190">
            <template #default="{ row }">
              <span class="mono">{{ row.recordNo }}</span>
              <div class="small">{{ row.directionLabel }} · {{ row.modeLabel }}<template v-if="row.carrier"> · {{ row.carrier }}</template></div>
              <div class="muted small">{{ fmtTime(row.shippedAt) }} 发运<template v-if="row.deliveredAt">，{{ fmtTime(row.deliveredAt) }} 到达</template></div>
              <div v-if="narrow" class="small">{{ row.docsText }}</div>
            </template>
          </el-table-column>
          <el-table-column v-if="!narrow" label="单据" min-width="200">
            <template #default="{ row }">{{ row.docsText }}<div v-if="row.waybillNo" class="muted small">运单 {{ row.waybillNo }}</div></template>
          </el-table-column>
          <el-table-column v-if="!narrow" label="条件 / 途中温度" min-width="190">
            <template #default="{ row }">{{ row.reqText }}<div class="muted small">{{ row.readingText }}<template v-if="row.device">（{{ row.device }}）</template></div></template>
          </el-table-column>
          <el-table-column label="结果" min-width="190">
            <template #default="{ row }">
              <el-tag v-if="row.voided" type="info" size="small" disable-transitions>已作废</el-tag>
              <el-tag v-else :type="row.conforming ? 'success' : 'danger'" size="small" disable-transitions>{{ row.conforming ? '符合' : '不符合' }}</el-tag>
              <span v-if="row.problem" class="small"> {{ row.problem }}</span>
              <div v-if="row.deviationNo" class="small">偏差 <router-link to="/qms?tab=deviations" class="mono">{{ row.deviationNo }}</router-link>（{{ row.deviationStatusLabel }}）</div>
              <div v-if="row.voided" class="muted small">{{ row.voidedBy }} 作废：{{ row.voidReason }}</div>
            </template>
          </el-table-column>
          <el-table-column label="" width="120" :fixed="narrow ? false : 'right'">
            <template #default="{ row }">
              <el-button size="small" text @click="openDetail(row)">详情</el-button>
              <el-button v-if="canVoid && !row.voided" size="small" text type="danger" @click="voidRecord(row)">作废</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="recs && recs.total > recs.rows.length" class="muted small pad">这段时间共 {{ recs.total }} 条，这里显示最近 {{ recs.rows.length }} 条；缩小日期范围或用“导出”查看全部。</p>
      </el-card>
    </template>

    <!-- 设定 / 修改运输条件 -->
    <el-dialog v-model="rq.visible" :title="rq.id ? `修改运输条件：${rq.itemName}` : '设定运输条件'" width="560px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item v-if="!rq.id" label="成品">
          <el-select id="tq-item" v-model="rq.itemId" filterable placeholder="选择成品" style="width:100%">
            <el-option v-for="i in fgOptions" :key="i.id" :value="i.id" :label="`${i.name}（${i.code}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="运输温度范围（℃）">
          <div class="pair">
            <el-input-number id="tq-min" v-model="rq.tempMin" :precision="1" :step="1" :min="-80" :max="100" controls-position="right" placeholder="下限" />
            <span>至</span>
            <el-input-number v-model="rq.tempMax" aria-label="运输温度上限" :precision="1" :step="1" :min="-80" :max="100" controls-position="right" placeholder="上限" />
          </div>
        </el-form-item>
        <p class="muted small hint">按产品标签和质量标准的贮存条件填写，不需要的留空（如只控上限就只填上限）。改条件不影响已登记的记录。</p>
        <el-form-item label="途中要记录温度"><el-switch v-model="rq.monitorRequired" aria-label="途中要记录温度" /></el-form-item>
        <el-form-item label="运输注意事项（选填）"><el-input id="tq-note" v-model="rq.handlingNote" maxlength="300" placeholder="如：避光、防冻、堆码不超过 6 层" /></el-form-item>
        <div class="form-row">
          <el-form-item label="从哪天起要登记"><el-date-picker id="tq-from" v-model="rq.effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item v-if="rq.id" label="启用"><el-switch v-model="rq.active" aria-label="启用" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="rq.visible = false">取消</el-button>
        <el-button type="primary" :loading="rq.saving" @click="saveReq">{{ rq.id ? '保存修改' : '设定条件' }}</el-button>
      </template>
    </el-dialog>

    <!-- 登记运输记录 -->
    <el-dialog v-model="cr.visible" title="登记运输记录" width="760px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="运输方向">
          <el-radio-group v-model="cr.direction" @change="onDirection">
            <el-radio-button value="OUTBOUND">销售发运</el-radio-button>
            <el-radio-button value="INBOUND">受托厂成品到仓</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <div v-if="cr.direction === 'OUTBOUND'" class="form-row">
          <el-form-item label="出库日期"><el-date-picker id="tr-date" v-model="cr.date" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:100%" @change="loadCandidates" /></el-form-item>
          <el-form-item label="出库仓库">
            <el-select id="tr-wh" v-model="cr.warehouseId" placeholder="全部仓库" clearable style="width:100%" @change="loadCandidates">
              <el-option v-for="w in rd.stockWarehouses.value" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
        </div>
        <p v-else class="muted small hint">列出最近 120 天完工入库、还没有登记到仓运输的委托单。</p>

        <div class="cand-head">
          <el-checkbox :model-value="allChecked" :indeterminate="cr.picked.length > 0 && !allChecked" :disabled="!cr.candidates.length" @change="toggleAll">全选</el-checkbox>
          <span class="muted small">已选 {{ cr.picked.length }} 张{{ cr.candLoading ? '，正在加载…' : '' }}</span>
        </div>
        <el-table :data="cr.candidates" size="small" max-height="240" :empty-text="cr.candLoading ? '正在加载…' : cr.direction === 'OUTBOUND' ? '这天没有待登记运输的出库单' : '没有待登记到仓运输的委托单'" scrollbar-tabindex="0">
          <el-table-column label="" width="48">
            <template #default="{ row }"><el-checkbox :model-value="cr.picked.includes(row.docId)" :aria-label="`选择 ${row.docNo}`" @change="(v) => toggle(row.docId, v)" /></template>
          </el-table-column>
          <el-table-column label="单据" min-width="170">
            <template #default="{ row }">
              <span class="mono">{{ row.docNo }}</span> <el-tag v-if="row.required" size="small" type="warning" disable-transitions>有运输条件</el-tag>
              <div class="small">{{ row.partyName }}<template v-if="row.warehouseName"> · {{ row.warehouseName }}</template></div>
              <div v-if="narrow" class="muted small">{{ row.itemsText }}</div>
            </template>
          </el-table-column>
          <el-table-column v-if="!narrow" label="产品和批次" min-width="200">
            <template #default="{ row }">{{ row.itemsText }}<div class="muted small">{{ row.batchesText }}</div></template>
          </el-table-column>
        </el-table>
        <p v-if="pickedReqs.length" class="small req-line"><b>运输条件：</b>{{ pickedReqs.join('；') }}（多个产品取最严的，以保存结果为准）</p>
        <p v-else-if="cr.picked.length" class="muted small req-line">选中的单据没有设运输条件的成品，登记了也只记录运输情况。</p>

        <div class="form-row">
          <el-form-item label="运输方式">
            <el-select id="tr-mode" v-model="cr.mode" style="width:100%">
              <el-option v-for="m in modeOptions" :key="m.code" :value="m.code" :label="m.label" />
            </el-select>
          </el-form-item>
          <el-form-item :label="cr.mode === 'PICKUP' ? '承运单位（选填）' : '承运单位'"><el-input id="tr-carrier" v-model="cr.carrier" maxlength="100" placeholder="如：顺丰速运、受托厂自有车辆" /></el-form-item>
          <el-form-item label="运单号（选填）"><el-input id="tr-waybill" v-model="cr.waybillNo" maxlength="100" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="发运时间"><el-date-picker id="tr-shipped" v-model="cr.shippedAt" type="datetime" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
          <el-form-item label="到达时间（选填）"><el-date-picker id="tr-delivered" v-model="cr.deliveredAt" type="datetime" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="途中记录了温度"><el-switch v-model="cr.tempMonitored" aria-label="途中记录了温度" /></el-form-item>
          <el-form-item v-if="cr.tempMonitored" label="途中最低 / 最高温度（℃）">
            <div class="pair">
              <el-input-number id="tr-low" v-model="cr.tempLow" :precision="1" :step="0.5" :min="-80" :max="100" controls-position="right" placeholder="最低" />
              <span>至</span>
              <el-input-number v-model="cr.tempHigh" aria-label="途中最高温度" :precision="1" :step="0.5" :min="-80" :max="100" controls-position="right" placeholder="最高"
                               @keydown.enter.prevent="save" />
            </div>
          </el-form-item>
          <el-form-item v-if="cr.tempMonitored" label="记录仪编号（选填）"><el-input id="tr-device" v-model="cr.device" maxlength="60" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="到货异常"><el-switch v-model="cr.damaged" aria-label="到货异常" /></el-form-item>
          <el-form-item v-if="cr.damaged" label="异常情况"><el-input id="tr-damage" v-model="cr.damageNote" maxlength="300" placeholder="如：外箱压坏 2 箱、受潮" /></el-form-item>
        </div>
        <el-form-item label="备注（选填）"><el-input id="tr-note" v-model="cr.note" maxlength="300" /></el-form-item>
        <p v-if="crProblem" class="bad small">{{ crProblem }}，保存后会生成偏差。</p>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" :disabled="cr.candLoading || !cr.picked.length" @click="save">保存运输记录</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="dt.visible" :title="`运输记录 ${dt.row?.recordNo || ''}`" width="700px">
      <template v-if="dt.row">
        <p class="small">{{ dt.row.directionLabel }} · {{ dt.row.modeLabel }}<template v-if="dt.row.carrier"> · {{ dt.row.carrier }}</template><template v-if="dt.row.waybillNo">，运单 {{ dt.row.waybillNo }}</template></p>
        <p class="small">{{ fmtTime(dt.row.shippedAt) }} 发运<template v-if="dt.row.deliveredAt">，{{ fmtTime(dt.row.deliveredAt) }} 到达</template>；登记人 {{ dt.row.createdBy }}</p>
        <p class="small"><b>运输条件：</b>{{ dt.row.reqText }}；<b>途中温度：</b>{{ dt.row.readingText }}<template v-if="dt.row.device">（{{ dt.row.device }}）</template></p>
        <p v-if="dt.row.damaged" class="small"><b>到货异常：</b>{{ dt.row.damageNote }}</p>
        <p v-if="dt.row.problem" class="small bad"><b>不符合：</b>{{ dt.row.problem }}</p>
        <p v-if="dt.row.note" class="small"><b>备注：</b>{{ dt.row.note }}</p>
        <h4>单据（{{ dt.row.docs.length }}）</h4>
        <el-table :data="dt.row.docs" size="small" max-height="260" scrollbar-tabindex="0">
          <el-table-column label="单据" min-width="150"><template #default="{ row }"><span class="mono">{{ row.docNo }}</span><div class="muted small">{{ row.docTypeLabel }} · {{ row.date }}</div></template></el-table-column>
          <el-table-column prop="partyName" label="收货方 / 受托厂" min-width="140" />
          <el-table-column label="产品和批次" min-width="200"><template #default="{ row }">{{ row.itemsText }}<div class="muted small">{{ row.batchesText }}</div></template></el-table-column>
        </el-table>
        <h4>附件</h4>
        <AttachmentList owner-type="TRANSPORT_RECORD" :owner-id="dt.row.id" :can-upload="canRecord" />
      </template>
      <template #footer><el-button @click="dt.visible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from './AttachmentList.vue'
import ExportButton from './ExportButton.vue'
import LoadError from './LoadError.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { useLoader } from '../utils/loader'
import { latestOnly } from '../utils/latest'
import { daysFromToday, fmtTime, today } from '../utils/format'

const emit = defineEmits(['changed'])
const auth = useAuth()
const rd = useRefData()
const canReq = computed(() => auth.hasAny('ADMIN', 'QA', 'QSO'))
const canRecord = computed(() => auth.hasAny('ADMIN', 'WAREHOUSE', 'SALES', 'PRODUCTION', 'QA', 'QSO'))
const canVoid = computed(() => auth.hasAny('QA', 'QSO'))
const endOfToday = new Date(`${today()}T23:59:59`)
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }

const reqs = ref([])
const missing = ref({ rows: [], graceDays: 3, activeReqs: 0 })
const modeOptions = ref([])
const loader = useLoader(async () => {
  const [q, m, modes] = await Promise.all([http.get('/transport/requirements'), http.get('/transport/missing'), http.get('/transport/modes')])
  reqs.value = q
  missing.value = m
  modeOptions.value = modes
})
async function load() {
  await loader.run()
  loadRecords()
}
defineExpose({ load, missing })
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

// ---------- 记录 ----------
const range = ref([daysFromToday(-29), today()])
const recs = ref(null)
const recLoading = ref(false)
const recReq = latestOnly()
async function loadRecords() {
  const current = recReq()
  recLoading.value = true
  try {
    const r = await http.get('/transport/records', { params: { from: range.value[0], to: range.value[1] } })
    if (current()) recs.value = r
  } finally {
    if (current()) recLoading.value = false
  }
}

// ---------- 运输条件 ----------
const fgOptions = computed(() => rd.finished.value.filter((i) => !reqs.value.some((q) => q.itemId === i.id)))
const rq = reactive({ visible: false, saving: false, id: null, itemId: null, itemName: '', tempMin: null, tempMax: null, monitorRequired: false, handlingNote: '', effectiveFrom: '', active: true })
async function openReq(row) {
  await rd.ready()
  if (row) {
    Object.assign(rq, { visible: true, saving: false, id: row.id, itemId: row.itemId, itemName: row.itemName, tempMin: row.tempMin, tempMax: row.tempMax,
      monitorRequired: row.monitorRequired, handlingNote: row.handlingNote || '', effectiveFrom: row.effectiveFrom, active: row.active })
  } else {
    Object.assign(rq, { visible: true, saving: false, id: null, itemId: null, itemName: '', tempMin: null, tempMax: null, monitorRequired: false, handlingNote: '',
      effectiveFrom: today(), active: true })
  }
}
const num = (v) => (v === null || v === undefined || v === '' ? null : v)
async function saveReq() {
  if (!rq.itemId) return ElMessage.warning('请选择成品')
  if (num(rq.tempMin) === null && num(rq.tempMax) === null && !rq.handlingNote.trim()) return ElMessage.warning('请设定温度范围或填写运输注意事项')
  if (!rq.effectiveFrom) return ElMessage.warning('请选择从哪天起要登记')
  rq.saving = true
  const body = { itemId: rq.itemId, tempMin: num(rq.tempMin), tempMax: num(rq.tempMax), monitorRequired: rq.monitorRequired, handlingNote: rq.handlingNote,
    effectiveFrom: rq.effectiveFrom, active: rq.active }
  try {
    const r = rq.id ? await http.put(`/transport/requirements/${rq.id}`, body) : await http.post('/transport/requirements', body)
    ElMessage.success(`${r.itemName}：${r.text}`)
    rq.visible = false
    load()
    emit('changed')
  } finally {
    rq.saving = false
  }
}

// ---------- 登记 ----------
const nowText = () => new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16).replace(' ', 'T') + ':00'
const cr = reactive({ visible: false, saving: false, direction: 'OUTBOUND', date: '', warehouseId: null, candidates: [], candLoading: false, picked: [], mode: 'EXPRESS',
  carrier: '', waybillNo: '', shippedAt: '', deliveredAt: '', tempMonitored: false, tempLow: null, tempHigh: null, device: '', damaged: false, damageNote: '', note: '' })
const candReq = latestOnly()
async function openCreate(row) {
  await rd.ready()
  const inbound = row?.direction === 'INBOUND'
  Object.assign(cr, { visible: true, saving: false, direction: inbound ? 'INBOUND' : 'OUTBOUND', date: row && !inbound ? row.date : today(),
    warehouseId: row && !inbound ? row.warehouseId : null, candidates: [], picked: [], mode: inbound ? 'VEHICLE' : 'EXPRESS', carrier: '', waybillNo: '',
    shippedAt: nowText(), deliveredAt: '', tempMonitored: false, tempLow: null, tempHigh: null, device: '', damaged: false, damageNote: '', note: '' })
  await loadCandidates()
  if (row?.docId) cr.picked = [row.docId]
  else if (row) cr.picked = cr.candidates.filter((c) => c.required).map((c) => c.docId)
}
function onDirection() {
  cr.mode = cr.direction === 'INBOUND' ? 'VEHICLE' : 'EXPRESS'
  loadCandidates()
}
async function loadCandidates() {
  const current = candReq()
  cr.candLoading = true
  cr.picked = []
  try {
    const params = cr.direction === 'OUTBOUND' ? { direction: 'OUTBOUND', date: cr.date, warehouseId: cr.warehouseId || undefined } : { direction: 'INBOUND' }
    const list = await http.get('/transport/candidates', { params })
    if (current()) cr.candidates = list
  } finally {
    if (current()) cr.candLoading = false
  }
}
const allChecked = computed(() => cr.candidates.length > 0 && cr.picked.length === cr.candidates.length)
function toggleAll(v) {
  cr.picked = v ? cr.candidates.map((c) => c.docId) : []
}
function toggle(id, v) {
  cr.picked = v ? [...new Set([...cr.picked, id])] : cr.picked.filter((x) => x !== id)
}
const pickedReqs = computed(() => [...new Set(cr.candidates.filter((c) => cr.picked.includes(c.docId) && c.reqText).map((c) => c.reqText))])
// 保存前先提示会不会不符合（判定以后端为准）：只能看出“要求记录却没记录”和到货异常，温度范围以保存结果为准
const crProblem = computed(() => {
  const out = []
  const needMonitor = cr.candidates.some((c) => cr.picked.includes(c.docId) && c.reqText?.includes('记录途中温度'))
  if (needMonitor && !cr.tempMonitored && cr.mode !== 'PICKUP') out.push('选中的产品要求记录途中温度')
  if (cr.damaged) out.push('到货异常')
  return out.join('，')
})
async function save() {
  if (cr.saving || cr.candLoading) return
  if (!cr.picked.length) return ElMessage.warning('请至少选一张单据')
  if (cr.mode !== 'PICKUP' && !cr.carrier.trim()) return ElMessage.warning('请填写承运单位')
  if (!cr.shippedAt) return ElMessage.warning('请选择发运时间')
  if (cr.tempMonitored && (num(cr.tempLow) === null || num(cr.tempHigh) === null)) return ElMessage.warning('请填写途中的最低和最高温度')
  if (cr.damaged && !cr.damageNote.trim()) return ElMessage.warning('请写明到货异常的情况')
  cr.saving = true
  try {
    const r = await http.post('/transport/records', { direction: cr.direction, docIds: cr.picked, mode: cr.mode, carrier: cr.carrier, waybillNo: cr.waybillNo,
      shippedAt: cr.shippedAt, deliveredAt: cr.deliveredAt || null, tempMonitored: cr.tempMonitored, tempLow: cr.tempMonitored ? num(cr.tempLow) : null,
      tempHigh: cr.tempMonitored ? num(cr.tempHigh) : null, device: cr.tempMonitored ? cr.device : null, damaged: cr.damaged, damageNote: cr.damaged ? cr.damageNote : null, note: cr.note })
    if (r.conforming) ElMessage.success(`已登记 ${r.recordNo}，符合运输条件`)
    else ElMessage.warning(`已登记 ${r.recordNo}。不符合：${r.problem}，偏差 ${r.deviationNo}`)
    cr.visible = false
    load()
    emit('changed')
  } finally {
    cr.saving = false
  }
}

// ---------- 详情和作废 ----------
const dt = reactive({ visible: false, row: null })
function openDetail(row) {
  Object.assign(dt, { visible: true, row })
}
async function voidRecord(row) {
  let reason
  try {
    reason = (await ElMessageBox.prompt(`${row.recordNo}（${row.docsText}）。作废后记录保留、标为作废，这些单据可以重新登记；生成过偏差的，偏差请评估后关闭。`,
      '作废运输记录', { confirmButtonText: '作废', cancelButtonText: '不了', inputPlaceholder: '作废原因，如：选错单据', inputValidator: (v) => !!v?.trim() || '请写明作废原因' })).value
  } catch {
    return
  }
  await http.post(`/transport/records/${row.id}/void`, { reason })
  ElMessage.success('已作废')
  load()
  emit('changed')
}
</script>

<style scoped>
.intro { margin: 0 0 10px; line-height: 1.7; }
.toolbar-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.block { margin-bottom: 12px; }
.pad { margin: 8px 16px; }
.filters { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.form-row { display: flex; flex-wrap: wrap; gap: 0 12px; }
.form-row > * { flex: 1 1 160px; }
.pair { display: flex; align-items: center; gap: 6px; width: 100%; }
.pair :deep(.el-input-number) { flex: 1; min-width: 0; }
.hint { margin: -6px 0 12px; }
.cand-head { display: flex; align-items: center; gap: 12px; margin-bottom: 4px; }
.req-line { margin: 8px 0 12px; }
.bad { color: var(--el-color-danger); }
</style>
