<template>
  <span v-if="ratio === null || ratio === undefined" class="muted">—</span>
  <span v-else class="shelf" :title="`剩余效期 ${pctText}`">
    <span class="bar"><i :class="level" :style="{ width: pctText }" /></span>
    <span class="mono small">{{ pctText }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'

// 剩余效期：低于 1/3 红色，低于 1/2 黄色
const props = defineProps({ ratio: { type: Number, default: null } })
const pctText = computed(() => `${Math.round((props.ratio || 0) * 100)}%`)
const level = computed(() => (props.ratio < 1 / 3 ? 'bad' : props.ratio < 0.5 ? 'warn' : 'ok'))
</script>

<style scoped>
.shelf { display: inline-flex; align-items: center; gap: 8px; }
.bar { width: 60px; height: 6px; border-radius: 3px; background: var(--el-fill-color); overflow: hidden; display: inline-block; }
.bar i { display: block; height: 100%; }
.ok { background: var(--el-color-success); }
.warn { background: var(--el-color-warning); }
.bad { background: var(--el-color-danger); }
</style>
