<template>
  <div class="print-page">
    <div class="bar no-print">
      <b>批生产记录</b>
      <el-button type="primary" :icon="Printer" :disabled="!rec" @click="print">打印</el-button>
      <el-button @click="$router.back()">返回</el-button>
    </div>
    <p v-if="error" class="no-print err">{{ error }}</p>
    <article v-if="rec && mo" class="doc">
      <h1>批生产记录</h1>
      <table class="meta">
        <tr><th>产品</th><td>{{ mo.fgName }}（{{ mo.fgCode }}）</td><th>生产单</th><td>{{ mo.moNo }}</td></tr>
        <tr><th>生产方</th><td>{{ mo.factoryName }}</td><th>配方版本</th><td>V{{ detail.formulaVersionNo ?? '—' }}</td></tr>
        <tr><th>计划产量</th><td>{{ fmtQty(mo.plannedQty) }}</td><th>实际产量 / 收率</th><td>{{ mo.actualQty ? `${fmtQty(mo.actualQty)} / ${mo.yieldPct}%` : '—' }}</td></tr>
        <tr><th>成品批号</th><td>{{ mo.outBatchNo || '—' }}</td><th>状态</th><td>{{ mo.statusLabel }}</td></tr>
      </table>

      <h2>物料</h2>
      <table>
        <thead><tr><th>物料</th><th>供料</th><th class="r">理论用量</th><th>批号</th></tr></thead>
        <tbody>
          <tr v-for="r in detail.requirements" :key="r.itemId">
            <td>{{ r.itemName }}（{{ r.itemCode }}）</td><td>{{ r.supplyLabel }}</td><td class="r">{{ fmtQty(r.requiredQty) }} {{ r.unit }}</td>
            <td>{{ r.picks.map((p) => `${p.batchNo} × ${fmtQty(p.qty)}`).join('；') || r.extBatchNo || '—' }}</td>
          </tr>
        </tbody>
      </table>

      <h2>清场</h2>
      <table>
        <thead><tr><th>阶段</th><th>区域 / 设备</th><th>确认人</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="c in rec.clearances" :key="c.phase"><td>{{ c.phaseLabel }}</td><td>{{ c.area }}</td><td>{{ c.signedBy }}</td><td>{{ fmtTime(c.signedAt) }}</td></tr>
          <tr v-if="!rec.clearances.length"><td colspan="4">未确认</td></tr>
        </tbody>
      </table>

      <h2>工序</h2>
      <table>
        <thead><tr><th>#</th><th>工序</th><th>设备</th><th>过程检验</th><th>实测</th><th>操作人 / 时间</th><th>复核人 / 时间</th><th>备注</th></tr></thead>
        <tbody>
          <tr v-for="s in rec.steps" :key="s.stepNo">
            <td>{{ s.stepNo }}</td>
            <td>{{ s.name }}{{ s.critical ? '（关键）' : '' }}</td>
            <td>{{ s.equipmentName || (s.requiresEquipment ? '未记录' : '—') }}</td>
            <td>{{ s.ipcName ? `${s.ipcName}：${limits(s)}` : '—' }}</td>
            <td>{{ s.ipcValue != null ? fmtQty(s.ipcValue) : '—' }}{{ s.ipcPass === false ? '（超限）' : '' }}</td>
            <td>{{ s.operator || '未执行' }}<br>{{ s.doneAt ? fmtTime(s.doneAt) : '' }}</td>
            <td>{{ s.reviewer || (s.critical ? '未复核' : '—') }}<br>{{ s.reviewedAt ? fmtTime(s.reviewedAt) : '' }}</td>
            <td>{{ s.note || '' }}{{ s.deviationNo ? ` 偏差 ${s.deviationNo}` : '' }}</td>
          </tr>
        </tbody>
      </table>
      <p class="foot">打印时间 {{ printedAt }}。系统内的电子记录为准。</p>
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
const rec = ref(null)
const mo = ref(null)
const detail = ref(null)
const error = ref('')
const printedAt = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16)

const pageStyle = document.createElement('style')
pageStyle.textContent = '@page { size: A4 landscape; margin: 12mm; }'
document.head.appendChild(pageStyle)
onUnmounted(() => pageStyle.remove())
const print = () => window.print()

function limits(s) {
  const u = s.ipcUnit ? ` ${s.ipcUnit}` : ''
  if (s.ipcMin != null && s.ipcMax != null) return `${fmtQty(s.ipcMin)}–${fmtQty(s.ipcMax)}${u}`
  return s.ipcMin != null ? `≥ ${fmtQty(s.ipcMin)}${u}` : `≤ ${fmtQty(s.ipcMax)}${u}`
}

onMounted(async () => {
  try {
    const id = route.query.mo
    const [d, r] = await Promise.all([http.get(`/mfg-orders/${id}`, { silent: true }), http.get(`/mes/records/${id}`, { silent: true })])
    detail.value = d
    mo.value = d.mo
    rec.value = r
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
.doc { background: #fff; color: #111; padding: 18px; max-width: 1100px; font-size: 12px; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; border-radius: 6px; }
h1 { font-size: 18px; margin: 0 0 12px; text-align: center; }
h2 { font-size: 13px; margin: 14px 0 6px; }
table { width: 100%; border-collapse: collapse; }
th, td { border: 1px solid #999; padding: 4px 6px; text-align: left; vertical-align: top; }
th { background: #f0f0f0; font-weight: 600; }
.meta th { width: 11%; }
.r { text-align: right; }
.foot { margin: 10px 0 0; color: #555; font-size: 11px; }
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
