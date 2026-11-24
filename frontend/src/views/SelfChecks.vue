<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>GMP 自查</h2>
        <p>按《化妆品生产质量管理规范检查要点（委托生产版）》24 项逐项自查：关键项目 1 项、其他重点项目 8 项、一般项目 15 项。系统能核对的项目给出自动核对结果，结论按判定原则自动算出；存在瑕疵或不符合的要转 CAPA，由另一位质量安全负责人签批。每年至少做一次。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">发起自查</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <el-table :data="rows" v-loading="loader.loading.value" empty-text="还没有做过自查">
        <el-table-column label="编号" width="170"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row.id)">{{ row.checkNo }}</a><div class="muted small">{{ row.periodFrom }} 至 {{ row.periodTo }}</div></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
        <el-table-column label="结论" width="150"><template #default="{ row }"><el-tag v-if="row.conclusion" :type="CONCLUSION_TAG[row.conclusion]" size="small" disable-transitions>{{ row.conclusionLabel }}</el-tag><div v-if="row.status === 'DRAFT'" class="muted small">按当前结果</div></template></el-table-column>
        <el-table-column label="进度" min-width="200"><template #default="{ row }">已检查 {{ row.counts.total - row.counts.pending }} / {{ row.counts.total }}<div class="muted small">瑕疵 {{ row.counts.flawed }} · 不符合 {{ row.counts.noncompliant }} · 不适用 {{ row.counts.notApplicable }}</div></template></el-table-column>
        <el-table-column label="签批" min-width="160"><template #default="{ row }"><template v-if="row.approvedBy">{{ row.approvedBy }}<div class="muted small">{{ fmtTime(row.approvedAt) }}</div></template><span v-else-if="row.submittedBy" class="muted small">{{ row.submittedBy }} 提交</span><span v-else class="muted small">{{ row.createdBy }} 发起</span></template></el-table-column>
        <el-table-column label="" width="90" :fixed="narrow ? false : 'right'"><template #default="{ row }"><el-button size="small" text @click="openDetail(row.id)">详情</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <!-- 发起 -->
    <el-dialog v-model="cr.visible" title="发起 GMP 自查" width="520px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-row">
          <el-form-item label="自查期间开始"><el-date-picker id="sc-from" v-model="cr.periodFrom" type="date" value-format="YYYY-MM-DD" :disabled-date="(x) => x > endOfToday" style="width:100%" /></el-form-item>
          <el-form-item label="自查期间结束"><el-date-picker id="sc-to" v-model="cr.periodTo" type="date" value-format="YYYY-MM-DD" :disabled-date="(x) => x > endOfToday" style="width:100%" /></el-form-item>
        </div>
        <el-form-item label="自查范围（选填）"><el-input id="sc-scope" v-model="cr.scope" type="textarea" :rows="2" maxlength="500" placeholder="如：全部委托生产产品和受托生产企业" /></el-form-item>
        <p class="muted small">期间最长一年，系统按这个期间做自动核对。同一时间只能有一份自查在进行。</p>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" @click="submitCreate">发起自查</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="dt.visible" :title="`GMP 自查 ${c?.checkNo || ''}`" width="1100px" top="4vh">
      <template v-if="dt.data">
        <p class="summary-line">
          <StatusTag :status="c.status" :label="c.statusLabel" /> {{ c.editionLabel }} · 期间 {{ c.periodFrom }} 至 {{ c.periodTo }}
          <template v-if="c.scope"> · {{ c.scope }}</template> · {{ c.createdBy }} 发起
        </p>
        <el-alert v-if="dt.data.rejectNote && c.status === 'DRAFT'" type="warning" :closable="false" show-icon :title="`被退回：${dt.data.rejectNote}`" />
        <el-alert v-if="dt.data.cancelReason" type="info" :closable="false" show-icon :title="`已作废：${dt.data.cancelReason}`" />

        <div class="verdict">
          <div>
            <span class="muted small">{{ c.status === 'DRAFT' ? '按当前结果' : '结论' }}</span>
            <el-tag :type="CONCLUSION_TAG[c.conclusion]" size="large" disable-transitions>{{ c.conclusionLabel }}</el-tag>
          </div>
          <div class="small">
            符合 {{ c.counts.compliant }} · 存在瑕疵 {{ c.counts.flawed }} · 不符合 {{ c.counts.noncompliant }} · 不适用 {{ c.counts.notApplicable }} · 未检查 {{ c.counts.pending }}
            <div class="muted">关键项目不符合即严重缺陷；关键瑕疵加其他重点不符合 {{ c.counts.rule2 }} / 4；重点不符合、重点瑕疵加一般不符合 {{ c.counts.rule3 }} / 8，到了也是严重缺陷。</div>
          </div>
        </div>
        <p v-if="dt.data.summary" class="small"><b>自查总结：</b>{{ dt.data.summary }}<span class="muted">（{{ c.submittedBy }} 提交<template v-if="c.approvedBy">，{{ c.approvedBy }} 于 {{ fmtTime(c.approvedAt) }} 签批</template>）</span></p>

        <div class="filters">
          <el-radio-group v-model="dt.filter" size="small" aria-label="筛选检查项目">
            <el-radio-button value="all">全部 24 项</el-radio-button>
            <el-radio-button value="pending">未检查</el-radio-button>
            <el-radio-button value="issues">有问题</el-radio-button>
            <el-radio-button value="auto">有自动核对</el-radio-button>
          </el-radio-group>
          <span v-if="!ev.data" class="muted small">正在读取自动核对…</span>
        </div>

        <div v-for="it in shownItems" :key="it.no" class="item-card" :class="{ issue: it.result === 'FLAWED' || it.result === 'NONCOMPLIANT' }">
          <div class="item-head">
            <b>第 {{ it.no }} 项 {{ it.title }}</b>
            <el-tag :type="LEVEL_TAG[it.level]" size="small" disable-transitions>{{ it.levelLabel }}</el-tag>
            <span class="muted small">{{ it.clause }} · {{ it.category }}</span>
            <el-tag :type="RESULT_TAG[it.result]" size="small" effect="plain" disable-transitions class="result-tag">{{ it.resultLabel }}</el-tag>
          </div>
          <p class="muted small points">{{ it.points }}</p>
          <div v-if="ev.byNo[it.no]" class="evidence" :class="ev.byNo[it.no].level.toLowerCase()">
            <b>系统核对：</b>{{ ev.byNo[it.no].summary }}
            <ul v-if="ev.byNo[it.no].details.length"><li v-for="(x, i) in ev.byNo[it.no].details" :key="i">{{ x }}</li></ul>
          </div>

          <template v-if="editable">
            <el-radio-group v-model="edits[it.no].result" :aria-label="`第 ${it.no} 项检查结果`" class="result-radio">
              <el-radio-button value="COMPLIANT">符合</el-radio-button>
              <el-radio-button v-if="it.level !== 'GENERAL'" value="FLAWED">存在瑕疵</el-radio-button>
              <el-radio-button value="NONCOMPLIANT">不符合</el-radio-button>
              <el-radio-button v-if="it.level === 'GENERAL'" value="NOT_APPLICABLE">不适用</el-radio-button>
            </el-radio-group>
            <div class="edit-grid">
              <el-input :id="`sc-finding-${it.no}`" v-model="edits[it.no].finding" type="textarea" :rows="2" maxlength="1000"
                        :placeholder="findingHint(edits[it.no].result)" :aria-label="`第 ${it.no} 项发现的问题`" />
              <el-input :id="`sc-evidence-${it.no}`" v-model="edits[it.no].evidence" type="textarea" :rows="2" maxlength="1000"
                        placeholder="查看了哪些记录、文件（选填）" :aria-label="`第 ${it.no} 项查看的记录`" />
            </div>
            <div class="item-actions">
              <el-button size="small" type="primary" :loading="dt.savingNo === it.no" :disabled="!edits[it.no].result" @click="saveItem(it)">保存第 {{ it.no }} 项</el-button>
              <span v-if="it.checkedBy" class="muted small">{{ it.checkedBy }} {{ fmtTime(it.checkedAt) }} 登记</span>
              <template v-if="it.result === 'FLAWED' || it.result === 'NONCOMPLIANT'">
                <router-link v-if="it.capaNo" to="/qms?tab=capas" class="mono small">CAPA {{ it.capaNo }}（{{ it.capaStatusLabel }}）</router-link>
                <el-button v-else size="small" @click="openCapa(it)">转 CAPA</el-button>
              </template>
            </div>
          </template>
          <template v-else>
            <p v-if="it.finding" class="small"><b>{{ it.result === 'NOT_APPLICABLE' ? '不适用原因' : '发现的问题' }}：</b>{{ it.finding }}</p>
            <p v-if="it.evidence" class="small"><b>查看的记录：</b>{{ it.evidence }}</p>
            <p class="small muted">
              <template v-if="it.checkedBy">{{ it.checkedBy }} {{ fmtTime(it.checkedAt) }} 登记</template>
              <router-link v-if="it.capaNo" to="/qms?tab=capas" class="mono"> · CAPA {{ it.capaNo }}（{{ it.capaStatusLabel }}）</router-link>
            </p>
          </template>
        </div>
        <p v-if="!shownItems.length" class="muted small">没有符合筛选的项目。</p>
      </template>
      <template #footer>
        <template v-if="dt.data">
          <el-button v-if="canEdit && (c.status === 'DRAFT' || c.status === 'SUBMITTED')" text @click="noteAction('cancel', '作废自查', '作废后已登记的结果和转出的 CAPA 都保留。')">作废</el-button>
          <el-button v-if="isQso && c.status === 'SUBMITTED'" text @click="noteAction('reject', '退回自查', '写明退回原因，退回后可以重新检查。')">退回</el-button>
          <el-button @click="printReport">打印报告</el-button>
          <el-button v-if="canEdit && c.status === 'DRAFT'" type="primary" :disabled="c.counts.pending > 0" @click="openSubmit">提交签批</el-button>
          <el-button v-if="isQso && c.status === 'SUBMITTED'" type="primary" @click="openApprove">签批</el-button>
        </template>
        <el-button @click="dt.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 转 CAPA -->
    <el-dialog v-model="cp.visible" :title="`第 ${cp.item?.no || ''} 项转 CAPA`" width="520px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <p v-if="cp.item" class="small" style="margin-top:0">{{ cp.item.title }}：{{ cp.item.finding }}</p>
        <el-form-item label="措施类型">
          <el-radio-group v-model="cp.actionType">
            <el-radio-button value="CORRECTIVE">纠正措施</el-radio-button>
            <el-radio-button value="PREVENTIVE">预防措施</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="措施内容"><el-input id="sc-capa-desc" v-model="cp.description" type="textarea" :rows="3" maxlength="900" placeholder="怎么整改、整改到什么程度" /></el-form-item>
        <div class="form-row">
          <el-form-item label="负责人"><el-input id="sc-capa-owner" v-model="cp.owner" maxlength="64" /></el-form-item>
          <el-form-item label="完成期限"><el-date-picker id="sc-capa-due" v-model="cp.dueDate" type="date" value-format="YYYY-MM-DD" :disabled-date="(x) => x < startOfToday" style="width:100%" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="cp.visible = false">取消</el-button>
        <el-button type="primary" :loading="cp.saving" @click="submitCapa">生成 CAPA</el-button>
      </template>
    </el-dialog>

    <!-- 提交 -->
    <el-dialog v-model="sb.visible" title="提交签批" width="520px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <p v-if="c" class="small" style="margin-top:0">结论：<el-tag :type="CONCLUSION_TAG[c.conclusion]" size="small" disable-transitions>{{ c.conclusionLabel }}</el-tag>（存在瑕疵或不符合的项目要先转 CAPA）</p>
        <el-form-item label="自查总结"><el-input id="sc-summary" v-model="sb.summary" type="textarea" :rows="4" maxlength="1000" placeholder="自查情况、主要问题和整改安排" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sb.visible = false">取消</el-button>
        <el-button type="primary" :loading="sb.saving" @click="submitCheck">提交签批</el-button>
      </template>
    </el-dialog>

    <!-- 签批 -->
    <el-dialog v-model="ap.visible" title="签批 GMP 自查" width="460px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <p v-if="c" style="margin-top:0">结论：<b>{{ c.conclusionLabel }}</b></p>
        <p v-if="c?.submittedBy === auth.user?.username" class="late small">这份自查是您提交的，要由另一位质量安全负责人签批。</p>
        <el-form-item label="签批密码"><el-input id="sc-password" v-model="ap.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="submitApprove" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ap.visible = false">取消</el-button>
        <el-button type="primary" :loading="ap.saving" @click="submitApprove">签批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { daysFromToday, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'

const CONCLUSION_TAG = { NO_DEFECT: 'success', DEFECT: 'warning', SEVERE: 'danger' }
const LEVEL_TAG = { KEY: 'danger', IMPORTANT: 'warning', GENERAL: 'info' }
const RESULT_TAG = { PENDING: 'info', COMPLIANT: 'success', FLAWED: 'warning', NONCOMPLIANT: 'danger', NOT_APPLICABLE: 'info' }

const auth = useAuth()
const router = useRouter()
const canEdit = computed(() => auth.hasAny('QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const startOfToday = startOfBusinessDay()
const endOfToday = new Date(`${today()}T23:59:59`)
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }

const rows = ref([])
const loader = useLoader(async () => {
  rows.value = await http.get('/qms/self-checks')
})
const load = () => loader.run()
onMounted(() => { load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

// 发起
const cr = reactive({ visible: false, saving: false, periodFrom: '', periodTo: '', scope: '' })
function openCreate() {
  Object.assign(cr, { visible: true, saving: false, periodFrom: daysFromToday(-364), periodTo: today(), scope: '' })
}
async function submitCreate() {
  if (!cr.periodFrom || !cr.periodTo) return ElMessage.warning('请选择自查期间')
  cr.saving = true
  try {
    const d = await http.post('/qms/self-checks', { periodFrom: cr.periodFrom, periodTo: cr.periodTo, scope: cr.scope })
    ElMessage.success(`自查 ${d.check.checkNo} 已发起`)
    cr.visible = false
    load()
    show(d)
  } finally {
    cr.saving = false
  }
}

// 详情
const dt = reactive({ visible: false, data: null, filter: 'all', savingNo: null })
const c = computed(() => dt.data?.check)
const editable = computed(() => canEdit.value && c.value?.status === 'DRAFT')
const edits = reactive({})
const ev = reactive({ data: null, byNo: {} })
const detailReq = latestOnly()
const evReq = latestOnly()
const shownItems = computed(() => {
  const list = dt.data?.items || []
  if (dt.filter === 'pending') return list.filter((i) => i.result === 'PENDING')
  if (dt.filter === 'issues') return list.filter((i) => i.result === 'FLAWED' || i.result === 'NONCOMPLIANT' || ev.byNo[i.no]?.level === 'WARN')
  if (dt.filter === 'auto') return list.filter((i) => ev.byNo[i.no])
  return list
})
function resetEdit(it) {
  edits[it.no] = { result: it.result === 'PENDING' ? '' : it.result, finding: it.finding || '', evidence: it.evidence || '' }
}
function show(d, keepEdits = false) {
  const first = !dt.visible || dt.data?.check.id !== d.check.id
  dt.data = d
  dt.visible = true
  if (first || !keepEdits) {
    d.items.forEach(resetEdit)
  }
  if (first) {
    dt.filter = 'all'
    loadEvidence(d.check.id)
  }
}
async function openDetail(id) {
  const current = detailReq()
  const d = await http.get(`/qms/self-checks/${id}`)
  if (current()) show(d)
}
async function loadEvidence(id) {
  ev.data = null
  ev.byNo = {}
  const current = evReq()
  const list = await http.get(`/qms/self-checks/${id}/evidence`, { silent: true }).catch(() => [])
  if (!current()) return
  ev.data = list
  ev.byNo = Object.fromEntries(list.map((x) => [x.itemNo, x]))
}
function apply(d) {
  show(d, true)
  load()
}
function findingHint(result) {
  if (result === 'NOT_APPLICABLE') return '为什么不适用（必填）'
  if (result === 'FLAWED' || result === 'NONCOMPLIANT') return '发现的问题（必填）'
  return '发现的问题（选填）'
}
async function saveItem(it) {
  const e = edits[it.no]
  if ((e.result === 'FLAWED' || e.result === 'NONCOMPLIANT') && !e.finding.trim()) return ElMessage.warning(`请写明第 ${it.no} 项发现的问题`)
  if (e.result === 'NOT_APPLICABLE' && !e.finding.trim()) return ElMessage.warning(`请写明第 ${it.no} 项为什么不适用`)
  dt.savingNo = it.no
  try {
    const d = await http.post(`/qms/self-checks/${c.value.id}/items/${it.no}`, { result: e.result, finding: e.finding, evidence: e.evidence })
    apply(d)
    resetEdit(d.items[it.no - 1])
    ElMessage.success(`第 ${it.no} 项已保存`)
  } finally {
    dt.savingNo = null
  }
}

// 转 CAPA
const cp = reactive({ visible: false, saving: false, item: null, actionType: 'CORRECTIVE', description: '', owner: '', dueDate: '' })
function openCapa(item) {
  Object.assign(cp, { visible: true, saving: false, item, actionType: 'CORRECTIVE', description: '', owner: auth.user?.username || '', dueDate: daysFromToday(30) })
}
async function submitCapa() {
  if (!cp.description.trim() || !cp.owner.trim() || !cp.dueDate) return ElMessage.warning('请填写措施内容、负责人和完成期限')
  cp.saving = true
  try {
    const d = await http.post(`/qms/self-checks/${c.value.id}/items/${cp.item.no}/capa`, { actionType: cp.actionType, description: cp.description, owner: cp.owner, dueDate: cp.dueDate })
    apply(d)
    ElMessage.success(`已生成 CAPA ${d.items[cp.item.no - 1].capaNo}`)
    cp.visible = false
  } finally {
    cp.saving = false
  }
}

// 提交、签批、退回、作废
const sb = reactive({ visible: false, saving: false, summary: '' })
function openSubmit() {
  Object.assign(sb, { visible: true, saving: false, summary: '' })
}
async function submitCheck() {
  if (!sb.summary.trim()) return ElMessage.warning('请填写自查总结')
  sb.saving = true
  try {
    apply(await http.post(`/qms/self-checks/${c.value.id}/submit`, { summary: sb.summary }))
    ElMessage.success('已提交，等待质量安全负责人签批')
    sb.visible = false
  } finally {
    sb.saving = false
  }
}
const ap = reactive({ visible: false, saving: false, password: '' })
function openApprove() {
  Object.assign(ap, { visible: true, saving: false, password: '' })
}
async function submitApprove() {
  if (!ap.password) return ElMessage.warning('请输入签批密码')
  ap.saving = true
  try {
    apply(await http.post(`/qms/self-checks/${c.value.id}/approve`, { password: ap.password }))
    ElMessage.success('已签批')
    ap.visible = false
  } finally {
    ap.saving = false
  }
}
async function noteAction(action, title, hint) {
  let note
  try {
    note = (await ElMessageBox.prompt(hint, title, { confirmButtonText: title, cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  apply(await http.post(`/qms/self-checks/${c.value.id}/${action}`, { note }))
  ElMessage.success(`已${title}`)
}
const printReport = () => router.push({ path: '/self-check-report', query: { id: c.value.id } })
</script>

<style scoped>
.late { color: var(--el-color-danger); }
.summary-line { margin: 0 0 8px; line-height: 1.8; }
.form-row { display: flex; flex-wrap: wrap; gap: 0 12px; }
.form-row > * { flex: 1 1 180px; }
.verdict { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 20px; margin: 10px 0; padding: 10px 12px; border-radius: 12px; background: var(--lg-tint); }
.verdict > div:first-child { display: flex; align-items: center; gap: 8px; }
.filters { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin: 10px 0; }
.item-card { padding: 10px 0; border-top: 1px solid var(--el-border-color-lighter); }
.item-card.issue .item-head b { color: var(--el-color-danger); }
.item-head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; }
.result-tag { margin-left: auto; }
.points { margin: 4px 0 6px; line-height: 1.6; }
.evidence { margin: 6px 0; padding: 6px 10px; border-radius: 8px; font-size: 13px; line-height: 1.6; border-left: 3px solid var(--el-border-color); background: var(--el-fill-color-light); }
.evidence.ok { border-left-color: var(--el-color-success); }
.evidence.warn { border-left-color: var(--el-color-danger); }
.evidence ul { margin: 4px 0 0; padding-left: 18px; }
.result-radio { margin: 6px 0; }
.edit-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 8px; }
.item-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; margin-top: 6px; }
</style>
