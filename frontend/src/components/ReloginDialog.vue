<template>
  <!-- 令牌到期（默认 12 小时）或在别处改了密码时弹出。不跳转登录页，正在填的表单才不会丢 -->
  <el-dialog :model-value="session.expired" title="登录已过期" width="420px" append-to-body
             :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" @opened="focus">
    <p class="small" style="margin:0 0 14px">你正在填写的内容还在。重新输入密码后，再操作一次就可以。</p>
    <el-form label-position="top" @submit.prevent="relogin">
      <el-form-item label="账号"><el-input :model-value="auth.user?.username" disabled /></el-form-item>
      <el-form-item label="密码">
        <el-input ref="input" v-model="password" type="password" show-password autocomplete="current-password" @keyup.enter="relogin" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="leave">退出登录</el-button>
      <el-button type="primary" :loading="busy" :disabled="!password" @click="relogin">重新登录</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'
import { session } from '../utils/session'

const auth = useAuth()
const router = useRouter()
const password = ref('')
const busy = ref(false)
const input = ref(null)

const focus = () => input.value?.focus()

async function relogin() {
  if (!password.value || busy.value) return
  busy.value = true
  try {
    await auth.login(auth.user.username, password.value)
    password.value = ''
    session.expired = false
    ElMessage.success('已重新登录，请再操作一次')
  } catch {
    // 密码错误、账号锁定或停用：接口层已提示，留在这里
  } finally {
    busy.value = false
  }
}

function leave() {
  const redirect = router.currentRoute.value.fullPath
  password.value = ''
  auth.logout()
  router.push({ name: 'login', query: { redirect } })
}
</script>
