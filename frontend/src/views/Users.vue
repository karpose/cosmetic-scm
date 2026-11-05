<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>账号与权限</h2>
        <p>按岗位分配角色。成品上市放行只授予“质量安全负责人”角色，系统管理员也不能代签。受托厂和经销商用门户账号：只能有一个门户角色，必须绑定所属单位，只看得到本单位的数据。连续输错 5 次密码会锁定 15 分钟，可在这里提前解锁。</p>
      </div>
      <el-button type="primary" @click="openCreate">新增账号</el-button>
    </div>
    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="t.keyword.value" placeholder="搜索账号、姓名或所属单位" clearable />
          <el-select aria-label="按角色筛选" v-model="role" placeholder="全部角色" clearable>
            <el-option v-for="(v, k) in ROLE_LABELS" :key="k" :value="k" :label="v" />
          </el-select>
        </div>
      </template>
      <LoadError v-if="error" :message="error" :loading="loading" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loading" empty-text="没有符合条件的账号">
        <el-table-column label="账号" width="140"><template #default="{ row }"><span class="mono">{{ row.username }}</span></template></el-table-column>
        <el-table-column label="姓名 / 所属单位" min-width="180">
          <template #default="{ row }">{{ row.displayName }}<div v-if="row.partnerName" class="muted small">{{ row.partnerName }}</div></template>
        </el-table-column>
        <el-table-column label="角色" min-width="240">
          <template #default="{ row }">
            <el-tag v-for="r in row.roles" :key="r" size="small" class="role" :type="PORTAL_ROLES[r] ? 'warning' : 'primary'" disable-transitions>{{ ROLE_LABELS[r] || r }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small" disable-transitions>{{ row.enabled ? '启用' : '停用' }}</el-tag>
            <el-tag v-if="row.lockedUntil" type="danger" size="small" disable-transitions style="margin-left:4px">锁定至 {{ fmtTime(row.lockedUntil).slice(11) }}</el-tag>
            <div v-if="row.passwordChangeRequired" class="muted small">{{ row.passwordChangeRequired === 'EXPIRED' ? '密码已过期' : '待改初始密码' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text @click="openReset(row)">重置密码</el-button>
            <el-button v-if="row.lockedUntil" size="small" text type="danger" @click="unlock(row)">解锁</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <el-dialog v-model="form.visible" :title="form.id ? '编辑账号' : '新增账号'" width="600px">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="账号"><el-input id="us-username" v-model="form.username" :disabled="!!form.id" /></el-form-item>
          <el-form-item label="姓名"><el-input id="us-name" v-model="form.displayName" /></el-form-item>
          <el-form-item v-if="!portalRole" label="邮箱（选填，接收本岗位的每日待办）"><el-input id="us-email" v-model="form.email" maxlength="120" /></el-form-item>
          <el-form-item v-if="!form.id" label="初始密码"><el-input id="us-password" v-model="form.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        </div>
        <p v-if="!form.id" class="muted small hint">{{ PASSWORD_RULE }}。对方首次登录须修改密码。</p>
        <el-form-item label="内部岗位角色">
          <el-checkbox-group id="us-roles" v-model="form.roles" @change="onRoles">
            <el-checkbox v-for="(v, k) in internalRoles" :key="k" :value="k">{{ v }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="外部门户角色（二选一，不能和内部角色同时勾选）">
          <el-radio-group v-model="portalRole" @change="onPortal">
            <el-radio value="">不是门户账号</el-radio>
            <el-radio v-for="(cfg, k) in PORTAL_ROLES" :key="k" :value="k">{{ ROLE_LABELS[k] }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="portalRole" :label="portalRole === 'OEM_PORTAL' ? '所属受托生产企业' : '所属经销商（客户）'">
          <el-select v-model="form.partnerId" filterable placeholder="选择所属单位" style="width:100%">
            <el-option v-for="p in partnerOptions" :key="p.id" :value="p.id" :label="`${p.name}（${p.code}）`" />
          </el-select>
        </el-form-item>
        <el-checkbox v-if="form.id" id="us-enabled" v-model="form.enabled">启用</el-checkbox>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" :loading="form.saving" @click="save">保存账号</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reset.visible" :title="`重置密码：${reset.username}`" width="440px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="新密码"><el-input id="rs-password" v-model="reset.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        <p class="muted small hint">{{ PASSWORD_RULE }}。重置后账号同时解锁，对方下次登录须修改密码。</p>
      </el-form>
      <template #footer>
        <el-button @click="reset.visible = false">取消</el-button>
        <el-button type="primary" :loading="reset.saving" @click="doReset">重置密码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import { PASSWORD_RULE, PORTAL_ROLES, ROLE_LABELS } from '../stores/auth'
import { fmtTime } from '../utils/format'
import { textMatch, usePaged } from '../utils/table'

const users = ref([])
const partners = ref([])
const loading = ref(false)
const error = ref('')
const role = ref('')
const t = usePaged(users, { match: textMatch('username', 'displayName', 'partnerName'), extra: (u) => !role.value || u.roles.includes(role.value) })
const form = reactive({ visible: false, saving: false, id: null, username: '', displayName: '', password: '', roles: [], enabled: true, partnerId: null, email: '' })
const reset = reactive({ visible: false, saving: false, id: null, username: '', password: '' })
const portalRole = ref('')

const internalRoles = computed(() => Object.fromEntries(Object.entries(ROLE_LABELS).filter(([k]) => !PORTAL_ROLES[k])))
const partnerOptions = computed(() => partners.value.filter((p) => p.kind === PORTAL_ROLES[portalRole.value]?.partnerKind))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [us, ps] = await Promise.all([http.get('/users'), http.get('/partners')])
    users.value = us
    partners.value = ps
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// 勾内部角色就清掉门户角色，反之亦然：两者互斥
function onRoles() {
  if (form.roles.length) {
    portalRole.value = ''
    form.partnerId = null
  }
}
function onPortal(v) {
  if (v) form.roles = []
  form.partnerId = null
}

function openCreate() {
  Object.assign(form, { visible: true, id: null, username: '', displayName: '', password: '', roles: ['VIEWER'], enabled: true, partnerId: null, email: '' })
  portalRole.value = ''
}

function openEdit(u) {
  const portal = u.roles.find((r) => PORTAL_ROLES[r]) || ''
  Object.assign(form, {
    visible: true, id: u.id, username: u.username, displayName: u.displayName, password: '',
    roles: u.roles.filter((r) => !PORTAL_ROLES[r]), enabled: u.enabled, partnerId: u.partnerId, email: u.email || ''
  })
  portalRole.value = portal
}

async function save() {
  const roles = portalRole.value ? [portalRole.value] : form.roles
  if (!roles.length) return ElMessage.warning('至少选择一个角色')
  const partnerId = portalRole.value ? form.partnerId : null
  form.saving = true
  try {
    if (form.id) {
      await http.put(`/users/${form.id}`, { displayName: form.displayName, enabled: form.enabled, roles, partnerId, email: form.email })
    } else {
      await http.post('/users', { username: form.username, displayName: form.displayName, password: form.password, roles, partnerId, email: form.email })
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
    ElMessage.success('密码已重置，账号已解锁')
    reset.visible = false
    load()
  } finally {
    reset.saving = false
  }
}

async function unlock(u) {
  await http.post(`/users/${u.id}/unlock`)
  ElMessage.success(`${u.username} 已解锁`)
  load()
}

onMounted(load)
</script>

<style scoped>
.role { margin: 2px 4px 2px 0; }
.hint { margin: -6px 0 14px; }
</style>
