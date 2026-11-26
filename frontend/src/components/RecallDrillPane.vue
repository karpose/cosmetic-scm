<template>
  <el-card shadow="never" body-style="padding:0 0 8px">
    <template #header>
      <div class="head">
        <p class="muted small rule-line">
          模拟召回：选一个已发货的成品批次，按出库、退货和库存记录逐一联系收货客户、受托生产企业和仓库，看多久能把在外数量找全。只是演练，不冻结批次、不发召回通知。
          <template v-if="data">超过 {{ data.intervalMonths }} 个月没有审核过的演练会进总览待办<template v-if="data.lastReviewed">，最近一次 {{ data.lastReviewed }}</template>。</template>
        </p>
        <div class="toolbar">
          <ExportButton report="recall-drills" />
          <el-button v-if="canStart" type="primary" @click="openStart">发起演练</el-button>
        </div>
      </div>
    </template>
    <el-alert v-if="data?.due" type="warning" show-icon :closable="false" class="due"
              :title="data.lastReviewed ? `最近一次审核过的演练是 ${data.lastReviewed}，已超过 ${data.intervalMonths} 个月` : '还没有审核过的召回演练'" />
    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-table v-else :data="data?.drills || []" v-loading="loader.loading.value" empty-text="还没有召回演练" scrollbar-tabindex="0">
      <el-table-column label="演练" :min-width="narrow ? 200 : 150">
        <template #default="{ row }">
          <span class="mono">{{ row.drillNo }}</span><div class="muted small mono">{{ fmtTime(row.startedAt) }}</div>
          <div v-if="narrow" class="small">{{ row.itemName }} · <BatchNo :no="row.batchNo" /></div>
        </template>
      </el-table-column>
      <el-table-column v-if="!narrow" label="产品 / 批号" min-width="170">
        <template #default="{ row }">{{ row.itemName }}<div><BatchNo :no="row.batchNo" /></div></template>
      </el-table-column>
      <el-table-column label="进度和用时" min-width="200">
        <template #default="{ row }">
          <div class="prog"><el-progress aria-label="已登记联系结果的比例" :percentage="pctOf(row.recordedCount, row.lines.length)" :stroke-width="8" :show-text="false" /><span class="mono small">{{ row.recordedCount }}/{{ row.lines.length }}</span></div>
          <div class="small">用时 <span class="mono">{{ row.elapsedHours ?? '—' }}</span> / 目标 <span class="mono">{{ row.targetHours }}</span> 小时<template v-if="row.locatedPct != null">，定位 <span class="mono">{{ row.locatedPct }}%</span></template></div>
        </template>
      </el-table-column>
      <el-table-column label="结论" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.status === 'IN_PROGRESS' && row.recordedCount < row.lines.length" type="info" size="small" disable-transitions>还在联系</el-tag>
          <el-tag v-else :type="row.met ? 'success' : 'danger'" size="small" disable-transitions>{{ row.met ? '达标' : '没达标' }}</el-tag>
          <div v-if="row.capaNo" class="small muted">CAPA {{ row.capaStatusLabel }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status]" size="small" disable-transitions>{{ row.statusLabel }}</el-tag>
          <div v-if="row.status === 'IN_PROGRESS' && row.rejectNote" class="warn small">已退回</div>
        </template>
      </el-table-column>
      <el-table-column label="" width="120" :fixed="narrow ? false : 'right'">
        <template #default="{ row }">
          <el-button size="small" text @click="openDetail(row)">详情</el-button>
          <el-button v-if="isQso && row.status === 'SUBMITTED'" size="small" text type="primary" @click="openDetail(row, true)">审核</el-button>
        </template>
      </el-table-column>
    </el-table>
    <ListCapHint :count="data?.drills?.length || 0" />
  </el-card>

  <!-- 发起 -->
  <el-dialog v-model="st.visible" title="发起召回演练" width="620px" :close-on-click-modal="false" append-to-body>
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="成品批号">
        <div class="batch-row">
          <el-input id="rd-batch" v-model="st.batchNo" placeholder="已发货的成品批号" @keydown.enter.prevent="preview" @change="preview" />
          <el-button :loading="st.previewing" @click="preview">列出要联系的对象</el-button>
        </div>
      </el-form-item>
      <div v-if="st.plan" class="plan glass">
        <div><b>{{ st.plan.itemName }}</b> · <BatchNo :no="st.plan.batchNo" /></div>
        <div v-for="(g, k) in planGroups" :key="k" class="small">{{ g.label }}：{{ g.names }}</div>
        <div class="muted small">数量平衡：{{ st.plan.balance }}</div>
      </div>
      <el-form-item label="模拟的召回情景"><el-input id="rd-scenario" v-model="st.scenario" type="textarea" :rows="2" maxlength="500" placeholder="如：假设消费者投诉这批有异味，需要确定在外数量和去向" /></el-form-item>
      <el-form-item label="目标用时（小时）"><el-input-number id="rd-hours" v-model="st.targetHours" :min="1" :max="720" :step="1" step-strictly /></el-form-item>
      <p class="muted small">从发起到最后一家联系完算用时。目标由企业自定，系统不内置。</p>
    </el-form>
    <template #footer>
      <el-button @click="st.visible = false">取消</el-button>
      <el-button type="primary" :loading="st.saving" :disabled="!st.plan" @click="submitStart">发起演练</el-button>
    </template>
  </el-dialog>

  <!-- 详情、登记、提交、审核 -->
  <el-dialog v-model="dt.visible" :title="`召回演练 ${dt.row?.drillNo || ''}`" width="760px" append-to-body @closed="load">
    <template v-if="dt.row">
      <p class="summary-line">
        <el-tag :type="STATUS_TAG[dt.row.status]" size="small" disable-transitions>{{ dt.row.statusLabel }}</el-tag>
        <el-tag v-if="dt.row.recordedCount === dt.row.lines.length" :type="dt.row.met ? 'success' : 'danger'" size="small" disable-transitions>{{ dt.row.met ? '达标' : '没达标' }}</el-tag>
        {{ dt.row.itemName }} · <BatchNo :no="dt.row.batchNo" />
      </p>
      <p class="small"><b>情景：</b>{{ dt.row.scenario }}</p>
      <p class="small"><b>用时：</b><span class="mono">{{ dt.row.elapsedHours ?? '—' }}</span> 小时（目标 {{ dt.row.targetHours }} 小时）<template v-if="dt.row.locatedPct != null">；在外数量定位 <span class="mono">{{ dt.row.locatedPct }}%</span></template></p>
      <p class="small muted"><b>数量平衡：</b>{{ dt.row.balance }}</p>
      <el-alert v-if="dt.row.rejectNote && dt.row.status === 'IN_PROGRESS'" type="error" :closable="false" show-icon :title="`已退回：${dt.row.rejectNote}`" class="gap" />
      <el-alert v-if="dt.row.cancelReason" type="info" :closable="false" show-icon :title="`已作废：${dt.row.cancelReason}`" class="gap" />
      <el-alert v-if="dt.row.gaps.length && dt.row.recordedCount > 0" :type="dt.row.recordedCount === dt.row.lines.length ? 'error' : 'warning'" :closable="false" show-icon class="gap"
                :title="dt.row.recordedCount === dt.row.lines.length ? '没达标' : '还没完成'">
        <div v-for="(g, i) in dt.row.gaps" :key="i" class="small">{{ g }}</div>
      </el-alert>

      <el-table :data="dt.row.lines" size="small" scrollbar-tabindex="0">
        <el-table-column label="对象" min-width="170">
          <template #default="{ row }">{{ row.name }}<div class="muted small">{{ row.targetLabel }}<template v-if="row.contact"> · {{ row.contact }}</template></div></template>
        </el-table-column>
        <el-table-column label="在外 / 账面" width="100" align="right">
          <template #default="{ row }"><span class="mono">{{ row.expectedQty == null ? '—' : fmtQty(row.expectedQty) }}</span></template>
        </el-table-column>
        <el-table-column label="联系结果" min-width="190">
          <template #default="{ row }">
            <template v-if="row.result">
              <el-tag :type="row.result === 'REACHED' ? 'success' : 'danger'" size="small" disable-transitions>{{ row.resultLabel }}</el-tag>
              <span v-if="row.confirmedQty != null" class="small"> {{ row.target === 'WAREHOUSE' ? '实盘' : '在手' }} <span class="mono">{{ fmtQty(row.confirmedQty) }}</span></span>
              <div class="muted small">{{ row.recordedBy }} · {{ fmtTime(row.contactedAt) }}</div>
              <div v-if="row.note" class="muted small">{{ row.note }}</div>
            </template>
            <span v-else class="muted small">还没登记</span>
          </template>
        </el-table-column>
        <el-table-column v-if="editable" label="" width="90">
          <template #default="{ row }"><el-button v-if="canRecord" size="small" text type="primary" @click="openRecord(row)">{{ row.result ? '改' : '登记' }}</el-button></template>
        </el-table-column>
      </el-table>

      <p v-if="dt.row.capaNo" class="small"><b>CAPA：</b><router-link to="/qms?tab=capas" class="mono">{{ dt.row.capaNo }}</router-link>（{{ dt.row.capaStatusLabel }}）</p>
      <p v-if="dt.row.summary && dt.row.status !== 'IN_PROGRESS'" class="small pre"><b>演练总结：</b>{{ dt.row.summary }}</p>
      <p class="muted small">{{ dt.row.startedBy }} {{ fmtTime(dt.row.startedAt) }} 发起<template v-if="dt.row.submittedBy">；{{ dt.row.submittedBy }} {{ fmtTime(dt.row.submittedAt) }} 提交</template><template v-if="dt.row.reviewedBy">；{{ dt.row.reviewedBy }} {{ fmtTime(dt.row.reviewedAt) }} 审核<template v-if="dt.row.reviewNote">：{{ dt.row.reviewNote }}</template></template></p>

      <el-form v-if="editable && canStart" label-position="top" @submit.prevent>
        <el-form-item label="演练总结"><el-input id="rd-summary" v-model="dt.summary" type="textarea" :rows="3" maxlength="1000" placeholder="联系情况、发现的问题、名单和流程要改进的地方" /></el-form-item>
        <p v-if="needCapa" class="warn small">没达标，要先转 CAPA 才能提交。</p>
      </el-form>
      <template v-if="dt.review">
        <el-divider />
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="审核意见（选填）"><el-input id="rd-review-note" v-model="dt.note" maxlength="300" /></el-form-item>
          <el-form-item label="签批密码"><el-input id="rd-review-pwd" v-model="dt.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="submitReview" /></el-form-item>
          <p class="muted small">审核人不能是发起人或提交人。联系不充分或记录不全的请退回。</p>
        </el-form>
      </template>
    </template>
    <template #footer>
      <el-button @click="dt.visible = false">关闭</el-button>
      <template v-if="dt.row">
        <el-button v-if="canStart && ['IN_PROGRESS', 'SUBMITTED'].includes(dt.row.status) && !dt.review" type="danger" plain @click="cancel">作废</el-button>
        <el-button v-if="editable && canStart && needCapa" type="warning" @click="openCapa">转 CAPA</el-button>
        <el-button v-if="editable && canStart" type="primary" :loading="dt.saving" @click="submitForReview">提交审核</el-button>
        <template v-if="dt.review">
          <el-button type="danger" plain @click="reject">退回</el-button>
          <el-button type="primary" :loading="dt.saving" @click="submitReview">审核通过</el-button>
        </template>
      </template>
    </template>
  </el-dialog>

  <!-- 登记联系结果 -->
  <el-dialog v-model="rc.visible" :title="`登记联系结果：${rc.line?.name || ''}`" width="500px" :close-on-click-modal="false" append-to-body>
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="联系结果">
        <el-radio-group v-model="rc.result">
          <el-radio-button value="REACHED">已联系上</el-radio-button>
          <el-radio-button value="UNREACHABLE">联系不上</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="rc.result === 'REACHED' && rc.line?.target === 'WAREHOUSE'" :label="`实盘数量（账面 ${fmtQty(rc.line.expectedQty)}）`">
        <el-input-number id="rd-qty" v-model="rc.qty" :min="0" :precision="4" :controls="false" style="width:100%" />
      </el-form-item>
      <el-form-item v-if="rc.result === 'REACHED' && rc.line?.target === 'CUSTOMER'" :label="`客户反馈还在手上的数量（选填，在外 ${fmtQty(rc.line.expectedQty)}）`">
        <el-input-number id="rd-qty" v-model="rc.qty" :min="0" :precision="4" :controls="false" style="width:100%" />
      </el-form-item>
      <el-form-item label="联系时间（留空按现在）">
        <el-date-picker id="rd-at" v-model="rc.at" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="现在" style="width:100%" />
      </el-form-item>
      <el-form-item :label="rc.result === 'UNREACHABLE' ? '试过的方式' : '说明（选填）'">
        <el-input id="rd-note" v-model="rc.note" maxlength="300" :placeholder="rc.result === 'UNREACHABLE' ? '如：电话两次未接、微信未回' : '对接人、联系方式'" @keydown.enter.prevent="submitRecord" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="rc.visible = false">取消</el-button>
      <el-button type="primary" :loading="rc.saving" @click="submitRecord">保存联系结果</el-button>
    </template>
  </el-dialog>

  <!-- 转 CAPA -->
  <el-dialog v-model="cp.visible" title="召回演练没达标，转 CAPA" width="520px" :close-on-click-modal="false" append-to-body>
    <el-form label-position="top" @submit.prevent>
      <div v-if="dt.row" class="small gap"><div v-for="(g, i) in dt.row.gaps" :key="i">{{ g }}</div></div>
      <el-form-item label="措施类型">
        <el-radio-group v-model="cp.actionType">
          <el-radio-button value="CORRECTIVE">纠正措施</el-radio-button>
          <el-radio-button value="PREVENTIVE">预防措施</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="措施内容"><el-input id="rd-capa-desc" v-model="cp.description" type="textarea" :rows="3" maxlength="900" placeholder="如：更新客户紧急联系人名单、规定仓库盘点差异的处理" /></el-form-item>
      <div class="form-row">
        <el-form-item label="负责人"><el-input id="rd-capa-owner" v-model="cp.owner" maxlength="64" /></el-form-item>
        <el-form-item label="完成期限"><el-date-picker id="rd-capa-due" v-model="cp.dueDate" type="date" value-format="YYYY-MM-DD" :disabled-date="(x) => x < startOfToday" style="width:100%" /></el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="cp.visible = false">取消</el-button>
      <el-button type="primary" :loading="cp.saving" @click="submitCapa">生成 CAPA</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import BatchNo from './BatchNo.vue'
import ExportButton from './ExportButton.vue'
import ListCapHint from './ListCapHint.vue'
import LoadError from './LoadError.vue'
import { useAuth } from '../stores/auth'
import { useLoader } from '../utils/loader'
import { latestOnly } from '../utils/latest'
import { daysFromToday, fmtQty, fmtTime, startOfBusinessDay } from '../utils/format'

const STATUS_TAG = { IN_PROGRESS: 'warning', SUBMITTED: 'primary', REVIEWED: 'success', CANCELLED: 'info' }

const auth = useAuth()
const canStart = computed(() => auth.hasAny('QA', 'QSO'))
const canRecord = computed(() => auth.hasAny('QA', 'QSO', 'SALES', 'WAREHOUSE'))
const isQso = computed(() => auth.hasAny('QSO'))
const startOfToday = startOfBusinessDay()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
const pctOf = (a, b) => (Number(b) > 0 ? Math.min(100, Math.round((Number(a) / Number(b)) * 100)) : 0)

const data = ref(null)
const loader = useLoader(async () => {
  data.value = await http.get('/recall-drills')
  if (dt.visible && dt.row) dt.row = data.value.drills.find((d) => d.id === dt.row.id) || dt.row
})
const load = () => loader.run()
onMounted(() => { load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))
defineExpose({ load })

// 发起
const st = reactive({ visible: false, saving: false, previewing: false, batchNo: '', scenario: '', targetHours: 24, plan: null })
const previewReq = latestOnly()
const planGroups = computed(() => {
  const out = {}
  for (const l of st.plan?.lines || []) {
    const g = out[l.target] || (out[l.target] = { label: l.targetLabel, list: [] })
    g.list.push(l.expectedQty == null ? l.name : `${l.name}（${fmtQty(l.expectedQty)}）`)
  }
  return Object.fromEntries(Object.entries(out).map(([k, g]) => [k, { label: `${g.label} ${g.list.length}`, names: g.list.join('、') }]))
})
function openStart() {
  Object.assign(st, { visible: true, saving: false, previewing: false, batchNo: '', scenario: '', targetHours: 24, plan: null })
}
async function preview() {
  const current = previewReq()
  st.plan = null
  const no = st.batchNo.trim()
  if (!no) return
  st.previewing = true
  try {
    const p = await http.get('/recall-drills/preview', { params: { batchNo: no }, silent: true })
    // 发起时按批号重新算，但预览不能显示上一个批号的名单
    if (current()) st.plan = p
  } catch (e) {
    if (current()) ElMessage.warning(e.message)
  } finally {
    if (current()) st.previewing = false
  }
}
async function submitStart() {
  if (!st.scenario.trim()) return ElMessage.warning('请写明模拟的召回情景')
  st.saving = true
  try {
    const d = await http.post('/recall-drills', { batchNo: st.plan.batchNo, scenario: st.scenario, targetHours: st.targetHours })
    ElMessage.success(`召回演练 ${d.drillNo} 已发起，开始计时，请逐一联系并登记结果`)
    st.visible = false
    await load()
    openDetail(d)
  } finally {
    st.saving = false
  }
}

// 详情
const dt = reactive({ visible: false, row: null, review: false, summary: '', note: '', password: '', saving: false })
const editable = computed(() => dt.row?.status === 'IN_PROGRESS')
const needCapa = computed(() => dt.row && dt.row.recordedCount === dt.row.lines.length && !dt.row.met && !dt.row.capaId)
function openDetail(row, review = false) {
  Object.assign(dt, { visible: true, row, review: review && isQso.value && row.status === 'SUBMITTED', summary: row.summary || '', note: '', password: '', saving: false })
}

// 登记联系结果
const rc = reactive({ visible: false, saving: false, line: null, result: 'REACHED', qty: undefined, at: null, note: '' })
function openRecord(line) {
  Object.assign(rc, { visible: true, saving: false, line, result: line.result || 'REACHED', qty: line.confirmedQty == null ? undefined : Number(line.confirmedQty),
    at: null, note: line.note || '' })
}
async function submitRecord() {
  if (rc.result === 'UNREACHABLE' && !rc.note.trim()) return ElMessage.warning('联系不上请写明试过的方式')
  if (rc.result === 'REACHED' && rc.line.target === 'WAREHOUSE' && rc.qty == null) return ElMessage.warning('请填实盘数量')
  rc.saving = true
  try {
    dt.row = await http.post(`/recall-drills/${dt.row.id}/lines/${rc.line.id}/record`, {
      result: rc.result, confirmedQty: rc.result === 'REACHED' && rc.line.target !== 'FACTORY' ? rc.qty ?? null : null, contactedAt: rc.at || null, note: rc.note
    })
    ElMessage.success('已登记联系结果')
    rc.visible = false
  } finally {
    rc.saving = false
  }
}

// 转 CAPA
const cp = reactive({ visible: false, saving: false, actionType: 'CORRECTIVE', description: '', owner: '', dueDate: '' })
function openCapa() {
  Object.assign(cp, { visible: true, saving: false, actionType: 'CORRECTIVE', description: '', owner: auth.user?.username || '', dueDate: daysFromToday(30) })
}
async function submitCapa() {
  if (!cp.description.trim() || !cp.owner.trim() || !cp.dueDate) return ElMessage.warning('请填写措施内容、负责人和完成期限')
  cp.saving = true
  try {
    dt.row = await http.post(`/recall-drills/${dt.row.id}/capa`, { actionType: cp.actionType, description: cp.description, owner: cp.owner, dueDate: cp.dueDate })
    ElMessage.success(`已生成 CAPA ${dt.row.capaNo}`)
    cp.visible = false
  } finally {
    cp.saving = false
  }
}

// 提交、审核、退回、作废
async function submitForReview() {
  if (!dt.summary.trim()) return ElMessage.warning('请填写演练总结')
  dt.saving = true
  try {
    const d = await http.post(`/recall-drills/${dt.row.id}/submit`, { summary: dt.summary })
    ElMessage.success(`召回演练 ${d.drillNo} 已提交，等质量安全负责人审核`)
    dt.visible = false
  } finally {
    dt.saving = false
  }
}
async function submitReview() {
  if (!dt.password) return ElMessage.warning('请输入签批密码')
  dt.saving = true
  try {
    const d = await http.post(`/recall-drills/${dt.row.id}/review`, { note: dt.note, password: dt.password })
    ElMessage.success(`召回演练 ${d.drillNo} 已审核`)
    dt.visible = false
  } finally {
    dt.saving = false
  }
}
async function askReason(message, title, confirm) {
  try {
    return (await ElMessageBox.prompt(message, title, { confirmButtonText: confirm, cancelButtonText: '不了', inputPlaceholder: '原因', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return null
  }
}
async function reject() {
  const note = await askReason('退回后演练回到进行中，补登记后重新提交。', `退回 ${dt.row.drillNo}`, '退回')
  if (!note) return
  await http.post(`/recall-drills/${dt.row.id}/reject`, { note })
  ElMessage.success('已退回')
  dt.visible = false
}
async function cancel() {
  const note = await askReason('作废后不能恢复，登记的结果和转出的 CAPA 保留。', `作废 ${dt.row.drillNo}`, '作废')
  if (!note) return
  await http.post(`/recall-drills/${dt.row.id}/cancel`, { note })
  ElMessage.success('已作废')
  dt.visible = false
}
</script>

<style scoped>
.head { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: flex-start; justify-content: space-between; }
.head .toolbar { display: flex; gap: 8px; }
.rule-line { margin: 0; flex: 1 1 320px; }
.due { margin: 8px 12px; width: auto; }
.prog { display: grid; grid-template-columns: 1fr auto; gap: 8px; align-items: center; }
.batch-row { display: flex; gap: 8px; width: 100%; }
.batch-row .el-input { flex: 1; }
.plan { padding: 10px 14px; margin-bottom: 14px; display: grid; gap: 4px; border-radius: 14px; }
.summary-line { margin: 0 0 8px; line-height: 1.8; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.gap { margin-bottom: 8px; }
.form-row { display: flex; flex-wrap: wrap; gap: 0 12px; }
.form-row > * { flex: 1 1 160px; }
.warn { color: var(--el-color-danger); }
.pre { white-space: pre-wrap; }
</style>
