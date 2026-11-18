<template>
  <div class="panel">
    <div v-if="!panel" class="muted small">正在读取检验记录…</div>
    <template v-else-if="!panel.spec">
      <p class="muted small none">这个物料没有生效的质量标准，按检验报告编号放行。</p>
    </template>
    <template v-else>
      <div class="head">
        <b>检验记录</b>
        <span class="muted small">按 {{ panel.spec.specNo }}（V{{ panel.spec.versionNo }}）</span>
        <el-button v-if="canRecord" size="small" type="primary" plain @click="openRecord">录入检验结果</el-button>
      </div>
      <el-alert v-if="panel.blocker" type="warning" :closable="false" show-icon :title="panel.blocker" class="blocker" />
      <div v-for="i in panel.inspections" :key="i.id" class="row" :class="{ voided: i.status === 'VOID' }">
        <span class="mono">{{ i.inspectionNo }}</span>
        <el-tag v-if="i.status === 'VOID'" type="info" size="small" disable-transitions>已作废</el-tag>
        <el-tag v-else :type="i.passed ? 'success' : 'danger'" size="small" disable-transitions>{{ i.passed ? '合格' : '不合格' }}</el-tag>
        <span class="muted small">{{ i.inspectedDate }} · {{ i.inspector }}<template v-if="i.specVersion !== panel.spec.versionNo"> · 按 V{{ i.specVersion }}</template></span>
        <router-link v-if="i.deviationNo" to="/qms" class="mono small">{{ i.deviationNo }}</router-link>
        <el-button v-if="canVoid && i.status === 'VALID'" size="small" text @click="voidOne(i)">作废</el-button>
        <div class="small results">
          <span v-for="r in i.results" :key="r.lineId" :class="{ late: !r.passed }">{{ lineName(r.lineId) }} {{ shown(r) }}{{ r.source === 'COA' ? '（供方报告）' : '' }}{{ r.passed ? '' : '（不合格）' }}</span>
        </div>
        <div v-if="i.coaNo || i.sampleNote" class="muted small extra">
          <span v-if="i.coaNo">供方报告 <span class="mono">{{ i.coaNo }}</span></span>
          <span v-if="i.sampleNote">取样：{{ i.sampleNote }}</span>
        </div>
        <div v-if="i.note || i.voidReason" class="muted small">{{ i.voidReason ? `作废原因：${i.voidReason}` : i.note }}</div>
      </div>
      <p v-if="!panel.inspections.length" class="muted small none">还没有检验记录。</p>
    </template>

    <el-dialog v-model="rc.visible" :title="`录入检验结果：${batchNo}`" width="560px" :close-on-click-modal="false" append-to-body>
      <el-form v-if="panel?.spec" label-position="top" @submit.prevent>
        <el-form-item label="检验日期"><el-date-picker id="qi-date" v-model="rc.date" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
        <el-form-item :label="sampleRequired ? '取样情况（必填）' : '取样情况（选填）'">
          <div v-if="panel.spec.samplingPlan" class="muted small field-hint">标准的取样要求：{{ panel.spec.samplingPlan }}</div>
          <el-input id="qi-sample" v-model="rc.sampleNote" maxlength="200" placeholder="取了多少、从哪里取、谁取的" />
        </el-form-item>
        <el-alert v-if="hasCoaLines && panel.coaBlocker" type="warning" :closable="false" show-icon :title="`这批不能采用供方报告：${panel.coaBlocker}`" class="coa-alert" />
        <div v-for="l in rc.lines" :key="l.line.id" class="result-line">
          <div class="result-name"><b>{{ l.line.name }}</b> <span class="muted small">{{ l.line.limitText || l.line.criteria || '' }}<template v-if="l.line.method"> · {{ l.line.method }}</template></span></div>
          <template v-if="l.line.judgedBySystem">
            <el-input-number v-model="l.value" :controls="false" :aria-label="`${l.line.name}的实测值`" />
            <el-tag v-if="l.value != null" :type="failed(l) ? 'danger' : 'success'" size="small" disable-transitions>{{ failed(l) ? '超限' : '合格' }}</el-tag>
          </template>
          <template v-else>
            <el-input v-model="l.text" maxlength="200" placeholder="结果" :aria-label="`${l.line.name}的结果`" class="result-text" />
            <el-radio-group v-model="l.passed" :aria-label="`${l.line.name}是否合格`">
              <el-radio-button :value="true">合格</el-radio-button>
              <el-radio-button :value="false">不合格</el-radio-button>
            </el-radio-group>
          </template>
          <el-checkbox v-if="l.line.coaAllowed && !panel.coaBlocker" v-model="l.fromCoa" :aria-label="`${l.line.name}采用供方报告`">采用供方报告</el-checkbox>
        </div>
        <div v-if="anyCoa" class="coa-box">
          <el-form-item label="供方检验报告编号">
            <el-input id="qi-coa" v-model="rc.coaNo" maxlength="60" :placeholder="panel.supplierName ? `${panel.supplierName} 报告上的编号` : '报告上的编号'" />
          </el-form-item>
          <AttachmentList owner-type="BATCH" :owner-id="batchId" :can-upload="auth.canAttach()" title="这个批次的附件" empty-text="批次上还没有附件"
                          notice="采用供方报告要有报告扫描件：传到这里；采购单或委托单上已有附件的（供应商、受托厂在门户里传的）不用再传。" />
        </div>
        <el-alert v-if="anyFailed" type="error" :closable="false" show-icon title="有不合格项，保存后会自动生成重大偏差，请在备注里写明情况。" />
        <el-form-item :label="noteLabel" style="margin-top:12px"><el-input id="qi-note" v-model="rc.note" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        <p class="muted small">结果提交后不能改。录错了请质量安全负责人作废后重录。</p>
      </el-form>
      <template #footer>
        <el-button @click="rc.visible = false">取消</el-button>
        <el-button type="primary" :loading="rc.saving" @click="submit">保存检验结果</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from './AttachmentList.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, startOfBusinessDay, today } from '../utils/format'
import { latestOnly } from '../utils/latest'

const props = defineProps({
  batchId: { type: Number, required: true },
  batchNo: { type: String, default: '' },
  canRecord: { type: Boolean, default: false },
  canVoid: { type: Boolean, default: false }
})
// saved：录入成功。弹窗里可能刚上传了报告扫描件，外面同一个批次的附件列表要跟着刷新
const emit = defineEmits(['loaded', 'saved'])

const auth = useAuth()
const panel = ref(null)
const startOfToday = startOfBusinessDay()
const req = latestOnly()

async function load() {
  const current = req()
  panel.value = null
  emit('loaded', null)
  const p = await http.get(`/quality/batches/${props.batchId}/inspections`)
  if (!current()) return
  apply(p)
}
function apply(p) {
  panel.value = p
  emit('loaded', p)
}
watch(() => props.batchId, load, { immediate: true })

const lineName = (id) => panel.value?.spec?.lines.find((l) => l.id === id)?.name || '已替代的项目'
const lineOf = (id) => panel.value?.spec?.lines.find((l) => l.id === id)
const shown = (r) => [r.value != null ? `${fmtQty(r.value)}${lineOf(r.lineId)?.unit ? ` ${lineOf(r.lineId).unit}` : ''}` : '', r.text || ''].filter(Boolean).join(' ')

const rc = reactive({ visible: false, saving: false, date: '', note: '', sampleNote: '', coaNo: '', lines: [] })
function openRecord() {
  Object.assign(rc, { visible: true, saving: false, date: today(), note: '', sampleNote: '', coaNo: panel.value.defaultCoaNo || '', lines: panel.value.spec.lines.map((line) => ({ line, value: undefined, text: '', passed: null, fromCoa: false })) })
}
const hasCoaLines = computed(() => !!panel.value?.spec?.lines.some((l) => l.coaAllowed))
const anyCoa = computed(() => rc.lines.some((l) => l.fromCoa))
// 标准写了取样要求，而且有项目是自检的，就要写取样情况；全部采用供方报告时不用取样
const sampleRequired = computed(() => !!panel.value?.spec?.samplingPlan && rc.lines.some((l) => !l.fromCoa))
function failed(l) {
  if (!l.line.judgedBySystem) return l.passed === false
  if (l.value == null) return false
  return (l.line.minValue != null && l.value < Number(l.line.minValue)) || (l.line.maxValue != null && l.value > Number(l.line.maxValue))
}
const anyFailed = computed(() => rc.lines.some(failed))
const hasFailedBefore = computed(() => panel.value?.inspections.some((i) => i.status === 'VALID' && !i.passed))
const noteLabel = computed(() => {
  if (anyFailed.value) return '情况说明（必填）'
  return hasFailedBefore.value ? '复检原因和依据（必填）' : '备注（选填）'
})
async function submit() {
  if (!rc.date) return ElMessage.warning('请选择检验日期')
  for (const l of rc.lines) {
    if (l.line.judgedBySystem && l.value == null) return ElMessage.warning(`请填写“${l.line.name}”的实测值`)
    if (!l.line.judgedBySystem && (!l.text.trim() || l.passed === null)) return ElMessage.warning(`请填写“${l.line.name}”的结果并判定是否合格`)
  }
  if (sampleRequired.value && !rc.sampleNote.trim()) return ElMessage.warning('标准有取样要求，请填写取样情况')
  if (anyCoa.value && !rc.coaNo.trim()) return ElMessage.warning('请填写供方检验报告编号')
  if ((anyFailed.value || hasFailedBefore.value) && !rc.note.trim()) return ElMessage.warning(anyFailed.value ? '有不合格项，请写明情况' : '复检请写明原因和依据')
  rc.saving = true
  try {
    const results = rc.lines.map((l) => ({ lineId: l.line.id, value: l.value ?? null, text: l.text, passed: l.passed, source: l.fromCoa ? 'COA' : 'TEST' }))
    const p = await http.post(`/quality/batches/${props.batchId}/inspections`, { inspectedDate: rc.date, results, note: rc.note, sampleNote: rc.sampleNote, coaNo: anyCoa.value ? rc.coaNo : null })
    const dev = p.inspections[0]?.deviationNo
    ElMessage[dev ? 'warning' : 'success'](dev ? `检验不合格，已生成偏差 ${dev}` : '检验结果已保存')
    rc.visible = false
    apply(p)
    emit('saved')
  } finally {
    rc.saving = false
  }
}

async function voidOne(i) {
  let note
  try {
    note = (await ElMessageBox.prompt('作废后记录保留，可以重新录入。', `作废检验记录 ${i.inspectionNo}`, { confirmButtonText: '作废', cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  apply(await http.post(`/quality/batches/${props.batchId}/inspections/${i.id}/void`, { note }))
  ElMessage.success('已作废')
}

defineExpose({ reload: load })
</script>

<style scoped>
.panel { margin-top: 12px; padding: 10px 12px; border-radius: 12px; background: var(--lg-tint); }
.head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.head .el-button { margin-left: auto; }
.blocker { margin-top: 8px; }
.row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px; padding: 8px 0; border-top: 1px solid var(--el-border-color-lighter); }
.row:first-of-type { border-top: 0; }
.row.voided { opacity: 0.65; }
.results, .extra { flex: 1 1 100%; display: flex; flex-wrap: wrap; gap: 4px 14px; }
.coa-alert { margin-bottom: 8px; }
.field-hint { flex: 1 1 100%; line-height: 1.5; margin-bottom: 4px; }
.coa-box { margin-top: 10px; padding: 10px 12px; border-radius: 12px; background: var(--lg-tint); }
.none { margin: 6px 0 0; }
.late { color: var(--el-color-danger); }
.result-line { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px 0; border-top: 1px solid var(--el-border-color-lighter); }
.result-name { flex: 1 1 100%; }
.result-text { flex: 1 1 160px; }
</style>
