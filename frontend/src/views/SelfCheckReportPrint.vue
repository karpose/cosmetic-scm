<template>
  <div class="print-page">
    <div class="bar no-print">
      <b>GMP 自查报告</b>
      <el-button type="primary" :icon="Printer" :disabled="!d" @click="print">打印</el-button>
      <el-button @click="$router.back()">返回</el-button>
    </div>
    <p v-if="error" class="no-print err">{{ error }}</p>
    <article v-if="d" class="doc">
      <p v-if="c.status !== 'APPROVED'" class="draft">{{ c.status === 'CANCELLED' ? '本次自查已作废，不能作为自查记录' : `本次自查${c.statusLabel}，结论还没有签批，仅供内部参考` }}</p>
      <h1>化妆品生产质量管理规范自查报告</h1>
      <p class="sub">依据：{{ c.editionLabel }}（国家药监局 2022 年第 90 号公告附 2）</p>
      <table class="meta">
        <tbody>
          <tr><th>自查编号</th><td class="mono">{{ c.checkNo }}</td><th>自查期间</th><td>{{ c.periodFrom }} 至 {{ c.periodTo }}</td></tr>
          <tr><th>自查范围</th><td colspan="3">{{ c.scope || '—' }}</td></tr>
          <tr><th>结论</th><td><b>{{ c.conclusionLabel || '—' }}</b></td><th>检查结果</th><td>符合 {{ c.counts.compliant }}，存在瑕疵 {{ c.counts.flawed }}，不符合 {{ c.counts.noncompliant }}，不适用 {{ c.counts.notApplicable }}，未检查 {{ c.counts.pending }}</td></tr>
          <tr><th>发起</th><td>{{ c.createdBy }} / {{ fmtTime(c.createdAt) }}</td><th>提交</th><td>{{ c.submittedBy ? `${c.submittedBy} / ${fmtTime(c.submittedAt)}` : '—' }}</td></tr>
          <tr><th>签批（质量安全负责人）</th><td>{{ c.approvedBy || '未签批' }}</td><th>签批时间</th><td>{{ c.approvedAt ? fmtTime(c.approvedAt) : '—' }}</td></tr>
          <tr><th>自查总结</th><td colspan="3">{{ d.summary || '—' }}</td></tr>
        </tbody>
      </table>

      <h2>逐项检查结果</h2>
      <table class="items">
        <thead>
          <tr><th class="n">序号</th><th class="clause">条款</th><th class="lv">类别</th><th>检查项目</th><th class="res">结果</th><th>发现的问题 / 不适用原因</th><th class="capa">CAPA</th></tr>
        </thead>
        <tbody>
          <tr v-for="it in d.items" :key="it.no" :class="{ bad: it.result === 'NONCOMPLIANT' || it.result === 'FLAWED' }">
            <td>{{ it.no }}</td>
            <td>{{ it.clause }}</td>
            <td>{{ LEVEL_SHORT[it.level] }}</td>
            <td>{{ it.title }}</td>
            <td>{{ it.resultLabel }}</td>
            <td>{{ it.finding || '' }}<template v-if="it.evidence"><br><span class="muted">查看：{{ it.evidence }}</span></template></td>
            <td class="mono">{{ it.capaNo || '' }}</td>
          </tr>
        </tbody>
      </table>
      <p class="foot">判定原则：关键项目不符合；或关键项目瑕疵数与其他重点项目不符合数之和达到 4 项；或重点项目不符合、重点项目瑕疵、一般项目不符合之和达到 8 项，判“存在严重缺陷”。本报告“检查项目”是要点概括，以原文为准。打印时间 {{ printedAt }}，系统内的电子记录为准。</p>
    </article>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Printer } from '@element-plus/icons-vue'
import http from '../api'
import { fmtTime } from '../utils/format'

const LEVEL_SHORT = { KEY: '关键', IMPORTANT: '重点', GENERAL: '一般' }
const route = useRoute()
const d = ref(null)
const error = ref('')
const c = computed(() => d.value.check)
const printedAt = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16)

const pageStyle = document.createElement('style')
pageStyle.textContent = '@page { size: A4 portrait; margin: 12mm; }'
document.head.appendChild(pageStyle)
onUnmounted(() => pageStyle.remove())
const print = () => window.print()

onMounted(async () => {
  try {
    d.value = await http.get(`/qms/self-checks/${route.query.id}`, { silent: true })
    document.title = `GMP 自查报告 ${d.value.check.checkNo}`
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
.doc { background: #fff; color: #111; padding: 18px; max-width: 900px; font-size: 12px; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; border-radius: 6px; }
h1 { font-size: 18px; margin: 0 0 4px; text-align: center; }
.sub { text-align: center; margin: 0 0 12px; color: #444; }
h2 { font-size: 13px; margin: 14px 0 6px; }
table { width: 100%; border-collapse: collapse; }
th, td { border: 1px solid #999; padding: 4px 6px; text-align: left; vertical-align: top; }
th { background: #f0f0f0; font-weight: 600; }
.meta th { width: 15%; }
.items .n { width: 5%; }
.items .clause { width: 14%; }
.items .lv { width: 6%; }
.items .res { width: 9%; }
.items .capa { width: 14%; }
.bad td { font-weight: 600; }
.muted { color: #555; font-weight: 400; }
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
