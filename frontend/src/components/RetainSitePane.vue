<template>
  <div>
    <p class="muted small intro">
      对应检查要点委托生产版序号 15（GMP 第五十六条第一、二款）：委托方在住所或主要经营场所留样，也可以在住所或主要经营场所所在地的其他经营场所留样。
      留样地点不是住所或主要经营场所的，要在首次留样之日起 20 个工作日内，把留样地点的地址等信息报告所在地负责药品监督管理的部门。
      上市放行时选留样地点，不选的用默认地点；截止日按周一至周五估算，没有扣除法定节假日，实际截止日可能稍晚。
    </p>
    <div class="toolbar-row">
      <el-button v-if="canEdit" type="primary" @click="openSite()">登记留样地点</el-button>
    </div>
    <el-card shadow="never" body-style="padding:0 0 8px">
      <el-table :data="sites" row-key="id" empty-text="还没有登记留样地点：请登记公司住所、主要经营场所或所在地的其他经营场所" scrollbar-tabindex="0">
        <el-table-column label="留样地点" min-width="220">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <el-tag v-if="row.defaultSite" size="small" type="primary" disable-transitions class="tag">默认</el-tag>
            <el-tag v-if="!row.active" size="small" type="info" disable-transitions class="tag">已停用</el-tag>
            <div class="muted small"><span class="mono">{{ row.code }}</span> · {{ row.kindLabel }}</div>
            <div class="small">{{ row.address }}</div>
          </template>
        </el-table-column>
        <el-table-column label="首次留样" width="120">
          <template #default="{ row }">
            <span class="mono">{{ row.firstRetainDate || '—' }}</span>
            <div v-if="row.usedSince" class="muted small">上线前 {{ row.usedSince }} 起</div>
          </template>
        </el-table-column>
        <el-table-column label="报告药监部门" min-width="230">
          <template #default="{ row }">
            <el-tag :type="STATE_TAG[row.state]" size="small" disable-transitions>{{ row.stateLabel }}</el-tag>
            <div v-if="row.deadline" class="small">截止 <span class="mono">{{ row.deadline }}</span></div>
            <div v-if="row.reportDate" class="small">{{ row.reportDate }} 报告{{ row.reportAuthority }}<span v-if="row.reportRef">，回执 {{ row.reportRef }}</span></div>
          </template>
        </el-table-column>
        <el-table-column label="留样（在库 / 全部）" width="140" align="right">
          <template #default="{ row }"><span class="mono">{{ row.heldCount }} / {{ row.sampleCount }}</span></template>
        </el-table-column>
        <el-table-column label="" width="200" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="canEdit" size="small" text @click="openSite(row)">修改</el-button>
            <el-button v-if="canEdit && row.kind === 'OTHER_PREMISES' && !row.reportDate" size="small" text type="primary" @click="openReport(row)">登记报告</el-button>
            <el-button v-if="canEdit && row.reportDate" size="small" text @click="voidReport(row)">作废报告</el-button>
            <el-button v-if="row.kind === 'OTHER_PREMISES'" size="small" text @click="openFiles(row)">回执</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 登记 / 修改留样地点 -->
    <el-dialog v-model="st.visible" :title="st.id ? `修改留样地点 ${st.code}` : '登记留样地点'" width="560px">
      <el-form label-position="top" @submit.prevent>
        <div class="form-row">
          <el-form-item label="地点编码"><el-input id="rs-code" v-model="st.code" maxlength="40" /></el-form-item>
          <el-form-item label="地点名称"><el-input id="rs-name" v-model="st.name" maxlength="100" placeholder="如：品质部留样室" /></el-form-item>
        </div>
        <el-form-item label="地点类型">
          <el-radio-group id="rs-kind" v-model="st.kind" :disabled="st.fixed">
            <el-radio v-for="k in kinds" :key="k.code" :value="k.code">{{ k.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="地址（和营业执照、报告药监部门的一致）"><el-input id="rs-address" v-model="st.address" maxlength="300" :disabled="st.fixed" /></el-form-item>
        <p v-if="st.fixed" class="muted small hint">这个地点已经存放过留样或报告过，地址和类型不能改。搬迁或报告的信息有变的，请新登记一个地点，把这个地点停用。</p>
        <el-form-item v-if="st.kind === 'OTHER_PREMISES'">
          <el-checkbox id="rs-local" v-model="st.sameLocality" :disabled="st.fixed">在住所或主要经营场所所在地（不在所在地的不能作为留样地点）</el-checkbox>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="上线前开始在这里留样的日期（选填）">
            <el-date-picker id="rs-since" v-model="st.usedSince" value-format="YYYY-MM-DD" :disabled-date="(d) => d > endOfToday" style="width:100%" />
          </el-form-item>
          <el-form-item label="放行时">
            <el-checkbox id="rs-default" v-model="st.defaultSite" :disabled="!st.active">设为默认地点</el-checkbox>
          </el-form-item>
        </div>
        <el-form-item v-if="st.id" label="状态"><el-switch id="rs-active" v-model="st.active" active-text="在用" inactive-text="停用" /></el-form-item>
        <el-form-item label="备注"><el-input id="rs-note" v-model="st.note" maxlength="300" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="st.visible = false">取消</el-button>
        <el-button type="primary" :loading="st.saving" @click="saveSite">保存留样地点</el-button>
      </template>
    </el-dialog>

    <!-- 登记报告 -->
    <el-dialog v-model="rp.visible" :title="`登记报告：${rp.row?.name || ''}`" width="520px">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small hint">
          <template v-if="rp.row?.firstRetainDate">{{ rp.row.firstRetainDate }} 首次留样，报告截止 {{ rp.row.deadline }}（按周一至周五估算）。</template>
          <template v-else>这个地点还没有留样，可以先报告。</template>
          报告回执可以在登记后点“回执”上传。
        </p>
        <div class="form-row">
          <el-form-item label="报告日期"><el-date-picker id="rp-date" v-model="rp.reportDate" value-format="YYYY-MM-DD" :disabled-date="(d) => d > endOfToday" style="width:100%" /></el-form-item>
          <el-form-item label="回执或受理编号（选填）"><el-input id="rp-ref" v-model="rp.ref" maxlength="60" /></el-form-item>
        </div>
        <el-form-item label="受理报告的药品监督管理部门"><el-input id="rp-authority" v-model="rp.authority" maxlength="100" placeholder="如：某某区市场监督管理局" /></el-form-item>
        <el-form-item label="说明（选填）"><el-input id="rp-note" v-model="rp.note" maxlength="300" placeholder="如：通过政务服务网报告，报告了地址和联系人" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rp.visible = false">取消</el-button>
        <el-button type="primary" :loading="rp.saving" @click="saveReport">登记报告</el-button>
      </template>
    </el-dialog>

    <!-- 回执 -->
    <el-dialog v-model="fl.visible" :title="`报告回执：${fl.row?.name || ''}`" width="560px">
      <AttachmentList v-if="fl.row" owner-type="RETAIN_SITE" :owner-id="fl.row.id" :can-upload="canEdit" />
      <template #footer><el-button @click="fl.visible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from './AttachmentList.vue'
import { useAuth } from '../stores/auth'
import { today } from '../utils/format'

defineProps({ sites: { type: Array, default: () => [] } })
const emit = defineEmits(['changed'])
const STATE_TAG = { NOT_REQUIRED: 'success', NOT_ALLOWED: 'danger', NOT_USED: 'info', PENDING: 'warning', OVERDUE: 'danger', REPORTED: 'success', REPORTED_LATE: 'warning' }
const auth = useAuth()
const canEdit = computed(() => auth.hasAny('QA', 'QSO'))
const endOfToday = new Date(`${today()}T23:59:59`)
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const kinds = ref([])
let kindsLoaded = null
const loadKinds = () => (kindsLoaded ||= http.get('/qms/retain-site-kinds').then((k) => { kinds.value = k }).catch((e) => { kindsLoaded = null; throw e }))

const st = reactive({ visible: false, saving: false, id: null, fixed: false, code: '', name: '', address: '', kind: 'DOMICILE', sameLocality: false, usedSince: null, defaultSite: false, active: true, note: '' })
async function openSite(row) {
  await loadKinds()
  if (row) {
    Object.assign(st, { visible: true, saving: false, id: row.id, fixed: row.fixed, code: row.code, name: row.name, address: row.address, kind: row.kind,
      sameLocality: row.sameLocality, usedSince: row.usedSince, defaultSite: row.defaultSite, active: row.active, note: row.note || '' })
  } else {
    Object.assign(st, { visible: true, saving: false, id: null, fixed: false, code: '', name: '', address: '', kind: 'DOMICILE', sameLocality: false, usedSince: null,
      defaultSite: false, active: true, note: '' })
  }
}
async function saveSite() {
  if (!st.code.trim() || !st.name.trim() || !st.address.trim()) return ElMessage.warning('请填写地点编码、名称和地址')
  if (st.kind === 'OTHER_PREMISES' && !st.sameLocality) return ElMessage.warning('其他经营场所要在住所或主要经营场所所在地，请核对后勾选')
  st.saving = true
  const body = { code: st.code, name: st.name, address: st.address, kind: st.kind, sameLocality: st.sameLocality, usedSince: st.usedSince || null,
    active: st.id ? st.active : null, defaultSite: st.id ? st.defaultSite : (st.defaultSite || null), note: st.note }
  try {
    const r = st.id ? await http.put(`/qms/retain-sites/${st.id}`, body) : await http.post('/qms/retain-sites', body)
    ElMessage.success(`${r.name}：${r.stateLabel}${r.deadline && !r.reportDate ? `，报告截止 ${r.deadline}` : ''}`)
    st.visible = false
    emit('changed')
  } finally {
    st.saving = false
  }
}

const rp = reactive({ visible: false, saving: false, row: null, reportDate: '', authority: '', ref: '', note: '' })
function openReport(row) {
  Object.assign(rp, { visible: true, saving: false, row, reportDate: today(), authority: '', ref: '', note: '' })
}
async function saveReport() {
  if (!rp.reportDate || !rp.authority.trim()) return ElMessage.warning('请填写报告日期和受理的部门')
  rp.saving = true
  try {
    const r = await http.post(`/qms/retain-sites/${rp.row.id}/report`, { reportDate: rp.reportDate, authority: rp.authority, ref: rp.ref, note: rp.note })
    ElMessage.success(`${r.name}：${r.stateLabel}`)
    rp.visible = false
    emit('changed')
  } finally {
    rp.saving = false
  }
}

async function voidReport(row) {
  let reason
  try {
    reason = (await ElMessageBox.prompt(`${row.name}：${row.reportDate} 报告${row.reportAuthority}。作废后报告记录清空（审计日志保留），可以重新登记。`,
      '作废报告', { confirmButtonText: '作废报告', cancelButtonText: '不了', inputPlaceholder: '作废原因，如：报告日期录错', inputValidator: (v) => !!v?.trim() || '请写明作废原因' })).value
  } catch {
    return
  }
  await http.post(`/qms/retain-sites/${row.id}/void-report`, { reason })
  ElMessage.success('报告已作废')
  emit('changed')
}

const fl = reactive({ visible: false, row: null })
function openFiles(row) {
  Object.assign(fl, { visible: true, row })
}
</script>

<style scoped>
.intro { margin: 0 0 10px; line-height: 1.7; }
.toolbar-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.form-row { display: flex; flex-wrap: wrap; gap: 0 12px; }
.form-row > * { flex: 1 1 200px; }
.hint { margin: -6px 0 12px; }
.tag { margin-left: 6px; }
</style>
