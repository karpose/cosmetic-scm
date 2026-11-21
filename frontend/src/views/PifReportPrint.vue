<template>
  <div class="print-page">
    <div class="bar no-print">
      <b>产品信息档案（PIF）</b>
      <el-button type="primary" :icon="Printer" :disabled="!pif" @click="print">打印 / 另存 PDF</el-button>
      <el-button @click="$router.back()">返回</el-button>
    </div>
    <p v-if="error" class="no-print err">{{ error }}</p>
    <article v-if="pif" class="doc">
      <h1>化妆品产品信息档案（PIF）</h1>
      <table class="meta">
        <tr><th>产品名称</th><td>{{ pif.fgName }}</td><th>产品编码</th><td class="mono">{{ pif.fgCode }}</td></tr>
        <tr><th>备案 / 注册号</th><td class="mono">{{ pif.filingNo || '暂未备案' }}</td><th>保质期 / 净含量</th><td class="mono">{{ pif.shelfMonths ? `${pif.shelfMonths} 个月` : '—' }} · {{ pif.netG ? `${fmtQty(pif.netG)} g` : '—' }}</td></tr>
        <tr><th>现行配方版本</th><td class="mono">{{ pif.effectiveVersionNo ? `V${pif.effectiveVersionNo}` : '无生效配方' }}（批准：{{ pif.effectiveApprovedBy || '—' }}）</td><th>现行质量标准</th><td class="mono">{{ pif.qualitySpecNo || '未生效' }}</td></tr>
        <tr><th>稳定性依据</th><td>{{ pif.stabilitySupportedMonths ? `支持 ${pif.stabilitySupportedMonths} 个月（${pif.stabilityState}）` : '暂无已签批结论' }}</td><th>档案生成时间</th><td class="mono">{{ printedAt }}</td></tr>
      </table>

      <h2>一、现行生效配方明细（BOM）</h2>
      <table>
        <thead><tr><th>编码</th><th>物料名称</th><th>类型</th><th>INCI / CAS</th><th class="r">每件单耗</th><th class="r">浓度(%)</th><th>供料方式</th></tr></thead>
        <tbody>
          <tr v-for="l in pif.formulaLines" :key="l.itemCode">
            <td class="mono">{{ l.itemCode }}</td>
            <td>{{ l.itemName }}</td>
            <td>{{ l.itemType }}</td>
            <td class="small">{{ l.inci || '—' }}<span v-if="l.casNo" class="mono"> / {{ l.casNo }}</span></td>
            <td class="r mono">{{ fmtQty(l.qtyPer, 6) }} {{ l.unit }}</td>
            <td class="r mono">{{ l.concentrationPct != null ? `${l.concentrationPct}%` : '—' }}</td>
            <td>{{ l.supplyModeLabel }}</td>
          </tr>
          <tr v-if="!pif.formulaLines.length"><td colspan="7">暂无生效配方明细</td></tr>
        </tbody>
      </table>

      <template v-if="pif.ingredients">
        <h2>二、全成分表草稿（按含量降序）</h2>
        <div class="box">{{ pif.ingredients.text || '—' }}</div>
        <ul v-if="pif.ingredients.problems?.length" class="notes">
          <li v-for="(pr, i) in pif.ingredients.problems" :key="i">{{ pr }}</li>
        </ul>
      </template>

      <h2>三、备案 / 注册记录</h2>
      <table>
        <thead><tr><th>类型</th><th>配方版本</th><th>编号</th><th>提交日期</th><th>完成日期</th><th>有效期</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="f in pif.filings" :key="f.id">
            <td>{{ f.filingTypeLabel }}</td>
            <td class="mono">{{ f.versionNo ? `V${f.versionNo}` : '—' }}</td>
            <td class="mono">{{ f.filingNo || '—' }}</td>
            <td class="mono">{{ f.submittedDate || '—' }}</td>
            <td class="mono">{{ f.filedDate || '—' }}</td>
            <td class="mono">{{ f.expiryDate || '长期' }}</td>
            <td>{{ f.statusLabel }}</td>
          </tr>
          <tr v-if="!pif.filings.length"><td colspan="7">暂无备案记录</td></tr>
        </tbody>
      </table>

      <h2>四、安全评估、功效与标签合规资料</h2>
      <table>
        <thead><tr><th>单号</th><th>类型</th><th>配方版本</th><th>标题</th><th>评估人 / 机构</th><th>日期</th><th>状态 / 签批人</th></tr></thead>
        <tbody>
          <tr v-for="d in pif.complianceDocs" :key="d.id">
            <td class="mono">{{ d.docNo }}</td>
            <td>{{ d.docTypeLabel }}</td>
            <td class="mono">{{ d.versionNo ? `V${d.versionNo}` : '—' }}</td>
            <td>{{ d.title }}</td>
            <td>{{ d.evaluator }}</td>
            <td class="mono">{{ d.docDate }}</td>
            <td>{{ d.statusLabel }}<span v-if="d.signedBy"> · {{ d.signedBy }}</span></td>
          </tr>
          <tr v-if="!pif.complianceDocs.length"><td colspan="7">暂无合规资料</td></tr>
        </tbody>
      </table>

      <h2>五、现行已批准包材设计稿</h2>
      <table>
        <thead><tr><th>设计稿编号</th><th>包材</th><th>版本</th><th>批准人</th><th>批准时间</th></tr></thead>
        <tbody>
          <tr v-for="a in pif.artworks" :key="a.id">
            <td class="mono">{{ a.artworkNo }}</td>
            <td>{{ a.pkName }}（{{ a.pkCode }}）</td>
            <td class="mono">第 {{ a.versionNo }} 版</td>
            <td>{{ a.approvedBy || '—' }}</td>
            <td class="mono">{{ a.approvedAt ? fmtTime(a.approvedAt) : '—' }}</td>
          </tr>
          <tr v-if="!pif.artworks.length"><td colspan="5">暂无已批准的包材设计稿</td></tr>
        </tbody>
      </table>

      <h2>六、配方版本变更历史</h2>
      <table>
        <thead><tr><th>版本</th><th>状态</th><th>变更说明</th><th>制单人 / 时间</th><th>批准人 / 时间</th></tr></thead>
        <tbody>
          <tr v-for="v in pif.versionHistory" :key="v.versionNo">
            <td class="mono">V{{ v.versionNo }}</td>
            <td>{{ v.statusLabel }}</td>
            <td>{{ v.changeNote || '—' }}</td>
            <td class="mono">{{ v.createdBy }} · {{ fmtTime(v.createdAt) }}</td>
            <td class="mono">{{ v.approvedBy ? `${v.approvedBy} · ${fmtTime(v.approvedAt)}` : '—' }}</td>
          </tr>
        </tbody>
      </table>

      <p class="foot">打印时间 {{ printedAt }}。本产品信息档案（PIF）由系统实时聚合生成，签批电子记录以系统为准。</p>
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
const pif = ref(null)
const error = ref('')
const printedAt = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16)

const pageStyle = document.createElement('style')
pageStyle.textContent = '@page { size: A4 portrait; margin: 12mm; }'
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
    pif.value = await http.get(`/plm/pif/${fg}`, { silent: true })
  } catch (e) {
    error.value = e.message
  }
})
</script>

<style scoped>
.print-page { min-height: 100%; padding: 16px; box-sizing: border-box; }
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 14px; }
.err { color: var(--el-color-danger); }
.doc { background: #fff; color: #111; padding: 20px; max-width: 960px; font-size: 12px; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; border-radius: 6px; }
h1 { font-size: 18px; margin: 0 0 14px; text-align: center; }
h2 { font-size: 13px; margin: 14px 0 6px; }
table { width: 100%; border-collapse: collapse; }
th, td { border: 1px solid #999; padding: 4px 6px; text-align: left; vertical-align: top; }
th { background: #f3f3f3; font-weight: 600; }
.meta th { width: 15%; }
.r { text-align: right; }
.box { border: 1px solid #999; padding: 8px 10px; line-height: 1.6; }
.notes { margin: 6px 0 0 18px; padding: 0; color: #555; font-size: 11px; }
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
