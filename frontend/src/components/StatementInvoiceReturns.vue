<template>
  <div v-if="history.length" class="invoice-returns">
    <el-alert v-if="statement.invoiceCorrectionPending" type="warning" :closable="false" show-icon
              :title="`发票退回原因：${history[0].reason}`" />
    <details class="small">
      <summary>发票退回记录（{{ history.length }} 次）</summary>
      <div v-for="entry in history" :key="entry.id" class="entry">
        <div><span class="muted mono">{{ fmtTime(entry.returnedAt) }}</span> · {{ entry.reason }}</div>
        <div v-for="inv in entry.invoices" :key="inv.id" class="muted mono">
          {{ inv.invoiceTypeLabel }} {{ inv.invoiceNo }} · {{ inv.invoiceDate }} · 税率 {{ fmtQty(inv.taxRate) }}% · ¥{{ fmtMoney(inv.amount) }}
        </div>
        <div v-if="!entry.invoices.length && entry.invoiceNo" class="muted mono">
          {{ entry.invoiceNo }} · {{ entry.invoiceDate }} · ¥{{ fmtMoney(entry.invoiceAmount) }}
        </div>
      </div>
    </details>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { fmtMoney, fmtQty, fmtTime } from '../utils/format'

const props = defineProps({ statement: { type: Object, required: true } })
const history = computed(() => props.statement.invoiceReturns ?? [])
</script>

<style scoped>
.invoice-returns, .entry { margin-top: 8px; }
summary { cursor: pointer; }
</style>
