<template>
  <p v-if="count >= cap" class="cap muted small">这里只显示最近 {{ cap }} 条，更早的记录请用“导出 Excel”查看全部。</p>
</template>

<script setup>
defineProps({ count: { type: Number, default: 0 }, cap: { type: Number, default: 300 } })
</script>

<style scoped>
.cap { margin: 8px 16px 0; }
</style>
