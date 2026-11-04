<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>配方与备案</h2>
        <p>配方修改走版本：发起变更 → 提交 → 质量安全负责人签批后生效。原料有变化时，要先完成这个版本的备案；只换包材不需要。还没发料的生产单改用新版本，已发料的保留当时的版本。</p>
      </div>
      <div class="toolbar">
        <el-button v-if="tab === 'filings' && canFile" type="primary" @click="openFiling()">登记备案</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" class="seg">
      <!-- ================= 配方版本 ================= -->
      <el-tab-pane :label="`配方版本（${pendingCount} 个待签批）`" name="versions">
        <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
        <div v-else v-loading="loader.loading.value" class="products">
          <el-empty v-if="!products.length" description="还没有录入配方：先到主数据的 BOM 录入首版" :image-size="60" />
          <el-card v-for="p in products" :key="p.fgItemId" shadow="never">
            <template #header>
              <div class="ph">
                <span><b>{{ p.fgName }}</b> <span class="mono muted small">{{ p.fgCode }}</span></span>
                <el-button v-if="canEdit && !p.inProgress" size="small" @click="openDraft(p)">发起配方变更</el-button>
              </div>
            </template>
            <div v-for="v in p.versions" :key="v.id" class="ver" :class="v.status">
              <div class="vh">
                <span class="vno mono">V{{ v.versionNo }}</span>
                <el-tag :type="STATUS[v.status]" size="small" disable-transitions>{{ v.statusLabel }}</el-tag>
                <span class="grow">{{ v.changeNote }}</span>
                <span class="muted small">{{ v.createdBy }} · {{ fmtTime(v.createdAt) }}</span>
              </div>
              <el-alert v-if="v.rejectNote" type="warning" :closable="false" :title="`被退回：${v.rejectNote}`" style="margin:6px 0" />

              <!-- 进行中的版本：显示和生效版本的差异 -->
              <template v-if="v.status === 'DRAFT' || v.status === 'PENDING'">
                <el-table v-if="v.changes.length" :data="v.changes" size="small" class="changes">
                  <el-table-column label="变化" width="120"><template #default="{ row }">{{ row.kindLabel }}</template></el-table-column>
                  <el-table-column label="物料" min-width="180"><template #default="{ row }">{{ row.itemName }} <span class="mono muted small">{{ row.itemCode }}</span></template></el-table-column>
                  <el-table-column label="单耗" min-width="170"><template #default="{ row }"><span class="mono">{{ qty(row.oldQty) }} → {{ qty(row.newQty) }} {{ row.unit }}</span></template></el-table-column>
                  <el-table-column label="涉及配方" width="90"><template #default="{ row }"><el-tag v-if="row.affectsFormula" type="warning" size="small" disable-transitions>是</el-tag><span v-else class="muted">否</span></template></el-table-column>
                </el-table>
                <p v-else class="muted small">和生效版本没有区别。</p>
                <div v-if="v.needsFiling" class="filing small">
                  <template v-if="v.filingNo"><el-icon class="ok"><CircleCheckFilled /></el-icon> 备案已完成：<span class="mono">{{ v.filingNo }}</span></template>
                  <template v-else><el-icon class="warn"><WarningFilled /></el-icon> 改了原料，需要先完成这个版本的备案才能生效。
                    <a v-if="canFile" href="#" @click.prevent="openFiling(v)">登记备案</a></template>
                </div>
                <div class="toolbar actions">
                  <template v-if="v.status === 'DRAFT' && canEdit">
                    <el-button size="small" @click="openDraft(p, v)">修改</el-button>
                    <el-button size="small" type="primary" @click="submit(v)">提交签批</el-button>
                    <el-button size="small" text @click="discard(v)">作废</el-button>
                  </template>
                  <template v-if="v.status === 'PENDING' && isQso">
                    <el-button size="small" type="primary" @click="openSign(v, 'approve')">签批生效</el-button>
                    <el-button size="small" @click="openSign(v, 'reject')">退回</el-button>
                  </template>
                  <span v-if="v.status === 'PENDING' && !isQso" class="muted small">等待质量安全负责人签批</span>
                </div>
              </template>

              <el-collapse v-else class="lines">
                <el-collapse-item :title="`配方明细（${v.lines.length} 行）· 批准人 ${v.approvedBy || '—'}`">
                  <div v-for="l in v.lines" :key="l.itemId" class="line small"><span>{{ l.itemName }} <span class="mono muted">{{ l.itemCode }}</span></span><span class="mono">{{ qty(l.qtyPer) }} {{ l.unit }}</span></div>
                </el-collapse-item>
              </el-collapse>
            </div>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- ================= 备案 ================= -->
      <el-tab-pane label="备案登记" name="filings">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header><el-input v-model="ft.keyword.value" placeholder="搜索产品、编号" clearable style="width:240px" /></template>
          <el-table :data="ft.paged.value" empty-text="还没有备案记录">
            <el-table-column label="产品" min-width="180"><template #default="{ row }">{{ row.fgName }}<div class="muted small mono">{{ row.fgCode }}<template v-if="row.versionNo"> · 配方 V{{ row.versionNo }}</template></div></template></el-table-column>
            <el-table-column prop="filingTypeLabel" label="类型" width="150" />
            <el-table-column label="编号" min-width="170"><template #default="{ row }"><span class="mono">{{ row.filingNo || '—' }}</span></template></el-table-column>
            <el-table-column label="提交 / 完成" width="130"><template #default="{ row }"><span class="mono small">{{ row.submittedDate || '—' }}</span><div class="mono small muted">{{ row.filedDate || '—' }}</div></template></el-table-column>
            <el-table-column label="有效期" width="130"><template #default="{ row }"><span class="mono" :class="{ bad: row.expiryDaysLeft != null && row.expiryDaysLeft < 0 }">{{ row.expiryDate || '—' }}</span><div v-if="row.expiryDaysLeft != null && row.expiryDaysLeft < 0" class="bad small">已到期</div></template></el-table-column>
            <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="FILING_TAG[row.status]" size="small" disable-transitions>{{ row.statusLabel }}</el-tag></template></el-table-column>
            <el-table-column v-if="canFile" label="" width="170" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'SUBMITTED'">
                  <el-button size="small" type="primary" @click="openComplete(row)">登记完成</el-button>
                  <el-button size="small" text @click="withdraw(row)">撤回</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <ListPager v-model:page="ft.page.value" v-model:size="ft.size.value" :total="ft.total.value" />
        </el-card>
      </el-tab-pane>

      <!-- ================= 工艺路线 ================= -->
      <el-tab-pane label="工艺路线" name="routing">
        <el-card shadow="never">
          <template #header>
            <div class="ph">
              <el-select id="rt-fg" v-model="rt.fgItemId" placeholder="选择成品" filterable style="width:min(320px, 100%)" @change="loadRouting">
                <el-option v-for="f in rd.finished.value" :key="f.id" :value="f.id" :label="`${f.code} ${f.name}`" />
              </el-select>
              <span class="muted small">自产工单发料时按这里生成批生产记录；之后修改不影响已发料的单子。委托生产不用这张表。</span>
            </div>
          </template>
          <el-empty v-if="!rt.fgItemId" description="先选择一个成品" :image-size="60" />
          <template v-else>
            <div v-for="(s, i) in rt.steps" :key="i" class="step">
              <div class="sno mono">{{ i + 1 }}</div>
              <div class="sbody">
                <div class="srow">
                  <el-input v-model="s.name" :disabled="!canRoute" placeholder="工序名称" style="flex:1 1 180px" maxlength="100" />
                  <el-checkbox v-model="s.critical" :disabled="!canRoute">关键工序（须他人复核）</el-checkbox>
                  <el-checkbox v-model="s.requiresEquipment" :disabled="!canRoute">须记录设备</el-checkbox>
                </div>
                <el-input v-model="s.instruction" :disabled="!canRoute" placeholder="操作要求（选填）" maxlength="500" />
                <div class="srow">
                  <el-input v-model="s.ipcName" :disabled="!canRoute" placeholder="过程检验项目（选填）" style="flex:1 1 160px" maxlength="60" />
                  <el-input-number v-model="s.ipcMin" :disabled="!canRoute" :controls="false" placeholder="下限" style="width:90px" />
                  <el-input-number v-model="s.ipcMax" :disabled="!canRoute" :controls="false" placeholder="上限" style="width:90px" />
                  <el-input v-model="s.ipcUnit" :disabled="!canRoute" placeholder="单位" style="width:70px" maxlength="20" />
                </div>
              </div>
              <div v-if="canRoute" class="sops">
                <el-button text size="small" :disabled="i === 0" aria-label="上移" @click="move(i, -1)">↑</el-button>
                <el-button text size="small" :disabled="i === rt.steps.length - 1" aria-label="下移" @click="move(i, 1)">↓</el-button>
                <el-button text size="small" @click="rt.steps.splice(i, 1)">删除</el-button>
              </div>
            </div>
            <el-empty v-if="!rt.steps.length" description="还没有工序，这个成品发料时不会生成批生产记录" :image-size="60" />
            <div v-if="canRoute" class="toolbar actions">
              <el-button size="small" @click="rt.steps.push(emptyStep())">添加工序</el-button>
              <el-button size="small" type="primary" :loading="rt.saving" :disabled="!rt.steps.length" @click="saveRouting">保存工艺路线</el-button>
            </div>
          </template>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 配方草稿 -->
    <el-dialog v-model="dr.visible" :title="dr.id ? `修改配方变更：${dr.fgName}` : `发起配方变更：${dr.fgName}`" width="780px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="变更内容和原因"><el-input id="dr-note" v-model="dr.changeNote" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
        <div v-for="(l, i) in dr.lines" :key="i" class="bom-row">
          <el-select v-model="l.itemId" placeholder="选择物料" filterable @change="check">
            <el-option v-for="m in rd.materials.value" :key="m.id" :value="m.id" :label="`${m.code} ${m.name}${m.banned ? '（禁用）' : ''}`" />
          </el-select>
          <el-input-number v-model="l.qtyPer" :min="0" :precision="6" :controls="false" placeholder="每件单耗" @change="check" />
          <el-select v-model="l.supplyMode"><el-option value="BRAND" label="品牌供料" /><el-option value="OEM" label="工厂代采" /></el-select>
          <el-button text :disabled="dr.lines.length === 1" @click="dr.lines.splice(i, 1); check()">删除</el-button>
        </div>
        <el-button size="small" @click="dr.lines.push({ itemId: null, qtyPer: null, supplyMode: 'BRAND' })">添加一行</el-button>
        <div class="checks">
          <div v-for="c in dr.problems" :key="c.itemId" class="small bad">{{ c.itemName }}：{{ c.checkMessage }}</div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dr.visible = false">取消</el-button>
        <el-button type="primary" :loading="dr.saving" :disabled="dr.problems.length > 0" @click="saveDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 签批 / 退回 -->
    <el-dialog v-model="sg.visible" :title="sg.mode === 'approve' ? `签批生效：V${sg.v?.versionNo}` : `退回：V${sg.v?.versionNo}`" width="480px">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">{{ sg.v?.fgName }}：{{ sg.v?.changeNote }}</p>
        <el-form-item v-if="sg.mode === 'reject'" label="退回原因"><el-input id="sg-reason" v-model="sg.reason" type="textarea" :rows="2" maxlength="300" /></el-form-item>
        <el-form-item label="签批密码"><el-input id="sg-pwd" v-model="sg.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sg.visible = false">取消</el-button>
        <el-button type="primary" :loading="sg.saving" @click="sign">{{ sg.mode === 'approve' ? '签批生效' : '确认退回' }}</el-button>
      </template>
    </el-dialog>

    <!-- 登记备案 -->
    <el-dialog v-model="fl.visible" title="登记备案（已提交）" width="520px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="成品">
          <el-select v-model="fl.fgItemId" filterable style="width:100%" :disabled="!!fl.lockVersion" @change="fl.formulaVersionId = null">
            <el-option v-for="f in rd.finished.value" :key="f.id" :value="f.id" :label="`${f.code} ${f.name}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="对应配方版本（配方变更的备案必须选）">
          <el-select v-model="fl.formulaVersionId" clearable style="width:100%" :disabled="!!fl.lockVersion">
            <el-option v-for="v in versionsOf(fl.fgItemId)" :key="v.id" :value="v.id" :label="`V${v.versionNo}（${v.statusLabel}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型"><el-select v-model="fl.filingType" style="width:100%"><el-option value="GENERAL" label="普通化妆品备案" /><el-option value="SPECIAL" label="特殊化妆品注册" /></el-select></el-form-item>
        <el-form-item label="提交日期"><el-date-picker v-model="fl.submittedDate" value-format="YYYY-MM-DD" :clearable="false" style="width:100%" /></el-form-item>
        <el-form-item label="备注（选填）"><el-input v-model="fl.note" maxlength="300" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fl.visible = false">取消</el-button>
        <el-button type="primary" :loading="fl.saving" @click="saveFiling">登记备案</el-button>
      </template>
    </el-dialog>

    <!-- 备案完成 -->
    <el-dialog v-model="fc.visible" :title="`备案完成：${fc.row?.fgName || ''}`" width="480px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="备案 / 注册编号"><el-input id="fc-no" v-model="fc.filingNo" class="mono" maxlength="60" /></el-form-item>
        <el-form-item label="完成日期"><el-date-picker v-model="fc.filedDate" value-format="YYYY-MM-DD" :clearable="false" style="width:100%" /></el-form-item>
        <el-form-item :label="fc.row?.filingType === 'SPECIAL' ? '注册证有效期（到期后不能上市放行）' : '有效期（普通备案不用填）'"><el-date-picker v-model="fc.expiryDate" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <p class="muted small" style="margin:0">编号会同步到物料的备案编号。</p>
      </el-form>
      <template #footer>
        <el-button @click="fc.visible = false">取消</el-button>
        <el-button type="primary" :loading="fc.saving" @click="completeFiling">登记完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue'
import http from '../api'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime, today } from '../utils/format'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const STATUS = { DRAFT: 'info', PENDING: 'warning', EFFECTIVE: 'success', RETIRED: 'info', DISCARDED: 'info' }
const FILING_TAG = { SUBMITTED: 'warning', FILED: 'success', WITHDRAWN: 'info' }

const auth = useAuth()
const route = useRoute()
const rd = useRefData()
const tab = ref(['filings', 'routing'].includes(route.query.tab) ? route.query.tab : 'versions')
const canEdit = computed(() => auth.hasAny('ADMIN', 'MASTER_DATA'))
const canFile = computed(() => auth.hasAny('ADMIN', 'MASTER_DATA', 'QA', 'QSO'))
const canRoute = computed(() => auth.hasAny('ADMIN', 'MASTER_DATA', 'PRODUCTION'))
const isQso = computed(() => auth.hasAny('QSO'))
const qty = (v) => (v == null ? '—' : fmtQty(v, 6))

const versions = ref([])
const filings = ref([])
const ft = usePaged(filings, { match: textMatch('fgName', 'fgCode', 'filingNo') })
const pendingCount = computed(() => versions.value.filter((v) => v.status === 'PENDING').length)
const versionsOf = (fgId) => versions.value.filter((v) => v.fgItemId === fgId && v.status !== 'DISCARDED')

// 按成品分组，版本从新到旧；已作废的不显示
const products = computed(() => {
  const map = new Map()
  for (const v of versions.value) {
    if (v.status === 'DISCARDED') continue
    if (!map.has(v.fgItemId)) map.set(v.fgItemId, { fgItemId: v.fgItemId, fgName: v.fgName, fgCode: v.fgCode, versions: [], inProgress: false })
    const p = map.get(v.fgItemId)
    p.versions.push(v)
    if (v.status === 'DRAFT' || v.status === 'PENDING') p.inProgress = true
  }
  const list = [...map.values()]
  list.forEach((p) => p.versions.sort((a, b) => b.versionNo - a.versionNo))
  return list.sort((a, b) => a.fgCode.localeCompare(b.fgCode))
})

const loader = useLoader(async () => {
  const [a, b] = await Promise.all([http.get('/plm/formulas'), http.get('/plm/filings')])
  versions.value = a
  filings.value = b
})
const load = () => loader.run()

// ---------- 配方草稿 ----------
const dr = reactive({ visible: false, saving: false, id: null, fgItemId: null, fgName: '', changeNote: '', lines: [], problems: [] })
function openDraft(p, v) {
  const source = v || p.versions.find((x) => x.status === 'EFFECTIVE')
  Object.assign(dr, {
    visible: true, id: v?.id ?? null, fgItemId: p.fgItemId, fgName: p.fgName, changeNote: v?.changeNote ?? '', problems: [],
    lines: (source?.lines || []).map((l) => ({ itemId: l.itemId, qtyPer: Number(l.qtyPer), supplyMode: l.supplyMode }))
  })
  check()
}
const draftLines = () => dr.lines.filter((l) => l.itemId && l.qtyPer > 0)
let seq = 0
async function check() {
  const lines = draftLines()
  if (!lines.length) {
    dr.problems = []
    return
  }
  const mine = ++seq
  try {
    const res = await http.post(`/boms/${dr.fgItemId}/check`, { lines }, { silent: true })
    if (mine === seq) dr.problems = res.filter((c) => c.checkLevel === 'BANNED' || c.checkLevel === 'OVER_LIMIT')
  } catch {
    if (mine === seq) dr.problems = []
  }
}
async function saveDraft() {
  const body = { changeNote: dr.changeNote, lines: draftLines() }
  if (!body.lines.length) return ElMessage.warning('至少填写一行物料')
  dr.saving = true
  try {
    if (dr.id) await http.put(`/plm/versions/${dr.id}`, body)
    else await http.post(`/plm/formulas/${dr.fgItemId}/draft`, body)
    ElMessage.success('草稿已保存，确认后提交签批')
    dr.visible = false
    load()
  } finally {
    dr.saving = false
  }
}
async function submit(v) {
  await http.post(`/plm/versions/${v.id}/submit`)
  ElMessage.success('已提交，等待质量安全负责人签批')
  load()
}
async function discard(v) {
  try {
    await ElMessageBox.confirm(`作废 ${v.fgName} 的 V${v.versionNo}？版本号不会复用，记录会保留。`, '作废配方变更', { confirmButtonText: '作废', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  await http.post(`/plm/versions/${v.id}/discard`)
  ElMessage.success('已作废')
  load()
}

// ---------- 签批 ----------
const sg = reactive({ visible: false, saving: false, v: null, mode: 'approve', reason: '', password: '' })
const openSign = (v, mode) => Object.assign(sg, { visible: true, v, mode, reason: '', password: '' })
async function sign() {
  sg.saving = true
  try {
    if (sg.mode === 'approve') await http.post(`/plm/versions/${sg.v.id}/approve`, { password: sg.password })
    else await http.post(`/plm/versions/${sg.v.id}/reject`, { reason: sg.reason, password: sg.password })
    ElMessage.success(sg.mode === 'approve' ? `V${sg.v.versionNo} 已生效` : '已退回')
    sg.visible = false
    load()
  } finally {
    sg.saving = false
  }
}

// ---------- 备案 ----------
const fl = reactive({ visible: false, saving: false, lockVersion: false, fgItemId: null, formulaVersionId: null, filingType: 'GENERAL', submittedDate: '', note: '' })
function openFiling(v) {
  Object.assign(fl, { visible: true, lockVersion: !!v, fgItemId: v?.fgItemId ?? null, formulaVersionId: v?.id ?? null, filingType: 'GENERAL', submittedDate: today(), note: '' })
}
async function saveFiling() {
  fl.saving = true
  try {
    await http.post('/plm/filings', { fgItemId: fl.fgItemId, formulaVersionId: fl.formulaVersionId, filingType: fl.filingType, submittedDate: fl.submittedDate, note: fl.note })
    ElMessage.success('备案已登记')
    fl.visible = false
    tab.value = 'filings'
    load()
  } finally {
    fl.saving = false
  }
}
const fc = reactive({ visible: false, saving: false, row: null, filingNo: '', filedDate: '', expiryDate: '' })
const openComplete = (row) => Object.assign(fc, { visible: true, row, filingNo: '', filedDate: today(), expiryDate: '' })
async function completeFiling() {
  fc.saving = true
  try {
    await http.post(`/plm/filings/${fc.row.id}/complete`, { filingNo: fc.filingNo, filedDate: fc.filedDate, expiryDate: fc.expiryDate || null })
    ElMessage.success('备案已完成')
    fc.visible = false
    load()
  } finally {
    fc.saving = false
  }
}
async function withdraw(row) {
  let reason
  try {
    reason = (await ElMessageBox.prompt('写明撤回原因。', `撤回 ${row.fgName} 的备案`, { confirmButtonText: '撤回', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '请填写撤回原因' })).value
  } catch {
    return
  }
  await http.post(`/plm/filings/${row.id}/withdraw`, { reason })
  ElMessage.success('已撤回')
  load()
}

// ---------- 工艺路线 ----------
const emptyStep = () => ({ name: '', instruction: '', critical: false, ipcName: '', ipcMin: undefined, ipcMax: undefined, ipcUnit: '', requiresEquipment: false })
const rt = reactive({ fgItemId: null, steps: [], saving: false })
async function loadRouting() {
  const list = await http.get(`/mes/routings/${rt.fgItemId}`)
  rt.steps = list.map((s) => ({ ...s, ipcMin: s.ipcMin ?? undefined, ipcMax: s.ipcMax ?? undefined, ipcName: s.ipcName || '', ipcUnit: s.ipcUnit || '', instruction: s.instruction || '' }))
}
function move(i, d) {
  const [s] = rt.steps.splice(i, 1)
  rt.steps.splice(i + d, 0, s)
}
async function saveRouting() {
  rt.saving = true
  try {
    await http.put(`/mes/routings/${rt.fgItemId}`, { steps: rt.steps.map(({ name, instruction, critical, ipcName, ipcMin, ipcMax, ipcUnit, requiresEquipment }) => ({ name, instruction, critical, ipcName, ipcMin: ipcMin ?? null, ipcMax: ipcMax ?? null, ipcUnit, requiresEquipment })) })
    ElMessage.success('工艺路线已保存，之后发料的工单按新路线')
    loadRouting()
  } finally {
    rt.saving = false
  }
}

onMounted(() => {
  load()
  rd.load()
})
</script>

<style scoped>
.products { display: grid; gap: 14px; min-height: 80px; }
.ph { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; }
.ver { padding: 10px 0; border-top: 1px solid var(--lg-stroke); }
.ver:first-child { border-top: 0; padding-top: 0; }
.ver.RETIRED { opacity: .65; }
.vh { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.vno { font-weight: 700; }
.grow { flex: 1 1 200px; min-width: 0; }
.changes { margin: 8px 0; }
.filing { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin: 6px 0; }
.actions { margin-top: 8px; }
.lines { margin-top: 6px; border: 0; }
.line { display: flex; justify-content: space-between; gap: 12px; padding: 2px 0; }
.ok { color: var(--el-color-success); }
.warn { color: var(--el-color-warning); }
.bad { color: var(--el-color-danger); }
.bom-row { display: grid; grid-template-columns: minmax(0, 1fr) 130px 110px auto; gap: 8px; margin-bottom: 8px; }
.checks { margin-top: 8px; display: grid; gap: 4px; }
.step { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; gap: 10px; padding: 10px 0; border-top: 1px solid var(--lg-stroke); }
.step:first-child { border-top: 0; padding-top: 0; }
.sno { width: 26px; height: 26px; border-radius: 50%; display: grid; place-items: center; background: var(--lg-fill); font-size: 12px; }
.sbody { display: grid; gap: 6px; min-width: 0; }
.srow { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.sops { display: flex; flex-direction: column; align-items: flex-end; }
.sops .el-button + .el-button { margin-left: 0; }
@media (max-width: 640px) {
  .bom-row { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
  .step { grid-template-columns: 28px minmax(0, 1fr); }
  .sops { grid-column: 2; flex-direction: row; justify-content: flex-end; }
}
</style>
