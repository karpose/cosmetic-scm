<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>审计日志</h2>
        <p>每次新增、收货、发料、签批、出库、冻结和账号变更都会记录操作人、时间和原因。日志只增不改，系统不提供修改或删除入口。</p>
      </div>
      <ExportButton report="audit" />
    </div>
    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input id="au-q" v-model="q" placeholder="按批号、单号、操作或账号搜索（回车查询）" clearable style="width:300px" @change="load" />
          <el-select v-model="action" placeholder="全部操作" clearable filterable>
            <el-option v-for="a in actions" :key="a" :value="a" :label="a" />
          </el-select>
          <span class="muted small">显示最近 300 条</span>
        </div>
      </template>
      <LoadError v-if="error" :message="error" :loading="loading" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loading" empty-text="没有记录">
        <el-table-column label="时间" width="150"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
        <el-table-column label="操作人" width="160"><template #default="{ row }">{{ row.displayName }} <span class="muted small">{{ row.username }}</span></template></el-table-column>
        <el-table-column prop="action" label="操作" width="130" />
        <el-table-column label="对象" width="200"><template #default="{ row }"><span class="mono small">{{ row.target }}</span></template></el-table-column>
        <el-table-column prop="detail" label="内容" min-width="240" />
        <el-table-column prop="reason" label="原因" min-width="180" />
      </el-table>
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>
  </div>
</template>

<script setup>
import ExportButton from '../components/ExportButton.vue'
import { computed, onMounted, ref } from 'vue'
import http from '../api'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import { fmtTime } from '../utils/format'
import { usePaged } from '../utils/table'

const q = ref('')
const action = ref('')
const rows = ref([])
const loading = ref(false)
const error = ref('')
const actions = computed(() => [...new Set(rows.value.map((r) => r.action))].sort())
const t = usePaged(rows, { extra: (r) => !action.value || r.action === action.value, pageSize: 50 })

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await http.get('/audit', { params: { limit: 300, ...(q.value ? { q: q.value } : {}) } })
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
