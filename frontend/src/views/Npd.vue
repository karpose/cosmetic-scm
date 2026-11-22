<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>新品立项</h2>
        <p>立项由管理员批准；开发中记录小试和中试，关联成品；上市检查从配方、备案、合规资料、稳定性、质量标准实时核对，全部通过才能提交，由质量安全负责人批准上市。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="npd-projects" label="导出项目台账" />
        <ExportButton report="npd-readiness" label="导出上市就绪度" />
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="canCreate" type="primary" @click="openCreate">申请立项</el-button>
      </div>
    </div>

    <div v-if="summary" class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">进行中新品项目</div>
        <div class="kpi-val mono">{{ summary.proposedCount + summary.activeCount + summary.launchReviewCount }} <span class="kpi-unit">/ {{ summary.totalCount }}</span></div>
        <div class="kpi-sub">立项待批 {{ summary.proposedCount }} · 开发中 {{ summary.activeCount }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.launchReviewCount > 0 }">
        <div class="kpi-label">待 QSO 批准上市</div>
        <div class="kpi-val mono">{{ summary.launchReviewCount }}</div>
        <div class="kpi-sub">上市门禁已全部通过</div>
      </div>
      <div class="kpi-card" :class="{ danger: summary.lateCount > 0 }">
        <div class="kpi-label">超计划上市日期</div>
        <div class="kpi-val mono">{{ summary.lateCount }}</div>
        <div class="kpi-sub">进行中项目已超期</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">已批准上市</div>
        <div class="kpi-val mono">{{ summary.launchedCount }}</div>
        <div class="kpi-sub">已取消/不予立项 {{ summary.rejectedOrCancelledCount }} 个</div>
      </div>
    </div>

    <el-card v-if="readiness.length" shadow="never" body-style="padding:0 0 8px" style="margin-bottom:14px">
      <template #header><b>新品上市门禁就绪度矩阵</b></template>
      <el-table :data="readiness" size="small">
        <el-table-column label="项目" min-width="160"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row.projectId)">{{ row.projectNo }}</a> {{ row.name }}</template></el-table-column>
        <el-table-column prop="statusLabel" label="状态" width="95" />
        <el-table-column label="计划上市" width="130"><template #default="{ row }"><span class="mono" :class="{ late: row.late }">{{ row.targetLaunch }}</span><div class="muted small">{{ row.daysLeft >= 0 ? `剩余 ${row.daysLeft} 天` : `超期 ${Math.abs(row.daysLeft)} 天` }}</div></template></el-table-column>
        <el-table-column label="门禁进度" width="130" align="right"><template #default="{ row }"><b class="mono" :class="{ ok: row.ready }">{{ row.passedCount }}/{{ row.totalCount }}（{{ row.readinessPct }}%）</b></template></el-table-column>
        <el-table-column label="待补齐项" min-width="220">
          <template #default="{ row }">
            <el-tag v-if="row.ready" size="small" type="success" disable-transitions>全部通过，可提交上市</el-tag>
            <span v-else class="muted small">{{ row.missingItems?.join('、') || '待批准立项' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="pg.keyword.value" placeholder="搜索编号、名称、品类、成品" clearable />
          <el-checkbox v-model="openOnly">只看进行中</el-checkbox>
        </div>
      </template>
      <el-table :data="pg.paged.value" v-loading="loader.loading.value" empty-text="还没有新品项目">
        <el-table-column label="编号" width="160"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row.id)">{{ row.projectNo }}</a><div class="muted small">{{ row.filingTypeLabel }}</div></template></el-table-column>
        <el-table-column label="产品" min-width="200"><template #default="{ row }">{{ row.name }}<div class="muted small">{{ row.category }}<template v-if="row.claims"> · {{ row.claims }}</template></div></template></el-table-column>
        <el-table-column label="关联成品" min-width="150"><template #default="{ row }"><template v-if="row.fgCode">{{ row.fgName }}<div class="muted small mono">{{ row.fgCode }}</div></template><span v-else class="muted">—</span></template></el-table-column>
        <el-table-column label="计划上市" width="120"><template #default="{ row }"><span :class="{ late: row.late }">{{ row.targetLaunch }}</span><div v-if="row.late" class="late small">已超期</div></template></el-table-column>
        <el-table-column label="试制" width="100"><template #default="{ row }">{{ row.trials }} 次<div v-if="row.pilotPassed" class="muted small">有中试通过</div></template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
        <el-table-column label="" width="90" :fixed="narrow ? false : 'right'"><template #default="{ row }"><el-button size="small" text @click="openDetail(row.id)">详情</el-button></template></el-table-column>
      </el-table>
      <ListCapHint :count="projects.length" />
      <ListPager v-model:page="pg.page.value" v-model:size="pg.size.value" :total="pg.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" title="申请立项" width="600px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="产品名称"><el-input id="np-name" v-model="cr.name" maxlength="100" /></el-form-item>
          <el-form-item label="品类"><el-input id="np-category" v-model="cr.category" maxlength="60" placeholder="如：面霜、面膜、防晒" /></el-form-item>
          <el-form-item label="备案类型">
            <el-select id="np-filing" v-model="cr.filingType" style="width:100%">
              <el-option value="GENERAL" label="普通化妆品备案" />
              <el-option value="SPECIAL" label="特殊化妆品注册" />
            </el-select>
          </el-form-item>
          <el-form-item label="计划上市日期"><el-date-picker id="np-target" v-model="cr.targetLaunch" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d < startOfToday" /></el-form-item>
        </div>
        <el-form-item label="目标宣称（选填）"><el-input id="np-claims" v-model="cr.claims" maxlength="300" placeholder="如：保湿、修护" /></el-form-item>
        <el-form-item label="说明（选填）"><el-input id="np-note" v-model="cr.note" type="textarea" :rows="2" maxlength="500" placeholder="目标人群、渠道、定位等" /></el-form-item>
        <p class="muted small">提交后由管理员批准立项，批准人不能是申请人。</p>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" @click="submitCreate">提交立项</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dt.visible" :title="`新品项目 ${dt.data?.project.projectNo || ''}`" width="820px">
      <template v-if="dt.data">
        <p class="summary-line">
          <StatusTag :status="p.status" :label="p.statusLabel" />
          <b>{{ p.name }}</b> · {{ p.category }} · {{ p.filingTypeLabel }} · 计划 <span :class="{ late: p.late }">{{ p.targetLaunch }}</span> 上市
        </p>
        <p v-if="p.claims || p.note" class="small muted">{{ [p.claims && `宣称：${p.claims}`, p.note].filter(Boolean).join('；') }}</p>
        <p class="small muted">{{ p.createdBy }} 申请<template v-if="p.approvedBy">，{{ p.approvedBy }} 批准立项</template><template v-if="p.submittedBy">，{{ p.submittedBy }} 提交上市</template><template v-if="p.launchedBy">，{{ p.launchedBy }} 批准上市于 {{ fmtTime(p.launchedAt) }}</template></p>
        <el-alert v-if="p.rejectNote" type="warning" :closable="false" show-icon :title="`${p.status === 'REJECTED' ? '不予立项' : '上市被退回'}：${p.rejectNote}`" />
        <el-alert v-if="p.cancelReason" type="info" :closable="false" show-icon :title="`已取消：${p.cancelReason}`" />

        <template v-if="p.status === 'ACTIVE' && canEdit">
          <div class="link-row">
            <el-select id="np-fg" v-model="dt.fgItemId" filterable placeholder="选择成品" aria-label="关联成品" style="flex:1">
              <el-option v-for="i in rd.finished.value" :key="i.id" :value="i.id" :label="`${i.name}（${i.code}）`" />
            </el-select>
            <el-button :disabled="!dt.fgItemId || dt.fgItemId === p.fgItemId" @click="link">{{ p.fgItemId ? '改为关联这个成品' : '关联成品' }}</el-button>
          </div>
          <p class="muted small">成品先在主数据建好。一个成品只能属于一个项目。</p>
        </template>

        <template v-if="dt.data.checks.length">
          <div class="sub-head"><b>上市检查</b><span class="small" :class="dt.data.ready ? 'ok' : 'muted'">{{ passed }} / {{ dt.data.checks.length }} 项通过</span></div>
          <div v-for="c in dt.data.checks" :key="c.title" class="check-row">
            <el-tag :type="c.ok ? 'success' : 'info'" size="small" disable-transitions>{{ c.ok ? '通过' : '未通过' }}</el-tag>
            <b>{{ c.title }}</b>
            <span class="small" :class="c.ok ? 'muted' : ''">{{ c.detail }}</span>
            <router-link v-if="!c.ok && c.route" :to="c.route" class="small">去处理</router-link>
          </div>
        </template>

        <div class="sub-head"><b>试制记录</b><el-button v-if="p.status === 'ACTIVE' && canTrial" size="small" type="primary" plain @click="openTrial">记录试制</el-button></div>
        <el-table :data="dt.data.trials" size="small" empty-text="还没有试制记录" scrollbar-tabindex="0">
          <el-table-column label="编号" width="150"><template #default="{ row }"><span class="mono">{{ row.trialNo }}</span><div class="muted small">{{ row.trialDate }}</div></template></el-table-column>
          <el-table-column label="类型" width="110"><template #default="{ row }">{{ row.kindLabel }}<span v-if="row.versionNo" class="mono muted small"> V{{ row.versionNo }}</span></template></el-table-column>
          <el-table-column label="结论" width="90"><template #default="{ row }"><el-tag :type="RESULT_TAG[row.result]" size="small" disable-transitions>{{ row.resultLabel }}</el-tag></template></el-table-column>
          <el-table-column label="情况" min-width="220"><template #default="{ row }">{{ row.summary }}<div class="muted small">{{ [row.batchSize, row.recordedBy].filter(Boolean).join(' · ') }}</div></template></el-table-column>
        </el-table>

        <div style="margin-top:12px"><AttachmentList owner-type="NPD_PROJECT" :owner-id="p.id" :can-upload="auth.canAttach()" title="立项和试制资料" /></div>
      </template>
      <template #footer>
        <template v-if="dt.data">
          <el-button v-if="canCancel && ['PROPOSED', 'ACTIVE', 'LAUNCH_REVIEW'].includes(p.status)" text @click="noteAction('cancel', '取消项目', '取消后记录保留，关联的成品可以给别的项目用。')">取消项目</el-button>
          <template v-if="isAdmin && p.status === 'PROPOSED'">
            <el-button text @click="noteAction('reject', '不予立项', '写明原因。')">不予立项</el-button>
            <el-button type="primary" :loading="dt.saving" @click="approve">批准立项</el-button>
          </template>
          <el-button v-if="canEdit && p.status === 'ACTIVE'" type="primary" :disabled="!dt.data.ready" :loading="dt.saving" @click="submitLaunch">提交上市</el-button>
          <template v-if="isQso && p.status === 'LAUNCH_REVIEW'">
            <el-button text @click="noteAction('launch-reject', '退回', '写明退回原因，退回后回到开发中。')">退回</el-button>
            <el-button type="primary" @click="lc.visible = true; lc.password = ''">批准上市</el-button>
          </template>
        </template>
        <el-button @click="dt.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="tr.visible" title="记录试制" width="560px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="类型">
            <el-radio-group id="nt-kind" v-model="tr.kind">
              <el-radio-button value="LAB">小试</el-radio-button>
              <el-radio-button value="PILOT">中试</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="试制日期"><el-date-picker id="nt-date" v-model="tr.trialDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
          <el-form-item :label="tr.kind === 'PILOT' ? '配方版本（中试必选）' : '配方版本（选填）'">
            <el-select id="nt-version" v-model="tr.formulaVersionId" clearable :placeholder="p?.fgItemId ? '选择版本' : '先关联成品'" :disabled="!p?.fgItemId" style="width:100%">
              <el-option v-for="v in versionOptions" :key="v.id" :value="v.id" :label="`V${v.versionNo} · ${v.statusLabel}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="批量（选填）"><el-input id="nt-size" v-model="tr.batchSize" maxlength="60" placeholder="如：5 kg" /></el-form-item>
        </div>
        <el-form-item label="结论">
          <el-radio-group id="nt-result" v-model="tr.result">
            <el-radio value="PASS">通过</el-radio>
            <el-radio value="ADJUST">需调整</el-radio>
            <el-radio value="FAIL">不通过</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="试制情况"><el-input id="nt-summary" v-model="tr.summary" type="textarea" :rows="3" maxlength="500" placeholder="外观、肤感、工艺问题、下一步调整" /></el-form-item>
        <p class="muted small">上市检查要有一次按当前生效配方版本做的中试，结论为通过。记录后不能改。</p>
      </el-form>
      <template #footer>
        <el-button @click="tr.visible = false">取消</el-button>
        <el-button type="primary" :loading="tr.saving" @click="submitTrial">保存试制记录</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="lc.visible" title="批准新品上市" width="460px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <p v-if="p" style="margin-top:0">{{ p.name }}（{{ p.fgCode }}）。批准时会重新核对一遍上市检查。</p>
        <p v-if="p?.submittedBy === auth.user?.username" class="late small">这个项目是您提交的，要由另一位质量安全负责人批准。</p>
        <el-form-item label="签批密码"><el-input id="np-password" v-model="lc.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="launch" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lc.visible = false">取消</el-button>
        <el-button type="primary" :loading="lc.saving" @click="launch">批准上市</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtTime, startOfBusinessDay, today } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const RESULT_TAG = { PASS: 'success', ADJUST: 'warning', FAIL: 'danger' }
const auth = useAuth()
const rd = useRefData()
const canCreate = computed(() => auth.hasAny('ADMIN', 'MASTER_DATA', 'QA', 'QSO'))
const canEdit = computed(() => auth.hasAny('MASTER_DATA', 'QA', 'QSO'))
const canTrial = computed(() => auth.hasAny('MASTER_DATA', 'QA', 'QSO', 'PRODUCTION'))
const canCancel = computed(() => auth.hasAny('ADMIN', 'MASTER_DATA'))
const isAdmin = computed(() => auth.hasAny('ADMIN'))
const isQso = computed(() => auth.hasAny('QSO'))
const projects = ref([])
const versions = ref([])
const readiness = ref([])
const summary = ref(null)
const openOnly = ref(true)
const loader = useLoader(async () => {
  const [p, v, r, s] = await Promise.all([
    http.get('/npd/projects'),
    http.get('/plm/formulas'),
    http.get('/npd/readiness'),
    http.get('/npd/summary', { silent: true }).catch(() => null)
  ])
  projects.value = p
  versions.value = v
  readiness.value = r
  summary.value = s
})
const load = () => loader.run()
const shown = computed(() => openOnly.value ? projects.value.filter((x) => ['PROPOSED', 'ACTIVE', 'LAUNCH_REVIEW'].includes(x.status)) : projects.value)
const pg = usePaged(shown, { match: textMatch('projectNo', 'name', 'category', 'fgCode', 'fgName') })
const startOfToday = startOfBusinessDay()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { load(); rd.load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const cr = reactive({ visible: false, saving: false, name: '', category: '', filingType: 'GENERAL', targetLaunch: '', claims: '', note: '' })
function openCreate() {
  Object.assign(cr, { visible: true, saving: false, name: '', category: '', filingType: 'GENERAL', targetLaunch: '', claims: '', note: '' })
}
async function submitCreate() {
  if (!cr.name.trim() || !cr.category.trim() || !cr.targetLaunch) return ElMessage.warning('请填写产品名称、品类和计划上市日期')
  cr.saving = true
  try {
    const d = await http.post('/npd/projects', { ...cr, visible: undefined, saving: undefined })
    ElMessage.success(`已提交立项 ${d.project.projectNo}，等待管理员批准`)
    cr.visible = false
    load()
    show(d)
  } finally {
    cr.saving = false
  }
}

const dt = reactive({ visible: false, saving: false, data: null, fgItemId: null })
const p = computed(() => dt.data?.project)
const passed = computed(() => dt.data?.checks.filter((c) => c.ok).length || 0)
const versionOptions = computed(() => versions.value.filter((v) => v.fgItemId === p.value?.fgItemId && ['DRAFT', 'PENDING', 'EFFECTIVE'].includes(v.status)))
const detailReq = latestOnly()
function show(d) {
  dt.data = d
  dt.fgItemId = d.project.fgItemId
  dt.visible = true
}
async function openDetail(id) {
  const current = detailReq()
  const d = await http.get(`/npd/projects/${id}`)
  if (current()) show(d)
}
function applyDetail(d) {
  dt.data = d
  dt.fgItemId = d.project.fgItemId
  load()
}
async function act(path, body, msg) {
  dt.saving = true
  try {
    applyDetail(await http.post(`/npd/projects/${p.value.id}/${path}`, body))
    ElMessage.success(msg)
  } finally {
    dt.saving = false
  }
}
const approve = () => act('approve', {}, '已批准立项')
const link = () => act('link', { fgItemId: dt.fgItemId }, '已关联成品')
const submitLaunch = () => act('submit', {}, '已提交上市，等待质量安全负责人批准')
async function noteAction(path, title, hint) {
  let note
  try {
    note = (await ElMessageBox.prompt(hint, title, { confirmButtonText: title, cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  await act(path, { note }, `已${title}`)
}

const tr = reactive({ visible: false, saving: false, kind: 'LAB', trialDate: '', formulaVersionId: null, batchSize: '', result: 'PASS', summary: '' })
function openTrial() {
  Object.assign(tr, { visible: true, saving: false, kind: 'LAB', trialDate: today(), formulaVersionId: null, batchSize: '', result: 'PASS', summary: '' })
}
async function submitTrial() {
  if (!tr.trialDate || !tr.summary.trim()) return ElMessage.warning('请填写试制日期和试制情况')
  if (tr.kind === 'PILOT' && !tr.formulaVersionId) return ElMessage.warning('中试要选配方版本')
  tr.saving = true
  try {
    applyDetail(await http.post(`/npd/projects/${p.value.id}/trials`, { kind: tr.kind, trialDate: tr.trialDate, formulaVersionId: tr.formulaVersionId, batchSize: tr.batchSize, result: tr.result, summary: tr.summary }))
    ElMessage.success('试制记录已保存')
    tr.visible = false
  } finally {
    tr.saving = false
  }
}

const lc = reactive({ visible: false, saving: false, password: '' })
async function launch() {
  if (!lc.password) return ElMessage.warning('请输入签批密码')
  lc.saving = true
  try {
    applyDetail(await http.post(`/npd/projects/${p.value.id}/launch`, { password: lc.password }))
    ElMessage.success('已批准上市')
    lc.visible = false
  } finally {
    lc.saving = false
  }
}
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
.kpi-card { background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 12px 14px; }
.kpi-card.danger { border-color: var(--el-color-danger-light-5); background: var(--el-color-danger-light-9); }
.kpi-card.warn { border-color: var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); }
.kpi-label { font-size: 12px; color: var(--el-text-color-secondary); }
.kpi-val { font-size: 22px; font-weight: 700; margin: 4px 0 2px; line-height: 1.2; }
.kpi-unit { font-size: 12px; font-weight: 400; color: var(--el-text-color-secondary); }
.kpi-sub { font-size: 12px; color: var(--el-text-color-secondary); }
.late { color: var(--el-color-danger); }
@media (max-width: 900px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.ok { color: var(--el-color-success); }
.summary-line { margin: 0 0 6px; line-height: 1.8; }
.sub-head { display: flex; justify-content: space-between; align-items: center; margin: 14px 0 6px; }
.link-row { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.check-row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; padding: 6px 0; border-top: 1px solid var(--el-border-color-lighter); }
</style>
