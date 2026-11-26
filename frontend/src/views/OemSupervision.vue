<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>受托生产监督</h2>
        <p>对受托生产企业生产活动的监督，对应检查要点委托生产版第 9 项（生产过程、检验、放行）和第 18 项（受托方保存的记录）。登记每次驻厂监督、现场检查、调阅记录或远程视频监督和发现的问题：一般问题写当场纠正的情况或转 CAPA，重大问题要转 CAPA；提交后由质量安全负责人审核。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <ExportButton report="oem-supervision" />
        <el-button v-if="canEdit" type="primary" @click="openCreate()">登记监督</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <template v-else-if="data">
      <p class="muted small rule-line">有在做的委托单、超过 {{ data.intervalDays }} 天没有提交监督记录的受托厂会进总览待办。登记中和作废的记录不算。</p>
      <el-tabs v-model="tab" class="seg">
        <el-tab-pane :label="`监督记录（${data.records.length}）`" name="records">
          <el-card shadow="never" body-style="padding:0 0 8px">
            <el-table :data="data.records" empty-text="还没有监督记录" scrollbar-tabindex="0">
              <el-table-column label="编号" width="150">
                <template #default="{ row }"><span class="mono">{{ row.supervisionNo }}</span><div class="muted small">{{ row.supervisedOn }}</div></template>
              </el-table-column>
              <el-table-column label="受托生产企业" min-width="160">
                <template #default="{ row }">{{ row.factoryName }}<div v-if="row.moNo" class="muted small"><span class="mono">{{ row.moNo }}</span> {{ row.fgName }}</div></template>
              </el-table-column>
              <el-table-column label="方式和环节" min-width="200">
                <template #default="{ row }">{{ row.methodLabel }}<div class="muted small">{{ row.scopeLabels }}</div></template>
              </el-table-column>
              <el-table-column label="结论" width="130">
                <template #default="{ row }">
                  <el-tag :type="CONCLUSION_TAG[row.conclusion]" size="small" disable-transitions>{{ row.conclusionLabel }}</el-tag>
                  <div v-if="row.openCapaCount" class="small muted">{{ row.openCapaCount }} 个 CAPA 未验证</div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }"><el-tag :type="STATUS_TAG[row.status]" size="small" disable-transitions>{{ row.statusLabel }}</el-tag>
                  <div v-if="row.status === 'DRAFT' && row.rejectNote" class="warn small">已退回</div>
                </template>
              </el-table-column>
              <el-table-column label="" width="130" :fixed="narrow ? false : 'right'">
                <template #default="{ row }">
                  <el-button size="small" text @click="openDetail(row)">详情</el-button>
                  <el-button v-if="isQso && row.status === 'SUBMITTED'" size="small" text type="primary" @click="openDetail(row, true)">审核</el-button>
                </template>
              </el-table-column>
            </el-table>
            <ListCapHint :count="data.records.length" />
          </el-card>
        </el-tab-pane>
        <el-tab-pane :label="`受托厂${dueCount ? `（${dueCount} 家到期）` : ''}`" name="factories">
          <el-card shadow="never" body-style="padding:0 0 8px">
            <el-table :data="data.factories" empty-text="主数据里还没有受托生产企业" scrollbar-tabindex="0">
              <el-table-column label="受托生产企业" min-width="180">
                <template #default="{ row }">{{ row.factoryName }}<div class="muted small mono">{{ row.factoryCode }}<template v-if="!row.qualified">（已停用）</template></div></template>
              </el-table-column>
              <el-table-column label="在做的委托单" width="110"><template #default="{ row }">{{ row.openOrders }}</template></el-table-column>
              <el-table-column label="最近一次监督" width="130"><template #default="{ row }">{{ row.lastSupervised || '—' }}</template></el-table-column>
              <el-table-column :label="`近 ${data.intervalDays} 天`" width="90"><template #default="{ row }">{{ row.recentCount }} 次</template></el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag v-if="row.due" type="danger" size="small" disable-transitions>该去监督了</el-tag>
                  <span v-else class="muted small">{{ row.openOrders ? '正常' : '没有在做的单' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="" width="110" :fixed="narrow ? false : 'right'">
                <template #default="{ row }"><el-button v-if="canEdit" size="small" text type="primary" @click="openCreate({ factoryId: row.factoryId })">登记监督</el-button></template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-tab-pane>
        <el-tab-pane label="委托单" name="orders">
          <el-card shadow="never" body-style="padding:0 0 8px">
            <p class="muted small pad">还没完成的委托单，和最近 90 天下的或完工的委托单（不含已取消），各有哪些针对它的监督。</p>
            <el-table :data="orders" empty-text="没有委托单" scrollbar-tabindex="0">
              <el-table-column label="委托单" width="150"><template #default="{ row }"><span class="mono">{{ row.moNo }}</span><div class="muted small">{{ fmtTime(row.createdAt) }}</div></template></el-table-column>
              <el-table-column label="受托厂和成品" min-width="200"><template #default="{ row }">{{ row.factoryName }}<div class="muted small">{{ row.fgName }} <span class="mono">{{ row.fgCode }}</span></div></template></el-table-column>
              <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
              <el-table-column label="监督" min-width="200">
                <template #default="{ row }">
                  <div v-for="r in row.records" :key="r.id" class="small"><span class="mono">{{ r.supervisionNo }}</span> {{ r.supervisedOn }} {{ r.methodLabel }}（{{ r.statusLabel }}）</div>
                  <span v-if="!row.records.length" class="muted small">没有针对本单的监督</span>
                </template>
              </el-table-column>
              <el-table-column label="" width="110" :fixed="narrow ? false : 'right'">
                <template #default="{ row }"><el-button v-if="canEdit" size="small" text type="primary" @click="openCreate({ factoryId: row.factoryId, moId: row.moId })">登记监督</el-button></template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- 登记 / 修改 -->
    <el-dialog v-model="ed.visible" :title="ed.id ? `修改监督记录 ${ed.no}` : '登记受托生产监督'" width="640px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-row">
          <el-form-item label="受托生产企业">
            <el-select id="sv-factory" v-model="ed.factoryId" placeholder="选择受托生产企业" filterable style="width:100%" @change="ed.moId = null">
              <el-option v-for="p in rd.oems.value" :key="p.id" :value="p.id" :label="p.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="委托单（选填）">
            <el-select id="sv-mo" v-model="ed.moId" placeholder="定期现场检查可以不选" clearable filterable style="width:100%">
              <el-option v-for="o in factoryOrders" :key="o.moId" :value="o.moId" :label="`${o.moNo} ${o.fgName}（${o.statusLabel}）`" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="监督方式">
            <el-select id="sv-method" v-model="ed.method" placeholder="请选择" style="width:100%">
              <el-option v-for="m in data?.methods || []" :key="m.code" :value="m.code" :label="m.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="监督日期"><el-date-picker id="sv-date" v-model="ed.supervisedOn" type="date" value-format="YYYY-MM-DD" :disabled-date="(x) => x > endOfToday" style="width:100%" /></el-form-item>
        </div>
        <el-form-item label="监督的环节">
          <el-checkbox-group v-model="ed.scopes" aria-label="监督的环节">
            <el-checkbox v-for="s in data?.scopes || []" :key="s.code" :value="s.code">{{ s.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="参加人员（选填）"><el-input id="sv-people" v-model="ed.participants" maxlength="200" placeholder="如：品牌方 QA 张三；受托方 QA 主管李四" /></el-form-item>
        <el-form-item label="监督情况"><el-input id="sv-content" v-model="ed.content" type="textarea" :rows="4" maxlength="1000" placeholder="看了哪些工序、检验和记录，执行情况怎样" /></el-form-item>
        <p class="muted small">保存后在详情里逐条登记发现的问题，可以上传照片和检查表。</p>
      </el-form>
      <template #footer>
        <el-button @click="ed.visible = false">取消</el-button>
        <el-button type="primary" :loading="ed.saving" @click="submitEdit">{{ ed.id ? '保存修改' : '保存记录' }}</el-button>
      </template>
    </el-dialog>

    <!-- 详情、问题、提交、审核 -->
    <el-dialog v-model="dt.visible" :title="`监督记录 ${dt.row?.supervisionNo || ''}`" width="720px" @closed="load">
      <template v-if="dt.row">
        <p class="summary-line">
          <el-tag :type="STATUS_TAG[dt.row.status]" size="small" disable-transitions>{{ dt.row.statusLabel }}</el-tag>
          <el-tag :type="CONCLUSION_TAG[dt.row.conclusion]" size="small" disable-transitions>{{ dt.row.conclusionLabel }}</el-tag>
          {{ dt.row.factoryName }} · {{ dt.row.supervisedOn }} {{ dt.row.methodLabel }}
        </p>
        <p v-if="dt.row.moNo" class="small"><b>委托单：</b><span class="mono">{{ dt.row.moNo }}</span> {{ dt.row.fgName }}</p>
        <p class="small"><b>环节：</b>{{ dt.row.scopeLabels }}</p>
        <p v-if="dt.row.participants" class="small"><b>参加人员：</b>{{ dt.row.participants }}</p>
        <p class="small pre"><b>监督情况：</b>{{ dt.row.content }}</p>
        <el-alert v-if="dt.row.rejectNote && dt.row.status === 'DRAFT'" type="error" :closable="false" show-icon :title="`已退回：${dt.row.rejectNote}`" style="margin-bottom:8px" />
        <el-alert v-if="dt.row.cancelReason" type="info" :closable="false" show-icon :title="`已作废：${dt.row.cancelReason}`" style="margin-bottom:8px" />
        <p class="muted small">{{ dt.row.createdBy }} {{ fmtTime(dt.row.createdAt) }} 登记<template v-if="dt.row.submittedBy">；{{ dt.row.submittedBy }} {{ fmtTime(dt.row.submittedAt) }} 提交</template><template v-if="dt.row.reviewedBy">；{{ dt.row.reviewedBy }} {{ fmtTime(dt.row.reviewedAt) }} 审核<template v-if="dt.row.reviewNote">：{{ dt.row.reviewNote }}</template></template></p>

        <h4>发现的问题（{{ dt.row.findings.length }}）</h4>
        <p v-if="!dt.row.findings.length" class="muted small">没有登记问题。</p>
        <div v-for="f in dt.row.findings" :key="f.id" class="finding">
          <div>
            <el-tag :type="f.severity === 'MAJOR' ? 'danger' : 'warning'" size="small" disable-transitions>{{ f.severityLabel }}</el-tag>
            <b>问题 {{ f.seq }}</b>：{{ f.description }}
          </div>
          <div v-if="f.correction" class="small">当场纠正：{{ f.correction }}</div>
          <div v-if="f.capaNo" class="small"><router-link to="/qms?tab=capas" class="mono">CAPA {{ f.capaNo }}</router-link>（{{ f.capaStatusLabel }}）</div>
          <div v-if="!f.handled" class="warn small">{{ f.severity === 'MAJOR' ? '重大问题要转 CAPA' : '还要写当场纠正的情况或转 CAPA' }}</div>
          <div v-if="editable" class="finding-actions">
            <el-button v-if="f.severity === 'MINOR'" size="small" text @click="correct(f)">{{ f.correction ? '修改纠正情况' : '写纠正情况' }}</el-button>
            <el-button v-if="!f.capaId" size="small" text type="primary" @click="openCapa(f)">转 CAPA</el-button>
            <el-button v-if="!f.capaId" size="small" text type="danger" @click="removeFinding(f)">删除</el-button>
          </div>
        </div>
        <el-form v-if="editable" label-position="top" class="add-finding" @submit.prevent>
          <el-form-item label="问题的程度">
            <el-radio-group v-model="nf.severity">
              <el-radio-button v-for="s in data?.severities || []" :key="s.code" :value="s.code">{{ s.label }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="发现的问题"><el-input id="sv-finding" v-model="nf.description" type="textarea" :rows="2" maxlength="500" placeholder="在哪个工序、记录或检验里发现了什么" /></el-form-item>
          <el-form-item v-if="nf.severity === 'MINOR'" label="当场纠正的情况（选填）"><el-input id="sv-correction" v-model="nf.correction" maxlength="500" placeholder="没有当场纠正的，之后转 CAPA" /></el-form-item>
          <el-button :loading="nf.saving" @click="addFinding">登记问题</el-button>
        </el-form>

        <AttachmentList owner-type="OEM_SUPERVISION" :owner-id="dt.row.id" :can-upload="canEdit && ['DRAFT', 'SUBMITTED'].includes(dt.row.status)"
                        title="照片和检查表" empty-text="还没有上传（不是必须的）" />
        <template v-if="dt.review">
          <el-divider />
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="审核意见（选填）"><el-input id="sv-review-note" v-model="dt.note" maxlength="300" /></el-form-item>
            <el-form-item label="签批密码"><el-input id="sv-review-pwd" v-model="dt.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="submitConfirm" /></el-form-item>
            <p class="muted small">审核人不能是登记人或提交人。监督不充分或问题处理不当的请退回。</p>
          </el-form>
        </template>
      </template>
      <template #footer>
        <el-button @click="dt.visible = false">关闭</el-button>
        <template v-if="dt.row">
          <el-button v-if="canEdit && ['DRAFT', 'SUBMITTED'].includes(dt.row.status) && !dt.review" type="danger" plain @click="cancel(dt.row)">作废</el-button>
          <el-button v-if="editable" @click="openEdit(dt.row)">修改</el-button>
          <el-button v-if="editable" type="primary" :loading="dt.saving" @click="submitForReview(dt.row)">提交审核</el-button>
          <template v-if="dt.review">
            <el-button type="danger" plain @click="reject(dt.row)">退回</el-button>
            <el-button type="primary" :loading="dt.saving" @click="submitConfirm">审核通过</el-button>
          </template>
        </template>
      </template>
    </el-dialog>

    <!-- 转 CAPA -->
    <el-dialog v-model="cp.visible" :title="`问题 ${cp.finding?.seq || ''} 转 CAPA`" width="520px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <p v-if="cp.finding" class="small" style="margin-top:0">{{ cp.finding.severityLabel }}：{{ cp.finding.description }}</p>
        <el-form-item label="措施类型">
          <el-radio-group v-model="cp.actionType">
            <el-radio-button value="CORRECTIVE">纠正措施</el-radio-button>
            <el-radio-button value="PREVENTIVE">预防措施</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="措施内容"><el-input id="sv-capa-desc" v-model="cp.description" type="textarea" :rows="3" maxlength="900" placeholder="受托方怎么整改、整改到什么程度" /></el-form-item>
        <div class="form-row">
          <el-form-item label="负责人"><el-input id="sv-capa-owner" v-model="cp.owner" maxlength="64" /></el-form-item>
          <el-form-item label="完成期限"><el-date-picker id="sv-capa-due" v-model="cp.dueDate" type="date" value-format="YYYY-MM-DD" :disabled-date="(x) => x < startOfToday" style="width:100%" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="cp.visible = false">取消</el-button>
        <el-button type="primary" :loading="cp.saving" @click="submitCapa">生成 CAPA</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { useLoader } from '../utils/loader'
import { daysFromToday, fmtTime, startOfBusinessDay, today } from '../utils/format'

const STATUS_TAG = { DRAFT: 'info', SUBMITTED: 'warning', CONFIRMED: 'success', CANCELLED: 'info' }
const CONCLUSION_TAG = { NONE: 'success', MINOR: 'warning', MAJOR: 'danger' }
const TABS = ['records', 'factories', 'orders']

const auth = useAuth()
const route = useRoute()
const rd = useRefData()
const canEdit = computed(() => auth.hasAny('ADMIN', 'PRODUCTION', 'QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const endOfToday = new Date(`${today()}T23:59:59`)
const startOfToday = startOfBusinessDay()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }

const data = ref(null)
const orders = ref([])
const tab = ref(TABS.includes(route.query.tab) ? route.query.tab : 'records')
const dueCount = computed(() => (data.value?.factories || []).filter((f) => f.due).length)
const loader = useLoader(async () => {
  const [o, os] = await Promise.all([http.get('/oem-supervision'), http.get('/oem-supervision/orders')])
  data.value = o
  orders.value = os
  // 详情开着时换成最新的
  if (dt.visible && dt.row) dt.row = o.records.find((r) => r.id === dt.row.id) || dt.row
})
const load = () => loader.run()
onMounted(() => { load(); rd.ready(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

// 登记、修改
const ed = reactive({ visible: false, saving: false, id: null, no: '', factoryId: null, moId: null, method: 'ON_SITE', supervisedOn: '', scopes: [], participants: '', content: '' })
const factoryOrders = computed(() => orders.value.filter((o) => o.factoryId === ed.factoryId))
async function openCreate(preset = {}) {
  await rd.ready()
  const oems = rd.oems.value
  Object.assign(ed, { visible: true, saving: false, id: null, no: '', factoryId: preset.factoryId ?? (oems.length === 1 ? oems[0].id : null), moId: preset.moId ?? null,
    method: preset.moId ? 'ON_SITE' : 'INSPECTION', supervisedOn: today(), scopes: [], participants: '', content: '' })
}
async function openEdit(row) {
  await rd.ready()
  dt.visible = false
  Object.assign(ed, { visible: true, saving: false, id: row.id, no: row.supervisionNo, factoryId: row.factoryId, moId: row.moId, method: row.method,
    supervisedOn: row.supervisedOn, scopes: [...row.scopes], participants: row.participants || '', content: row.content })
}
async function submitEdit() {
  if (!ed.factoryId || !ed.method || !ed.supervisedOn) return ElMessage.warning('请选择受托生产企业、监督方式和日期')
  if (!ed.scopes.length) return ElMessage.warning('请至少选一个监督的环节')
  if (!ed.content.trim()) return ElMessage.warning('请填写监督情况')
  ed.saving = true
  const body = { factoryId: ed.factoryId, moId: ed.moId, method: ed.method, supervisedOn: ed.supervisedOn, scopes: ed.scopes, participants: ed.participants, content: ed.content }
  try {
    const r = ed.id ? await http.put(`/oem-supervision/${ed.id}`, body) : await http.post('/oem-supervision', body)
    ElMessage.success(ed.id ? `监督记录 ${r.supervisionNo} 已修改` : `监督记录 ${r.supervisionNo} 已登记，请登记发现的问题后提交审核`)
    ed.visible = false
    tab.value = 'records'
    await load()
    openDetail(r)
  } finally {
    ed.saving = false
  }
}

// 详情
const dt = reactive({ visible: false, row: null, review: false, note: '', password: '', saving: false })
const editable = computed(() => canEdit.value && dt.row?.status === 'DRAFT')
function openDetail(row, review = false) {
  Object.assign(dt, { visible: true, row, review: review && isQso.value && row.status === 'SUBMITTED', note: '', password: '', saving: false })
  resetFinding()
}

// 问题
const nf = reactive({ severity: 'MINOR', description: '', correction: '', saving: false })
function resetFinding() {
  Object.assign(nf, { severity: 'MINOR', description: '', correction: '', saving: false })
}
async function addFinding() {
  if (!nf.description.trim()) return ElMessage.warning('请写明发现的问题')
  nf.saving = true
  try {
    dt.row = await http.post(`/oem-supervision/${dt.row.id}/findings`, { severity: nf.severity, description: nf.description, correction: nf.severity === 'MINOR' ? nf.correction : null })
    ElMessage.success('已登记问题')
    resetFinding()
  } finally {
    nf.saving = false
  }
}
async function correct(f) {
  let correction
  try {
    correction = (await ElMessageBox.prompt('写明当场怎样纠正的。', `问题 ${f.seq} 的纠正情况`, { confirmButtonText: '保存纠正情况', cancelButtonText: '不了',
      inputValue: f.correction || '', inputValidator: (v) => !!v?.trim() || '请写明纠正情况' })).value
  } catch {
    return
  }
  dt.row = await http.post(`/oem-supervision/${dt.row.id}/findings/${f.id}/correction`, { correction })
  ElMessage.success('已保存纠正情况')
}
async function removeFinding(f) {
  try {
    await ElMessageBox.confirm(`删除问题 ${f.seq}：${f.description}？`, '删除问题', { confirmButtonText: '删除', cancelButtonText: '不了', type: 'warning' })
  } catch {
    return
  }
  dt.row = await http.post(`/oem-supervision/${dt.row.id}/findings/${f.id}/remove`)
  ElMessage.success('已删除')
}

// 转 CAPA
const cp = reactive({ visible: false, saving: false, finding: null, actionType: 'CORRECTIVE', description: '', owner: '', dueDate: '' })
function openCapa(f) {
  Object.assign(cp, { visible: true, saving: false, finding: f, actionType: 'CORRECTIVE', description: '', owner: auth.user?.username || '', dueDate: daysFromToday(30) })
}
async function submitCapa() {
  if (!cp.description.trim() || !cp.owner.trim() || !cp.dueDate) return ElMessage.warning('请填写措施内容、负责人和完成期限')
  cp.saving = true
  try {
    dt.row = await http.post(`/oem-supervision/${dt.row.id}/findings/${cp.finding.id}/capa`, { actionType: cp.actionType, description: cp.description, owner: cp.owner, dueDate: cp.dueDate })
    const f = dt.row.findings.find((x) => x.id === cp.finding.id)
    ElMessage.success(`已生成 CAPA ${f?.capaNo || ''}`)
    cp.visible = false
  } finally {
    cp.saving = false
  }
}

// 提交、审核、退回、作废
async function submitForReview(row) {
  dt.saving = true
  try {
    const r = await http.post(`/oem-supervision/${row.id}/submit`)
    ElMessage.success(`监督记录 ${r.supervisionNo} 已提交，等质量安全负责人审核`)
    dt.visible = false
  } finally {
    dt.saving = false
  }
}
async function submitConfirm() {
  if (!dt.password) return ElMessage.warning('请输入签批密码')
  dt.saving = true
  try {
    const r = await http.post(`/oem-supervision/${dt.row.id}/confirm`, { note: dt.note, password: dt.password })
    ElMessage.success(`监督记录 ${r.supervisionNo} 已审核`)
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
async function reject(row) {
  const note = await askReason('退回后记录回到登记中，登记人补充后重新提交。', `退回 ${row.supervisionNo}`, '退回')
  if (!note) return
  await http.post(`/oem-supervision/${row.id}/reject`, { note })
  ElMessage.success('已退回')
  dt.visible = false
}
async function cancel(row) {
  const note = await askReason('作废后不能恢复，登记的问题和转出的 CAPA 保留。', `作废 ${row.supervisionNo}`, '作废')
  if (!note) return
  await http.post(`/oem-supervision/${row.id}/cancel`, { note })
  ElMessage.success('已作废')
  dt.visible = false
}
</script>

<style scoped>
.rule-line { margin: 0 0 8px; }
.pad { padding: 8px 12px 0; margin: 0; }
.summary-line { margin: 0 0 8px; line-height: 1.8; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.form-row { display: flex; flex-wrap: wrap; gap: 0 12px; }
.form-row > * { flex: 1 1 160px; }
.warn { color: var(--el-color-danger); }
.pre { white-space: pre-wrap; }
.finding { padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter); line-height: 1.7; }
.finding-actions { margin-top: 2px; }
.add-finding { margin: 12px 0 4px; }
</style>
