<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>合规资料</h2>
        <p>产品的安全评估、功效宣称依据和标签，都对应配方版本，配方变了要重新评估。法规目录决定配方、下单和询价能不能过校验。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="tab === 'products' && canRegister" type="primary" @click="openCreate()">登记资料</el-button>
        <el-button v-if="tab === 'regulatory' && canEditCatalog" type="primary" @click="importOpen = true">导入法规目录</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" class="seg">
      <el-tab-pane :label="`产品档案（${incomplete} 个不全）`" name="products" />
      <el-tab-pane :label="`法规目录（${activeRules} 条）`" name="regulatory" />
    </el-tabs>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />

    <template v-else-if="tab === 'products'">
      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header><div class="filters"><el-input v-model="pt.keyword.value" placeholder="搜索编码、成品名称" clearable /></div></template>
        <el-table :data="pt.paged.value" v-loading="loader.loading.value" empty-text="还没有成品">
          <el-table-column label="成品" min-width="190"><template #default="{ row }">{{ row.fgName }}<div class="muted small mono">{{ row.fgCode }}</div></template></el-table-column>
          <el-table-column label="生效配方" width="90"><template #default="{ row }"><span class="mono">{{ row.versionNo ? `V${row.versionNo}` : '—' }}</span></template></el-table-column>
          <el-table-column label="安全评估" width="130"><template #default="{ row }"><el-tag :type="STATE[row.safety].tag" size="small" disable-transitions>{{ STATE[row.safety].label }}</el-tag></template></el-table-column>
          <el-table-column label="功效宣称依据" width="130"><template #default="{ row }"><el-tag :type="STATE[row.efficacy].tag === 'danger' ? 'warning' : STATE[row.efficacy].tag" size="small" disable-transitions>{{ STATE[row.efficacy].label }}</el-tag></template></el-table-column>
          <el-table-column label="标签" width="130"><template #default="{ row }"><el-tag :type="STATE[row.label].tag" size="small" disable-transitions>{{ STATE[row.label].label }}</el-tag></template></el-table-column>
          <el-table-column label="" width="190" :fixed="narrow ? false : 'right'">
            <template #default="{ row }">
              <el-button v-if="row.versionId" size="small" text @click="openIngredients(row)">全成分表</el-button>
              <el-button v-if="row.versionId && canRegister" size="small" text @click="openCreate(row)">登记</el-button>
            </template>
          </el-table-column>
        </el-table>
        <ListPager v-model:page="pt.page.value" v-model:size="pt.size.value" :total="pt.total.value" />
      </el-card>

      <el-card shadow="never" body-style="padding:0 0 8px" style="margin-top:14px">
        <template #header><b>资料记录</b></template>
        <el-table :data="dt.paged.value" empty-text="还没有登记资料">
          <el-table-column label="编号" width="150"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openFiles(row)">{{ row.docNo }}</a><div class="muted small mono">{{ row.docDate }}</div></template></el-table-column>
          <el-table-column label="成品和版本" min-width="170"><template #default="{ row }">{{ row.fgName }} <span class="mono muted small">V{{ row.versionNo }}</span></template></el-table-column>
          <el-table-column label="资料" min-width="220"><template #default="{ row }"><b>{{ row.docTypeLabel }}</b> {{ row.title }}<div v-if="row.claim" class="small">宣称：{{ row.claim }}</div><div class="muted small">{{ row.evaluator }}</div></template></el-table-column>
          <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.signedBy" class="muted small">{{ row.signedBy }}</div><div v-if="row.rejectNote || row.voidReason" class="muted small">{{ row.rejectNote || row.voidReason }}</div></template></el-table-column>
          <el-table-column label="" width="170" :fixed="narrow ? false : 'right'">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING' && isQso" size="small" type="primary" @click="openApprove(row)">签批</el-button>
              <el-button v-if="row.status === 'PENDING' && isQso" size="small" text @click="note(row, 'reject', '退回', '写明退回原因。')">退回</el-button>
              <el-button v-if="row.status === 'APPROVED' && isQso" size="small" text @click="note(row, 'void', '作废', '作废后要重新登记。记录会保留。')">作废</el-button>
            </template>
          </el-table-column>
        </el-table>
        <ListCapHint :count="docs.length" />
        <ListPager v-model:page="dt.page.value" v-model:size="dt.size.value" :total="dt.total.value" />
      </el-card>
    </template>

    <template v-else>
      <el-alert type="warning" :closable="false" show-icon title="系统不内置法规内容。目录要按官方发布的版本导入，并写明来源；法规更新时要重新导入。只按 INCI 名称对照。" />
      <el-card v-if="compare?.violations.length" shadow="never" class="block" body-style="padding:0 0 8px">
        <template #header><b class="late">生效配方不符合目录（{{ compare.violations.length }}）</b></template>
        <el-table :data="compare.violations" size="small">
          <el-table-column label="成品" min-width="180"><template #default="{ row }">{{ row.fgName }}<div class="muted small mono">{{ row.fgCode }}</div></template></el-table-column>
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="message" label="问题" min-width="260" />
        </el-table>
      </el-card>
      <el-card v-if="compare?.gaps.length" shadow="never" class="block" body-style="padding:0 0 8px">
        <template #header><b>物料和目录不一致（{{ compare.gaps.length }}）</b></template>
        <el-table :data="compare.gaps" size="small" max-height="280" scrollbar-tabindex="0">
          <el-table-column label="物料" min-width="170"><template #default="{ row }">{{ row.name }}<div class="muted small mono">{{ row.code }}</div></template></el-table-column>
          <el-table-column prop="inci" label="INCI" min-width="150" />
          <el-table-column prop="detail" label="问题" min-width="280" />
        </el-table>
      </el-card>
      <el-card shadow="never" class="block" body-style="padding:0 0 8px">
        <template #header><div class="filters"><el-input v-model="rt.keyword.value" placeholder="搜索 INCI、中文名、CAS、来源" clearable /></div></template>
        <el-table :data="rt.paged.value" empty-text="还没有导入法规目录">
          <el-table-column label="INCI" min-width="190"><template #default="{ row }">{{ row.inciName }}<div class="muted small">{{ row.cnName }} <span class="mono">{{ row.casNo }}</span></div><div v-if="row.aliases" class="muted small">别名：{{ row.aliases }}</div></template></el-table-column>
          <el-table-column label="类别" width="90"><template #default="{ row }"><el-tag :type="row.catalog === 'BANNED' ? 'danger' : 'warning'" size="small" disable-transitions>{{ row.catalogLabel }}</el-tag></template></el-table-column>
          <el-table-column label="上限" width="90" align="right"><template #default="{ row }"><span class="mono">{{ row.maxPct != null ? `${fmtQty(row.maxPct)}%` : '—' }}</span></template></el-table-column>
          <el-table-column prop="conditions" label="条件" min-width="150" />
          <el-table-column prop="source" label="来源" min-width="170" />
          <el-table-column label="状态" width="150"><template #default="{ row }"><el-tag :type="row.active ? 'success' : 'info'" size="small" disable-transitions>{{ row.active ? '有效' : '已停用' }}</el-tag> <el-button v-if="row.active && canEditCatalog" size="small" text @click="retire(row)">停用</el-button></template></el-table-column>
        </el-table>
        <ListPager v-model:page="rt.page.value" v-model:size="rt.size.value" :total="rt.total.value" />
      </el-card>
    </template>

    <el-dialog v-model="cr.visible" title="登记合规资料" width="600px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="成品">
            <el-select id="cd-fg" v-model="cr.fgItemId" filterable placeholder="选择成品" style="width:100%" @change="cr.formulaVersionId = null">
              <el-option v-for="p in withFormula" :key="p.fgItemId" :value="p.fgItemId" :label="`${p.fgName}（${p.fgCode}）`" />
            </el-select>
          </el-form-item>
          <el-form-item label="资料类型">
            <el-select id="cd-type" v-model="cr.docType" style="width:100%">
              <el-option value="SAFETY" label="安全评估" />
              <el-option value="EFFICACY" label="功效宣称依据" />
              <el-option value="LABEL" label="标签" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="对应配方版本（默认当前生效版本；配方变更时可以先为新版本做评估）">
          <el-select id="cd-version" v-model="cr.formulaVersionId" clearable placeholder="当前生效版本" style="width:100%">
            <el-option v-for="v in versionOptions" :key="v.id" :value="v.id" :label="`V${v.versionNo} · ${v.statusLabel}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题"><el-input id="cd-title" v-model="cr.title" maxlength="120" placeholder="如：安全评估报告 2026-01" /></el-form-item>
        <el-form-item v-if="cr.docType === 'EFFICACY'" label="宣称内容"><el-input id="cd-claim" v-model="cr.claim" maxlength="300" placeholder="如：保湿" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="评估人或出具机构"><el-input id="cd-evaluator" v-model="cr.evaluator" maxlength="100" /></el-form-item>
          <el-form-item label="日期"><el-date-picker id="cd-date" v-model="cr.docDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
        </div>
        <el-form-item label="备注（选填）"><el-input id="cd-note" v-model="cr.note" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        <p class="muted small">保存后点编号上传报告文件，再由质量安全负责人签批。</p>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" @click="submitCreate">保存资料</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ap.visible" :title="`签批：${ap.row?.docNo || ''}`" width="480px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p style="margin-top:0">{{ ap.row?.fgName }} V{{ ap.row?.versionNo }}：{{ ap.row?.docTypeLabel }} {{ ap.row?.title }}</p>
        <AttachmentList v-if="ap.row" owner-type="COMPLIANCE_DOC" :owner-id="ap.row.id" title="报告文件" empty-text="还没有上传文件，签批前请确认" />
        <p v-if="ap.row?.createdBy === auth.user?.username" class="late small">这份资料是您登记的，要由另一位质量安全负责人签批。</p>
        <el-form-item label="签批密码"><el-input id="cd-password" v-model="ap.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ap.visible = false">取消</el-button>
        <el-button type="primary" :loading="ap.saving" @click="submitApprove">签批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="fl.visible" :title="`报告文件：${fl.row?.docNo || ''}`" width="560px">
      <AttachmentList v-if="fl.row" owner-type="COMPLIANCE_DOC" :owner-id="fl.row.id" :can-upload="auth.canAttach() && fl.row.status !== 'VOID'" title="报告文件" />
      <template #footer><el-button @click="fl.visible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="ing.visible" :title="`全成分表草稿：${ing.data?.fgName || ''}`" width="680px">
      <template v-if="ing.data">
        <el-alert type="warning" :closable="false" show-icon title="这只是草稿，不能直接用于标签。" :description="ing.data.problems.join('；')" />
        <el-table :data="ing.data.lines" size="small" max-height="300" style="margin-top:12px" scrollbar-tabindex="0">
          <el-table-column prop="itemName" label="原料" min-width="150" />
          <el-table-column prop="inci" label="INCI" min-width="170" />
          <el-table-column label="含量" width="100" align="right"><template #default="{ row }"><span class="mono">{{ row.pct != null ? `${fmtQty(row.pct)}%` : '—' }}</span></template></el-table-column>
        </el-table>
        <p class="small" style="margin-bottom:0"><b>排序：</b>{{ ing.data.text }}</p>
      </template>
      <template #footer><el-button @click="ing.visible = false">关闭</el-button></template>
    </el-dialog>

    <ImportDialog v-model="importOpen" kind="regulated" @done="load" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import ImportDialog from '../components/ImportDialog.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, startOfBusinessDay, today } from '../utils/format'
import { useLoader } from '../utils/loader'
import { textMatch, usePaged } from '../utils/table'

const STATE = {
  OK: { tag: 'success', label: '已签批' },
  PENDING: { tag: 'warning', label: '待签批' },
  STALE: { tag: 'danger', label: '要重新评估' },
  MISSING: { tag: 'info', label: '未登记' }
}
const route = useRoute()
const auth = useAuth()
const tab = ref(route.query.tab === 'regulatory' ? 'regulatory' : 'products')
const canRegister = computed(() => auth.hasAny('MASTER_DATA', 'QA', 'QSO'))
const canEditCatalog = computed(() => auth.hasAny('QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const products = ref([])
const docs = ref([])
const rules = ref([])
const compare = ref(null)
const versions = ref([])
const importOpen = ref(false)
const loader = useLoader(async () => {
  const [p, d, r, c, v] = await Promise.all([http.get('/compliance/products'), http.get('/compliance/docs'), http.get('/regulatory'), http.get('/regulatory/comparison'), http.get('/plm/formulas')])
  products.value = p
  docs.value = d
  rules.value = r
  compare.value = c
  versions.value = v
})
const load = () => loader.run()
const pt = usePaged(products, { match: textMatch('fgCode', 'fgName') })
const dt = usePaged(docs, { pageSize: 10 })
const rt = usePaged(rules, { match: textMatch('inciName', 'cnName', 'casNo', 'source', 'aliases') })
const incomplete = computed(() => products.value.filter((p) => p.versionId && !(p.safety === 'OK' && p.label === 'OK')).length)
const activeRules = computed(() => rules.value.filter((r) => r.active).length)
const withFormula = computed(() => products.value.filter((p) => p.versionId))
const startOfToday = startOfBusinessDay()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const cr = reactive({ visible: false, saving: false, fgItemId: null, docType: 'SAFETY', formulaVersionId: null, title: '', claim: '', evaluator: '', docDate: null, note: '' })
const versionOptions = computed(() => versions.value.filter((v) => v.fgItemId === cr.fgItemId && ['DRAFT', 'PENDING', 'EFFECTIVE'].includes(v.status)))
function openCreate(row) {
  Object.assign(cr, { visible: true, saving: false, fgItemId: row?.fgItemId || null, docType: 'SAFETY', formulaVersionId: null, title: '', claim: '', evaluator: '', docDate: today(), note: '' })
}
async function submitCreate() {
  if (!cr.fgItemId) return ElMessage.warning('请选择成品')
  if (!cr.title.trim() || !cr.evaluator.trim() || !cr.docDate) return ElMessage.warning('请填写标题、评估人和日期')
  if (cr.docType === 'EFFICACY' && !cr.claim.trim()) return ElMessage.warning('请写明宣称内容')
  cr.saving = true
  try {
    const d = await http.post('/compliance/docs', { fgItemId: cr.fgItemId, docType: cr.docType, formulaVersionId: cr.formulaVersionId, title: cr.title, claim: cr.claim, evaluator: cr.evaluator, docDate: cr.docDate, note: cr.note })
    ElMessage.success(`资料 ${d.docNo} 已登记，请上传报告文件`)
    cr.visible = false
    await load()
    openFiles(d)
  } finally {
    cr.saving = false
  }
}

const fl = reactive({ visible: false, row: null })
function openFiles(row) {
  Object.assign(fl, { visible: true, row })
}

const ap = reactive({ visible: false, saving: false, row: null, password: '' })
function openApprove(row) {
  Object.assign(ap, { visible: true, saving: false, row, password: '' })
}
async function submitApprove() {
  if (!ap.password) return ElMessage.warning('请输入签批密码')
  ap.saving = true
  try {
    await http.post(`/compliance/docs/${ap.row.id}/approve`, { password: ap.password })
    ElMessage.success('已签批')
    ap.visible = false
    load()
  } finally {
    ap.saving = false
  }
}

async function note(row, action, title, hint) {
  let text
  try {
    text = (await ElMessageBox.prompt(hint, `${title} ${row.docNo}`, { confirmButtonText: title, cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  await http.post(`/compliance/docs/${row.id}/${action}`, { note: text })
  ElMessage.success(`已${title}`)
  load()
}

const ing = reactive({ visible: false, data: null })
async function openIngredients(row) {
  ing.data = await http.get(`/compliance/products/${row.fgItemId}/ingredients`)
  ing.visible = true
}

async function retire(row) {
  let reason
  try {
    reason = (await ElMessageBox.prompt('停用后不再校验，记录会保留。', `停用 ${row.inciName}`, { confirmButtonText: '停用', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '请写明停用原因' })).value
  } catch {
    return
  }
  await http.post(`/regulatory/${row.id}/retire`, { reason })
  ElMessage.success('已停用')
  load()
}
</script>

<style scoped>
.block { margin-top: 14px; }
.late { color: var(--el-color-danger); }
</style>
