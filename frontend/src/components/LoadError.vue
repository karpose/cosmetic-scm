<template>
  <div class="load-error">
    <el-icon class="icon"><WarningFilled /></el-icon>
    <div>
      <b>没有加载出来</b>
      <p class="muted small">{{ message }}</p>
    </div>
    <el-button size="small" :loading="loading" @click="$emit('retry')">重新加载</el-button>
  </div>
</template>

<script setup>
import { WarningFilled } from '@element-plus/icons-vue'

defineProps({ message: { type: String, default: '' }, loading: { type: Boolean, default: false } })
defineEmits(['retry'])
</script>

<style scoped>
.load-error { display: flex; align-items: center; gap: 12px; padding: 16px 18px; margin: 4px 0; border-radius: 16px; background: color-mix(in srgb, var(--el-color-danger) 8%, transparent); border: 1px solid color-mix(in srgb, var(--el-color-danger) 25%, transparent); flex-wrap: wrap; }
.icon { font-size: 22px; color: var(--el-color-danger); }
.load-error > div { flex: 1; min-width: 180px; }
p { margin: 2px 0 0; }
</style>
