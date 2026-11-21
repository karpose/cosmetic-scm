<template>
  <div class="print-page">
    <div class="bar no-print">
      <b>产品年度质量回顾报告（PQR）</b>
      <el-button type="primary" :icon="Printer" :disabled="!pqr" @click="print">打印 / 另存 PDF</el-button>
      <el-button @click="$router.back()">返回</el-button>
    </div>
    <p v-if="error" class="no-print err">{{ error }}</p>
    <article v-if="pqr" class="doc">
      <h1>化妆品产品年度质量回顾报告（PQR）</h1>
      <table class="meta">
        <tr><th>产品名称</th><td>{{ pqr.fgName }}</td><th>产品编码</th><td class="mono">{{ pqr.fgCode }}</td></tr>
        <tr><th>备案 / 注册号</th><td class="mono">{{ pqr.filingNo || '—' }}</td><th>回顾区间</th><td class="mono">{{ pqr.from }} 至 {{ pqr.to }}</td></tr>
        <tr><th>现行配方版本</th><td class="mono">{{ pqr.effectiveVersionNo ? `V${pqr.effectiveVersionNo}` : '—' }}（期内批准变更 {{ pqr.formulaChangesInPeriod }} 次）</td><th>稳定性支持</th><td>{{ pqr.stabilitySupportedMonths ? `${pqr.stabilitySupportedMonths} 个月（${pqr.stabilityState}）` : '暂无结论' }}</td></tr>
      </table>

      <h2>一、质量与生产核心指标汇总</h2>
      <table>
        <thead><tr><th>完工批数</th><th>计划总产量</th><th>实际总产量</th><th>平均收率</th><th>成品放行合格率</th><th>偏差(重大/严重)</th><th>投诉(不良反应)</th><th>召回次数</th><th>客户退货量</th></tr></thead>
        <tbody>
          <tr>
            <td class="r mono">{{ pqr.batchCount }}</td>
            <td class="r mono">{{ fmtQty(pqr.totalPlannedQty) }}</td>
            <td class="r mono">{{ fmtQty(pqr.totalActualQty) }}</td>
            <td class="r mono">{{ pqr.avgYieldPct != null ? `${pqr.avgYieldPct}%` : '—' }}</td>
            <td class="r mono">{{ pqr.fgPassRatePct != null ? `${pqr.fgPassRatePct}%（${pqr.fgBatchReleased}/${pqr.fgBatchTotal}）` : '—' }}</td>
            <td class="r mono">{{ pqr.deviationCount }}（{{ pqr.majorOrCriticalDeviations }}）</td>
            <td class="r mono">{{ pqr.complaintCount }}（{{ pqr.adverseCount }}）</td>
            <td class="r mono">{{ pqr.recallCount }}</td>
            <td class="r mono">{{ fmtQty(pqr.returnedQty) }}</td>
          </tr>
        </tbody>
      </table>

      <h2>二、回顾期内完工生产批次明细</h2>
      <table>
        <thead><tr><th>生产单号</th><th>类型</th><th>生产方</th><th>产出批号</th><th class="r">计划产量</th><th class="r">实际产量</th><th class="r">收率</th><th>完工时间</th></tr></thead>
        <tbody>
          <tr v-for="b in pqr.batches" :key="b.moNo">
            <td class="mono">{{ b.moNo }}</td>
            <td>{{ b.moTypeLabel }}</td>
            <td>{{ b.factoryName }}</td>
            <td class="mono">{{ b.outBatchNo }}</td>
            <td class="r mono">{{ fmtQty(b.plannedQty) }}</td>
            <td class="r mono">{{ fmtQty(b.actualQty) }}</td>
            <td class="r mono">{{ b.yieldPct != null ? `${b.yieldPct}%` : '—' }}</td>
            <td class="mono">{{ fmtTime(b.completedAt) }}</td>
          </tr>
          <tr v-if="!pqr.batches.length"><td colspan="8">回顾期内无完工生产批次</td></tr>
        </tbody>
      </table>

      <table class="sign">
        <tr>
          <td><b>质量保证部编制（签字/日期）：</b><div class="sig-space" /></td>
          <td><b>质量安全负责人批准（签字/日期）：</b><div class="sig-space" /></td>
        </tr>
      </table>
      <p class="foot">打印时间 {{ printedAt }}。本年度质量回顾报告由系统实时统计生成，原始记录以系统电子签批为准。</p>
    </article>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Printer } from '@element-plus/icons-vue'
import http from '../api'
import { fmtQty, fmtTime } from '../utils/format'

const route = useRoute()
const pqr = ref(null)
const error = ref('')
const printedAt = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16)

const pageStyle = document.createElement('style')
pageStyle.textContent = '@page { size: A4 landscape; margin: 12mm; }'
document.head.appendChild(pageStyle)
onUnmounted(() => pageStyle.remove())
const print = () => window.print()

onMounted(async () => {
  const fg = route.query.fg
  if (!fg) {
    error.value = '缺少成品 ID 参数'
    return
  }
  try {
    pqr.value = await http.get('/analytics/pqr', { params: { fgItemId: fg, from: route.query.from || undefined, to: route.query.to || undefined }, silent: true })
  } catch (e) {
    error.value = e.message
  }
})
</script>

<style scoped>
.print-page { min-height: 100%; padding: 16px; box-sizing: border-box; }
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 14px; }
.err { color: var(--el-color-danger); }
.doc { background: #fff; color: #111; padding: 20px; max-width: 1080px; font-size: 12px; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; border-radius: 6px; }
h1 { font-size: 18px; margin: 0 0 14px; text-align: center; }
h2 { font-size: 13px; margin: 14px 0 6px; }
table { width: 100%; border-collapse: collapse; }
th, td { border: 1px solid #999; padding: 5px 7px; text-align: left; vertical-align: top; }
th { background: #f3f3f3; font-weight: 600; }
.meta th { width: 14%; }
.r { text-align: right; }
.sign { margin-top: 18px; }
.sign td { width: 50%; padding: 10px 12px; }
.sig-space { min-height: 44px; }
.foot { margin: 12px 0 0; color: #555; font-size: 11px; }
@media print {
  .no-print { display: none !important; }
  .print-page { padding: 0; }
  .doc { padding: 0; max-width: none; border-radius: 0; }
  tr { break-inside: avoid; }
}
</style>

<style>
@media print {
  body::before { display: none !important; }
  body { background: #fff !important; }
}
</style>
