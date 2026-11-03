<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>质量放行</h2>
        <p>物料须检验放行后才能发料；成品在受托厂出厂放行之后，还须由品牌方质量安全负责人审核签批才可上市销售。每次签批都要再次输入本人密码，并记录签批人、时间和理由。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never" body-style="padding:0">
      <template #header><b>物料待检</b> <span class="muted small">{{ q.quarantine.length }} 个批次</span></template>
      <el-table :data="q.quarantine" empty-text="没有待检物料">
        <el-table-column label="批号" width="150"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
        <el-table-column prop="itemName" label="物料" min-width="130" />
        <el-table-column label="供应商 / 来料批号" min-width="190"><template #default="{ row }">{{ row.partnerName }} <span class="mono muted small">{{ row.extBatchNo }}</span></template></el-table-column>
        <el-table-column label="数量" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qtyIn) }} {{ row.unit }}</span></template></el-table-column>
        <el-table-column label="生产 → 有效期" width="200"><template #default="{ row }"><span class="mono small">{{ fmtDate(row.mfgDate) }} → {{ fmtDate(row.expDate) }}</span></template></el-table-column>
        <el-table-column label="" width="110" fixed="right">
          <template #default="{ row }"><el-button size="small" type="primary" :disabled="!canQc" @click="openQc(row)">检验放行</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" body-style="padding:0">
      <template #header>
        <b>成品待上市放行</b> <span class="muted small">{{ q.pendingRelease.length }} 个批次</span>
        <span v-if="!canRelease" class="muted small">　只有质量安全负责人可以签批上市放行</span>
      </template>
      <el-table :data="q.pendingRelease" empty-text="没有待放行的成品批次">
        <el-table-column label="批号" width="150"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
        <el-table-column prop="itemName" label="成品" min-width="150" />
        <el-table-column prop="partnerName" label="生产方" min-width="130" />
        <el-table-column label="数量" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qtyIn) }}</span></template></el-table-column>
        <el-table-column label="受托厂放行" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.oemReleased" type="success" size="small" disable-transitions>已出厂放行</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="生产 → 有效期" width="200"><template #default="{ row }"><span class="mono small">{{ fmtDate(row.mfgDate) }} → {{ fmtDate(row.expDate) }}</span></template></el-table-column>
        <el-table-column label="" width="130" fixed="right">
          <template #default="{ row }"><el-button size="small" type="primary" :disabled="!canRelease" @click="openRelease(row)">上市放行审核</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="q.frozen.length" shadow="never" body-style="padding:0">
      <template #header><b>已冻结批次</b></template>
      <el-table :data="q.frozen">
        <el-table-column label="批号" width="150"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
        <el-table-column prop="itemName" label="物料 / 成品" min-width="150" />
        <el-table-column label="当前库存" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.onHand) }}</span></template></el-table-column>
        <el-table-column prop="freezeReason" label="冻结原因" min-width="200" />
        <el-table-column label="" width="90" fixed="right">
          <template #default="{ row }"><el-button size="small" :disabled="!canRelease" @click="openUnfreeze(row)">解冻</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" body-style="padding:0">
      <template #header><b>近期签批记录</b></template>
      <el-table :data="q.recent" empty-text="还没有签批记录">
        <el-table-column label="批号" width="150"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
        <el-table-column prop="itemName" label="物料 / 成品" min-width="140" />
        <el-table-column prop="stageLabel" label="环节" width="120" />
        <el-table-column label="结论" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.decision === 'RELEASED' ? 'success' : 'danger'" disable-transitions>{{ row.decisionLabel }}</el-tag></template>
        </el-table-column>
        <el-table-column label="报告" width="150"><template #default="{ row }"><span class="mono small">{{ row.reportNo || '—' }}</span></template></el-table-column>
        <el-table-column prop="signedByName" label="签批人" width="150" />
        <el-table-column label="时间" width="140"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.signedAt) }}</span></template></el-table-column>
        <el-table-column prop="note" label="说明" min-width="200" />
      </el-table>
    </el-card>

    <!-- 物料检验 -->
    <el-dialog v-model="qc.visible" :title="`物料检验放行：${qc.batch?.batchNo || ''}`" width="520px">
      <el-descriptions v-if="qc.batch" :column="1" size="small" border>
        <el-descriptions-item label="物料">{{ qc.batch.itemName }}（{{ qc.batch.itemCode }}）</el-descriptions-item>
        <el-descriptions-item label="供应商">{{ qc.batch.partnerName }} · 来料批号 {{ qc.batch.extBatchNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ fmtQty(qc.batch.qtyIn) }} {{ qc.batch.unit }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-position="top" style="margin-top:12px" @submit.prevent>
        <el-form-item label="结论">
          <el-radio-group id="qc-decision" v-model="qc.decision">
            <el-radio value="RELEASED">合格，放行</el-radio>
            <el-radio value="REJECTED">不合格，拒收</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="检验报告编号"><el-input id="qc-report" v-model="qc.reportNo" placeholder="内部检验或供应商 COA 编号" /></el-form-item>
        <el-form-item label="签批说明"><el-input id="qc-note" v-model="qc.note" type="textarea" :rows="2" placeholder="例如：外观、气味、含量检测符合内控标准" /></el-form-item>
        <el-form-item label="签批密码（本人登录密码）"><el-input id="qc-password" v-model="qc.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="qc.visible = false">取消</el-button>
        <el-button type="primary" :loading="qc.saving" @click="submitQc">签批</el-button>
      </template>
    </el-dialog>

    <!-- 上市放行 -->
    <el-dialog v-model="rel.visible" :title="`成品上市放行：${rel.batch?.batchNo || ''}`" width="640px">
      <el-descriptions v-if="rel.batch" :column="1" size="small" border>
        <el-descriptions-item label="成品">{{ rel.batch.itemName }}</el-descriptions-item>
        <el-descriptions-item label="生产方">{{ rel.batch.partnerName }} · 生产单 {{ rel.batch.sourceNo }}</el-descriptions-item>
        <el-descriptions-item label="数量 / 效期">{{ fmtQty(rel.batch.qtyIn) }} · {{ fmtDate(rel.batch.mfgDate) }} 至 {{ fmtDate(rel.batch.expDate) }}</el-descriptions-item>
        <el-descriptions-item label="批次追溯"><BatchNo :no="rel.batch.batchNo" /> <span class="muted small">点击查看所用物料批次</span></el-descriptions-item>
      </el-descriptions>
      <el-form label-position="top" style="margin-top:12px" @submit.prevent>
        <el-form-item label="放行检查">
          <div class="checks">
            <el-checkbox v-if="rel.batch?.oemReleased" id="rel-c1" v-model="rel.oemReleaseReviewed">受托厂出厂放行记录已审核</el-checkbox>
            <el-checkbox id="rel-c2" v-model="rel.inspectionPassed">出厂检验合格，报告已审核</el-checkbox>
            <el-checkbox id="rel-c3" v-model="rel.recordsComplete">批生产记录完整，物料平衡在限度内</el-checkbox>
            <el-checkbox id="rel-c4" v-model="rel.consistentWithFiling">配方、工艺、标签与注册备案资料一致</el-checkbox>
          </div>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="出厂检验报告编号"><el-input id="rel-report" v-model="rel.reportNo" /></el-form-item>
          <el-form-item label="留样数量"><el-input-number id="rel-rq" v-model="rel.retainQty" :min="0" :precision="0" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="留样地点"><el-input id="rel-rl" v-model="rel.retainLocation" placeholder="如：品质部留样室 A-03" /></el-form-item>
        </div>
        <p class="muted small" style="margin-top:-6px">留样数量至少为出厂检验需求量的 2 倍，保存至产品有效期届满后 6 个月。</p>
        <el-form-item label="结论">
          <el-radio-group id="rel-decision" v-model="rel.decision">
            <el-radio value="RELEASED">准予上市放行</el-radio>
            <el-radio value="REJECTED">不予放行</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="签批说明"><el-input id="rel-note" v-model="rel.note" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="签批密码（本人登录密码）"><el-input id="rel-password" v-model="rel.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rel.visible = false">取消</el-button>
        <el-button type="primary" :loading="rel.saving" @click="submitRelease">签批</el-button>
      </template>
    </el-dialog>

    <!-- 解冻 -->
    <el-dialog v-model="unf.visible" :title="`解冻：${unf.batch?.batchNo || ''}`" width="460px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="解冻原因"><el-input id="unf-reason" v-model="unf.reason" type="textarea" :rows="2" placeholder="例如：调查结论为非产品质量问题" /></el-form-item>
        <el-form-item label="签批密码"><el-input id="unf-password" v-model="unf.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="unf.visible = false">取消</el-button>
        <el-button type="primary" :loading="unf.saving" @click="submitUnfreeze">解冻</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import { useAuth } from '../stores/auth'
import { fmtDate, fmtQty, fmtTime } from '../utils/format'

const auth = useAuth()
const loading = ref(false)
const q = reactive({ quarantine: [], pendingRelease: [], frozen: [], recent: [] })
const canQc = computed(() => auth.hasAny('QA', 'QSO'))
const canRelease = computed(() => auth.hasAny('QSO'))

const qc = reactive({ visible: false, saving: false, batch: null, decision: 'RELEASED', reportNo: '', note: '', password: '' })
const rel = reactive({
  visible: false, saving: false, batch: null, decision: 'RELEASED', reportNo: '', note: '', password: '',
  oemReleaseReviewed: false, inspectionPassed: false, recordsComplete: false, consistentWithFiling: false,
  retainQty: null, retainLocation: ''
})
const unf = reactive({ visible: false, saving: false, batch: null, reason: '', password: '' })

async function load() {
  loading.value = true
  try {
    Object.assign(q, await http.get('/quality/queue'))
  } finally {
    loading.value = false
  }
}

function openQc(batch) {
  Object.assign(qc, { visible: true, batch, decision: 'RELEASED', reportNo: '', note: '', password: '' })
}

async function submitQc() {
  if (!qc.reportNo.trim() || !qc.note.trim() || !qc.password) return ElMessage.warning('请填写报告编号、签批说明和签批密码')
  qc.saving = true
  try {
    await http.post(`/quality/batches/${qc.batch.id}/material-decision`, {
      decision: qc.decision, reportNo: qc.reportNo, note: qc.note, password: qc.password
    })
    ElMessage.success(qc.decision === 'RELEASED' ? '已放行，可用于发料' : '已判定不合格')
    qc.visible = false
    load()
  } finally {
    qc.saving = false
  }
}

function openRelease(batch) {
  Object.assign(rel, {
    visible: true, batch, decision: 'RELEASED', reportNo: '', note: '', password: '',
    oemReleaseReviewed: false, inspectionPassed: false, recordsComplete: false, consistentWithFiling: false,
    retainQty: null, retainLocation: ''
  })
}

async function submitRelease() {
  if (!rel.note.trim() || !rel.password) return ElMessage.warning('请填写签批说明和签批密码')
  rel.saving = true
  try {
    const { visible, saving, batch, ...body } = rel
    await http.post(`/quality/batches/${rel.batch.id}/market-release`, body)
    ElMessage.success(rel.decision === 'RELEASED' ? '已上市放行，可以出库' : '已判定不予放行')
    rel.visible = false
    load()
  } finally {
    rel.saving = false
  }
}

function openUnfreeze(batch) {
  Object.assign(unf, { visible: true, batch, reason: '', password: '' })
}

async function submitUnfreeze() {
  if (!unf.reason.trim() || !unf.password) return ElMessage.warning('请填写解冻原因和签批密码')
  unf.saving = true
  try {
    await http.post(`/quality/batches/${unf.batch.id}/unfreeze`, { reason: unf.reason, password: unf.password })
    ElMessage.success('已解冻')
    unf.visible = false
    load()
  } finally {
    unf.saving = false
  }
}

onMounted(load)
</script>

<style scoped>
.checks { display: grid; gap: 4px; }
</style>
