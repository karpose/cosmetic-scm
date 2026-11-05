<template>
  <div v-if="total > sizes[0]" class="pager">
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="sizes"
      background
      :layout="compact ? 'prev, pager, next' : 'total, slot, prev, pager, next'"
      :pager-count="compact ? 5 : 7"
    >
      <!-- 组件自带的每页条数下拉没有可访问名称，也没有属性可传，所以自己放一个 -->
      <el-select v-model="size" aria-label="每页条数" class="pager-size">
        <el-option v-for="s in sizes" :key="s" :label="`${s} 条/页`" :value="s" />
      </el-select>
    </el-pagination>
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
