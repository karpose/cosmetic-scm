<template>
  <div class="print-page">
    <div class="bar no-print">
      <b>{{ isAr ? '客户应收对账单' : '往来应付对账单' }}</b>
      <el-button type="primary" :icon="Printer" :disabled="!doc" @click="print">打印 / 另存 PDF</el-button>
      <el-button @click="$router.back()">返回</el-button>
    </div>
    <p v-if="error" class="no-print err">{{ error }}</p>
    <article v-if="doc" class="doc">
      <h1>{{ isAr ? '客户应收对账函' : (doc.statementType === 'OUTSOURCING' ? '委外加工费对账函' : '供应商往来对账函') }}</h1>
      <table class="meta">
        <tr>
          <th>对账单号</th><td class="mono">{{ doc.statementNo }}</td>
          <th>对账截止日</th><td class="mono">{{ doc.periodTo }}</td>
        </tr>
        <tr>
          <th>{{ isAr ? '客户名称' : '往来单位' }}</th><td>{{ isAr ? doc.customerName : doc.supplierName }}</td>
          <th>税号 / 信用代码</th><td class="mono">{{ (isAr ? doc.customerTaxId : doc.supplierTaxId) || '—' }}</td>
        </tr>
        <tr>
          <th>信用账期 / 到期日</th><td class="mono">{{ doc.creditDays ?? 30 }} 天 · {{ doc.dueDate || '—' }}</td>
          <th>当前状态</th><td>{{ doc.statusLabel }}</td>
        </tr>
        <tr>
          <th>含税合计</th><td class="mono"><b>{{ fmtMoney(doc.totalAmount) }}</b>（含税额 {{ fmtMoney(doc.taxAmount) }}）</td>
          <th>{{ isAr ? '已回款 / 待回款' : '已结 / 未结' }}</th>
          <td class="mono">{{ fmtMoney(isAr ? doc.receivedAmount : doc.paidAmount) }} / {{ fmtMoney(isAr ? doc.unreceived : doc.unpaid) }}</td>
        </tr>
        <tr>
          <th>发票信息</th><td class="mono">{{ doc.invoiceNo ? `${doc.invoiceNo}（${doc.invoiceDate || ''}）` : '暂未登记' }}</td>
          <th>外部财务凭证</th><td class="mono">{{ doc.extVoucherNo || doc.financeSyncLabel || '待同步' }}</td>
        </tr>
      </table>

      <template v-if="doc.taxBreakdown?.length">
        <h2>分税率汇总</h2>
        <table>
          <thead><tr><th>税率</th><th class="r">行数</th><th class="r">不含税金额</th><th class="r">税额</th><th class="r">含税金额</th></tr></thead>
          <tbody>
            <tr v-for="tb in doc.taxBreakdown" :key="tb.taxRate">
              <td class="mono">{{ fmtQty(tb.taxRate) }}%</td>
              <td class="r mono">{{ tb.lineCount }}</td>
              <td class="r mono">{{ fmtMoney(tb.netAmount) }}</td>
              <td class="r mono">{{ fmtMoney(tb.taxAmount) }}</td>
              <td class="r mono">{{ fmtMoney(tb.goodsAmount) }}</td>
            </tr>
          </tbody>
        </table>
      </template>

      <h2>对账明细</h2>
      <table v-if="isAr">
        <thead><tr><th>出库单 / 退货单</th><th>日期</th><th>成品</th><th class="r">数量</th><th class="r">含税单价</th><th class="r">税率</th><th class="r">含税金额</th></tr></thead>
        <tbody>
          <tr v-for="(l, idx) in doc.lines" :key="idx">
            <td class="mono">{{ l.returnNo ? `退货冲减 ${l.returnNo}` : l.soNo }}</td>
            <td class="mono">{{ l.orderDate || '—' }}</td>
            <td>{{ l.itemName }}（{{ l.itemCode }}）</td>
            <td class="r mono">{{ fmtQty(l.qty) }} {{ l.unit }}</td>
            <td class="r mono">{{ fmtQty(l.unitPrice) }}</td>
            <td class="r mono">{{ fmtQty(l.taxRate) }}%</td>
            <td class="r mono">{{ fmtMoney(l.amount) }}</td>
          </tr>
        </tbody>
      </table>
      <table v-else>
        <thead><tr><th>批号 / 外部批号</th><th>来源单号</th><th>物料</th><th class="r">数量</th><th class="r">含税单价</th><th class="r">税率</th><th class="r">含税金额</th></tr></thead>
        <tbody>
          <tr v-for="(l, idx) in doc.lines" :key="idx">
            <td class="mono">{{ l.batchNo }} {{ l.extBatchNo ? `/ ${l.extBatchNo}` : '' }}</td>
            <td class="mono">{{ l.returnNo ? `退供冲减 ${l.returnNo}` : (l.poNo || '—') }}</td>
            <td>{{ l.itemName }}</td>
            <td class="r mono">{{ fmtQty(l.qty) }}</td>
            <td class="r mono">{{ fmtQty(l.unitPrice) }}</td>
            <td class="r mono">{{ fmtQty(l.taxRate) }}%</td>
            <td class="r mono">{{ fmtMoney(l.amount) }}</td>
          </tr>
        </tbody>
      </table>

      <div v-if="Number(doc.adjustment || 0) !== 0" class="adj">
        调整金额：<b class="mono">{{ fmtMoney(doc.adjustment) }}</b>（原因：{{ doc.adjustmentNote || '—' }}）
      </div>

      <table class="sign">
        <tr>
          <td><b>品牌方确认（盖章/签字）：</b><div class="sig-space">制单：{{ doc.createdBy || '—' }}　审批：{{ doc.approvedBy || '—' }}</div><div class="sig-date">日期：____ 年 __ 月 __ 日</div></td>
          <td><b>{{ isAr ? '客户确认（盖章/签字）：' : '往来单位确认（盖章/签字）：' }}</b><div class="sig-space">请核对以上明细与金额无误后盖章回传。</div><div class="sig-date">日期：____ 年 __ 月 __ 日</div></td>
        </tr>
      </table>
      <p class="foot">打印时间 {{ printedAt }}。本对账函由系统自动生成，具体记账以正式税务发票及财务凭证为准。</p>
    </article>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Printer } from '@element-plus/icons-vue'
import http from '../api'
import { fmtMoney, fmtQty } from '../utils/format'

const route = useRoute()
const isAr = computed(() => String(route.query.side || 'AP').toUpperCase() === 'AR')
const doc = ref(null)
const error = ref('')
const printedAt = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16)

const pageStyle = document.createElement('style')
pageStyle.textContent = '@page { size: A4 portrait; margin: 12mm; }'
document.head.appendChild(pageStyle)
onUnmounted(() => pageStyle.remove())
const print = () => window.print()

onMounted(async () => {
  const no = typeof route.query.no === 'string' ? route.query.no.trim() : ''
  if (!no) {
    error.value = '缺少对账单号参数'
    return
  }
  try {
    const url = isAr.value ? '/ar-statements' : '/statements'
    const list = await http.get(url, { params: { statementNo: no }, silent: true })
    if (!list.length) {
      error.value = `未找到对账单 ${no}`
    } else {
      doc.value = list[0]
    }
  } catch (e) {
    error.value = e.message
  }
})
</script>

<style scoped>
.print-page { min-height: 100%; padding: 16px; box-sizing: border-box; }
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 14px; }
.err { color: var(--el-color-danger); }
.doc { background: #fff; color: #111; padding: 20px; max-width: 900px; font-size: 12px; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; border-radius: 6px; }
h1 { font-size: 18px; margin: 0 0 14px; text-align: center; }
h2 { font-size: 13px; margin: 14px 0 6px; }
table { width: 100%; border-collapse: collapse; }
th, td { border: 1px solid #999; padding: 5px 7px; text-align: left; vertical-align: top; }
th { background: #f3f3f3; font-weight: 600; }
.meta th { width: 16%; }
.r { text-align: right; }
.adj { margin-top: 8px; }
.sign { margin-top: 18px; }
.sign td { width: 50%; padding: 10px 12px; }
.sig-space { min-height: 44px; margin-top: 8px; color: #444; }
.sig-date { margin-top: 6px; color: #444; }
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
