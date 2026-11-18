<template>
  <el-alert v-if="disputes.length" title="涉及待处理的收货异议" type="warning" :closable="false" show-icon class="receipt-alert">
    <ul class="receipt-list">
      <li v-for="item in disputes" :key="item.shipmentId">
        <span class="mono">{{ item.poNo }}</span> ·
        <RouterLink v-if="canOpen" class="mono" :to="{ path: '/purchase', query: { receiptDispute: item.shipmentId } }">{{ item.shipmentNo }}</RouterLink>
        <span v-else class="mono">{{ item.shipmentNo }}</span>
        <div>本单关联批次 <span class="mono">{{ item.batchNos.join('、') }}</span></div>
      </li>
    </ul>
  </el-alert>
</template>

<script setup>
defineProps({
  disputes: { type: Array, default: () => [] },
  canOpen: Boolean
})
</script>

<style scoped>
.receipt-alert { margin: 0 0 12px; }
.receipt-list { margin: 0; padding-inline-start: 18px; overflow-wrap: anywhere; }
</style>
