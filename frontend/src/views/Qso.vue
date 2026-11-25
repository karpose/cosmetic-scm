<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>质量安全负责人</h2>
        <p>任职档案和协助履职授权（检查要点委托生产版第 4、5 项）。质量安全负责人要有化妆品、化学、化工、生物、医学、药学、食品、公共卫生或法学等相关专业知识，5 年以上化妆品生产或质量管理经验；可以书面授权他人协助履行资料审核、物料和产品放行、不良反应监测职责（第七条第三至五项），授权写明职责、范围和期限，并定期监督。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="isAdmin" @click="openAppoint">登记任职</el-button>
        <el-button v-if="isCurrent" type="primary" @click="openDelegate">授权协助履职</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <template v-else-if="data">
      <el-card shadow="never" class="block">
        <template #header><b>现任</b></template>
        <template v-if="cur">
          <div class="qso-current">
            <div class="kv"><span class="muted small">姓名</span><b>{{ cur.personName }}</b><span class="muted small">账号 {{ cur.username }}</span></div>
            <div class="kv"><span class="muted small">专业</span>{{ cur.majorLabel }}<template v-if="cur.majorDetail">（{{ cur.majorDetail }}）</template><span v-if="cur.education" class="muted small">{{ cur.education }}</span></div>
            <div class="kv"><span class="muted small">经验</span>{{ cur.yearsExperience }} 年</div>
            <div class="kv"><span class="muted small">任职日期</span>{{ cur.appointedOn }}<span class="muted small">{{ cur.activatedBy }} {{ fmtTime(cur.activatedAt) }} 签批生效</span></div>
          </div>
          <p v-if="cur.experience" class="small"><b>工作经历：</b>{{ cur.experience }}</p>
          <AttachmentList owner-type="QSO_APPOINTMENT" :owner-id="cur.id" :can-upload="isAdmin" title="任命文件和资质证明" />
        </template>
        <el-alert v-else type="warning" :closable="false" show-icon title="还没有生效的质量安全负责人任职档案"
                  :description="isAdmin ? '点“登记任职”录入姓名、专业、经验，上传任命文件和资质证明后签批生效。' : '请管理员在本页登记任职档案。'" />
      </el-card>

      <el-card shadow="never" class="block">
        <template #header><b>账号核对</b><span class="muted small"> · 有“质量安全负责人”角色的账号，要么是现任，要么有生效中的协助履职授权</span></template>
        <el-table :data="data.qsoUsers" empty-text="没有账号带质量安全负责人角色">
          <el-table-column label="账号" min-width="160"><template #default="{ row }">{{ row.displayName }}<div class="muted small mono">{{ row.username }}</div></template></el-table-column>
          <el-table-column label="依据" min-width="240"><template #default="{ row }">{{ row.basis }}</template></el-table-column>
          <el-table-column label="核对" width="110"><template #default="{ row }"><el-tag :type="row.ok ? 'success' : 'danger'" size="small" disable-transitions>{{ row.ok ? '有依据' : '没有依据' }}</el-tag></template></el-table-column>
        </el-table>
        <p v-if="!cur && data.qsoUsers.length" class="muted small">还没有现任，暂不核对。</p>
      </el-card>

      <el-tabs v-model="tab" class="seg">
        <el-tab-pane :label="`协助履职授权（${data.delegations.length}）`" name="delegations">
          <el-card shadow="never" body-style="padding:0 0 8px">
            <el-table :data="data.delegations" empty-text="还没有授权">
              <el-table-column label="编号" width="140"><template #default="{ row }"><span class="mono">{{ row.delegationNo }}</span><div class="muted small">{{ row.grantedBy }} 授权</div></template></el-table-column>
              <el-table-column label="被授权人" min-width="110"><template #default="{ row }">{{ row.delegateName }}<div class="muted small mono">{{ row.delegateUsername }}</div></template></el-table-column>
              <el-table-column label="职责和事项" min-width="200"><template #default="{ row }">{{ row.dutyLabels }}<div class="muted small">{{ row.scope }}</div></template></el-table-column>
              <el-table-column label="期限" width="175"><template #default="{ row }"><span class="mono small">{{ row.validFrom }} 至 {{ row.validTo }}</span><div v-if="row.expiringSoon" class="warn small">快到期</div></template></el-table-column>
              <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="STATE_TAG[row.state]" size="small" disable-transitions>{{ row.stateLabel }}</el-tag></template></el-table-column>
              <el-table-column label="监督" min-width="140">
                <template #default="{ row }">
                  <template v-if="row.reviews.length">{{ last(row).reviewDate }} {{ last(row).reviewedBy }}<div class="muted small ellipsis">{{ last(row).note }}</div></template>
                  <span v-else class="muted small">还没有监督记录</span>
                  <div v-if="row.reviewDue" class="warn small">超过 6 个月没有监督</div>
                  <div v-if="row.revokeReason" class="muted small">撤销：{{ row.revokeReason }}</div>
                </template>
              </el-table-column>
              <el-table-column label="" width="140" :fixed="narrow ? false : 'right'">
                <template #default="{ row }">
                  <el-button size="small" text @click="openDelegation(row)">详情</el-button>
                  <template v-if="canManage(row)">
                    <el-button size="small" text @click="openReview(row)">监督</el-button>
                    <el-button size="small" text type="danger" @click="revoke(row)">撤销</el-button>
                  </template>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-tab-pane>
        <el-tab-pane :label="`任职档案（${data.appointments.length}）`" name="appointments">
          <el-card shadow="never" body-style="padding:0 0 8px">
            <el-table :data="data.appointments" empty-text="还没有任职档案">
              <el-table-column label="编号" width="150"><template #default="{ row }"><span class="mono">{{ row.appointNo }}</span><div class="muted small">{{ row.createdBy }} 登记</div></template></el-table-column>
              <el-table-column label="姓名" min-width="130"><template #default="{ row }">{{ row.personName }}<div class="muted small mono">{{ row.username }}</div></template></el-table-column>
              <el-table-column label="专业和经验" min-width="190"><template #default="{ row }">{{ row.majorLabel }}<template v-if="row.majorDetail">（{{ row.majorDetail }}）</template><div class="muted small">经验 {{ row.yearsExperience }} 年<template v-if="row.education"> · {{ row.education }}</template></div></template></el-table-column>
              <el-table-column label="任职" width="180"><template #default="{ row }"><span class="mono small">{{ row.appointedOn }}<template v-if="row.endedOn"> 至 {{ row.endedOn }}</template></span><div v-if="row.endReason" class="muted small">{{ row.endReason }}</div></template></el-table-column>
              <el-table-column label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
              <el-table-column label="" width="170" :fixed="narrow ? false : 'right'">
                <template #default="{ row }">
                  <el-button size="small" text @click="openFiles(row)">附件</el-button>
                  <template v-if="isAdmin">
                    <el-button v-if="row.status === 'DRAFT'" size="small" text type="primary" @click="openActivate(row)">生效</el-button>
                    <el-button v-if="row.status === 'ACTIVE'" size="small" text type="danger" @click="endAppointment(row)">离任</el-button>
                  </template>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- 登记任职 -->
    <el-dialog v-model="ap.visible" title="登记质量安全负责人任职" width="600px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-row">
          <el-form-item label="对应账号">
            <el-select id="qso-user" v-model="ap.username" placeholder="有质量安全负责人角色的账号" style="width:100%" @change="onPickUser">
              <el-option v-for="u in data?.qsoUsers || []" :key="u.username" :value="u.username" :label="`${u.displayName}（${u.username}）`" />
            </el-select>
          </el-form-item>
          <el-form-item label="姓名"><el-input id="qso-name" v-model="ap.personName" maxlength="60" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="所学专业类别">
            <el-select id="qso-major" v-model="ap.majorField" placeholder="请选择" style="width:100%">
              <el-option v-for="m in data?.majors || []" :key="m.code" :value="m.code" :label="m.label" />
            </el-select>
          </el-form-item>
          <el-form-item :label="ap.majorField === 'OTHER' ? '专业名称（必填）' : '专业名称（选填）'"><el-input id="qso-major-detail" v-model="ap.majorDetail" maxlength="100" placeholder="如：应用化学" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="学历（选填）"><el-input id="qso-edu" v-model="ap.education" maxlength="60" placeholder="如：本科" /></el-form-item>
          <el-form-item label="化妆品生产或质量管理经验（年）"><el-input-number id="qso-years" v-model="ap.yearsExperience" :min="0" :max="60" style="width:100%" /></el-form-item>
          <el-form-item label="任职日期"><el-date-picker id="qso-date" v-model="ap.appointedOn" type="date" value-format="YYYY-MM-DD" :disabled-date="(x) => x > endOfToday" style="width:100%" /></el-form-item>
        </div>
        <el-form-item label="工作经历（选填）"><el-input id="qso-exp" v-model="ap.experience" type="textarea" :rows="3" maxlength="500" placeholder="哪年到哪年，在哪家企业做什么" /></el-form-item>
        <p class="muted small">经验不少于 5 年。登记后在“任职档案”里上传任命文件和资质证明（学历、专业、工作经历证明），再签批生效；生效后原现任自动离任，原现任给出的授权一并撤销。</p>
      </el-form>
      <template #footer>
        <el-button @click="ap.visible = false">取消</el-button>
        <el-button type="primary" :loading="ap.saving" @click="submitAppoint">登记</el-button>
      </template>
    </el-dialog>

    <!-- 附件和生效 -->
    <el-dialog v-model="fl.visible" :title="`任职档案 ${fl.row?.appointNo || ''}`" width="620px">
      <template v-if="fl.row">
        <p class="summary-line">{{ fl.row.personName }}（{{ fl.row.username }}） · {{ fl.row.majorLabel }} · 经验 {{ fl.row.yearsExperience }} 年 · <StatusTag :status="fl.row.status" :label="fl.row.statusLabel" /></p>
        <AttachmentList owner-type="QSO_APPOINTMENT" :owner-id="fl.row.id" :can-upload="isAdmin && fl.row.status !== 'ENDED'" title="任命文件和资质证明" empty-text="还没有上传，生效前必须上传" />
        <template v-if="fl.activate">
          <el-divider />
          <p v-if="cur" class="warn small">生效后 {{ cur.personName }}（{{ cur.username }}）自动离任，其给出的授权一并撤销。</p>
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="签批密码"><el-input id="qso-activate-pwd" v-model="fl.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="submitActivate" /></el-form-item>
          </el-form>
        </template>
      </template>
      <template #footer>
        <el-button @click="fl.visible = false">关闭</el-button>
        <el-button v-if="fl.activate" type="primary" :loading="fl.saving" @click="submitActivate">签批生效</el-button>
      </template>
    </el-dialog>

    <!-- 授权 -->
    <el-dialog v-model="dl.visible" title="授权协助履行职责" width="600px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="被授权人">
          <el-select id="dl-user" v-model="dl.delegateUsername" filterable placeholder="选择内部账号" style="width:100%">
            <el-option v-for="u in delegateOptions" :key="u.code" :value="u.code" :label="`${u.label}（${u.code}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="协助履行的职责">
          <el-checkbox-group v-model="dl.duties" aria-label="协助履行的职责">
            <el-checkbox v-for="d in data?.duties || []" :key="d.code" :value="d.code">{{ d.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="具体事项和范围"><el-input id="dl-scope" v-model="dl.scope" type="textarea" :rows="2" maxlength="300" placeholder="如：质量安全负责人出差期间，审核受托厂出厂放行申报并签批上市放行" /></el-form-item>
        <div class="form-row">
          <el-form-item label="开始日期"><el-date-picker id="dl-from" v-model="dl.validFrom" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker id="dl-to" v-model="dl.validTo" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        </div>
        <el-form-item label="签批密码"><el-input id="dl-pwd" v-model="dl.password" type="password" show-password autocomplete="current-password" /></el-form-item>
        <p class="muted small">每份授权最长 12 个月，到期前 30 天提醒；建立和组织实施质量管理体系、产品质量安全问题决策（第七条第一、二项）不能授权。被授权人要具备相应资质和能力，授权期间至少每 6 个月记录一次监督情况。被授权人如还没有“质量安全负责人”角色，请管理员在账号与权限里加上。</p>
      </el-form>
      <template #footer>
        <el-button @click="dl.visible = false">取消</el-button>
        <el-button type="primary" :loading="dl.saving" @click="submitDelegate">授权</el-button>
      </template>
    </el-dialog>

    <!-- 授权详情 -->
    <el-dialog v-model="dd.visible" :title="`协助履职授权 ${dd.row?.delegationNo || ''}`" width="600px">
      <template v-if="dd.row">
        <p class="summary-line"><el-tag :type="STATE_TAG[dd.row.state]" size="small" disable-transitions>{{ dd.row.stateLabel }}</el-tag> {{ dd.row.qsoName }} 授权 {{ dd.row.delegateName }}（{{ dd.row.delegateUsername }}）</p>
        <p class="small"><b>职责：</b>{{ dd.row.dutyLabels }}</p>
        <p class="small"><b>事项：</b>{{ dd.row.scope }}</p>
        <p class="small"><b>期限：</b>{{ dd.row.validFrom }} 至 {{ dd.row.validTo }}；{{ dd.row.grantedBy }} {{ fmtTime(dd.row.grantedAt) }} 签批</p>
        <p v-if="dd.row.revokedBy" class="small"><b>撤销：</b>{{ dd.row.revokedBy }} {{ fmtTime(dd.row.revokedAt) }}，{{ dd.row.revokeReason }}</p>
        <h4>监督记录</h4>
        <el-timeline v-if="dd.row.reviews.length">
          <el-timeline-item v-for="(r, i) in dd.row.reviews" :key="i" :timestamp="`${r.reviewDate} ${r.reviewedBy}`">{{ r.note }}</el-timeline-item>
        </el-timeline>
        <p v-else class="muted small">还没有监督记录。</p>
      </template>
    </el-dialog>

    <!-- 记录监督 -->
    <el-dialog v-model="rv.visible" title="记录协助履职监督" width="520px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p v-if="rv.row" class="small" style="margin-top:0">{{ rv.row.delegateName }} · {{ rv.row.dutyLabels }}</p>
        <el-form-item label="监督情况"><el-input id="rv-note" v-model="rv.note" type="textarea" :rows="3" maxlength="500" placeholder="抽查了哪些放行审核、不良反应处理记录，有没有问题" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rv.visible = false">取消</el-button>
        <el-button type="primary" :loading="rv.saving" @click="submitReview">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtTime, today } from '../utils/format'
import { useLoader } from '../utils/loader'

const STATE_TAG = { IN_EFFECT: 'success', NOT_STARTED: 'warning', EXPIRED: 'info', REVOKED: 'info' }

const auth = useAuth()
const isAdmin = computed(() => auth.hasAny('ADMIN'))
const endOfToday = new Date(`${today()}T23:59:59`)
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }

const data = ref(null)
const tab = ref('delegations')
const cur = computed(() => data.value?.current || null)
const isCurrent = computed(() => !!cur.value && cur.value.username === auth.user?.username && auth.hasAny('QSO'))
const loader = useLoader(async () => {
  data.value = await http.get('/qso')
})
const load = () => loader.run()
onMounted(() => { load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const last = (row) => row.reviews[row.reviews.length - 1]
const canManage = (row) => isCurrent.value && row.status === 'ACTIVE' && row.appointmentId === cur.value.id

// 登记任职
const ap = reactive({ visible: false, saving: false, username: '', personName: '', majorField: '', majorDetail: '', education: '', yearsExperience: 5, experience: '', appointedOn: '' })
function openAppoint() {
  Object.assign(ap, { visible: true, saving: false, username: '', personName: '', majorField: '', majorDetail: '', education: '', yearsExperience: 5, experience: '', appointedOn: today() })
}
function onPickUser(v) {
  if (!ap.personName) ap.personName = data.value.qsoUsers.find((u) => u.username === v)?.displayName || ''
}
async function submitAppoint() {
  if (!ap.username || !ap.personName.trim() || !ap.majorField || !ap.appointedOn) return ElMessage.warning('请填写账号、姓名、专业类别和任职日期')
  ap.saving = true
  try {
    const a = await http.post('/qso/appointments', {
      username: ap.username, personName: ap.personName, majorField: ap.majorField, majorDetail: ap.majorDetail, education: ap.education,
      yearsExperience: ap.yearsExperience, experience: ap.experience, appointedOn: ap.appointedOn
    })
    ElMessage.success(`任职档案 ${a.appointNo} 已登记，请上传任命文件后签批生效`)
    ap.visible = false
    tab.value = 'appointments'
    await load()
    openFiles(a, true)
  } finally {
    ap.saving = false
  }
}

// 附件、生效
const fl = reactive({ visible: false, row: null, activate: false, password: '', saving: false })
function openFiles(row, activate = false) {
  Object.assign(fl, { visible: true, row, activate: activate && isAdmin.value && row.status === 'DRAFT', password: '', saving: false })
}
const openActivate = (row) => openFiles(row, true)
async function submitActivate() {
  if (!fl.password) return ElMessage.warning('请输入签批密码')
  fl.saving = true
  try {
    const a = await http.post(`/qso/appointments/${fl.row.id}/activate`, { password: fl.password })
    ElMessage.success(`${a.personName} 的任职已生效`)
    fl.visible = false
    load()
  } finally {
    fl.saving = false
  }
}
async function endAppointment(row) {
  let note
  try {
    note = (await ElMessageBox.prompt(`${row.personName} 离任后，其给出的协助履职授权一并撤销。`, '离任', { confirmButtonText: '离任', cancelButtonText: '不了', inputPlaceholder: '离任原因', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  await http.post(`/qso/appointments/${row.id}/end`, { note })
  ElMessage.success('已离任')
  load()
}

// 授权
const delegateOptions = computed(() => (data.value?.staff || []).filter((u) => u.code !== auth.user?.username))
const dl = reactive({ visible: false, saving: false, delegateUsername: '', duties: [], scope: '', validFrom: '', validTo: '', password: '' })
function openDelegate() {
  const d = new Date(`${today()}T00:00:00`)
  d.setMonth(d.getMonth() + 6)
  const to = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  Object.assign(dl, { visible: true, saving: false, delegateUsername: '', duties: [], scope: '', validFrom: today(), validTo: to, password: '' })
}
async function submitDelegate() {
  if (!dl.delegateUsername || !dl.duties.length || !dl.scope.trim() || !dl.validFrom || !dl.validTo) return ElMessage.warning('请选择被授权人和职责，写明事项和期限')
  if (!dl.password) return ElMessage.warning('请输入签批密码')
  dl.saving = true
  try {
    const d = await http.post('/qso/delegations', { delegateUsername: dl.delegateUsername, duties: dl.duties, scope: dl.scope, validFrom: dl.validFrom, validTo: dl.validTo, password: dl.password })
    ElMessage.success(`授权 ${d.delegationNo} 已签发`)
    dl.visible = false
    tab.value = 'delegations'
    load()
  } finally {
    dl.saving = false
  }
}
const dd = reactive({ visible: false, row: null })
function openDelegation(row) {
  Object.assign(dd, { visible: true, row })
}
async function revoke(row) {
  let note
  try {
    note = (await ElMessageBox.prompt(`撤销 ${row.delegateName} 的协助履职授权。`, '撤销授权', { confirmButtonText: '撤销', cancelButtonText: '不了', inputPlaceholder: '撤销原因', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  await http.post(`/qso/delegations/${row.id}/revoke`, { note })
  ElMessage.success('已撤销')
  load()
}

// 监督
const rv = reactive({ visible: false, saving: false, row: null, note: '' })
function openReview(row) {
  Object.assign(rv, { visible: true, saving: false, row, note: '' })
}
async function submitReview() {
  if (!rv.note.trim()) return ElMessage.warning('请写明监督情况')
  rv.saving = true
  try {
    await http.post(`/qso/delegations/${rv.row.id}/reviews`, { note: rv.note })
    ElMessage.success('监督记录已保存')
    rv.visible = false
    load()
  } finally {
    rv.saving = false
  }
}
</script>

<style scoped>
.block { margin-bottom: 12px; }
.qso-current { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 8px 16px; }
.kv { display: flex; flex-direction: column; gap: 2px; }
.summary-line { margin: 0 0 8px; line-height: 1.8; }
.form-row { display: flex; flex-wrap: wrap; gap: 0 12px; }
.form-row > * { flex: 1 1 160px; }
.warn { color: var(--el-color-danger); }
.ellipsis { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
