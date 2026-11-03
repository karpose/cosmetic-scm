<template>
  <div class="login">
    <div class="card glass">
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
    router.replace(auth.portalHome || route.query.redirect || '/')
  } catch {
    // 错误已由 api.js 统一提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login { min-height: 100%; display: grid; place-items: center; padding: 24px 16px; box-sizing: border-box; }
.card { width: min(400px, 100%); padding: 32px 30px 26px; box-sizing: border-box; border-radius: 28px; background: var(--lg-surface-raised); -webkit-backdrop-filter: var(--lg-blur-strong); backdrop-filter: var(--lg-blur-strong); }
.head { display: flex; gap: 14px; align-items: center; margin-bottom: 24px; }
.mark {
  width: 46px; height: 46px; border-radius: 14px; flex: none; display: grid; place-items: center;
  color: #fff; font-weight: 700; font-size: 20px;
  background: linear-gradient(145deg, color-mix(in srgb, var(--el-color-primary) 70%, white), var(--el-color-primary));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .5), 0 10px 22px -8px var(--el-color-primary);
}
h1 { font-size: 20px; margin: 0; letter-spacing: -0.02em; }
.head p { margin: 4px 0 0; font-size: 12.5px; }
.full { width: 100%; height: 42px; margin-top: 6px; font-size: 15px; }
.hint { margin: 18px 0 0; line-height: 1.6; }
</style>
