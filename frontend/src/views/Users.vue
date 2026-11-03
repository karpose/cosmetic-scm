<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>账号与权限</h2>
        <p>按岗位分配角色。成品上市放行只授予“质量安全负责人”角色，系统管理员也不能代签；请只把该角色给质量安全负责人及其书面指定的协助人员。</p>
      </div>
      <el-button type="primary" @click="openCreate">新增账号</el-button>
    </div>
    <el-card shadow="never" body-style="padding:0">
      <el-table :data="users" v-loading="loading">
        <el-table-column label="账号" width="140"><template #default="{ row }"><span class="mono">{{ row.username }}</span></template></el-table-column>
        <el-table-column prop="displayName" label="姓名" width="180" />
        <el-table-column label="角色" min-width="260">
          <template #default="{ row }"><el-tag v-for="r in row.roles" :key="r" size="small" class="role" disable-transitions>{{ ROLE_LABELS[r] || r }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small" disable-transitions>{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text @click="openReset(row)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="form.visible" :title="form.id ? '编辑账号' : '新增账号'" width="560px">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="账号"><el-input id="us-username" v-model="form.username" :disabled="!!form.id" /></el-form-item>
          <el-form-item label="姓名"><el-input id="us-name" v-model="form.displayName" /></el-form-item>
          <el-form-item v-if="!form.id" label="初始密码（至少 8 位）"><el-input id="us-password" v-model="form.password" type="password" show-password /></el-form-item>
        </div>
        <el-form-item label="角色">
          <el-checkbox-group id="us-roles" v-model="form.roles">
            <el-checkbox v-for="(v, k) in ROLE_LABELS" :key="k" :value="k">{{ v }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-checkbox v-if="form.id" id="us-enabled" v-model="form.enabled">启用</el-checkbox>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" :loading="form.saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reset.visible" :title="`重置密码：${reset.username}`" width="420px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="新密码（至少 8 位）"><el-input id="rs-password" v-model="reset.password" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reset.visible = false">取消</el-button>
        <el-button type="primary" :loading="reset.saving" @click="doReset">重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { ROLE_LABELS } from '../stores/auth'

const users = ref([])
const loading = ref(false)
const form = reactive({ visible: false, saving: false, id: null, username: '', displayName: '', password: '', roles: [], enabled: true })
const reset = reactive({ visible: false, saving: false, id: null, username: '', password: '' })

async function load() {
  loading.value = true
  try {
    users.value = await http.get('/users')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { visible: true, id: null, username: '', displayName: '', password: '', roles: ['VIEWER'], enabled: true })
}

function openEdit(u) {
  Object.assign(form, { visible: true, id: u.id, username: u.username, displayName: u.displayName, password: '', roles: [...u.roles], enabled: u.enabled })
}

async function save() {
  if (!form.roles.length) return ElMessage.warning('至少选择一个角色')
  form.saving = true
  try {
    if (form.id) {
      await http.put(`/users/${form.id}`, { displayName: form.displayName, enabled: form.enabled, roles: form.roles })
    } else {
      await http.post('/users', { username: form.username, displayName: form.displayName, password: form.password, roles: form.roles })
    }
    ElMessage.success('已保存')
    form.visible = false
    load()
  } finally {
    form.saving = false
  }
}

function openReset(u) {
  Object.assign(reset, { visible: true, id: u.id, username: u.username, password: '' })
}

async function doReset() {
  reset.saving = true
  try {
    await http.post(`/users/${reset.id}/reset-password`, { password: reset.password })
    ElMessage.success('密码已重置')
    reset.visible = false
  } finally {
    reset.saving = false
  }
}

onMounted(load)
</script>

<style scoped>
.role { margin: 2px 4px 2px 0; }
</style>
