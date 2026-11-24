<template>
  <div class="shell" :class="{ collapsed }">
    <!-- 手机上侧栏是抽屉：默认藏起来，不占屏幕；展开时盖在内容上 -->
    <div v-if="isMobile && !collapsed" class="scrim" @click="collapsed = true" />
    <aside class="aside glass" @keydown.esc="isMobile && (collapsed = true)">
      <div class="brand" title="化妆品批次主线系统">
        <span class="mark">批</span>
        <span v-if="!collapsed" class="name">批次主线系统</span>
      </div>
      <el-scrollbar class="nav-scroll">
        <!-- 侧栏是页面导航，用真实链接：能用 Tab 走、能在新标签页打开，读屏也能念出当前页 -->
        <nav class="menu" aria-label="主导航">
          <section v-for="g in groups" :key="g.title" class="menu-group">
            <h2 v-if="!collapsed" class="menu-title">{{ g.title }}</h2>
            <ul>
              <li v-for="m in g.items" :key="m.path">
                <el-tooltip :content="m.title" placement="right" :disabled="!collapsed" :show-after="150">
                  <router-link :to="m.path" class="menu-link" :class="{ active: activePath === m.path }" :aria-label="collapsed ? m.title : undefined">
                    <el-icon :size="18" aria-hidden="true"><component :is="m.icon" /></el-icon>
                    <span v-if="!collapsed" class="menu-text">{{ m.title }}</span>
                  </router-link>
                </el-tooltip>
              </li>
            </ul>
          </section>
        </nav>
      </el-scrollbar>
    </aside>

    <div class="body">
      <header class="header glass">
        <el-button text :icon="collapsed ? Expand : Fold" @click="collapsed = !collapsed" :aria-label="collapsed ? '展开菜单' : '折叠菜单'" :aria-expanded="!collapsed" />
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
        <!-- 必须先改密码时后端会拒绝所有业务接口。不加载页面，否则新员工第一眼看到的是红色报错 -->
        <router-view v-if="!forced" />
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
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, Bell, Box, Coin, Connection, Finished, DataAnalysis, DataBoard, Document, Expand, Fold, Goods, Guide, Iphone, Memo, Notebook, OfficeBuilding,
  Operation, Postcard, RefreshLeft, ShoppingCart, Shop, Stamp, Tickets, Files, MagicStick, Picture, Switch, Timer, TrendCharts, User, Van, WarningFilled, Checked
} from '@element-plus/icons-vue'
import { useAuth, PASSWORD_RULE, ROLE_LABELS } from '../stores/auth'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const collapsed = ref(window.innerWidth < 900)
const mobileQuery = window.matchMedia('(max-width: 768px)')
const isMobile = ref(mobileQuery.matches)
function onViewportChange(e) {
  isMobile.value = e.matches
  if (e.matches) collapsed.value = true
}
mobileQuery.addEventListener('change', onViewportChange)
onBeforeUnmount(() => mobileQuery.removeEventListener('change', onViewportChange))
// 手机上选完菜单就收起抽屉
watch(() => route.path, () => { if (isMobile.value) collapsed.value = true })

const groups = computed(() => {
  if (auth.hasAny('OEM_PORTAL')) return [{ title: '协同', items: [{ path: '/portal/oem', title: '委托生产协同', icon: OfficeBuilding }] }]
  if (auth.hasAny('SUPPLIER_PORTAL')) return [{ title: '协同', items: [{ path: '/portal/supplier', title: '采购订单协同', icon: Van }] }]
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
        { path: '/supplier-returns', title: '退供', icon: RefreshLeft },
        { path: '/production', title: '生产与委托', icon: Goods },
        { path: '/quality', title: '质量放行', icon: Stamp },
        { path: '/specs', title: '质量标准', icon: Tickets },
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
        { path: '/self-checks', title: 'GMP 自查', icon: Checked },
        { path: '/compliance', title: '合规资料', icon: Stamp },
        { path: '/stability', title: '稳定性试验', icon: Timer },
        { path: '/npd', title: '新品立项', icon: MagicStick },
        { path: '/artworks', title: '标签设计稿', icon: Picture },
        { path: '/docs', title: '受控文件', icon: Files },
        { path: '/changes', title: '变更控制', icon: Switch },
        { path: '/serials', title: '一物一码', icon: Postcard },
        { path: '/audit', title: '审计日志', icon: Document }
      ]
    },
    { title: '基础', items: [{ path: '/master', title: '主数据', icon: Coin }, { path: '/formulas', title: '配方与备案', icon: Notebook }] }
  ]
  // 价格和金额只给采购和财务
  if (auth.hasAny('ADMIN', 'PURCHASE', 'FINANCE')) {
    list[1].items.splice(3, 0, { path: '/sourcing', title: '询价比价', icon: Tickets }, { path: '/settlement', title: '对账结算', icon: Coin })
  }
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

.menu { padding: 0 10px 12px; }
.collapsed .menu { padding: 0 8px 12px; }
.collapsed .menu-group + .menu-group { margin-top: 10px; }
.menu ul { list-style: none; margin: 0; padding: 0; }
.menu-title { margin: 0; padding: 14px 0 6px 14px; font-size: 11.5px; font-weight: 500; line-height: 20px; letter-spacing: .04em; color: var(--el-text-color-secondary); }
.menu-link {
  display: flex; align-items: center; gap: 10px; height: 42px; margin: 2px 0; padding: 0 14px; box-sizing: border-box;
  border-radius: 12px; font-size: 14px; white-space: nowrap; text-decoration: none; color: var(--el-text-color-primary);
  transition: background .2s ease, color .2s ease;
}
.menu-link:hover { background: var(--lg-fill-hover); }
.menu-link:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.menu-link.active {
  color: var(--el-color-primary); font-weight: 600;
  background: var(--lg-surface-pop);
  box-shadow: var(--lg-highlight), 0 4px 12px -6px rgba(88, 28, 58, .25);
}
.collapsed .menu-link { justify-content: center; padding: 0; }

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
  .shell, .shell.collapsed { --gap: 8px; grid-template-columns: minmax(0, 1fr); }
  .aside {
    position: fixed; top: 8px; bottom: 8px; left: 8px; width: 228px; z-index: 1500;
    transition: transform .25s ease, visibility 0s;
  }
  /* visibility 让收起的链接也不能被 Tab 选中 */
  .shell.collapsed .aside { transform: translateX(calc(-100% - 16px)); visibility: hidden; transition: transform .25s ease, visibility 0s .25s; }
  .scrim { position: fixed; inset: 0; z-index: 1400; background: var(--el-overlay-color-lighter); }
  .header :deep(.el-button) { width: 40px; height: 40px; }
  .who { display: none; }
}
</style>
