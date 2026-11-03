<template>
  <el-tag :type="type" size="small" disable-transitions>{{ label }}</el-tag>
</template>

<script setup>
import { computed } from 'vue'
import { STATUS_TAG } from '../utils/format'

const props = defineProps({ status: { type: String, required: true }, label: { type: String, default: '' } })
const type = computed(() => STATUS_TAG[props.status] || 'info')
</script>
