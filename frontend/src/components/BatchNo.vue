<template>
  <router-link v-if="no" class="batch mono" :to="{ name: 'trace', query: { no } }" title="追溯此批次">{{ no }}</router-link>
  <span v-else class="muted">—</span>
</template>

<script setup>
defineProps({ no: { type: String, default: '' } })
</script>

<style scoped>
.batch {
  font-size: 12px; font-weight: 600; padding: 1px 6px; border-radius: 4px; text-decoration: none; white-space: nowrap;
  color: var(--el-text-color-primary); background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter);
}
.batch:hover { color: var(--el-color-primary); border-color: var(--el-color-primary); }
</style>
