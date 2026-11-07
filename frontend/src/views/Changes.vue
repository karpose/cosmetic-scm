<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>变更控制</h2>
        <p>配方、工艺、原料或供应商、包材、标签、文件、设备的变更。质量人员评估风险和影响，质量安全负责人批准；行动全部完成后由质量安全负责人确认效果并关闭。申请人不能批准或关闭自己的变更。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="canRaise" type="primary" @click="openCreate">提出变更</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="pg.keyword.value" placeholder="搜索编号、标题、范围" clearable />
          <el-checkbox v-model="openOnly">只看进行中</el-checkbox>
        </div>
      </template>
      <el-table :data="pg.paged.value" v-loading="loader.loading.value" empty-text="还没有变更">
        <el-table-column label="编号" width="160"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row.id)">{{ row.changeNo }}</a><div class="muted small">{{ row.changeTypeLabel }}</div></template></el-table-column>
        <el-table-column label="变更" min-width="220"><template #default="{ row }">{{ row.title }}<div v-if="row.scope" class="muted small">范围：{{ row.scope }}</div></template></el-table-column>
        <el-table-column label="风险" width="80"><template #default="{ row }"><el-tag v-if="row.risk" :type="RISK_TAG[row.risk]" size="small" disable-transitions>{{ row.riskLabel }}</el-tag><span v-else class="muted">—</span></template></el-table-column>
        <el-table-column label="行动" width="120"><template #default="{ row }"><template v-if="row.actionsTotal">{{ row.actionsTotal - row.actionsOpen }} / {{ row.actionsTotal }}<div v-if="row.actionsOverdue" class="late small">{{ row.actionsOverdue }} 项超期</div></template><span v-else class="muted">—</span></template></el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div class="muted small">{{ row.createdBy }} 提出</div></template></el-table-column>
        <el-table-column label="" width="90" :fixed="narrow ? false : 'right'"><template #default="{ row }"><el-button size="small" text @click="openDetail(row.id)">详情</el-button></template></el-table-column>
      </el-table>
      <ListCapHint :count="changes.length" />
      <ListPager v-model:page="pg.page.value" v-model:size="pg.size.value" :total="pg.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" title="提出变更" width="600px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="变更类型">
            <el-select id="cc-type" v-model="cr.changeType" style="width:100%">
              <el-option v-for="(v, k) in TYPES" :key="k" :value="k" :label="v" />
            </el-select>
          </el-form-item>
          <el-form-item label="影响范围（选填）"><el-input id="cc-scope" v-model="cr.scope" maxlength="300" placeholder="涉及的产品、物料" /></el-form-item>
        </div>
        <el-form-item label="标题"><el-input id="cc-title" v-model="cr.title" maxlength="120" /></el-form-item>
        <el-form-item label="变更原因"><el-input id="cc-reason" v-model="cr.reason" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        <el-form-item label="变更内容"><el-input id="cc-desc" v-model="cr.description" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" @click="submitCreate">提交变更</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dt.visible" :title="`变更 ${c?.changeNo || ''}`" width="780px">
      <template v-if="dt.data">
        <p class="summary-line"><StatusTag :status="c.status" :label="c.statusLabel" /> <b>{{ c.title }}</b> · {{ c.changeTypeLabel }}<template v-if="c.risk"> · 风险{{ c.riskLabel }}</template></p>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="原因">{{ c.reason }}</el-descriptions-item>
          <el-descriptions-item label="内容">{{ c.description }}</el-descriptions-item>
          <el-descriptions-item v-if="c.scope" label="范围">{{ c.scope }}</el-descriptions-item>
          <el-descriptions-item v-if="c.impact" label="影响评估">{{ c.impact }}<span class="muted small">（{{ c.assessedBy }}）</span></el-descriptions-item>
          <el-descriptions-item v-if="c.closeNote" label="效果确认">{{ c.closeNote }}<span class="muted small">（{{ c.closedBy }}）</span></el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="c.rejectNote" type="info" :closable="false" show-icon :title="`${c.statusLabel}：${c.rejectNote}`" style="margin-top:8px" />

        <template v-if="c.status === 'SUBMITTED' && isQuality">
          <div class="sub-head"><b>评估</b></div>
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="风险等级">
              <el-radio-group id="cc-risk" v-model="as.risk">
                <el-radio-button value="LOW">低</el-radio-button>
                <el-radio-button value="MEDIUM">中</el-radio-button>
                <el-radio-button value="HIGH">高</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="影响评估"><el-input id="cc-impact" v-model="as.impact" type="textarea" :rows="3" maxlength="1000" placeholder="对质量、安全、备案、标签、库存的影响" /></el-form-item>
            <el-form-item label="需要做的（勾选后自动生成行动项，负责人是您，期限 30 天）">
              <div class="checks">
                <el-checkbox v-model="as.needFiling">变更备案或注册</el-checkbox>
                <el-checkbox v-model="as.needStability">补做稳定性试验</el-checkbox>
                <el-checkbox v-model="as.needLabel">更新标签</el-checkbox>
                <el-checkbox v-model="as.needValidation">验证</el-checkbox>
              </div>
            </el-form-item>
          </el-form>
        </template>

        <div class="sub-head"><b>行动项</b><el-button v-if="c.status === 'APPROVED' && isQuality" size="small" type="primary" plain @click="openAction">加行动项</el-button></div>
        <el-table :data="dt.data.actions" size="small" empty-text="没有行动项" scrollbar-tabindex="0">
          <el-table-column label="行动" min-width="200"><template #default="{ row }">{{ row.content }}<div v-if="row.doneNote" class="muted small">{{ row.doneBy }}：{{ row.doneNote }}</div></template></el-table-column>
          <el-table-column label="负责人" width="100" prop="owner" />
          <el-table-column label="期限" width="110"><template #default="{ row }"><span :class="{ late: row.overdue }">{{ row.dueDate }}</span></template></el-table-column>
          <el-table-column label="" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.done" type="success" size="small" disable-transitions>已完成</el-tag>
              <el-button v-else-if="c.status === 'APPROVED' && (row.owner === auth.user?.username || isQuality)" size="small" @click="done(row)">登记完成</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top:12px"><AttachmentList owner-type="CHANGE_REQUEST" :owner-id="c.id" :can-upload="auth.canAttach()" title="评估和实施资料" /></div>

        <el-form v-if="isQso && ['ASSESSED', 'APPROVED'].includes(c.status)" label-position="top" style="margin-top:8px" @submit.prevent>
          <p v-if="c.createdBy === auth.user?.username" class="late small">这项变更是您提出的，要由另一位质量安全负责人{{ c.status === 'ASSESSED' ? '批准' : '关闭' }}。</p>
          <el-form-item v-if="c.status === 'APPROVED'" label="效果确认"><el-input id="cc-close-note" v-model="dt.closeNote" maxlength="500" placeholder="变更后的情况是否符合预期" /></el-form-item>
          <el-form-item label="签批密码"><el-input id="cc-password" v-model="dt.password" type="password" show-password autocomplete="current-password" /></el-form-item>
        </el-form>
      </template>
      <template #footer>
        <template v-if="dt.data">
          <el-button v-if="['SUBMITTED', 'ASSESSED'].includes(c.status) && (c.createdBy === auth.user?.username || isQuality)" text @click="noteAction('cancel', '取消变更')">取消变更</el-button>
          <el-button v-if="isQso && ['SUBMITTED', 'ASSESSED'].includes(c.status)" text @click="noteAction('reject', '不批准')">不批准</el-button>
          <el-button v-if="c.status === 'SUBMITTED' && isQuality" type="primary" :loading="dt.saving" @click="assess">保存评估</el-button>
          <el-button v-if="c.status === 'ASSESSED' && isQso" type="primary" :loading="dt.saving" @click="approve">批准变更</el-button>
          <el-button v-if="c.status === 'APPROVED' && isQso" type="primary" :loading="dt.saving" :disabled="dt.data.actions.some((a) => !a.done)" @click="close">关闭变更</el-button>
        </template>
        <el-button @click="dt.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ac.visible" title="加行动项" width="480px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="行动内容"><el-input id="cc-action" v-model="ac.content" maxlength="300" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="负责人账号"><el-input id="cc-owner" v-model="ac.owner" maxlength="64" placeholder="如 qa1" /></el-form-item>
          <el-form-item label="完成期限"><el-date-picker id="cc-due" v-model="ac.dueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(x) => x < startOfToday" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="ac.visible = false">取消</el-button>
        <el-button type="primary" :loading="ac.saving" @click="submitAction">保存行动项</el-button>
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
import { daysFromToday, startOfBusinessDay } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { textMatch, usePaged } from '../utils/table'

const TYPES = { FORMULA: '配方', PROCESS: '工艺', MATERIAL: '原料或供应商', PACKAGE: '包材', LABEL: '标签', DOCUMENT: '文件', EQUIPMENT: '设备', OTHER: '其他' }
const RISK_TAG = { LOW: 'info', MEDIUM: 'warning', HIGH: 'danger' }
const auth = useAuth()
const canRaise = computed(() => auth.hasAny('ADMIN', 'MASTER_DATA', 'PURCHASE', 'WAREHOUSE', 'PRODUCTION', 'QA', 'QSO', 'SALES', 'FINANCE'))
const isQuality = computed(() => auth.hasAny('QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const changes = ref([])
const openOnly = ref(true)
const loader = useLoader(async () => { changes.value = await http.get('/changes') })
const load = () => loader.run()
const shown = computed(() => openOnly.value ? changes.value.filter((x) => ['SUBMITTED', 'ASSESSED', 'APPROVED'].includes(x.status)) : changes.value)
const pg = usePaged(shown, { match: textMatch('changeNo', 'title', 'scope') })
const startOfToday = startOfBusinessDay()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const cr = reactive({ visible: false, saving: false, title: '', changeType: 'FORMULA', reason: '', description: '', scope: '' })
function openCreate() {
  Object.assign(cr, { visible: true, saving: false, title: '', changeType: 'FORMULA', reason: '', description: '', scope: '' })
}
async function submitCreate() {
  if (!cr.title.trim() || !cr.reason.trim() || !cr.description.trim()) return ElMessage.warning('请填写标题、原因和内容')
  cr.saving = true
  try {
    const x = await http.post('/changes', { title: cr.title, changeType: cr.changeType, reason: cr.reason, description: cr.description, scope: cr.scope })
    ElMessage.success(`变更 ${x.change.changeNo} 已提交，等待质量评估`)
    cr.visible = false
    load()
    show(x)
  } finally {
    cr.saving = false
  }
}

const dt = reactive({ visible: false, saving: false, data: null, password: '', closeNote: '' })
const c = computed(() => dt.data?.change)
const as = reactive({ risk: 'MEDIUM', impact: '', needFiling: false, needStability: false, needLabel: false, needValidation: false })
const req = latestOnly()
function show(x) {
  dt.data = x
  dt.password = ''
  dt.closeNote = ''
  Object.assign(as, { risk: 'MEDIUM', impact: '', needFiling: false, needStability: false, needLabel: false, needValidation: false })
  dt.visible = true
}
async function openDetail(id) {
  const current = req()
  const x = await http.get(`/changes/${id}`)
  if (current()) show(x)
}
async function post(path, body, msg) {
  dt.saving = true
  try {
    const x = await http.post(`/changes/${c.value.id}/${path}`, body)
    dt.data = x
    dt.password = ''
    load()
    ElMessage.success(msg)
  } finally {
    dt.saving = false
  }
}
function assess() {
  if (!as.impact.trim()) return ElMessage.warning('请填写影响评估')
  return post('assess', { ...as }, '评估已保存，等待质量安全负责人批准')
}
function approve() {
  if (!dt.password) return ElMessage.warning('请输入签批密码')
  return post('approve', { password: dt.password }, '已批准，开始实施')
}
function close() {
  if (!dt.closeNote.trim() || !dt.password) return ElMessage.warning('请填写效果确认和签批密码')
  return post('close', { note: dt.closeNote, password: dt.password }, '变更已关闭')
}
async function prompt(title, hint) {
  try {
    return (await ElMessageBox.prompt(hint, title, { confirmButtonText: title, cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请填写' })).value
  } catch {
    return null
  }
}
async function noteAction(path, title) {
  const note = await prompt(title, '写明原因。')
  if (note != null) await post(path, { note }, `已${title}`)
}
async function done(a) {
  const note = await prompt('登记完成', `“${a.content}”的完成情况。`)
  if (note != null) await post(`actions/${a.id}/done`, { note }, '已登记完成')
}

const ac = reactive({ visible: false, saving: false, content: '', owner: '', dueDate: '' })
function openAction() {
  Object.assign(ac, { visible: true, saving: false, content: '', owner: auth.user?.username || '', dueDate: daysFromToday(30) })
}
async function submitAction() {
  if (!ac.content.trim() || !ac.owner.trim() || !ac.dueDate) return ElMessage.warning('请填写行动内容、负责人和期限')
  ac.saving = true
  try {
    await post('actions', { content: ac.content, owner: ac.owner, dueDate: ac.dueDate }, '行动项已保存')
    ac.visible = false
  } finally {
    ac.saving = false
  }
}
</script>

<style scoped>
.late { color: var(--el-color-danger); }
.summary-line { margin: 0 0 8px; line-height: 1.8; }
.sub-head { display: flex; justify-content: space-between; align-items: center; margin: 14px 0 6px; }
.checks { display: flex; flex-wrap: wrap; gap: 4px 16px; }
</style>
