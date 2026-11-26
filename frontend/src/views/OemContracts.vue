<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>委托生产合同</h2>
        <p>和受托生产企业签订的委托生产合同（含质量协议），对应检查要点委托生产版第 8 项。登记合同覆盖的成品和物料采购分工，上传双方签字盖章的扫描件，由质量安全负责人逐项确认约定事项后签批生效。下委托单时系统核对有没有覆盖这个成品的有效合同。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <ExportButton report="oem-contracts" />
        <el-button v-if="canEdit" type="primary" @click="openCreate">登记合同</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <template v-else-if="data">
      <p class="muted small rule-line">
        {{ data.requireForOrder ? '已开启：没有覆盖成品的有效合同，不能下委托单。' : '没有覆盖成品的有效合同时，委托单照常保存，记入审计并在总览提醒。' }}
        委托单按下单那一刻核对，之后合同到期或终止不影响已下的单。
      </p>
      <el-tabs v-model="tab" class="seg">
        <el-tab-pane :label="`合同（${data.contracts.length}）`" name="contracts">
          <el-card shadow="never" body-style="padding:0 0 8px">
            <el-table :data="data.contracts" empty-text="还没有登记委托生产合同" scrollbar-tabindex="0">
              <el-table-column label="编号" width="150">
                <template #default="{ row }"><span class="mono">{{ row.contractNo }}</span><div v-if="row.extNo" class="muted small">{{ row.extNo }}</div></template>
              </el-table-column>
              <el-table-column label="受托生产企业" min-width="140"><template #default="{ row }">{{ row.factoryName }}</template></el-table-column>
              <el-table-column label="合同和成品" min-width="220">
                <template #default="{ row }">{{ row.title }}<div class="muted small">{{ row.productNames || '—' }}</div>
                  <div v-if="row.mismatches.length" class="warn small">采购分工和 BOM 有 {{ row.mismatches.length }} 处不一致</div>
                  <div v-if="row.licenseGaps.length" class="warn small">{{ row.licenseGaps.length }} 个成品超出受托厂许可项目的标注</div>
                </template>
              </el-table-column>
              <el-table-column label="期限" width="180">
                <template #default="{ row }"><span class="mono small">{{ row.validFrom }} 至 {{ row.validTo }}</span>
                  <div v-if="row.expiringSoon" class="warn small">{{ row.daysLeft }} 天后到期，请续签</div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }"><el-tag :type="STATE_TAG[row.state] || 'info'" size="small" disable-transitions>{{ row.stateLabel }}</el-tag>
                  <div v-if="row.status === 'DRAFT' && row.rejectNote" class="warn small">已退回</div>
                </template>
              </el-table-column>
              <el-table-column label="" width="170" :fixed="narrow ? false : 'right'">
                <template #default="{ row }">
                  <el-button size="small" text @click="openDetail(row)">详情</el-button>
                  <el-button v-if="canEdit && row.status === 'DRAFT'" size="small" text @click="openEdit(row)">修改</el-button>
                  <el-button v-if="isQso && row.status === 'PENDING'" size="small" text type="primary" @click="openDetail(row, true)">签批</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-tab-pane>
        <el-tab-pane :label="`委托单核对${uncovered ? `（${uncovered} 张没有合同）` : ''}`" name="orders">
          <el-card shadow="never" body-style="padding:0 0 8px">
            <p class="muted small pad">还没完成的委托单和最近 90 天下的委托单（不含已取消）。</p>
            <el-table :data="orders" empty-text="没有委托单" scrollbar-tabindex="0">
              <el-table-column label="委托单" width="150"><template #default="{ row }"><span class="mono">{{ row.moNo }}</span><div class="muted small">{{ fmtTime(row.createdAt) }}</div></template></el-table-column>
              <el-table-column label="受托生产企业" min-width="140"><template #default="{ row }">{{ row.factoryName }}</template></el-table-column>
              <el-table-column label="成品" min-width="180"><template #default="{ row }">{{ row.fgName }}<div class="muted small mono">{{ row.fgCode }}</div></template></el-table-column>
              <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
              <el-table-column label="合同依据" min-width="150">
                <template #default="{ row }">
                  <span v-if="row.covered" class="mono">{{ row.contractNo }}</span>
                  <el-tag v-else type="danger" size="small" disable-transitions>下单时没有合同</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- 登记 / 修改 -->
    <el-dialog v-model="ed.visible" :title="ed.id ? `修改合同 ${ed.no}` : '登记委托生产合同'" width="640px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-row">
          <el-form-item label="受托生产企业">
            <el-select id="oc-factory" v-model="ed.factoryId" placeholder="选择受托生产企业" filterable style="width:100%">
              <el-option v-for="p in rd.oems.value" :key="p.id" :value="p.id" :label="`${p.name}${p.qualified ? '' : '（不在合格名录）'}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="合同编号（选填）"><el-input id="oc-ext" v-model="ed.extNo" maxlength="60" placeholder="双方签署文本上的编号" /></el-form-item>
        </div>
        <el-form-item label="合同名称"><el-input id="oc-title" v-model="ed.title" maxlength="100" placeholder="如：2027 年度委托加工合同及质量协议" /></el-form-item>
        <div class="form-row">
          <el-form-item label="签订日期"><el-date-picker id="oc-signed" v-model="ed.signedOn" type="date" value-format="YYYY-MM-DD" :disabled-date="(x) => x > endOfToday" style="width:100%" /></el-form-item>
          <el-form-item label="开始日期"><el-date-picker id="oc-from" v-model="ed.validFrom" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker id="oc-to" v-model="ed.validTo" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        </div>
        <el-form-item label="委托生产的成品">
          <el-select id="oc-products" v-model="ed.productIds" multiple filterable placeholder="选择合同覆盖的成品" style="width:100%">
            <el-option v-for="f in rd.finished.value" :key="f.id" :value="f.id" :label="`${f.code} ${f.name}`" />
          </el-select>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="原料由谁采购">
            <el-select id="oc-rm" v-model="ed.rmSupply" placeholder="请选择" style="width:100%">
              <el-option v-for="s in data?.supplies || []" :key="s.code" :value="s.code" :label="s.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="包材由谁采购">
            <el-select id="oc-pk" v-model="ed.pkSupply" placeholder="请选择" style="width:100%">
              <el-option v-for="s in data?.supplies || []" :key="s.code" :value="s.code" :label="s.label" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="其他约定（选填）"><el-input id="oc-terms" v-model="ed.otherTerms" type="textarea" :rows="2" maxlength="500" placeholder="如：受托方代采的原料须提供每批检验报告" /></el-form-item>
        <p class="muted small">系统按成品现行 BOM 的供料方式核对采购分工：约定“委托方提供”的，BOM 里应是品牌供料；“受托方采购”的应是工厂代采；“按 BOM 逐项约定”的不核对。保存后上传双方签字盖章的扫描件，再提交签批。</p>
      </el-form>
      <template #footer>
        <el-button @click="ed.visible = false">取消</el-button>
        <el-button type="primary" :loading="ed.saving" @click="submitEdit">{{ ed.id ? '保存修改' : '保存草稿' }}</el-button>
      </template>
    </el-dialog>

    <!-- 详情、附件、提交、签批 -->
    <el-dialog v-model="dt.visible" :title="`委托生产合同 ${dt.row?.contractNo || ''}`" width="680px" @closed="load">
      <template v-if="dt.row">
        <p class="summary-line">
          <el-tag :type="STATE_TAG[dt.row.state] || 'info'" size="small" disable-transitions>{{ dt.row.stateLabel }}</el-tag>
          {{ dt.row.factoryName }} · {{ dt.row.title }}<template v-if="dt.row.extNo">（{{ dt.row.extNo }}）</template>
        </p>
        <p class="small"><b>期限：</b>{{ dt.row.validFrom }} 至 {{ dt.row.validTo }}，{{ dt.row.signedOn }} 签订<template v-if="dt.row.daysLeft != null">，还有 {{ dt.row.daysLeft }} 天到期</template></p>
        <p class="small"><b>成品：</b>{{ dt.row.products.map((p) => `${p.code} ${p.name}`).join('、') || '—' }}</p>
        <p class="small"><b>采购分工：</b>原料{{ dt.row.rmSupplyLabel }}，包材{{ dt.row.pkSupplyLabel }}</p>
        <p v-if="dt.row.otherTerms" class="small"><b>其他约定：</b>{{ dt.row.otherTerms }}</p>
        <el-alert v-if="dt.row.mismatches.length" type="warning" :closable="false" show-icon title="合同约定的物料采购分工和成品现行 BOM 不一致" style="margin-bottom:8px">
          <template #default><div v-for="(m, i) in dt.row.mismatches" :key="i" class="small">{{ m }}</div></template>
        </el-alert>
        <el-alert v-if="dt.row.licenseGaps.length" type="error" :closable="false" show-icon title="受托厂的生产许可项目没有标注这些成品要求的生产条件，不能签批" style="margin-bottom:8px">
          <template #default><div v-for="(m, i) in dt.row.licenseGaps" :key="i" class="small">{{ m }}</div><div class="small">许可证上已有标注的，请在“主数据 → 往来单位”给受托厂勾上；没有的，请退回把这些成品移出合同。</div></template>
        </el-alert>
        <el-alert v-if="dt.row.rejectNote && dt.row.status === 'DRAFT'" type="error" :closable="false" show-icon :title="`已退回：${dt.row.rejectNote}`" style="margin-bottom:8px" />
        <p class="muted small">{{ dt.row.createdBy }} {{ fmtTime(dt.row.createdAt) }} 登记<template v-if="dt.row.submittedBy">；{{ dt.row.submittedBy }} {{ fmtTime(dt.row.submittedAt) }} 提交</template><template v-if="dt.row.approvedBy">；{{ dt.row.approvedBy }} {{ fmtTime(dt.row.approvedAt) }} 签批</template><template v-if="dt.row.terminatedBy">；{{ dt.row.terminatedBy }} {{ fmtTime(dt.row.terminatedAt) }} 终止</template><template v-if="dt.row.endReason">（{{ dt.row.endReason }}）</template></p>
        <template v-if="dt.row.checked.length">
          <h4>签批时确认的约定事项</h4>
          <ul class="clauses small"><li v-for="c in dt.row.checked" :key="c">{{ clauseLabel(c) }}</li></ul>
        </template>
        <AttachmentList owner-type="OEM_CONTRACT" :owner-id="dt.row.id" :can-upload="canEdit && ['DRAFT', 'PENDING', 'ACTIVE'].includes(dt.row.status)"
                        title="合同扫描件" empty-text="还没有上传，签批前必须上传双方签字盖章的扫描件" />
        <template v-if="dt.approve">
          <el-divider />
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="对照扫描件，确认合同里写明了以下约定">
              <el-checkbox-group v-model="dt.checked" aria-label="合同里写明的约定事项" class="clause-group">
                <el-checkbox v-for="c in data?.clauses || []" :key="c.code" :value="c.code">{{ c.label }}</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="签批密码"><el-input id="oc-approve-pwd" v-model="dt.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="submitApprove" /></el-form-item>
            <p class="muted small">签批人不能是登记人或提交人；受托厂要在合格名录内、生产许可证有效。合同没写明的事项请退回，补签补充协议后再提交。</p>
          </el-form>
        </template>
      </template>
      <template #footer>
        <el-button @click="dt.visible = false">关闭</el-button>
        <template v-if="dt.row">
          <el-button v-if="canEdit && dt.row.status === 'DRAFT'" type="danger" plain @click="voidDraft(dt.row)">作废</el-button>
          <el-button v-if="canEdit && dt.row.status === 'DRAFT'" type="primary" :loading="dt.saving" @click="submitForApproval(dt.row)">提交签批</el-button>
          <el-button v-if="canTerminate && dt.row.status === 'ACTIVE'" type="danger" plain @click="terminate(dt.row)">终止合同</el-button>
          <template v-if="dt.approve">
            <el-button type="danger" plain @click="reject(dt.row)">退回</el-button>
            <el-button type="primary" :loading="dt.saving" @click="submitApprove">签批生效</el-button>
          </template>
        </template>
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
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { useLoader } from '../utils/loader'
import { fmtTime, today } from '../utils/format'

const STATE_TAG = { DRAFT: 'info', PENDING: 'warning', NOT_STARTED: 'primary', IN_EFFECT: 'success', EXPIRED: 'info', TERMINATED: 'info', VOID: 'info' }

const auth = useAuth()
const route = useRoute()
const rd = useRefData()
const canEdit = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'PRODUCTION', 'QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const canTerminate = computed(() => auth.hasAny('ADMIN', 'QSO'))
const endOfToday = new Date(`${today()}T23:59:59`)
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }

const data = ref(null)
const orders = ref([])
const tab = ref(route.query.tab === 'orders' ? 'orders' : 'contracts')
const uncovered = computed(() => orders.value.filter((o) => !o.covered).length)
const loader = useLoader(async () => {
  const [o, os] = await Promise.all([http.get('/oem-contracts'), http.get('/oem-contracts/orders')])
  data.value = o
  orders.value = os
})
const load = () => loader.run()
onMounted(() => { load(); rd.ready(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const clauseLabel = (code) => data.value?.clauses.find((c) => c.code === code)?.label || code

// 登记、修改
const ed = reactive({ visible: false, saving: false, id: null, no: '', factoryId: null, extNo: '', title: '', signedOn: '', validFrom: '', validTo: '', productIds: [], rmSupply: '', pkSupply: '', otherTerms: '' })
async function openCreate() {
  await rd.ready()
  const d = new Date(`${today()}T00:00:00`)
  d.setFullYear(d.getFullYear() + 1)
  d.setDate(d.getDate() - 1)
  const to = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const oems = rd.oems.value
  Object.assign(ed, { visible: true, saving: false, id: null, no: '', factoryId: oems.length === 1 ? oems[0].id : null, extNo: '', title: '委托加工合同及质量协议',
    signedOn: today(), validFrom: today(), validTo: to, productIds: [], rmSupply: 'MIXED', pkSupply: 'MIXED', otherTerms: '' })
}
async function openEdit(row) {
  await rd.ready()
  Object.assign(ed, { visible: true, saving: false, id: row.id, no: row.contractNo, factoryId: row.factoryId, extNo: row.extNo || '', title: row.title,
    signedOn: row.signedOn, validFrom: row.validFrom, validTo: row.validTo, productIds: row.products.map((p) => p.id), rmSupply: row.rmSupply,
    pkSupply: row.pkSupply, otherTerms: row.otherTerms || '' })
}
async function submitEdit() {
  if (!ed.factoryId || !ed.title.trim() || !ed.signedOn || !ed.validFrom || !ed.validTo) return ElMessage.warning('请选择受托生产企业，填写合同名称、签订日期和期限')
  if (!ed.productIds.length) return ElMessage.warning('请至少选一个委托生产的成品')
  if (!ed.rmSupply || !ed.pkSupply) return ElMessage.warning('请选择原料、包材分别由谁采购')
  ed.saving = true
  const body = { factoryId: ed.factoryId, extNo: ed.extNo, title: ed.title, signedOn: ed.signedOn, validFrom: ed.validFrom, validTo: ed.validTo,
    productIds: ed.productIds, rmSupply: ed.rmSupply, pkSupply: ed.pkSupply, otherTerms: ed.otherTerms }
  try {
    const c = ed.id ? await http.put(`/oem-contracts/${ed.id}`, body) : await http.post('/oem-contracts', body)
    ElMessage.success(ed.id ? `合同 ${c.contractNo} 已修改` : `合同 ${c.contractNo} 已登记，请上传扫描件后提交签批`)
    ed.visible = false
    tab.value = 'contracts'
    await load()
    openDetail(c)
  } finally {
    ed.saving = false
  }
}

// 详情、提交、签批
const dt = reactive({ visible: false, row: null, approve: false, checked: [], password: '', saving: false })
function openDetail(row, approve = false) {
  Object.assign(dt, { visible: true, row, approve: approve && isQso.value && row.status === 'PENDING', checked: [], password: '', saving: false })
}
async function submitForApproval(row) {
  dt.saving = true
  try {
    const c = await http.post(`/oem-contracts/${row.id}/submit`)
    ElMessage.success(`合同 ${c.contractNo} 已提交，等质量安全负责人签批`)
    dt.visible = false
  } finally {
    dt.saving = false
  }
}
async function submitApprove() {
  if (!dt.password) return ElMessage.warning('请输入签批密码')
  dt.saving = true
  try {
    const c = await http.post(`/oem-contracts/${dt.row.id}/approve`, { checked: dt.checked, password: dt.password })
    ElMessage.success(`合同 ${c.contractNo} 已签批生效`)
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
  const note = await askReason('退回后合同回到草稿，登记人改好后重新提交。', `退回 ${row.contractNo}`, '退回')
  if (!note) return
  await http.post(`/oem-contracts/${row.id}/reject`, { note })
  ElMessage.success('已退回')
  dt.visible = false
}
async function terminate(row) {
  const note = await askReason('终止前已下的委托单仍按这份合同执行，之后下的单不再有合同依据。', `终止 ${row.contractNo}`, '终止合同')
  if (!note) return
  await http.post(`/oem-contracts/${row.id}/terminate`, { note })
  ElMessage.success('合同已终止')
  dt.visible = false
}
async function voidDraft(row) {
  const note = await askReason('作废后不能恢复，记录会保留。', `作废 ${row.contractNo}`, '作废')
  if (!note) return
  await http.post(`/oem-contracts/${row.id}/void`, { note })
  ElMessage.success('已作废')
  dt.visible = false
}
</script>

<style scoped>
.rule-line { margin: 0 0 8px; }
.pad { padding: 8px 12px 0; margin: 0; }
.summary-line { margin: 0 0 8px; line-height: 1.8; }
.form-row { display: flex; flex-wrap: wrap; gap: 0 12px; }
.form-row > * { flex: 1 1 160px; }
.warn { color: var(--el-color-danger); }
.clauses { margin: 4px 0 12px; padding-left: 20px; }
.clause-group { display: flex; flex-direction: column; align-items: flex-start; }
</style>
