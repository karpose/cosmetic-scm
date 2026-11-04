<template>
  <div v-if="rec" class="br">
    <div class="br-h">
      <h4 class="sec">批生产记录</h4>
      <el-button v-if="rec.hasRecord" size="small" :icon="Printer" @click="printIt">打印批记录</el-button>
    </div>
    <p v-if="!rec.hasRecord" class="muted small">
      {{ rec.moStatus === 'PLANNED' ? '发料后按工艺路线生成批生产记录。' : '这张单没有批生产记录：发料时成品还没有维护工艺路线。' }}
    </p>
    <template v-else>
      <el-alert v-if="rec.missing.length && rec.moStatus === 'ISSUED'" type="warning" :closable="false" show-icon title="完工入库前还需要完成" style="margin-bottom:10px">
        <div v-for="m in rec.missing" :key="m" class="small">{{ m }}</div>
      </el-alert>

      <div class="clr" :class="{ done: before }">
        <el-icon><component :is="before ? CircleCheckFilled : Clock" /></el-icon>
        <div class="grow"><b>开工前清场</b><div v-if="before" class="muted small">{{ before.area }} · {{ before.signedBy }} · {{ fmtTime(before.signedAt) }}<template v-if="before.prevProduct"> · 上批 {{ before.prevProduct }}</template></div></div>
        <el-button v-if="!before && running && canClear" size="small" type="primary" @click="openClearance('BEFORE')">确认清场</el-button>
      </div>

      <div v-for="s in rec.steps" :key="s.stepNo" class="stp" :class="s.status">
        <div class="sno mono">{{ s.stepNo }}</div>
        <div class="grow">
          <div><b>{{ s.name }}</b> <el-tag v-if="s.critical" type="warning" size="small" disable-transitions>关键</el-tag> <StatusTag :status="STEP_TAG[s.status]" :label="s.statusLabel" /></div>
          <div v-if="s.instruction" class="muted small">{{ s.instruction }}</div>
          <div v-if="s.ipcName" class="small">过程检验：{{ s.ipcName }}（{{ limits(s) }}）
            <template v-if="s.ipcValue != null"><b class="mono" :class="{ bad: s.ipcPass === false }">实测 {{ fmtQty(s.ipcValue) }} {{ s.ipcUnit || '' }}</b><el-tag v-if="s.ipcPass === false" type="danger" size="small" disable-transitions> 超限</el-tag></template>
          </div>
          <div v-if="s.deviationNo" class="small bad">已生成偏差 <router-link class="mono" to="/qms">{{ s.deviationNo }}</router-link>，关闭前这批不能上市放行</div>
          <div v-if="s.note" class="small">备注：{{ s.note }}</div>
          <div v-if="s.operator" class="muted small">{{ s.operator }} · {{ fmtTime(s.doneAt) }}<template v-if="s.reviewer"> ｜ 复核 {{ s.reviewer }} · {{ fmtTime(s.reviewedAt) }}</template></div>
        </div>
        <div v-if="running" class="ops">
          <el-button v-if="s.status === 'PENDING' && canOperate && s.stepNo === nextStep" size="small" type="primary" :disabled="!before" @click="openStep(s)">执行</el-button>
          <el-button v-if="s.status === 'DONE' && s.critical && canClear" size="small" @click="openReview(s)">复核</el-button>
        </div>
      </div>

      <div class="clr" :class="{ done: after }">
        <el-icon><component :is="after ? CircleCheckFilled : Clock" /></el-icon>
        <div class="grow"><b>完工后清场</b><div v-if="after" class="muted small">{{ after.area }} · {{ after.signedBy }} · {{ fmtTime(after.signedAt) }}</div></div>
        <el-button v-if="!after && running && canClear" size="small" :disabled="!allStepsDone" @click="openClearance('AFTER')">确认清场</el-button>
      </div>
    </template>

    <!-- 执行工序 -->
    <el-dialog v-model="st.visible" :title="`第 ${st.step?.stepNo} 道：${st.step?.name}`" width="460px" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <p v-if="st.step?.instruction" class="muted small" style="margin-top:0">{{ st.step.instruction }}</p>
        <el-form-item v-if="st.step?.ipcName" :label="`${st.step.ipcName} 实测值（${limits(st.step)}）`">
          <el-input-number id="st-value" v-model="st.ipcValue" :controls="false" style="width:100%" />
        </el-form-item>
        <el-alert v-if="outOfSpec" type="error" :closable="false" show-icon title="超出限度：会自动生成重大偏差，关闭前这批不能上市放行。请在备注里写明现场情况。" style="margin-bottom:12px" />
        <el-form-item :label="outOfSpec ? '备注（必填）' : '备注（选填）'"><el-input id="st-note" v-model="st.note" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        <p class="muted small" style="margin:0">记录提交后不能修改。</p>
      </el-form>
      <template #footer>
        <el-button @click="st.visible = false">取消</el-button>
        <el-button type="primary" :loading="st.saving" @click="saveStep">提交工序记录</el-button>
      </template>
    </el-dialog>

    <!-- 清场 -->
    <el-dialog v-model="cl.visible" :title="cl.phase === 'BEFORE' ? '开工前清场确认' : '完工后清场确认'" width="480px" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="生产区域或设备"><el-input id="cl-area" v-model="cl.area" maxlength="100" /></el-form-item>
        <el-form-item v-if="cl.phase === 'BEFORE'" label="上批产品（选填）"><el-input v-model="cl.prevProduct" maxlength="100" /></el-form-item>
        <el-form-item label="确认事项">
          <div class="checks">
            <el-checkbox v-model="cl.materialsCleared">{{ cl.phase === 'BEFORE' ? '上批' : '本批' }}物料、中间产品已清离现场</el-checkbox>
            <el-checkbox v-model="cl.equipmentCleaned">设备和工器具已清洁</el-checkbox>
            <el-checkbox v-model="cl.documentsCleared">{{ cl.phase === 'BEFORE' ? '上批' : '本批' }}文件和标识已清离现场</el-checkbox>
          </div>
        </el-form-item>
        <el-form-item label="签批密码"><el-input id="cl-pwd" v-model="cl.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cl.visible = false">取消</el-button>
        <el-button type="primary" :loading="cl.saving" @click="saveClearance">签批确认清场</el-button>
      </template>
    </el-dialog>

    <!-- 复核 -->
    <el-dialog v-model="rv.visible" :title="`复核第 ${rv.step?.stepNo} 道：${rv.step?.name}`" width="420px" append-to-body>
      <el-form label-position="top" @submit.prevent="saveReview">
        <p class="muted small" style="margin-top:0">操作人 {{ rv.step?.operator }}。复核要由操作人以外的人进行。</p>
        <el-form-item label="签批密码"><el-input id="rv-pwd" v-model="rv.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rv.visible = false">取消</el-button>
        <el-button type="primary" :loading="rv.saving" @click="saveReview">签批复核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheckFilled, Clock, Printer } from '@element-plus/icons-vue'
import http from '../api'
import StatusTag from './StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime } from '../utils/format'

const props = defineProps({ moId: { type: Number, required: true } })
const emit = defineEmits(['changed'])
const STEP_TAG = { PENDING: 'CLOSED', DONE: 'PARTIAL', REVIEWED: 'RELEASED' }

const auth = useAuth()
const router = useRouter()
const rec = ref(null)
const canOperate = computed(() => auth.hasAny('PRODUCTION'))
const canClear = computed(() => auth.hasAny('PRODUCTION', 'QA', 'QSO'))
const running = computed(() => rec.value?.moStatus === 'ISSUED')
const before = computed(() => rec.value?.clearances.find((c) => c.phase === 'BEFORE'))
const after = computed(() => rec.value?.clearances.find((c) => c.phase === 'AFTER'))
const nextStep = computed(() => rec.value?.steps.find((s) => s.status === 'PENDING')?.stepNo)
// 前面的关键工序没复核时，下一道工序的“执行”会被后端拦下并说明原因
const allStepsDone = computed(() => rec.value?.steps.every((s) => s.status === 'REVIEWED' || (s.status === 'DONE' && !s.critical)))

async function load() {
  rec.value = await http.get(`/mes/records/${props.moId}`)
}
const applied = (r) => {
  rec.value = r
  emit('changed')
}
function limits(s) {
  const u = s.ipcUnit ? ` ${s.ipcUnit}` : ''
  if (s.ipcMin != null && s.ipcMax != null) return `${fmtQty(s.ipcMin)}–${fmtQty(s.ipcMax)}${u}`
  return s.ipcMin != null ? `不低于 ${fmtQty(s.ipcMin)}${u}` : `不高于 ${fmtQty(s.ipcMax)}${u}`
}
const printIt = () => router.push({ path: '/batch-record', query: { mo: props.moId } })

const st = reactive({ visible: false, saving: false, step: null, ipcValue: undefined, note: '' })
const openStep = (step) => Object.assign(st, { visible: true, step, ipcValue: undefined, note: '' })
const outOfSpec = computed(() => {
  const s = st.step
  if (!s?.ipcName || st.ipcValue == null) return false
  return (s.ipcMin != null && st.ipcValue < Number(s.ipcMin)) || (s.ipcMax != null && st.ipcValue > Number(s.ipcMax))
})
async function saveStep() {
  st.saving = true
  try {
    const r = await http.post(`/mes/records/${props.moId}/steps/${st.step.stepNo}`, { ipcValue: st.ipcValue ?? null, note: st.note })
    applied(r)
    ElMessage.success(outOfSpec.value ? '已记录并生成偏差' : '工序已记录')
    st.visible = false
  } finally {
    st.saving = false
  }
}

const cl = reactive({ visible: false, saving: false, phase: 'BEFORE', area: '', prevProduct: '', materialsCleared: false, equipmentCleaned: false, documentsCleared: false, password: '' })
const openClearance = (phase) => Object.assign(cl, { visible: true, phase, area: before.value?.area || '', prevProduct: '', materialsCleared: false, equipmentCleaned: false, documentsCleared: false, password: '' })
async function saveClearance() {
  cl.saving = true
  try {
    const { visible, saving, ...body } = cl
    applied(await http.post(`/mes/records/${props.moId}/clearance`, body))
    ElMessage.success('清场已确认')
    cl.visible = false
  } finally {
    cl.saving = false
  }
}

const rv = reactive({ visible: false, saving: false, step: null, password: '' })
const openReview = (step) => Object.assign(rv, { visible: true, step, password: '' })
async function saveReview() {
  rv.saving = true
  try {
    applied(await http.post(`/mes/records/${props.moId}/steps/${rv.step.stepNo}/review`, { password: rv.password }))
    ElMessage.success('已复核')
    rv.visible = false
  } finally {
    rv.saving = false
  }
}

defineExpose({ load })
watch(() => props.moId, load)
onMounted(load)
</script>

<style scoped>
.sec { margin: 20px 0 10px; font-size: 14px; }
.br-h { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.clr, .stp { display: flex; gap: 10px; align-items: flex-start; padding: 10px 12px; border-radius: 12px; border: 1px solid var(--lg-stroke); background: var(--lg-fill); margin-bottom: 8px; font-size: 13px; }
.clr .el-icon { font-size: 18px; margin-top: 1px; color: var(--el-text-color-secondary); }
.clr.done .el-icon { color: var(--el-color-success); }
.sno { width: 24px; height: 24px; flex: none; border-radius: 50%; display: grid; place-items: center; background: var(--lg-surface-pop); font-size: 12px; }
.grow { flex: 1; min-width: 0; display: grid; gap: 3px; }
.ops { flex: none; }
.bad { color: var(--el-color-danger); }
.checks { display: grid; gap: 2px; }
</style>
