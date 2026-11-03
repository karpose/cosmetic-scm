<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>审计日志</h2>
        <p>每次新增、收货、发料、签批、出库、冻结和账号变更都会记录操作人、时间和原因。日志只增不改，系统不提供修改或删除入口。</p>
      </div>
    </div>
    <el-card shadow="never" body-style="padding:0">
      <template #header>
        <div class="toolbar">
          <el-input id="au-q" v-model="q" placeholder="按批号、单号、操作或账号搜索" clearable style="width:280px" @change="load" />
          <span class="muted small">显示最近 300 条</span>
        </div>
      </template>
      <el-table :data="rows" v-loading="loading" empty-text="没有记录">
        <el-table-column label="时间" width="150"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
        <el-table-column label="操作人" width="160"><template #default="{ row }">{{ row.displayName }} <span class="muted small">{{ row.username }}</span></template></el-table-column>
        <el-table-column prop="action" label="操作" width="130" />
        <el-table-column label="对象" width="200"><template #default="{ row }"><span class="mono small">{{ row.target }}</span></template></el-table-column>
        <el-table-column prop="detail" label="内容" min-width="240" />
        <el-table-column prop="reason" label="原因" min-width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api'
import { fmtTime } from '../utils/format'

const q = ref('')
const rows = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/audit', { params: q.value ? { q: q.value } : {} })
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
