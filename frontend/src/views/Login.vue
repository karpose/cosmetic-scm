<template>
  <div class="login">
    <div class="card">
      <div class="head">
        <span class="mark">批</span>
        <div>
          <h1>化妆品批次主线系统</h1>
          <p class="muted">采购 · 放行 · 生产 · 出库 · 追溯</p>
        </div>
      </div>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="账号">
          <el-input id="login-username" v-model="form.username" autocomplete="username" autofocus />
        </el-form-item>
        <el-form-item label="密码">
          <el-input id="login-password" v-model="form.password" type="password" show-password autocomplete="current-password" @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" class="full" native-type="submit" :loading="loading">登录</el-button>
      </el-form>
      <p class="muted small hint">首次部署的管理员账号见 README；登录后请立即修改初始密码。</p>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) return
  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    router.replace(route.query.redirect || '/')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login { min-height: 100%; display: grid; place-items: center; padding: 24px 16px; box-sizing: border-box; }
.card { width: min(380px, 100%); background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 10px; padding: 28px; box-sizing: border-box; }
.head { display: flex; gap: 12px; align-items: center; margin-bottom: 20px; }
.mark { width: 40px; height: 40px; border-radius: 8px; background: var(--el-color-primary); color: #fff; display: grid; place-items: center; font-weight: 700; font-size: 18px; flex: none; }
h1 { font-size: 18px; margin: 0; }
.head p { margin: 2px 0 0; font-size: 12.5px; }
.full { width: 100%; margin-top: 4px; }
.hint { margin: 16px 0 0; }
</style>
