<template>
  <div v-if="total > sizes[0]" class="pager">
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="sizes"
      background
      :layout="compact ? 'prev, pager, next' : 'total, sizes, prev, pager, next'"
      :pager-count="compact ? 5 : 7"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const page = defineModel('page', { type: Number, default: 1 })
const size = defineModel('size', { type: Number, default: 20 })
defineProps({ total: { type: Number, default: 0 } })
const sizes = [10, 20, 50, 100]
const compact = computed(() => window.innerWidth < 640)
</script>
