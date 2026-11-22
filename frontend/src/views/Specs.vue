<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>质量标准</h2>
        <p>按物料建立检验项目和限度。每次修改是一个新版本，质量安全负责人签批后生效，旧版本自动替代。有生效标准的物料，放行前要逐项录入检验结果，最近一次检验合格才能放行。导出的是全部批次的检验结果，一个项目一行。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="quality-specs" label="导出标准台账" />
        <ExportButton report="inspections" label="导出检验结果" />
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="canCreate" type="primary" @click="openCreate()">新建标准</el-button>
      </div>
    </div>

    <div v-if="summary" class="kpi-grid">
      <div class="kpi-card">
        <div class="muted small">质量标准覆盖率</div>
        <div class="kpi-val mono">{{ summary.coverageRatePct != null ? `${summary.coverageRatePct}%` : '—' }} <span class="muted small">（{{ summary.coveredItems }}/{{ summary.activeItemsTotal }}）</span></div>
      </div>
      <div class="kpi-card">
        <div class="muted small">原料 / 包材 / 成品已覆盖</div>
        <div class="kpi-val mono">{{ summary.rmCovered }}/{{ summary.rmTotal }} · {{ summary.pkCovered }}/{{ summary.pkTotal }} · {{ summary.fgCovered }}/{{ summary.fgTotal }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.pendingSpecs > 0 }">
        <div class="muted small">待签批质量标准</div>
        <div class="kpi-val mono">{{ summary.pendingSpecs }}</div>
      </div>
      <div class="kpi-card">
        <div class="muted small">近 30 天检验一次合格率</div>
        <div class="kpi-val mono">{{ summary.firstPassRate30dPct != null ? `${summary.firstPassRate30dPct}%` : '—' }} <span class="muted small">（{{ summary.inspections30dPassed }}/{{ summary.inspections30dTotal }}）</span></div>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="pg.keyword.value" placeholder="搜索编号、物料" clearable />
          <el-checkbox v-model="currentOnly">只看生效和待签批</el-checkbox>
        </div>
      </template>
      <el-table :data="pg.paged.value" v-loading="loader.loading.value" empty-text="还没有质量标准">
        <el-table-column label="编号" width="160"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row)">{{ row.specNo }}</a><div class="muted small">V{{ row.versionNo }}</div></template></el-table-column>
        <el-table-column label="物料" min-width="200"><template #default="{ row }">{{ row.itemName }}<div class="muted small"><span class="mono">{{ row.itemCode }}</span> · {{ row.itemTypeLabel }}</div></template></el-table-column>
        <el-table-column label="项目" min-width="220"><template #default="{ row }"><span class="small">{{ row.lines.map((l) => l.name).join('、') }}</span><div v-if="coaCount(row)" class="muted small">{{ coaCount(row) }} 项可采用供方报告</div></template></el-table-column>
        <el-table-column label="修改原因" min-width="160"><template #default="{ row }"><span class="small">{{ row.changeNote }}</span></template></el-table-column>
        <el-table-column label="状态" width="140"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.approvedBy" class="muted small">{{ row.approvedBy }} 签批</div></template></el-table-column>
        <el-table-column label="" width="150" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button size="small" text @click="openDetail(row)">详情</el-button>
            <el-button v-if="canCreate && row.status === 'EFFECTIVE'" size="small" text @click="openCreate(row)">修订</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListCapHint :count="specs.length" />
      <ListPager v-model:page="pg.page.value" v-model:size="pg.size.value" :total="pg.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" :title="cr.from ? `修订标准：${cr.from.itemName}` : '新建质量标准'" width="720px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="物料">
          <el-select id="qs-item" v-model="cr.itemId" filterable placeholder="选择原料、包材或成品" style="width:100%" :disabled="!!cr.from" @change="loadCurrent">
            <el-option v-for="i in itemOptions" :key="i.id" :value="i.id" :label="`${i.name}（${i.code}）· ${TYPE[i.itemType]}`" />
          </el-select>
        </el-form-item>
        <el-alert v-if="cr.current" type="info" :closable="false" show-icon :title="`已带入现行 V${cr.current.versionNo} 的项目，改完提交后生成 V${cr.current.versionNo + 1}，签批前现行版本照常使用。`" />
        <el-form-item label="制定或修改的原因" style="margin-top:12px"><el-input id="qs-note" v-model="cr.changeNote" maxlength="300" placeholder="如：首版；供应商变更后加严含量" /></el-form-item>
        <el-form-item label="取样要求（选填）">
          <el-input id="qs-sampling" v-model="cr.samplingPlan" maxlength="300" placeholder="如：每批随机取 3 件，每件取 50 g" />
          <div class="muted small field-hint">填了以后，检验员录入自检结果时要写取样情况。系统不内置抽样方案。</div>
        </el-form-item>
        <div class="sub-head"><b>检测项目</b><el-button size="small" text @click="cr.lines.push(blankLine())">加一个项目</el-button></div>
        <p class="muted small sub-note">填了上下限的由系统判定合格与否；没填的由检验员判定。勾了“可采用供方报告”的项目可以不自检，照供应商或受托厂的检验报告录入，仍按限度判定；没勾的每批都要自检。系统不内置任何标准值，按贵司标准填写。</p>
        <p v-if="cr.loadingCurrent" class="muted small">正在读取这个物料的现行标准…</p>
        <div v-for="(l, i) in cr.lines" v-else :key="i" class="row-inputs line-row">
          <el-input v-model="l.name" maxlength="60" placeholder="项目" :aria-label="`第 ${i + 1} 个项目的名称`" />
          <el-input v-model="l.method" maxlength="100" placeholder="方法（选填）" :aria-label="`第 ${i + 1} 个项目的方法`" />
          <el-input v-model="l.unit" maxlength="20" placeholder="单位" :aria-label="`第 ${i + 1} 个项目的单位`" />
          <el-input-number v-model="l.minValue" :controls="false" placeholder="下限" :aria-label="`第 ${i + 1} 个项目的下限`" />
          <el-input-number v-model="l.maxValue" :controls="false" placeholder="上限" :aria-label="`第 ${i + 1} 个项目的上限`" />
          <el-input v-model="l.criteria" maxlength="200" placeholder="判定标准（选填）" :aria-label="`第 ${i + 1} 个项目的判定标准`" />
          <el-checkbox v-model="l.coaAllowed" :aria-label="`第 ${i + 1} 个项目可采用供方报告`">可采用供方报告</el-checkbox>
          <el-button size="small" text :disabled="cr.lines.length <= 1" @click="cr.lines.splice(i, 1)">删除</el-button>
        </div>
        <el-form-item v-if="anyCoa && !cr.loadingCurrent" label="连续采用供方报告最多几批（选填）" style="margin-top:8px">
          <el-input-number id="qs-streak" v-model="cr.coaMaxStreak" :min="1" :max="50" :precision="0" :controls="false" placeholder="不限" />
          <div class="muted small field-hint">填了以后，每家供方的首批要全项自检合格才能采用它的报告；连续采用到这个批数后，下一批要全项自检。不填就不限制。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" :disabled="cr.loadingCurrent" @click="submitCreate">提交签批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dt.visible" :title="`质量标准 ${dt.row?.specNo || ''}`" width="760px">
      <template v-if="dt.row">
        <p class="summary-line"><StatusTag :status="dt.row.status" :label="dt.row.statusLabel" /> {{ dt.row.itemName }} <span class="mono muted small">{{ dt.row.itemCode }}</span> · V{{ dt.row.versionNo }} · {{ dt.row.changeNote }}</p>
        <el-alert v-if="dt.row.rejectNote" type="warning" :closable="false" show-icon :title="`退回原因：${dt.row.rejectNote}`" />
        <p v-if="dt.row.samplingPlan" class="summary-line small"><b>取样要求</b>　{{ dt.row.samplingPlan }}</p>
        <p v-if="coaCount(dt.row)" class="summary-line small"><b>采用供方报告</b>　{{ dt.row.coaMaxStreak ? `每家供方首批全项自检合格后才能采用，最多连续 ${dt.row.coaMaxStreak} 批，之后要全项自检一批` : '不限连续批数' }}；供方要在合格名录内，要有报告编号和报告扫描件。</p>
        <el-table :data="dt.row.lines" size="small" style="margin-top:10px" scrollbar-tabindex="0">
          <el-table-column prop="name" label="项目" min-width="120" />
          <el-table-column label="方法" min-width="120"><template #default="{ row }">{{ row.method || '—' }}</template></el-table-column>
          <el-table-column label="限度" min-width="140"><template #default="{ row }">{{ row.limitText || '—' }}</template></el-table-column>
          <el-table-column label="判定标准" min-width="160"><template #default="{ row }">{{ row.criteria || '—' }}</template></el-table-column>
          <el-table-column label="判定" width="80"><template #default="{ row }">{{ row.judgedBySystem ? '系统' : '检验员' }}</template></el-table-column>
          <el-table-column label="检验方式" width="150"><template #default="{ row }">{{ row.coaAllowed ? '可采用供方报告' : '每批自检' }}</template></el-table-column>
        </el-table>
        <p class="muted small">{{ dt.row.createdBy }} 提交于 {{ fmtTime(dt.row.createdAt) }}<template v-if="dt.row.approvedBy">，{{ dt.row.approvedBy }} 签批于 {{ fmtTime(dt.row.approvedAt) }}</template></p>
        <AttachmentList owner-type="QUALITY_SPEC" :owner-id="dt.row.id" :can-upload="auth.canAttach()" title="标准文件" />
        <template v-if="isQso && dt.row.status === 'PENDING'">
          <p v-if="dt.row.createdBy === auth.user?.username" class="late small">这个版本是您提交的，要由另一位质量安全负责人签批。</p>
          <el-form label-position="top" style="margin-top:8px" @submit.prevent>
            <el-form-item label="签批密码"><el-input id="qs-password" v-model="dt.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="approve" /></el-form-item>
          </el-form>
        </template>
      </template>
      <template #footer>
        <template v-if="dt.row && isQso && dt.row.status === 'PENDING'">
          <el-button text @click="reject">退回</el-button>
          <el-button type="primary" :loading="dt.saving" @click="approve">签批生效</el-button>
        </template>
        <el-button @click="dt.visible = false">关闭</el-button>
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
import { fmtTime } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const TYPE = { RM: '原料', PK: '包材', FG: '成品' }
const auth = useAuth()
const rd = useRefData()
const canCreate = computed(() => auth.hasAny('MASTER_DATA', 'QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const specs = ref([])
const summary = ref(null)
const currentOnly = ref(true)
const loader = useLoader(async () => {
  const [sp, sum] = await Promise.all([http.get('/quality/specs'), http.get('/quality/specs/summary')])
  specs.value = sp
  summary.value = sum
})
const load = () => loader.run()
const shown = computed(() => currentOnly.value ? specs.value.filter((s) => s.status === 'EFFECTIVE' || s.status === 'PENDING') : specs.value)
const pg = usePaged(shown, { match: textMatch('specNo', 'itemName', 'itemCode') })
const itemOptions = computed(() => rd.s.items.filter((i) => i.active))
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { load(); rd.load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const blankLine = () => ({ name: '', method: '', unit: '', minValue: undefined, maxValue: undefined, criteria: '', coaAllowed: false })
const copyLine = (l) => ({ name: l.name, method: l.method || '', unit: l.unit || '', minValue: l.minValue != null ? Number(l.minValue) : undefined, maxValue: l.maxValue != null ? Number(l.maxValue) : undefined, criteria: l.criteria || '', coaAllowed: !!l.coaAllowed })
const coaCount = (spec) => spec.lines.filter((l) => l.coaAllowed).length

const cr = reactive({ visible: false, saving: false, loadingCurrent: false, from: null, current: null, itemId: null, changeNote: '', samplingPlan: '', coaMaxStreak: undefined, lines: [] })
const anyCoa = computed(() => cr.lines.some((l) => l.coaAllowed))
const currentReq = latestOnly()
async function openCreate(from) {
  Object.assign(cr, { visible: true, saving: false, loadingCurrent: false, from: from || null, current: null, itemId: from?.itemId || null, changeNote: '', samplingPlan: '', coaMaxStreak: undefined, lines: [blankLine()] })
  await rd.ready()
  if (from) loadCurrent()
}
// 选了已有标准的物料，带入现行版本的项目再改。读取期间不显示项目输入，否则读完会盖掉刚填的内容；
// 没有现行标准时保留已填的项目
async function loadCurrent() {
  const current = currentReq()
  cr.current = null
  if (!cr.itemId) return
  cr.loadingCurrent = true
  try {
    const spec = await http.get(`/quality/items/${cr.itemId}/spec`)
    if (!current()) return
    cr.current = spec || null
    if (spec) Object.assign(cr, { lines: spec.lines.map(copyLine), samplingPlan: spec.samplingPlan || '', coaMaxStreak: spec.coaMaxStreak ?? undefined })
  } finally {
    if (current()) cr.loadingCurrent = false
  }
}
async function submitCreate() {
  if (!cr.itemId) return ElMessage.warning('请选择物料')
  if (!cr.changeNote.trim()) return ElMessage.warning('请写明制定或修改的原因')
  if (cr.lines.some((l) => !l.name.trim())) return ElMessage.warning('请填写检测项目的名称，不用的项目请删除')
  cr.saving = true
  try {
    const lines = cr.lines.map((l) => ({ ...l, minValue: l.minValue ?? null, maxValue: l.maxValue ?? null }))
    // 取消了所有“可采用供方报告”以后，之前填的连续批数不再提交
    const s = await http.post('/quality/specs', { itemId: cr.itemId, changeNote: cr.changeNote, lines, samplingPlan: cr.samplingPlan, coaMaxStreak: anyCoa.value ? cr.coaMaxStreak ?? null : null })
    ElMessage.success(`标准 ${s.specNo} 已提交，等待签批`)
    cr.visible = false
    await load()
    openDetail(s)
  } finally {
    cr.saving = false
  }
}

const dt = reactive({ visible: false, saving: false, row: null, password: '' })
function openDetail(row) {
  Object.assign(dt, { visible: true, saving: false, row, password: '' })
}
async function approve() {
  if (!dt.password) return ElMessage.warning('请输入签批密码')
  dt.saving = true
  try {
    dt.row = await http.post(`/quality/specs/${dt.row.id}/approve`, { password: dt.password })
    dt.password = ''
    ElMessage.success('已签批，标准生效')
    load()
  } finally {
    dt.saving = false
  }
}
async function reject() {
  let note
  try {
    note = (await ElMessageBox.prompt('写明退回原因，提交人改好后重新提交。', `退回 ${dt.row.specNo}`, { confirmButtonText: '退回', cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  dt.row = await http.post(`/quality/specs/${dt.row.id}/reject`, { note })
  ElMessage.success('已退回')
  load()
}
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
.kpi-card { padding: 14px 16px; border-radius: 14px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); display: grid; gap: 4px; }
.kpi-card.warn { border-color: var(--el-color-danger-light-5); }
.kpi-val { font-size: 18px; font-weight: 600; }
.late { color: var(--el-color-danger); }
.summary-line { margin: 0 0 10px; line-height: 1.8; }
/* 表单项里的说明文字：不跟随输入框的行高，换行后不会拉得很开 */
.field-hint { flex: 1 1 100%; line-height: 1.5; margin-top: 4px; }
.sub-head { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; }
.sub-note { margin: 2px 0 8px; }
.row-inputs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 8px; }
.line-row > :nth-child(1) { flex: 1 1 100px; }
.line-row > :nth-child(2) { flex: 1 1 100px; }
.line-row > :nth-child(3) { flex: 0 1 70px; }
.line-row > :nth-child(4), .line-row > :nth-child(5) { flex: 0 1 80px; }
.line-row > :nth-child(6) { flex: 2 1 140px; }
.line-row > :nth-child(7) { flex: 0 0 auto; margin-right: 0; }
</style>
