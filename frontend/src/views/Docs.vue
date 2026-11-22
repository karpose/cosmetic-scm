<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>受控文件</h2>
        <p>SOP、工艺规程、记录表格等。每次修订是一个新版本，要上传文件，由质量安全负责人签批（不能是起草人）。现行版本是已签批、已到生效日期的最新一版；按复审周期提醒。</p>
      </div>
      <div class="toolbar">
        <ExportButton :report="activeTab === 'compliance' ? 'doc-compliance' : 'controlled-docs'" :label="activeTab === 'compliance' ? '导出合规清单' : '导出台账'" />
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">新建文件</el-button>
      </div>
    </div>

    <div v-if="docSum" class="kpi-grid">
      <div class="kpi-card" :class="{ warn: docSum.pendingApprovalCount > 0 }">
        <div class="kpi-label">在用受控文件</div>
        <div class="kpi-val mono">{{ docSum.activeDocs }} <span class="kpi-unit">份</span></div>
        <div class="kpi-sub">待 QSO 签批 {{ docSum.pendingApprovalCount }} 版</div>
      </div>
      <div class="kpi-card" :class="{ danger: docSum.reviewOverdueCount > 0, warn: docSum.reviewOverdueCount === 0 && docSum.reviewDueCount > 0 }">
        <div class="kpi-label">复审将到期 / 已超期</div>
        <div class="kpi-val mono">{{ docSum.reviewDueCount }} <span class="kpi-unit">/ {{ docSum.reviewOverdueCount }}</span></div>
        <div class="kpi-sub">按文件复审周期自动提醒</div>
      </div>
      <div class="kpi-card" :class="{ danger: docSum.ackOverdueDocs > 0, warn: docSum.ackOverdueDocs === 0 && docSum.ackPendingPeopleTotal > 0 }">
        <div class="kpi-label">待已读确认人次</div>
        <div class="kpi-val mono">{{ docSum.ackPendingPeopleTotal }}</div>
        <div class="kpi-sub">超期未确认文件 {{ docSum.ackOverdueDocs }} 份</div>
      </div>
      <div class="kpi-card" :class="{ danger: docSum.copiesRetrieveOverdueDocs > 0, warn: docSum.copiesRetrieveOverdueDocs === 0 && docSum.copiesToRetrieveTotal > 0 }">
        <div class="kpi-label">纸质副本待收回</div>
        <div class="kpi-val mono">{{ docSum.copiesToRetrieveTotal }} <span class="kpi-unit">/ {{ docSum.copiesOutTotal }} 在用</span></div>
        <div class="kpi-sub">超期未收回文件 {{ docSum.copiesRetrieveOverdueDocs }} 份</div>
      </div>
    </div>

    <el-card v-if="mine.length" shadow="never" class="mine">
      <template #header><b>待我确认（{{ mine.length }}）</b> <span class="muted small">请阅读后在文件详情里确认</span></template>
      <div v-for="m in mine" :key="m.revisionId" class="mine-row">
        <a href="#" class="mono" @click.prevent="openDetail(m.docId)">{{ m.docCode }}</a> {{ m.title }} <span class="muted small">第 {{ m.revNo }} 版<template v-if="m.effectiveDate">，{{ m.effectiveDate }} 生效</template></span>
      </div>
    </el-card>

    <el-tabs v-model="activeTab" class="seg">
      <el-tab-pane label="受控文件台账" name="list" />
      <el-tab-pane :label="`合规闭环监控（${compBoard?.totalCount ?? 0}）`" name="compliance" />
    </el-tabs>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-card v-else-if="activeTab === 'compliance'" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="compFilter" size="small">
            <el-radio-button value="ALL">全部 ({{ compBoard?.totalCount ?? 0 }})</el-radio-button>
            <el-radio-button value="OVERDUE">已超期 ({{ compBoard?.overdueCount ?? 0 }})</el-radio-button>
            <el-radio-button value="DUE_SOON">临期待办</el-radio-button>
            <el-radio-button value="PENDING">待签收 / 签批</el-radio-button>
          </el-radio-group>
        </div>
        <div v-if="compBoard" class="muted small" style="margin-top:8px">
          待闭环合规事项 <b class="mono">{{ compBoard.totalCount }}</b> 项 · 已超期 <b class="mono" :class="{ late: compBoard.overdueCount > 0 }">{{ compBoard.overdueCount }}</b> 项 · 复审提醒 <b class="mono">{{ compBoard.reviewDueCount }}</b> · 待签收 <b class="mono">{{ compBoard.ackPendingCount }}</b> · 纸质待收回 <b class="mono">{{ compBoard.copyRetrieveCount }}</b> · 待签批 <b class="mono">{{ compBoard.pendingApproveCount }}</b>
        </div>
      </template>
      <el-table :data="filteredCompRows" v-loading="loader.loading.value" size="small" empty-text="当前所有受控文件均处于合规状态">
        <el-table-column label="风险分级" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.riskLevel === 'OVERDUE' ? 'danger' : row.riskLevel === 'DUE_SOON' ? 'warning' : 'primary'" disable-transitions>{{ row.riskLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="issueTypeLabel" label="合规事项" width="115" />
        <el-table-column label="文件编号 / 名称" min-width="200">
          <template #default="{ row }">
            <a href="#" class="mono" @click.prevent="openDetail(row.docId)">{{ row.docCode }}</a>
            <span style="margin-left:6px">{{ row.title }}</span>
            <div class="muted small">{{ row.categoryLabel }}<template v-if="row.ownerDept"> · {{ row.ownerDept }}</template><template v-if="row.currentRev"> · V{{ row.currentRev }}</template></div>
          </template>
        </el-table-column>
        <el-table-column label="截止日 / 剩余" width="125" align="right">
          <template #default="{ row }">
            <template v-if="row.dueDate">
              <span class="mono">{{ row.dueDate }}</span>
              <div class="small mono" :class="{ late: row.daysLeft < 0 }">{{ row.daysLeft < 0 ? `超期 ${-row.daysLeft} 天` : `剩 ${row.daysLeft} 天` }}</div>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="具体情况" min-width="180" />
        <el-table-column prop="recommendedAction" label="合规处置建议" min-width="210" />
        <el-table-column label="" width="80" :fixed="narrow ? false : 'right'">
          <template #default="{ row }"><el-button size="small" @click="openDetail(row.docId)">处理</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="pg.keyword.value" placeholder="搜索编号、名称、部门" clearable />
          <el-select aria-label="按类别筛选" v-model="categoryFilter" placeholder="全部类别" clearable style="width:150px">
            <el-option v-for="(v, k) in CATEGORY" :key="k" :value="k" :label="v" />
          </el-select>
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
            <div v-if="row.ackPending" class="small" :class="row.ackOverdue ? 'late' : 'muted'">{{ row.ackPending }} 人待确认</div>
            <div v-if="row.copiesToRetrieve" class="small" :class="row.retrieveOverdue ? 'late' : 'warn'">{{ row.copiesToRetrieve }} 份纸质副本待收回</div>
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
        <el-form-item label="需要确认已读的岗位（选填）">
          <el-select id="dc-roles" v-model="cr.trainRoles" multiple placeholder="不选表示不需要确认" style="width:100%">
            <el-option v-for="r in roles" :key="r.code" :value="r.code" :label="r.label" />
          </el-select>
        </el-form-item>
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
        <div class="roles-row small">
          <span>需要确认的岗位：{{ d.trainRoles.length ? d.trainRoles.map(roleLabel).join('、') : '不需要确认' }}</span>
          <el-button v-if="isQuality && d.status === 'ACTIVE' && !tr.editing" size="small" text @click="tr.editing = true; tr.roles = [...d.trainRoles]">修改</el-button>
          <template v-if="tr.editing">
            <el-select v-model="tr.roles" multiple size="small" placeholder="不选表示不需要确认" aria-label="需要确认的岗位" style="min-width:240px">
              <el-option v-for="r in roles" :key="r.code" :value="r.code" :label="r.label" />
            </el-select>
            <el-button size="small" type="primary" :loading="tr.saving" @click="saveRoles">保存</el-button>
            <el-button size="small" text @click="tr.editing = false">取消</el-button>
          </template>
        </div>
        <div v-if="dt.data.ack" class="ack-box">
          <b>第 {{ dt.data.ack.revNo }} 版已读确认 {{ dt.data.ack.done }} / {{ dt.data.ack.total }}</b>
          <el-button v-if="needMyAck" size="small" type="primary" :loading="dt.saving" @click="ackMine">我已阅读并理解第 {{ dt.data.ack.revNo }} 版</el-button>
          <div class="small">
            <span v-for="p in dt.data.ack.people" :key="p.username" class="person" :class="p.ackedAt ? 'muted' : 'late'">{{ p.displayName || p.username }}{{ p.ackedAt ? ' ✓' : '' }}</span>
          </div>
        </div>
        <el-table :data="dt.data.revisions" size="small" style="margin-top:8px" highlight-current-row scrollbar-tabindex="0" @current-change="(r) => (dt.rev = r)">
          <el-table-column label="版本" width="110"><template #default="{ row }">第 {{ row.revNo }} 版 <el-tag v-if="row.current" type="success" size="small" disable-transitions>现行</el-tag><el-tag v-else-if="row.upcoming" size="small" disable-transitions>待生效</el-tag></template></el-table-column>
          <el-table-column label="修订内容" min-width="200"><template #default="{ row }">{{ row.changeSummary }}<div v-if="row.rejectNote" class="small late">退回：{{ row.rejectNote }}</div></template></el-table-column>
          <el-table-column label="生效日期" width="110"><template #default="{ row }">{{ row.effectiveDate || '签批当天' }}</template></el-table-column>
          <el-table-column label="状态" width="150"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div class="muted small">{{ row.approvedBy ? `${row.approvedBy} 签批` : `${row.draftedBy} 起草` }}</div></template></el-table-column>
        </el-table>
        <p class="muted small">点一行查看这一版的文件。</p>
        <AttachmentList v-if="dt.rev" owner-type="DOC_REVISION" :owner-id="dt.rev.id" :can-upload="canEdit && dt.rev.status === 'PENDING'" :title="`第 ${dt.rev.revNo} 版的文件`" empty-text="还没有上传文件" />

        <div class="copies-head">
          <b>纸质副本</b>
          <span class="muted small">在用 {{ d.copiesOut }} 份<template v-if="d.copiesToRetrieve">，<span :class="d.retrieveOverdue ? 'late' : 'warn'">{{ d.copiesToRetrieve }} 份要在 {{ d.retrieveDue }} 前收回</span></template></span>
          <el-button v-if="isQuality && d.status === 'ACTIVE' && d.currentRev" size="small" @click="openIssue">发放纸质副本</el-button>
        </div>
        <el-table v-if="dt.data.copies.length" :data="dt.data.copies" size="small" scrollbar-tabindex="0">
          <el-table-column label="副本" width="130"><template #default="{ row }">第 {{ row.revNo }} 版第 {{ row.copyNo }} 份</template></el-table-column>
          <el-table-column label="持有人或地点" min-width="150"><template #default="{ row }">{{ row.holder }}<div class="muted small">{{ row.issuedBy }} {{ fmtTime(row.issuedAt) }} 发放</div></template></el-table-column>
          <el-table-column label="状态" min-width="170">
            <template #default="{ row }">
              <StatusTag :status="row.status" :label="row.statusLabel" />
              <div v-if="row.mustRetrieve" class="small" :class="row.overdue ? 'late' : 'warn'">{{ row.overdue ? '回收已超期' : '要收回' }}，期限 {{ row.retrieveDue }}</div>
              <div v-if="row.closedBy" class="muted small">{{ row.closedBy }} {{ fmtTime(row.closedAt) }}<template v-if="row.closeNote">：{{ row.closeNote }}</template></div>
            </template>
          </el-table-column>
          <el-table-column v-if="isQuality" label="" width="130">
            <template #default="{ row }">
              <template v-if="row.status === 'ISSUED'">
                <el-button size="small" text type="primary" @click="retrieveCopy(row)">收回</el-button>
                <el-button size="small" text @click="loseCopy(row)">遗失</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <p v-else class="muted small">还没有发放纸质副本。</p>

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

    <el-dialog v-model="is.visible" :title="`发放纸质副本：第 ${d?.currentRev || ''} 版`" width="460px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="持有人、部门或存放地点"><el-input id="dc-holder" v-model="is.holder" maxlength="100" placeholder="如：灌装车间 2 号线" /></el-form-item>
        <el-form-item label="份数"><el-input-number id="dc-qty" v-model="is.quantity" :min="1" :max="20" :precision="0" /></el-form-item>
        <p class="muted small">只发现行版本，按版本连续编号。出新版或作废后，旧版副本要在 7 天内收回。</p>
      </el-form>
      <template #footer>
        <el-button @click="is.visible = false">取消</el-button>
        <el-button type="primary" :loading="is.saving" @click="submitIssue">发放</el-button>
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
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtTime, startOfBusinessDay } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { textMatch, usePaged } from '../utils/table'

const CATEGORY = { SOP: '标准操作规程', PROCESS: '工艺规程', FORM: '记录表格', MANUAL: '手册', OTHER: '其他' }
const auth = useAuth()
const canEdit = computed(() => auth.hasAny('MASTER_DATA', 'QA', 'QSO'))
const isQuality = computed(() => auth.hasAny('QA', 'QSO'))
const roles = ref([])
const roleLabel = (code) => roles.value.find((r) => r.code === code)?.label || code
const mine = ref([])
const isQso = computed(() => auth.hasAny('QSO'))
const activeTab = ref('list')
const docs = ref([])
const docSum = ref(null)
const compBoard = ref(null)
const compFilter = ref('ALL')
const filteredCompRows = computed(() => {
  const list = compBoard.value?.rows || []
  return compFilter.value === 'ALL' ? list : list.filter((r) => r.riskLevel === compFilter.value)
})
const activeOnly = ref(true)
const categoryFilter = ref('')
const loader = useLoader(async () => {
  const [d, m, s, cb] = await Promise.all([
    http.get('/docs'),
    http.get('/docs/my-pending'),
    http.get('/docs/summary'),
    http.get('/docs/compliance-board')
  ])
  docs.value = d
  mine.value = m
  docSum.value = s
  compBoard.value = cb
})
const load = () => loader.run()
const shown = computed(() => docs.value.filter((x) => {
  if (activeOnly.value && x.status !== 'ACTIVE') return false
  if (categoryFilter.value && x.category !== categoryFilter.value) return false
  return true
}))
const pg = usePaged(shown, { match: textMatch('docCode', 'title', 'ownerDept') })
const startOfToday = startOfBusinessDay()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => {
  load()
  http.get('/auth/roles').then((r) => { roles.value = r.filter((x) => !x.portal) })
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const cr = reactive({ visible: false, saving: false, docCode: '', title: '', category: 'SOP', ownerDept: '', reviewMonths: 24, effectiveDate: null, changeSummary: '首版', trainRoles: [] })
function openCreate() {
  Object.assign(cr, { visible: true, saving: false, docCode: '', title: '', category: 'SOP', ownerDept: '', reviewMonths: 24, effectiveDate: null, changeSummary: '首版', trainRoles: [] })
}
async function submitCreate() {
  if (!cr.docCode.trim() || !cr.title.trim() || !cr.changeSummary.trim()) return ElMessage.warning('请填写文件编号、名称和制定说明')
  cr.saving = true
  try {
    const x = await http.post('/docs', { docCode: cr.docCode, title: cr.title, category: cr.category, ownerDept: cr.ownerDept, reviewMonths: cr.reviewMonths, effectiveDate: cr.effectiveDate, changeSummary: cr.changeSummary, trainRoles: cr.trainRoles })
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
const tr = reactive({ editing: false, saving: false, roles: [] })
const needMyAck = computed(() => dt.data?.ack?.people.some((p) => p.username === auth.user?.username && !p.ackedAt))
async function saveRoles() {
  tr.saving = true
  try {
    apply(await http.post(`/docs/${d.value.id}/train-roles`, { trainRoles: tr.roles }))
    tr.editing = false
    ElMessage.success('已修改确认岗位')
  } finally {
    tr.saving = false
  }
}
async function ackMine() {
  dt.saving = true
  try {
    apply(await http.post(`/docs/${d.value.id}/revisions/${dt.data.ack.revisionId}/ack`, {}))
    ElMessage.success('已确认')
  } finally {
    dt.saving = false
  }
}
function show(x) {
  tr.editing = false
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

// 纸质副本
const is = reactive({ visible: false, saving: false, holder: '', quantity: 1 })
function openIssue() {
  Object.assign(is, { visible: true, saving: false, holder: '', quantity: 1 })
}
async function submitIssue() {
  if (!is.holder.trim()) return ElMessage.warning('请填写持有人或存放地点')
  is.saving = true
  try {
    apply(await http.post(`/docs/${d.value.id}/copies`, { holder: is.holder, quantity: is.quantity }))
    ElMessage.success(`已发放 ${is.quantity} 份`)
    is.visible = false
  } finally {
    is.saving = false
  }
}
async function retrieveCopy(row) {
  let note
  try {
    note = (await ElMessageBox.prompt('可以写明收回后怎么处理（选填）。', `收回第 ${row.revNo} 版第 ${row.copyNo} 份`, { confirmButtonText: '收回', cancelButtonText: '不了', inputPlaceholder: '如：已销毁' })).value
  } catch {
    return
  }
  apply(await http.post(`/docs/${d.value.id}/copies/${row.id}/retrieve`, { note }))
  ElMessage.success('已收回')
}
async function loseCopy(row) {
  const note = await prompt('登记遗失', `写明第 ${row.revNo} 版第 ${row.copyNo} 份怎么丢的、是否有风险。`)
  if (note == null) return
  apply(await http.post(`/docs/${d.value.id}/copies/${row.id}/lost`, { note }))
  ElMessage.success('已登记遗失')
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
.warn { color: var(--el-color-warning); }
.summary-line { margin: 0 0 6px; line-height: 1.8; }
.mine { margin-bottom: 14px; }
.mine-row { padding: 4px 0; }
.roles-row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; margin: 6px 0; }
.ack-box { margin: 8px 0; padding: 10px 12px; border-radius: 12px; background: var(--lg-tint); display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.ack-box > .small { flex: 1 1 100%; }
.person { margin-right: 12px; }
.copies-head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; margin: 14px 0 6px; }
</style>
