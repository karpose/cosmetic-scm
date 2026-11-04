<template>
  <div class="shell" :class="{ collapsed }">
    <aside class="aside glass">
      <div class="brand" title="化妆品批次主线系统">
        <span class="mark">批</span>
        <span v-if="!collapsed" class="name">批次主线系统</span>
      </div>
      <el-scrollbar class="nav-scroll">
        <el-menu :default-active="activePath" router :collapse="collapsed" class="menu">
          <template v-for="g in groups" :key="g.title">
            <el-menu-item-group :title="collapsed ? '' : g.title">
              <el-menu-item v-for="m in g.items" :key="m.path" :index="m.path">
                <el-icon><component :is="m.icon" /></el-icon>
                <template #title>{{ m.title }}</template>
              </el-menu-item>
            </el-menu-item-group>
          </template>
        </el-menu>
      </el-scrollbar>
    </aside>

    <div class="body">
      <header class="header glass">
        <el-button text :icon="collapsed ? Expand : Fold" @click="collapsed = !collapsed" aria-label="折叠菜单" />
        <span class="title">{{ $route.meta.title }}</span>
        <span class="spacer" />
        <el-dropdown v-if="auth.user" trigger="click" @command="onCommand">
          <span class="user">
            <span class="avatar">{{ initial }}</span>
            <span class="who">
              <span>{{ auth.user.displayName }}</span>
              <span class="muted small roles">{{ roleText }}</span>
            </span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <main class="main">
        <router-view />
      </main>
    </div>

    <el-dialog v-model="pwd.visible" :title="forced ? '请先修改密码' : '修改密码'" width="440px"
               :show-close="!forced" :close-on-click-modal="!forced" :close-on-press-escape="!forced">
      <el-alert v-if="forced" type="warning" :closable="false" show-icon style="margin-bottom:14px"
                :title="auth.user.passwordChangeRequired === 'EXPIRED' ? '密码已超过有效期，修改后才能继续使用。' : '这是管理员设置的初始密码，修改后才能继续使用。'" />
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="原密码"><el-input id="pwd-old" v-model="pwd.oldPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="新密码"><el-input id="pwd-new" v-model="pwd.newPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="再输一次新密码"><el-input id="pwd-new2" v-model="pwd.confirm" type="password" show-password autocomplete="new-password" /></el-form-item>
        <p class="muted small" style="margin:-6px 0 0">{{ PASSWORD_RULE }}</p>
      </el-form>
      <template #footer>
        <el-button v-if="forced" @click="onCommand('logout')">退出登录</el-button>
        <el-button v-else @click="pwd.visible = false">取消</el-button>
        <el-button type="primary" :loading="pwd.saving" @click="changePassword">保存新密码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, Bell, Box, Coin, Connection, Finished, DataAnalysis, DataBoard, Document, Expand, Fold, Goods, Guide, Iphone, Memo, Notebook, OfficeBuilding,
  Operation, Postcard, RefreshLeft, ShoppingCart, Shop, Stamp, Tickets, TrendCharts, User, Van, WarningFilled
} from '@element-plus/icons-vue'
import { useAuth, PASSWORD_RULE, ROLE_LABELS } from '../stores/auth'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const collapsed = ref(window.innerWidth < 900)

const groups = computed(() => {
  if (auth.hasAny('OEM_PORTAL')) return [{ title: '协同', items: [{ path: '/portal/oem', title: '委托生产协同', icon: OfficeBuilding }] }]
  if (auth.hasAny('DEALER')) return [{ title: '订货', items: [{ path: '/portal/dealer', title: '订货与召回', icon: Shop }] }]
  const list = [
    {
      title: '概况',
      items: [
        { path: '/', title: '总览', icon: DataBoard },
        { path: '/analytics', title: '经营看板', icon: DataAnalysis }
      ]
    },
    {
      title: '业务',
      items: [
        { path: '/planning', title: '补货建议', icon: TrendCharts },
        { path: '/purchase', title: '采购入库', icon: ShoppingCart },
        { path: '/production', title: '生产与委托', icon: Goods },
        { path: '/quality', title: '质量放行', icon: Stamp },
        { path: '/stock', title: '库存', icon: Box },
        { path: '/inventory-ops', title: '库存作业', icon: Operation },
        { path: '/scan', title: '扫码作业', icon: Iphone },
        { path: '/sales', title: '销售出库', icon: Van },
        { path: '/returns', title: '销售退货', icon: RefreshLeft },
        { path: '/dealer-orders', title: '经销商订单', icon: Tickets },
        { path: '/integration', title: '电商对接', icon: Connection }
      ]
    },
    {
      title: '合规',
      items: [
        { path: '/trace', title: '批次追溯', icon: Guide },
        { path: '/quality-events', title: '投诉与召回', icon: WarningFilled },
        { path: '/qms', title: '质量体系', icon: Memo },
        { path: '/serials', title: '一物一码', icon: Postcard },
        { path: '/audit', title: '审计日志', icon: Document }
      ]
    },
    { title: '基础', items: [{ path: '/master', title: '主数据', icon: Coin }, { path: '/formulas', title: '配方与备案', icon: Notebook }] }
  ]
  if (auth.hasAny('ADMIN', 'MASTER_DATA', 'QA', 'QSO')) list[3].items.push({ path: '/golive', title: '上线检查', icon: Finished })
  if (auth.hasAny('ADMIN')) {
    list[3].items.push({ path: '/users', title: '账号与权限', icon: User })
    list[3].items.push({ path: '/notifications', title: '消息提醒', icon: Bell })
  }
  return list
})

const activePath = computed(() => route.path)
const roleText = computed(() => auth.roles.map((r) => ROLE_LABELS[r] || r).join('、'))
const initial = computed(() => (auth.user?.displayName || '?').slice(0, 1))

const pwd = reactive({ visible: false, oldPassword: '', newPassword: '', confirm: '', saving: false })
// 后端要求改密（初始密码或已过期）时，弹窗不可关闭，改完才能用
const forced = computed(() => !!auth.user?.passwordChangeRequired)
watch(forced, (v) => { if (v) Object.assign(pwd, { visible: true, oldPassword: '', newPassword: '', confirm: '' }) }, { immediate: true })

function onCommand(cmd) {
  if (cmd === 'logout') {
    auth.logout()
    router.push({ name: 'login' })
  } else if (cmd === 'password') {
    Object.assign(pwd, { visible: true, oldPassword: '', newPassword: '', confirm: '' })
  }
}

async function changePassword() {
  if (pwd.newPassword !== pwd.confirm) return ElMessage.warning('两次输入的新密码不一致')
  const wasForced = forced.value
  pwd.saving = true
  try {
    await auth.changePassword(pwd.oldPassword, pwd.newPassword)
    ElMessage.success('密码已修改')
    pwd.visible = false
    // 之前被拦下的页面数据重新加载
    if (wasForced) location.reload()
  } finally {
    pwd.saving = false
  }
}
</script>

<style scoped>
.shell { --aside-w: 228px; --gap: 14px; height: 100%; display: grid; grid-template-columns: var(--aside-w) minmax(0, 1fr); gap: var(--gap); padding: var(--gap); box-sizing: border-box; transition: grid-template-columns .25s ease; }
.shell.collapsed { --aside-w: 72px; }

.aside { display: flex; flex-direction: column; min-height: 0; overflow: hidden; background: var(--lg-surface-raised); }
.brand { display: flex; align-items: center; gap: 10px; height: 60px; padding: 0 18px; flex: none; }
.collapsed .brand { justify-content: center; padding: 0; }
.mark {
  width: 32px; height: 32px; border-radius: 10px; flex: none; display: grid; place-items: center;
  color: #fff; font-weight: 700;
  background: linear-gradient(145deg, color-mix(in srgb, var(--el-color-primary) 70%, white), var(--el-color-primary));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .5), 0 6px 14px -6px var(--el-color-primary);
}
.name { font-weight: 700; white-space: nowrap; letter-spacing: -0.01em; }
.nav-scroll { flex: 1; min-height: 0; }

.menu {
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: var(--lg-fill-hover);
  --el-menu-item-height: 42px;
  --el-menu-base-level-padding: 14px;
  border-right: 0; padding: 0 10px 12px;
}
.menu.el-menu--collapse { width: 100%; padding: 0 8px 12px; }
.menu :deep(.el-menu-item-group__title) { padding: 14px 0 6px 14px !important; font-size: 11.5px; letter-spacing: .04em; color: var(--el-text-color-secondary); }
.menu :deep(.el-menu-item) { border-radius: 12px; margin: 2px 0; transition: background .2s ease, color .2s ease; }
.menu :deep(.el-menu-item.is-active) {
  color: var(--el-color-primary); font-weight: 600;
  background: var(--lg-surface-pop);
  box-shadow: var(--lg-highlight), 0 4px 12px -6px rgba(88, 28, 58, .25);
}
.menu.el-menu--collapse :deep(.el-menu-item) { justify-content: center; padding: 0 !important; }

.body { display: flex; flex-direction: column; min-width: 0; min-height: 0; gap: var(--gap); }
.header { display: flex; align-items: center; gap: 8px; height: 56px; padding: 0 12px 0 8px; flex: none; border-radius: 18px; background: var(--lg-surface-raised); }
.title { font-weight: 650; font-size: 15px; }
.spacer { flex: 1; }
.user { cursor: pointer; display: inline-flex; align-items: center; gap: 8px; padding: 4px 8px 4px 4px; border-radius: 999px; transition: background .2s ease; }
.user:hover { background: var(--lg-fill-hover); }
.avatar { width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center; font-weight: 600; font-size: 13px; background: var(--lg-tint); color: var(--el-color-primary); border: 1px solid var(--lg-stroke); }
.who { display: grid; line-height: 1.2; font-size: 13px; }
.roles { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11.5px; }
.main { flex: 1; min-height: 0; overflow: auto; padding: 0 2px; border-radius: 18px; }

@media (max-width: 768px) {
  .shell { --gap: 8px; --aside-w: 64px; }
  .shell:not(.collapsed) { --aside-w: 200px; }
  .who { display: none; }
}
</style>
