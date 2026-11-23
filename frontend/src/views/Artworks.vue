<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>标签设计稿</h2>
        <p>印刷包材的设计稿按包材分版本，对应提交时的生效配方。质量安全负责人对照成分表逐项评审、上传设计稿后才能批准（不能是提交人）；配方变了，已批准的设计稿要重新评审。</p>
      </div>
      <div class="toolbar">
        <ExportButton :report="activeTab === 'compliance' ? 'artwork-compliance' : 'artworks'" :label="activeTab === 'compliance' ? '导出合规清单' : '导出台账'" />
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">提交设计稿</el-button>
      </div>
    </div>

    <div v-if="summary" class="kpi-grid">
      <div class="kpi-card">
        <div class="muted small">现行已批准设计稿</div>
        <div class="kpi-val mono">{{ summary.approvedCount }} <span class="muted small">/ 共 {{ summary.totalArtworks }} 版</span></div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.pendingCount > 0 }">
        <div class="muted small">设计稿待签批</div>
        <div class="kpi-val mono">{{ summary.pendingCount }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.staleCount > 0 }">
        <div class="muted small">配方变更失效待重评</div>
        <div class="kpi-val mono">{{ summary.staleCount }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.pendingProofCount > 0 }">
        <div class="muted small">包材打样待签样</div>
        <div class="kpi-val mono">{{ summary.pendingProofCount }}</div>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="seg">
      <el-tab-pane label="设计稿台账" name="list" />
      <el-tab-pane :label="`合规闭环监控（${compBoard?.totalCount ?? 0}）`" name="compliance" />
    </el-tabs>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-card v-else-if="activeTab === 'compliance'" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="compFilter" size="small">
            <el-radio-button value="ALL">全部 ({{ compBoard?.totalCount ?? 0 }})</el-radio-button>
            <el-radio-button value="STALE">配方失效 ({{ compBoard?.staleArtworkCount ?? 0 }})</el-radio-button>
            <el-radio-button value="OVERDUE">超期滞留 ({{ compBoard?.overdueCount ?? 0 }})</el-radio-button>
            <el-radio-button value="PENDING_ARTWORK">设计稿待评 ({{ compBoard?.pendingArtworkCount ?? 0 }})</el-radio-button>
            <el-radio-button value="PENDING_PROOF">打样待签 ({{ compBoard?.pendingProofCount ?? 0 }})</el-radio-button>
          </el-radio-group>
        </div>
        <div v-if="compBoard" class="muted small" style="margin-top:8px">
          待闭环标签与打样事项 <b class="mono">{{ compBoard.totalCount }}</b> 项 · 配方失效阻断放行 <b class="mono" :class="{ late: compBoard.staleArtworkCount > 0 }">{{ compBoard.staleArtworkCount }}</b> · 超期滞留 <b class="mono" :class="{ late: compBoard.overdueCount > 0 }">{{ compBoard.overdueCount }}</b> · 设计稿待签批 <b class="mono">{{ compBoard.pendingArtworkCount }}</b> · 打样待签样 <b class="mono">{{ compBoard.pendingProofCount }}</b>
        </div>
      </template>
      <el-table :data="filteredCompRows" v-loading="loader.loading.value" size="small" empty-text="当前所有标签设计稿与包材打样均已合规闭环">
        <el-table-column label="风险分级" width="130" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.riskLevel === 'STALE' || row.overdue ? 'danger' : row.itemType === 'PENDING_ARTWORK' ? 'warning' : 'primary'" disable-transitions>{{ row.riskLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="itemTypeLabel" label="合规事项" width="115" />
        <el-table-column label="编号 / 版本" width="155">
          <template #default="{ row }"><span class="mono">{{ row.refNo }}</span><div class="muted small">第 {{ row.versionNo }} 版<template v-if="row.supplierName"> · {{ row.supplierName }}</template></div></template>
        </el-table-column>
        <el-table-column label="包材 / 成品" min-width="185">
          <template #default="{ row }">{{ row.pkName }} <span class="muted small mono">{{ row.pkCode }}</span><div v-if="row.fgName" class="muted small">{{ row.fgName }} ({{ row.fgCode }})</div></template>
        </el-table-column>
        <el-table-column label="滞留" width="80" align="right">
          <template #default="{ row }"><span class="mono" :class="{ late: row.overdue }">{{ row.agingDays }} 天</span></template>
        </el-table-column>
        <el-table-column prop="recommendedAction" label="合规处置建议" min-width="220" />
        <el-table-column label="" width="85" :fixed="narrow ? false : 'right'">
          <template #default="{ row }"><el-button size="small" @click="openComplianceItem(row)">处理</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="pg.keyword.value" placeholder="搜索编号、包材、成品" clearable />
          <el-checkbox v-model="currentOnly">只看现行和待签批</el-checkbox>
        </div>
      </template>
      <el-table :data="pg.paged.value" v-loading="loader.loading.value" empty-text="还没有设计稿">
        <el-table-column label="编号" width="160"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row)">{{ row.artworkNo }}</a><div class="muted small">第 {{ row.versionNo }} 版</div></template></el-table-column>
        <el-table-column label="包材" min-width="180"><template #default="{ row }">{{ row.pkName }}<div class="muted small mono">{{ row.pkCode }}</div></template></el-table-column>
        <el-table-column label="成品和配方" min-width="180"><template #default="{ row }">{{ row.fgName }}<div class="muted small"><span class="mono">{{ row.fgCode }}</span> · 配方 V{{ row.formulaVersionNo }}</div></template></el-table-column>
        <el-table-column label="状态" width="150"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.stale" class="late small">配方已变，要重新评审</div></template></el-table-column>
        <el-table-column label="" width="90" :fixed="narrow ? false : 'right'"><template #default="{ row }"><el-button size="small" text @click="openDetail(row)">详情</el-button></template></el-table-column>
      </el-table>
      <ListCapHint :count="artworks.length" />
      <ListPager v-model:page="pg.page.value" v-model:size="pg.size.value" :total="pg.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" title="提交设计稿" width="560px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="成品">
          <el-select id="aw-fg" v-model="cr.fgItemId" filterable placeholder="选择成品" style="width:100%">
            <el-option v-for="i in rd.finished.value" :key="i.id" :value="i.id" :label="`${i.name}（${i.code}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="包材（要在这个成品的生效配方里）">
          <el-select id="aw-pk" v-model="cr.pkItemId" filterable placeholder="选择包材" style="width:100%">
            <el-option v-for="i in packages" :key="i.id" :value="i.id" :label="`${i.name}（${i.code}）`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="cr.required && isRevision ? '关联的变更（改版必选）' : '关联的变更（选填）'">
          <el-select id="aw-change" v-model="cr.changeId" clearable :placeholder="cr.options ? (cr.options.length ? '选择实施中的标签、包材或配方变更' : '没有实施中的标签、包材或配方变更') : '正在读取…'" style="width:100%">
            <el-option v-for="o in cr.options || []" :key="o.id" :value="o.id" :label="`${o.changeNo} ${o.title}（${o.changeTypeLabel}）`" />
          </el-select>
          <p v-if="cr.required && isRevision && cr.options && !cr.options.length" class="small late" style="margin:4px 0 0">这个包材已有设计稿，改版要先到“变更控制”提出标签、包材或配方变更并批准。</p>
        </el-form-item>
        <el-form-item label="设计稿内容或改了什么"><el-input id="aw-note" v-model="cr.changeNote" maxlength="300" placeholder="如：首版；按新规改警示用语" /></el-form-item>
        <p class="muted small">保存后上传设计稿文件，再由质量安全负责人评审。</p>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" :disabled="!cr.options" @click="submitCreate">保存设计稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dt.visible" :title="`设计稿 ${a?.artworkNo || ''}`" width="720px">
      <template v-if="a">
        <p class="summary-line"><StatusTag :status="a.status" :label="a.statusLabel" /> <b>{{ a.pkName }}</b> 第 {{ a.versionNo }} 版 · {{ a.fgName }} 配方 V{{ a.formulaVersionNo }}</p>
        <p class="small">{{ a.changeNote }}<template v-if="a.changeNo"> · 关联变更 <router-link to="/changes" class="mono">{{ a.changeNo }}</router-link></template> <span class="muted">（{{ a.createdBy }} 提交<template v-if="a.approvedBy">，{{ a.approvedBy }} 批准于 {{ fmtTime(a.approvedAt) }}</template>）</span></p>
        <el-alert v-if="a.stale" type="warning" :closable="false" show-icon title="成品的生效配方已经变了，成分表可能要改，请提交新版设计稿重新评审。" />
        <el-alert v-if="a.rejectNote" type="info" :closable="false" show-icon :title="`退回：${a.rejectNote}`" />
        <AttachmentList owner-type="LABEL_ARTWORK" :owner-id="a.id" :can-upload="canEdit && a.status === 'PENDING'" title="设计稿文件" empty-text="还没有上传设计稿" />

        <div class="sub-head"><b>全成分表草稿（按当前生效配方，供核对）</b></div>
        <p v-if="!ing.data" class="muted small">正在读取…</p>
        <template v-else>
          <p class="small ing">{{ ing.data.text || '—' }}</p>
          <p class="muted small">{{ ing.data.problems.join('；') }}</p>
        </template>

        <div class="sub-head"><b>评审清单</b></div>
        <div class="checks">
          <el-checkbox v-for="c in checklist" :key="c.code" :model-value="a.status === 'PENDING' ? dt.checked.includes(c.code) : a.checked.includes(c.code)"
                       :disabled="!(isQso && a.status === 'PENDING')" @change="(v) => toggle(c.code, v)">{{ c.label }}</el-checkbox>
        </div>
        <el-form v-if="isQso && a.status === 'PENDING'" label-position="top" style="margin-top:10px" @submit.prevent>
          <p v-if="a.createdBy === auth.user?.username" class="late small">这版是您提交的，要由另一位质量安全负责人批准。</p>
          <el-form-item label="签批密码"><el-input id="aw-password" v-model="dt.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="approve" /></el-form-item>
        </el-form>

        <template v-if="a.status === 'APPROVED' || a.status === 'SUPERSEDED'">
          <div class="sub-head row">
            <b>打样和签样</b>
            <el-button v-if="canProof && a.status === 'APPROVED' && !a.stale" size="small" @click="openProofCreate">登记打样</el-button>
          </div>
          <p class="muted small">每家印刷供应商都要按这一版打样，质量核对样张后签样；有签样，这家供应商送来的包材才能放行。</p>
          <p v-if="!det.data" class="muted small">正在读取…</p>
          <template v-else>
            <el-table :data="det.data.proofs" size="small" empty-text="还没有打样">
              <el-table-column label="编号" width="150"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openProof(row)">{{ row.proofNo }}</a><div class="muted small">第 {{ row.roundNo }} 次</div></template></el-table-column>
              <el-table-column label="供应商" min-width="140"><template #default="{ row }">{{ row.supplierName }}<div class="muted small">{{ row.proofDate }} 收到</div></template></el-table-column>
              <el-table-column label="状态" width="120"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.current" class="small ok">现行签样</div></template></el-table-column>
              <el-table-column label="签样 / 原因" min-width="150"><template #default="{ row }"><template v-if="row.signedBy">{{ row.signedBy }}<div class="muted small">{{ fmtTime(row.signedAt) }}</div></template><div v-if="row.resultNote" class="small">{{ row.resultNote }}</div></template></el-table-column>
            </el-table>

            <div class="sub-head"><b>印刷批次（按这一版放行的包材）</b></div>
            <el-alert v-if="a.status === 'SUPERSEDED' && oldStock.length" type="warning" :closable="false" show-icon
                      :title="`按这一版印刷的包材还有 ${oldStock.length} 个批次在库，请质量部决定用完还是报废（要停用可以冻结批次）`" />
            <el-table :data="det.data.batches" size="small" empty-text="还没有按这一版放行的批次">
              <el-table-column label="批号" width="150"><template #default="{ row }"><span class="mono">{{ row.batchNo }}</span><div class="muted small">{{ row.statusLabel }}</div></template></el-table-column>
              <el-table-column label="供应商" min-width="140"><template #default="{ row }">{{ row.supplierName || '期初导入' }}<div v-if="row.extBatchNo" class="muted small">来料批号 {{ row.extBatchNo }}</div></template></el-table-column>
              <el-table-column label="入库 / 现存" width="120"><template #default="{ row }">{{ fmtQty(row.qtyIn) }} / <b :class="{ late: a.status === 'SUPERSEDED' && row.onHand > 0 }">{{ fmtQty(row.onHand) }}</b></template></el-table-column>
              <el-table-column label="签样" width="150"><template #default="{ row }"><span class="mono">{{ row.proofNo || '—' }}</span></template></el-table-column>
            </el-table>
          </template>
        </template>
      </template>
      <template #footer>
        <template v-if="a && isQso && a.status === 'PENDING'">
          <el-button text @click="reject">退回</el-button>
          <el-button type="primary" :loading="dt.saving" :disabled="dt.checked.length < checklist.length" @click="approve">批准</el-button>
        </template>
        <el-button @click="dt.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pc.visible" title="登记打样" width="520px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="印刷供应商">
          <el-select id="pf-supplier" v-model="pc.supplierId" filterable placeholder="选择供应商" style="width:100%">
            <el-option v-for="x in rd.suppliers.value" :key="x.id" :value="x.id" :label="`${x.name}（${x.code}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="收到样张的日期"><el-date-picker id="pf-date" v-model="pc.proofDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > new Date()" /></el-form-item>
        <el-form-item label="备注"><el-input id="pf-note" v-model="pc.note" maxlength="300" placeholder="如：首次打样；换了承印物" /></el-form-item>
        <p class="muted small">保存后上传样张照片或扫描件，再由质量人员签样。</p>
      </el-form>
      <template #footer>
        <el-button @click="pc.visible = false">取消</el-button>
        <el-button type="primary" :loading="pc.saving" @click="submitProof">保存打样</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pf.visible" :title="`包材打样 ${pf.row?.proofNo || ''}`" width="640px" append-to-body>
      <template v-if="pf.row">
        <p class="summary-line"><StatusTag :status="pf.row.status" :label="pf.row.statusLabel" /> <b>{{ pf.row.supplierName }}</b> 第 {{ pf.row.roundNo }} 次打样 · {{ pf.row.pkName }} 设计稿第 {{ pf.row.artworkVersionNo }} 版 · {{ pf.row.proofDate }} 收到</p>
        <p class="small">{{ pf.row.note }} <span class="muted">（{{ pf.row.createdBy }} 登记<template v-if="pf.row.signedBy">，{{ pf.row.signedBy }} 签样于 {{ fmtTime(pf.row.signedAt) }}</template>）</span></p>
        <el-alert v-if="pf.row.resultNote" type="info" :closable="false" show-icon :title="`${pf.row.statusLabel}：${pf.row.resultNote}`" />
        <AttachmentList owner-type="ARTWORK_PROOF" :owner-id="pf.row.id" :can-upload="canProof && pf.row.status === 'PENDING'" title="样张照片或扫描件" empty-text="还没有上传样张" />
        <div class="sub-head"><b>签样核对（对照已批准的设计稿）</b></div>
        <div class="checks">
          <el-checkbox v-for="c in proofChecklist" :key="c.code" :model-value="pf.row.status === 'PENDING' ? pf.checked.includes(c.code) : pf.row.checked.includes(c.code)"
                       :disabled="!(isQuality && pf.row.status === 'PENDING')" @change="(v) => toggleProof(c.code, v)">{{ c.label }}</el-checkbox>
        </div>
        <el-form v-if="isQuality && pf.row.status === 'PENDING'" label-position="top" style="margin-top:10px" @submit.prevent>
          <p v-if="pf.row.createdBy === auth.user?.username" class="late small">这次打样是您登记的，要由另一位质量人员签样。</p>
          <el-form-item label="签批密码"><el-input id="pf-password" v-model="pf.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="approveProof" /></el-form-item>
        </el-form>
      </template>
      <template #footer>
        <template v-if="pf.row && isQuality && pf.row.status === 'PENDING'">
          <el-button text @click="proofAction('reject', '判不合格', '写明哪里不合格，供应商改了以后重新登记打样。')">不合格</el-button>
          <el-button type="primary" :loading="pf.saving" :disabled="pf.checked.length < proofChecklist.length" @click="approveProof">签样</el-button>
        </template>
        <el-button v-if="pf.row && isQso && pf.row.status === 'APPROVED'" text @click="proofAction('void', '作废签样', '作废后这家供应商的这一版包材要重新打样签样才能放行，已放行的批次不受影响。')">作废签样</el-button>
        <el-button @click="pf.visible = false">关闭</el-button>
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
import { fmtQty, fmtTime } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const rd = useRefData()
const canEdit = computed(() => auth.hasAny('MASTER_DATA', 'QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const isQuality = computed(() => auth.hasAny('QA', 'QSO'))
const canProof = computed(() => auth.hasAny('MASTER_DATA', 'PURCHASE', 'QA', 'QSO'))
const activeTab = ref('list')
const artworks = ref([])
const checklist = ref([])
const summary = ref(null)
const compBoard = ref(null)
const compFilter = ref('ALL')
const filteredCompRows = computed(() => {
  const list = compBoard.value?.rows || []
  if (compFilter.value === 'ALL') return list
  if (compFilter.value === 'OVERDUE') return list.filter((r) => r.overdue)
  if (compFilter.value === 'STALE') return list.filter((r) => r.riskLevel === 'STALE')
  return list.filter((r) => r.itemType === compFilter.value)
})
function openComplianceItem(row) {
  const target = artworks.value.find((x) => x.id === row.artworkId)
  if (target) openDetail(target)
}
const currentOnly = ref(true)
const loader = useLoader(async () => {
  const [o, sum, cb] = await Promise.all([
    http.get('/artworks'),
    http.get('/artworks/summary'),
    http.get('/artworks/compliance-board')
  ])
  artworks.value = o.artworks
  checklist.value = o.checklist
  summary.value = sum
  compBoard.value = cb
})
const load = () => loader.run()
const shown = computed(() => currentOnly.value ? artworks.value.filter((x) => x.status === 'APPROVED' || x.status === 'PENDING') : artworks.value)
const pg = usePaged(shown, { match: textMatch('artworkNo', 'pkName', 'pkCode', 'fgName', 'fgCode') })
const packages = computed(() => rd.s.items.filter((i) => i.itemType === 'PK' && i.active))
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { load(); rd.load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const cr = reactive({ visible: false, saving: false, fgItemId: null, pkItemId: null, changeNote: '', changeId: null, options: null, required: false })
// 这个包材已有设计稿就是改版；开关打开时改版要关联变更（列表只有最近 300 条，以后端为准）
const isRevision = computed(() => !!cr.pkItemId && artworks.value.some((x) => x.pkItemId === cr.pkItemId))
const optionsReq = latestOnly()
async function openCreate() {
  Object.assign(cr, { visible: true, saving: false, fgItemId: null, pkItemId: null, changeNote: '', changeId: null, options: null, required: false })
  // 可选的变更读完之前不能保存，否则开关打开时改版一定报错
  const current = optionsReq()
  const o = await http.get('/changes/artwork-options')
  if (!current()) return
  cr.options = o.changes
  cr.required = o.required
}
async function submitCreate() {
  if (!cr.fgItemId || !cr.pkItemId || !cr.changeNote.trim()) return ElMessage.warning('请选择成品和包材，并写明设计稿内容')
  if (cr.required && isRevision.value && !cr.changeId) return ElMessage.warning('这个包材已有设计稿，改版请选择关联的变更')
  cr.saving = true
  try {
    const x = await http.post('/artworks', { fgItemId: cr.fgItemId, pkItemId: cr.pkItemId, changeNote: cr.changeNote, changeId: cr.changeId })
    ElMessage.success(`设计稿 ${x.artworkNo} 已保存，请上传文件`)
    cr.visible = false
    load()
    openDetail(x)
  } finally {
    cr.saving = false
  }
}

const dt = reactive({ visible: false, saving: false, row: null, checked: [], password: '' })
const a = computed(() => dt.row)
const ing = reactive({ data: null })
const ingReq = latestOnly()
async function openDetail(row) {
  Object.assign(dt, { visible: true, saving: false, row, checked: [], password: '' })
  ing.data = null
  loadDet(row.id)
  const current = ingReq()
  const d = await http.get(`/compliance/products/${row.fgItemId}/ingredients`, { silent: true }).catch(() => ({ text: '', problems: ['读取成分表失败'] }))
  if (current()) ing.data = d
}

// 打样和按这一版放行的批次
const det = reactive({ data: null })
const detReq = latestOnly()
const proofChecklist = computed(() => det.data?.proofChecklist || [])
const oldStock = computed(() => (det.data?.batches || []).filter((b) => b.onHand > 0))
async function loadDet(id) {
  det.data = null
  const current = detReq()
  const d = await http.get(`/artworks/${id}`)
  if (current()) {
    det.data = d
    dt.row = d.artwork
  }
}

const pc = reactive({ visible: false, saving: false, supplierId: null, proofDate: '', note: '' })
function openProofCreate() {
  rd.load()
  Object.assign(pc, { visible: true, saving: false, supplierId: null, proofDate: new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }), note: '' })
}
async function submitProof() {
  if (!pc.supplierId || !pc.proofDate) return ElMessage.warning('请选择供应商和收到样张的日期')
  pc.saving = true
  try {
    const x = await http.post(`/artworks/${a.value.id}/proofs`, { supplierId: pc.supplierId, proofDate: pc.proofDate, note: pc.note })
    ElMessage.success(`打样 ${x.proofNo} 已登记，请上传样张`)
    pc.visible = false
    loadDet(a.value.id)
    openProof(x)
  } finally {
    pc.saving = false
  }
}

const pf = reactive({ visible: false, saving: false, row: null, checked: [], password: '' })
function openProof(row) {
  Object.assign(pf, { visible: true, saving: false, row, checked: [], password: '' })
}
function toggleProof(code, v) {
  pf.checked = v ? [...new Set([...pf.checked, code])] : pf.checked.filter((x) => x !== code)
}
async function approveProof() {
  if (pf.checked.length < proofChecklist.value.length) return ElMessage.warning('请逐项核对样张')
  if (!pf.password) return ElMessage.warning('请输入签批密码')
  pf.saving = true
  try {
    pf.row = await http.post(`/artworks/proofs/${pf.row.id}/approve`, { checked: pf.checked, password: pf.password })
    pf.password = ''
    ElMessage.success('已签样')
    loadDet(a.value.id)
  } finally {
    pf.saving = false
  }
}
async function proofAction(action, title, hint) {
  let note
  try {
    note = (await ElMessageBox.prompt(hint, `${title} ${pf.row.proofNo}`, { confirmButtonText: title, cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  pf.row = await http.post(`/artworks/proofs/${pf.row.id}/${action}`, { note })
  ElMessage.success(action === 'void' ? '已作废签样' : '已判不合格')
  loadDet(a.value.id)
}
function toggle(code, v) {
  dt.checked = v ? [...new Set([...dt.checked, code])] : dt.checked.filter((x) => x !== code)
}
async function approve() {
  if (dt.checked.length < checklist.value.length) return ElMessage.warning('请逐项确认评审清单')
  if (!dt.password) return ElMessage.warning('请输入签批密码')
  dt.saving = true
  try {
    dt.row = await http.post(`/artworks/${a.value.id}/approve`, { checked: dt.checked, password: dt.password })
    dt.password = ''
    ElMessage.success('已批准')
    load()
    loadDet(a.value.id)
  } finally {
    dt.saving = false
  }
}
async function reject() {
  let note
  try {
    note = (await ElMessageBox.prompt('写明退回原因。', `退回 ${a.value.artworkNo}`, { confirmButtonText: '退回', cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  dt.row = await http.post(`/artworks/${a.value.id}/reject`, { note })
  ElMessage.success('已退回')
  load()
}
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
.kpi-card { padding: 14px 16px; border-radius: 14px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); display: grid; gap: 4px; }
.kpi-card.warn { border-color: var(--el-color-danger-light-5); }
.kpi-val { font-size: 20px; font-weight: 600; }
.late { color: var(--el-color-danger); }
.summary-line { margin: 0 0 6px; line-height: 1.8; }
.sub-head { margin: 14px 0 6px; }
.sub-head.row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ok { color: var(--el-color-success); }
.ing { line-height: 1.7; }
.checks { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 2px 16px; }
</style>
