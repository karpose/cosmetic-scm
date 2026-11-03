<template>
  <el-container class="shell">
    <el-aside :width="collapsed ? '64px' : '208px'" class="aside">
      <div class="brand" :title="'化妆品批次主线系统'">
        <span class="mark">批</span>
        <span v-if="!collapsed" class="name">批次主线系统</span>
      </div>
      <el-menu :default-active="$route.path" router :collapse="collapsed" class="menu">
        <template v-for="g in groups" :key="g.title">
          <el-menu-item-group :title="collapsed ? '' : g.title">
            <el-menu-item v-for="m in g.items" :key="m.path" :index="m.path">
              <el-icon><component :is="m.icon" /></el-icon>
              <template #title>{{ m.title }}</template>
            </el-menu-item>
          </el-menu-item-group>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-button text :icon="collapsed ? Expand : Fold" @click="collapsed = !collapsed" aria-label="折叠菜单" />
        <span class="title">{{ $route.meta.title }}</span>
        <span class="spacer" />
        <el-dropdown v-if="auth.user" trigger="click" @command="onCommand">
          <span class="user">
            {{ auth.user.displayName }}
            <span class="muted small">（{{ roleText }}）</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <el-dialog v-model="pwd.visible" title="修改密码" width="420px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="原密码"><el-input id="pwd-old" v-model="pwd.oldPassword" type="password" show-password /></el-form-item>
        <el-form-item label="新密码（至少 8 位）"><el-input id="pwd-new" v-model="pwd.newPassword" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwd.visible = false">取消</el-button>
        <el-button type="primary" :loading="pwd.saving" @click="changePassword">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, Box, Coin, DataBoard, Document, Expand, Fold, Goods, Guide, ShoppingCart, Stamp, User, Van
} from '@element-plus/icons-vue'
import http from '../api'
import { useAuth, ROLE_LABELS } from '../stores/auth'

const auth = useAuth()
const router = useRouter()
const collapsed = ref(window.innerWidth < 900)

const groups = computed(() => {
  const list = [
    { title: '概况', items: [{ path: '/', title: '总览', icon: DataBoard }] },
    {
      title: '业务',
      items: [
        { path: '/purchase', title: '采购入库', icon: ShoppingCart },
        { path: '/production', title: '生产与委托', icon: Goods },
        { path: '/quality', title: '质量放行', icon: Stamp },
        { path: '/stock', title: '库存', icon: Box },
        { path: '/sales', title: '销售出库', icon: Van }
      ]
    },
    {
      title: '合规',
      items: [
        { path: '/trace', title: '批次追溯', icon: Guide },
        { path: '/audit', title: '审计日志', icon: Document }
      ]
    },
    { title: '基础', items: [{ path: '/master', title: '主数据', icon: Coin }] }
  ]
  if (auth.hasAny('ADMIN')) list[3].items.push({ path: '/users', title: '账号与权限', icon: User })
  return list
})

const roleText = computed(() => auth.roles.map((r) => ROLE_LABELS[r] || r).join('、'))

const pwd = reactive({ visible: false, oldPassword: '', newPassword: '', saving: false })

function onCommand(cmd) {
  if (cmd === 'logout') {
    auth.logout()
    router.push({ name: 'login' })
  } else if (cmd === 'password') {
    Object.assign(pwd, { visible: true, oldPassword: '', newPassword: '' })
  }
}

async function changePassword() {
  pwd.saving = true
  try {
    await http.post('/auth/password', { oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    ElMessage.success('密码已修改')
    pwd.visible = false
  } finally {
    pwd.saving = false
  }
}

</script>

<style scoped>
.shell { height: 100%; }
.aside { background: var(--el-bg-color); border-right: 1px solid var(--el-border-color-light); transition: width .2s; overflow-x: hidden; }
.brand { display: flex; align-items: center; gap: 10px; height: 56px; padding: 0 18px; border-bottom: 1px solid var(--el-border-color-lighter); }
.mark { width: 28px; height: 28px; border-radius: 6px; background: var(--el-color-primary); color: #fff; display: grid; place-items: center; font-weight: 700; flex: none; }
.name { font-weight: 700; white-space: nowrap; }
.menu { border-right: 0; }
.header { display: flex; align-items: center; gap: 8px; background: var(--el-bg-color); border-bottom: 1px solid var(--el-border-color-light); height: 56px; }
.title { font-weight: 600; }
.spacer { flex: 1; }
.user { cursor: pointer; display: inline-flex; align-items: center; gap: 4px; }
.main { padding: 0; }
@media (max-width: 600px) { .user .muted { display: none; } }
</style>
