<template>
  <div class="print-page">
    <div class="bar no-print">
      <b>稳定性试验报告</b>
      <el-button type="primary" :icon="Printer" :disabled="!d" @click="print">打印</el-button>
      <el-button @click="$router.back()">返回</el-button>
    </div>
    <p v-if="error" class="no-print err">{{ error }}</p>
    <article v-if="d" class="doc">
      <p v-if="!approved" class="draft">{{ draftText }}</p>
      <h1>稳定性试验报告</h1>
      <table class="meta">
        <tbody>
          <tr><th>试验编号</th><td class="mono">{{ s.studyNo }}</td><th>状态</th><td>{{ s.statusLabel }}</td></tr>
          <tr><th>产品</th><td>{{ s.fgName }}（{{ s.fgCode }}）</td><th>配方版本</th><td>V{{ s.versionNo ?? '—' }}</td></tr>
          <tr><th>试验类型</th><td>{{ s.studyTypeLabel }}</td><th>储存条件</th><td>{{ s.condition }}</td></tr>
          <tr><th>样品</th><td>{{ s.batchNo ? `批号 ${s.batchNo}` : (s.sampleRef || '—') }}</td><th>开始日期</th><td>{{ s.startDate }}</td></tr>
          <tr><th>登记人</th><td>{{ s.createdBy }}</td><th>时间点</th><td>共 {{ s.pullsTotal }} 个，已完成 {{ done }} 个，未取样 {{ skipped }} 个</td></tr>
        </tbody>
      </table>

      <template v-for="(g, gi) in groups" :key="gi">
        <h2>检测结果<template v-if="groups.length > 1">（{{ g[0].label }} ~ {{ g[g.length - 1].label }}）</template></h2>
        <table class="results">
          <colgroup><col class="test"><col class="limit"><col v-for="p in g" :key="p.id"><col v-for="n in padOf(g)" :key="`pad${n}`"></colgroup>
          <thead>
            <tr><th>检测项目</th><th>限度</th><th v-for="p in g" :key="p.id">{{ p.label }}</th><th v-for="n in padOf(g)" :key="`pad${n}`" class="pad"></th></tr>
          </thead>
          <tbody>
            <tr v-for="t in d.tests" :key="t.id">
              <td>{{ t.name }}<template v-if="t.unit">（{{ t.unit }}）</template></td>
              <td>{{ limitText(t) || '—' }}</td>
              <td v-for="p in g" :key="p.id" :class="{ bad: resultOf(p, t) && !resultOf(p, t).passed }">
                <template v-if="resultOf(p, t)">{{ shown(resultOf(p, t)) }}<template v-if="!resultOf(p, t).passed">（不合格）</template></template>
                <template v-else>{{ p.status === 'SKIPPED' ? '未取样' : '—' }}</template>
              </td>
              <td v-for="n in padOf(g)" :key="`pad${n}`" class="pad"></td>
            </tr>
            <tr v-if="!d.tests.length"><td :colspan="g.length + padOf(g) + 2">没有检测项目</td></tr>
          </tbody>
        </table>
      </template>

      <h2>取样记录</h2>
      <table>
        <thead><tr><th>时间点</th><th>计划日期</th><th>取样日期</th><th>检测人</th><th>判定</th><th>偏差</th><th>备注</th></tr></thead>
        <tbody>
          <tr v-for="p in d.pulls" :key="p.id">
            <td>{{ p.label }}</td>
            <td>{{ p.dueDate }}</td>
            <td>{{ p.doneDate || '—' }}</td>
            <td>{{ p.testedBy || '—' }}</td>
            <td>{{ verdict(p) }}</td>
            <td class="mono">{{ p.deviationNo || '—' }}</td>
            <td>{{ [p.skipReason, p.note].filter(Boolean).join('；') }}</td>
          </tr>
        </tbody>
      </table>

      <h2>结论</h2>
      <table class="meta">
        <tbody>
          <tr><th>结论</th><td colspan="3">{{ d.conclusion || '未写结论' }}</td></tr>
          <tr><th>支持保质期</th><td>{{ s.supportedMonths != null ? `${s.supportedMonths} 个月` : '—' }}</td><th>提交人 / 时间</th><td>{{ s.submittedBy ? `${s.submittedBy} / ${fmtTime(d.submittedAt)}` : '—' }}</td></tr>
          <tr><th>签批人（质量安全负责人）</th><td>{{ s.signedBy || '未签批' }}</td><th>签批时间</th><td>{{ s.signedAt ? fmtTime(s.signedAt) : '—' }}</td></tr>
          <tr v-if="d.cancelReason"><th>{{ s.statusLabel }}原因</th><td colspan="3">{{ d.cancelReason }}</td></tr>
          <tr v-if="d.note"><th>备注</th><td colspan="3">{{ d.note }}</td></tr>
        </tbody>
      </table>
      <p class="foot">打印时间 {{ printedAt }}。系统内的电子记录为准；原始检测记录见系统附件。</p>
    </article>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Printer } from '@element-plus/icons-vue'
import http from '../api'
import { fmtQty, fmtTime } from '../utils/format'

const route = useRoute()
const d = ref(null)
const error = ref('')
const s = computed(() => d.value.study)
const printedAt = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16)
const approved = computed(() => s.value.status === 'APPROVED')
const done = computed(() => d.value.pulls.filter((p) => p.status === 'DONE').length)
// 一张表最多 12 个时间点，再多就分几张表，列宽固定，打印时不会挤成竖排
const PER_TABLE = 12
const groups = computed(() => {
  const ps = d.value.pulls
  const out = []
  for (let i = 0; i < ps.length; i += PER_TABLE) out.push(ps.slice(i, i + PER_TABLE))
  return out.length ? out : [[]]
})
// 分了几张表时，最后一张补空列，和前面的表列宽对齐
const padOf = (g) => (groups.value.length > 1 ? PER_TABLE - g.length : 0)
const skipped = computed(() => d.value.pulls.filter((p) => p.status === 'SKIPPED').length)
// 只有已签批的报告能当保质期依据；其他状态打印出来要一眼看出不能用
const draftText = computed(() => ({
  ACTIVE: '试验进行中，结论未签批，仅供内部参考',
  REVIEW: '结论待签批，仅供内部参考',
  CANCELLED: '试验已取消，不能作为保质期依据',
  VOID: '结论已作废，不能作为保质期依据',
}[s.value.status] || `${s.value.statusLabel}，不能作为保质期依据`))

const pageStyle = document.createElement('style')
pageStyle.textContent = '@page { size: A4 landscape; margin: 12mm; }'
document.head.appendChild(pageStyle)
onUnmounted(() => pageStyle.remove())
const print = () => window.print()

const resultOf = (pull, test) => pull.results.find((r) => r.testId === test.id)
const withUnit = (v, t) => `${fmtQty(v)}${t.unit ? ` ${t.unit}` : ''}`
// 单位写在项目名旁边，格子里只写数值，时间点多时也不挤
const shown = (r) => [r.value != null ? fmtQty(r.value) : '', r.text || ''].filter(Boolean).join(' ') || (r.passed ? '合格' : '')
function limitText(t) {
  if (t.minValue != null && t.maxValue != null) return `${fmtQty(t.minValue)} ~ ${fmtQty(t.maxValue)}${t.unit ? ` ${t.unit}` : ''}`
  if (t.minValue != null) return `≥ ${withUnit(t.minValue, t)}`
  if (t.maxValue != null) return `≤ ${withUnit(t.maxValue, t)}`
  return t.criteria || ''
}
function verdict(p) {
  if (p.status === 'SKIPPED') return '未取样'
  if (p.status !== 'DONE') return '待取样'
  return p.passed === false ? '不合格' : '合格'
}

onMounted(async () => {
  try {
    d.value = await http.get(`/stability/studies/${route.query.id}`, { silent: true })
    document.title = `稳定性试验报告 ${d.value.study.studyNo}`
  } catch (e) {
    error.value = e.message
  }
})
</script>

<style scoped>
.print-page { min-height: 100%; padding: 16px; box-sizing: border-box; }
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 14px; }
.err { color: var(--el-color-danger); }
/* 纸面样式：白底黑字，屏幕上看到的就是打印出来的样子 */
.doc { background: #fff; color: #111; padding: 18px; max-width: 1100px; font-size: 12px; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; border-radius: 4px; }
h1 { font-size: 18px; margin: 0 0 12px; text-align: center; }
h2 { font-size: 13px; margin: 14px 0 6px; }
table { width: 100%; border-collapse: collapse; }
th, td { border: 1px solid #999; padding: 4px 6px; text-align: left; vertical-align: top; }
th { background: #f0f0f0; font-weight: 600; }
.meta th { width: 13%; }
.results { table-layout: fixed; }
.results col.test { width: 13%; }
.results col.limit { width: 11%; }
.results td, .results th { overflow-wrap: anywhere; }
.results .pad { border: none; background: none; }
.bad { font-weight: 600; }
.mono { font-family: ui-monospace, Menlo, monospace; }
.draft { margin: 0 0 8px; padding: 4px 8px; border: 2px solid #111; font-weight: 600; text-align: center; }
.foot { margin: 10px 0 0; color: #555; font-size: 11px; }
@media print {
  .no-print { display: none !important; }
  .print-page { padding: 0; }
  .doc { padding: 0; max-width: none; border-radius: 0; }
  tr { break-inside: avoid; }
  h2 { break-after: avoid; }
}
</style>

<style>
@media print {
  body::before { display: none !important; }
  body { background: #fff !important; }
}
</style>
