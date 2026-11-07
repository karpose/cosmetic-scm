<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>受控文件</h2>
        <p>SOP、工艺规程、记录表格等。每次修订是一个新版本，要上传文件，由质量安全负责人签批（不能是起草人）。现行版本是已签批、已到生效日期的最新一版；按复审周期提醒。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">新建文件</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="pg.keyword.value" placeholder="搜索编号、名称、部门" clearable />
          <el-checkbox v-model="activeOnly">只看在用</el-checkbox>
        </div>
      </template>
      <el-table :data="pg.paged.value" v-loading="loader.loading.value" empty-text="还没有受控文件">
        <el-table-column label="编号" width="150"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row.id)">{{ row.docCode }}</a><div class="muted small">{{ row.categoryLabel }}</div></template></el-table-column>
        <el-table-column label="名称" min-width="200"><template #default="{ row }">{{ row.title }}<div v-if="row.ownerDept" class="muted small">{{ row.ownerDept }}</div></template></el-table-column>
        <el-table-column label="现行版本" width="150">
          <template #default="{ row }">
            <template v-if="row.currentRev">第 {{ row.currentRev }} 版<div class="muted small">{{ row.currentEffective }} 起</div></template>
            <span v-else class="muted">没有</span>
            <div v-if="row.upcomingRev" class="small">第 {{ row.upcomingRev }} 版 {{ row.upcomingEffective }} 生效</div>
            <div v-if="row.pendingRevisionId" class="small warn">有一版待签批</div>
          </template>
        </el-table-column>
        <el-table-column label="复审" width="130"><template #default="{ row }"><template v-if="row.reviewDue"><span :class="{ late: row.reviewOverdue }">{{ row.reviewDue }}</span><div class="muted small">每 {{ row.reviewMonths }} 个月</div></template><span v-else class="muted">—</span></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
        <el-table-column label="" width="90" :fixed="narrow ? false : 'right'"><template #default="{ row }"><el-button size="small" text @click="openDetail(row.id)">详情</el-button></template></el-table-column>
      </el-table>
      <ListCapHint :count="docs.length" />
      <ListPager v-model:page="pg.page.value" v-model:size="pg.size.value" :total="pg.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" title="新建受控文件" width="600px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="文件编号"><el-input id="dc-code" v-model="cr.docCode" maxlength="40" placeholder="如 SOP-QA-001" /></el-form-item>
          <el-form-item label="类别">
            <el-select id="dc-category" v-model="cr.category" style="width:100%">
              <el-option v-for="(v, k) in CATEGORY" :key="k" :value="k" :label="v" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="文件名称"><el-input id="dc-title" v-model="cr.title" maxlength="120" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="归口部门（选填）"><el-input id="dc-dept" v-model="cr.ownerDept" maxlength="60" /></el-form-item>
          <el-form-item label="复审周期（月）"><el-input-number id="dc-months" v-model="cr.reviewMonths" :min="1" :max="60" :precision="0" style="width:100%" /></el-form-item>
          <el-form-item label="生效日期（选填，默认签批当天）"><el-date-picker id="dc-effective" v-model="cr.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d < startOfToday" /></el-form-item>
        </div>
        <el-form-item label="制定说明"><el-input id="dc-summary" v-model="cr.changeSummary" maxlength="300" placeholder="如：首版" /></el-form-item>
        <p class="muted small">保存后上传文件，再由质量安全负责人签批。</p>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" @click="submitCreate">保存文件</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dt.visible" :title="`受控文件 ${d?.docCode || ''}`" width="780px">
      <template v-if="dt.data">
        <p class="summary-line"><StatusTag :status="d.status" :label="d.statusLabel" /> <b>{{ d.title }}</b> · {{ d.categoryLabel }}<template v-if="d.ownerDept"> · {{ d.ownerDept }}</template> · 每 {{ d.reviewMonths }} 个月复审</p>
        <el-alert v-if="d.obsoleteReason" type="info" :closable="false" show-icon :title="`已作废：${d.obsoleteReason}`" />
        <el-table :data="dt.data.revisions" size="small" style="margin-top:8px" highlight-current-row scrollbar-tabindex="0" @current-change="(r) => (dt.rev = r)">
          <el-table-column label="版本" width="110"><template #default="{ row }">第 {{ row.revNo }} 版 <el-tag v-if="row.current" type="success" size="small" disable-transitions>现行</el-tag><el-tag v-else-if="row.upcoming" size="small" disable-transitions>待生效</el-tag></template></el-table-column>
          <el-table-column label="修订内容" min-width="200"><template #default="{ row }">{{ row.changeSummary }}<div v-if="row.rejectNote" class="small late">退回：{{ row.rejectNote }}</div></template></el-table-column>
          <el-table-column label="生效日期" width="110"><template #default="{ row }">{{ row.effectiveDate || '签批当天' }}</template></el-table-column>
          <el-table-column label="状态" width="150"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div class="muted small">{{ row.approvedBy ? `${row.approvedBy} 签批` : `${row.draftedBy} 起草` }}</div></template></el-table-column>
        </el-table>
        <p class="muted small">点一行查看这一版的文件。</p>
        <AttachmentList v-if="dt.rev" owner-type="DOC_REVISION" :owner-id="dt.rev.id" :can-upload="canEdit && dt.rev.status === 'PENDING'" :title="`第 ${dt.rev.revNo} 版的文件`" empty-text="还没有上传文件" />

        <template v-if="isQso && pending">
          <p v-if="pending.draftedBy === auth.user?.username" class="late small">第 {{ pending.revNo }} 版是您起草的，要由另一位质量安全负责人签批。</p>
          <el-form label-position="top" style="margin-top:8px" @submit.prevent>
            <el-form-item :label="`签批第 ${pending.revNo} 版的密码`"><el-input id="dc-password" v-model="dt.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="approve" /></el-form-item>
          </el-form>
        </template>
      </template>
      <template #footer>
        <template v-if="dt.data && d.status === 'ACTIVE'">
          <el-button v-if="isQso" text @click="noteAction('obsolete', '作废文件', '作废后不能再修订，记录保留。')">作废</el-button>
          <el-button v-if="isQso && d.currentRev" text @click="noteAction('review', '复审', '确认现行版本仍然适用，下次复审从今天起算。')">复审确认</el-button>
          <el-button v-if="canEdit && !pending" @click="openRevise">出新版</el-button>
          <template v-if="isQso && pending">
            <el-button text @click="rejectPending">退回</el-button>
            <el-button type="primary" :loading="dt.saving" @click="approve">签批第 {{ pending.revNo }} 版</el-button>
          </template>
        </template>
        <el-button @click="dt.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rv.visible" title="出新版" width="480px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="修订内容"><el-input id="dc-rev-summary" v-model="rv.changeSummary" type="textarea" :rows="2" maxlength="300" /></el-form-item>
        <el-form-item label="生效日期（选填，默认签批当天）"><el-date-picker id="dc-rev-effective" v-model="rv.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(x) => x < startOfToday" /></el-form-item>
        <p class="muted small">保存后在新版本上上传文件，签批前现行版本照常使用。</p>
      </el-form>
      <template #footer>
        <el-button @click="rv.visible = false">取消</el-button>
        <el-button type="primary" :loading="rv.saving" @click="submitRevise">保存新版</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { startOfBusinessDay } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { textMatch, usePaged } from '../utils/table'

const CATEGORY = { SOP: '标准操作规程', PROCESS: '工艺规程', FORM: '记录表格', MANUAL: '手册', OTHER: '其他' }
const auth = useAuth()
const canEdit = computed(() => auth.hasAny('MASTER_DATA', 'QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const docs = ref([])
const activeOnly = ref(true)
const loader = useLoader(async () => { docs.value = await http.get('/docs') })
const load = () => loader.run()
const shown = computed(() => activeOnly.value ? docs.value.filter((x) => x.status === 'ACTIVE') : docs.value)
const pg = usePaged(shown, { match: textMatch('docCode', 'title', 'ownerDept') })
const startOfToday = startOfBusinessDay()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const cr = reactive({ visible: false, saving: false, docCode: '', title: '', category: 'SOP', ownerDept: '', reviewMonths: 24, effectiveDate: null, changeSummary: '首版' })
function openCreate() {
  Object.assign(cr, { visible: true, saving: false, docCode: '', title: '', category: 'SOP', ownerDept: '', reviewMonths: 24, effectiveDate: null, changeSummary: '首版' })
}
async function submitCreate() {
  if (!cr.docCode.trim() || !cr.title.trim() || !cr.changeSummary.trim()) return ElMessage.warning('请填写文件编号、名称和制定说明')
  cr.saving = true
  try {
    const x = await http.post('/docs', { docCode: cr.docCode, title: cr.title, category: cr.category, ownerDept: cr.ownerDept, reviewMonths: cr.reviewMonths, effectiveDate: cr.effectiveDate, changeSummary: cr.changeSummary })
    ElMessage.success(`文件 ${x.doc.docCode} 已保存，请上传文件`)
    cr.visible = false
    load()
    show(x)
  } finally {
    cr.saving = false
  }
}

const dt = reactive({ visible: false, saving: false, data: null, rev: null, password: '' })
const d = computed(() => dt.data?.doc)
const pending = computed(() => dt.data?.revisions.find((r) => r.status === 'PENDING'))
const req = latestOnly()
function show(x) {
  dt.data = x
  dt.rev = x.revisions.find((r) => r.status === 'PENDING') || x.revisions.find((r) => r.current) || x.revisions[0] || null
  dt.password = ''
  dt.visible = true
}
async function openDetail(id) {
  const current = req()
  const x = await http.get(`/docs/${id}`)
  if (current()) show(x)
}
function apply(x) {
  show(x)
  load()
}
async function approve() {
  if (!dt.password) return ElMessage.warning('请输入签批密码')
  dt.saving = true
  try {
    apply(await http.post(`/docs/${d.value.id}/revisions/${pending.value.id}/approve`, { password: dt.password }))
    ElMessage.success('已签批')
  } finally {
    dt.saving = false
  }
}
async function prompt(title, hint) {
  try {
    return (await ElMessageBox.prompt(hint, title, { confirmButtonText: title, cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请填写' })).value
  } catch {
    return null
  }
}
async function rejectPending() {
  const note = await prompt('退回', '写明退回原因。')
  if (note == null) return
  apply(await http.post(`/docs/${d.value.id}/revisions/${pending.value.id}/reject`, { note }))
  ElMessage.success('已退回')
}
async function noteAction(path, title, hint) {
  const note = await prompt(title, hint)
  if (note == null) return
  apply(await http.post(`/docs/${d.value.id}/${path}`, { note }))
  ElMessage.success(`已${title}`)
}

const rv = reactive({ visible: false, saving: false, changeSummary: '', effectiveDate: null })
function openRevise() {
  Object.assign(rv, { visible: true, saving: false, changeSummary: '', effectiveDate: null })
}
async function submitRevise() {
  if (!rv.changeSummary.trim()) return ElMessage.warning('请写明修订内容')
  rv.saving = true
  try {
    apply(await http.post(`/docs/${d.value.id}/revisions`, { changeSummary: rv.changeSummary, effectiveDate: rv.effectiveDate }))
    ElMessage.success('新版已保存，请上传文件')
    rv.visible = false
  } finally {
    rv.saving = false
  }
}
</script>

<style scoped>
.late { color: var(--el-color-danger); }
.warn { color: var(--el-color-warning); }
.summary-line { margin: 0 0 6px; line-height: 1.8; }
</style>
