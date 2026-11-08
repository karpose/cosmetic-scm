<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>标签设计稿</h2>
        <p>印刷包材的设计稿按包材分版本，对应提交时的生效配方。质量安全负责人对照成分表逐项评审、上传设计稿后才能批准（不能是提交人）；配方变了，已批准的设计稿要重新评审。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">提交设计稿</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="pg.keyword.value" placeholder="搜索编号、包材、成品" clearable />
          <el-checkbox v-model="currentOnly">只看现行和待签批</el-checkbox>
        </div>
      </template>
      <el-table :data="pg.paged.value" v-loading="loader.loading.value" empty-text="还没有设计稿">
        <el-table-column label="编号" width="160"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row)">{{ row.artworkNo }}</a><div class="muted small">第 {{ row.versionNo }} 版</div></template></el-table-column>
        <el-table-column label="包材" min-width="180"><template #default="{ row }">{{ row.pkName }}<div class="muted small mono">{{ row.pkCode }}</div></template></el-table-column>
        <el-table-column label="成品和配方" min-width="180"><template #default="{ row }">{{ row.fgName }}<div class="muted small"><span class="mono">{{ row.fgCode }}</span> · 配方 V{{ row.formulaVersionNo }}</div></template></el-table-column>
        <el-table-column label="状态" width="150"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.stale" class="late small">配方已变，要重新评审</div></template></el-table-column>
        <el-table-column label="" width="90" :fixed="narrow ? false : 'right'"><template #default="{ row }"><el-button size="small" text @click="openDetail(row)">详情</el-button></template></el-table-column>
      </el-table>
      <ListCapHint :count="artworks.length" />
      <ListPager v-model:page="pg.page.value" v-model:size="pg.size.value" :total="pg.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" title="提交设计稿" width="560px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="成品">
          <el-select id="aw-fg" v-model="cr.fgItemId" filterable placeholder="选择成品" style="width:100%">
            <el-option v-for="i in rd.finished.value" :key="i.id" :value="i.id" :label="`${i.name}（${i.code}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="包材（要在这个成品的生效配方里）">
          <el-select id="aw-pk" v-model="cr.pkItemId" filterable placeholder="选择包材" style="width:100%">
            <el-option v-for="i in packages" :key="i.id" :value="i.id" :label="`${i.name}（${i.code}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="设计稿内容或改了什么"><el-input id="aw-note" v-model="cr.changeNote" maxlength="300" placeholder="如：首版；按新规改警示用语" /></el-form-item>
        <p class="muted small">保存后上传设计稿文件，再由质量安全负责人评审。</p>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" @click="submitCreate">保存设计稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dt.visible" :title="`设计稿 ${a?.artworkNo || ''}`" width="720px">
      <template v-if="a">
        <p class="summary-line"><StatusTag :status="a.status" :label="a.statusLabel" /> <b>{{ a.pkName }}</b> 第 {{ a.versionNo }} 版 · {{ a.fgName }} 配方 V{{ a.formulaVersionNo }}</p>
        <p class="small">{{ a.changeNote }} <span class="muted">（{{ a.createdBy }} 提交<template v-if="a.approvedBy">，{{ a.approvedBy }} 批准于 {{ fmtTime(a.approvedAt) }}</template>）</span></p>
        <el-alert v-if="a.stale" type="warning" :closable="false" show-icon title="成品的生效配方已经变了，成分表可能要改，请提交新版设计稿重新评审。" />
        <el-alert v-if="a.rejectNote" type="info" :closable="false" show-icon :title="`退回：${a.rejectNote}`" />
        <AttachmentList owner-type="LABEL_ARTWORK" :owner-id="a.id" :can-upload="canEdit && a.status === 'PENDING'" title="设计稿文件" empty-text="还没有上传设计稿" />

        <div class="sub-head"><b>全成分表草稿（按当前生效配方，供核对）</b></div>
        <p v-if="!ing.data" class="muted small">正在读取…</p>
        <template v-else>
          <p class="small ing">{{ ing.data.text || '—' }}</p>
          <p class="muted small">{{ ing.data.problems.join('；') }}</p>
        </template>

        <div class="sub-head"><b>评审清单</b></div>
        <div class="checks">
          <el-checkbox v-for="c in checklist" :key="c.code" :model-value="a.status === 'PENDING' ? dt.checked.includes(c.code) : a.checked.includes(c.code)"
                       :disabled="!(isQso && a.status === 'PENDING')" @change="(v) => toggle(c.code, v)">{{ c.label }}</el-checkbox>
        </div>
        <el-form v-if="isQso && a.status === 'PENDING'" label-position="top" style="margin-top:10px" @submit.prevent>
          <p v-if="a.createdBy === auth.user?.username" class="late small">这版是您提交的，要由另一位质量安全负责人批准。</p>
          <el-form-item label="签批密码"><el-input id="aw-password" v-model="dt.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="approve" /></el-form-item>
        </el-form>
      </template>
      <template #footer>
        <template v-if="a && isQso && a.status === 'PENDING'">
          <el-button text @click="reject">退回</el-button>
          <el-button type="primary" :loading="dt.saving" :disabled="dt.checked.length < checklist.length" @click="approve">批准</el-button>
        </template>
        <el-button @click="dt.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtTime } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const rd = useRefData()
const canEdit = computed(() => auth.hasAny('MASTER_DATA', 'QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const artworks = ref([])
const checklist = ref([])
const currentOnly = ref(true)
const loader = useLoader(async () => {
  const o = await http.get('/artworks')
  artworks.value = o.artworks
  checklist.value = o.checklist
})
const load = () => loader.run()
const shown = computed(() => currentOnly.value ? artworks.value.filter((x) => x.status === 'APPROVED' || x.status === 'PENDING') : artworks.value)
const pg = usePaged(shown, { match: textMatch('artworkNo', 'pkName', 'pkCode', 'fgName', 'fgCode') })
const packages = computed(() => rd.s.items.filter((i) => i.itemType === 'PK' && i.active))
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { load(); rd.load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const cr = reactive({ visible: false, saving: false, fgItemId: null, pkItemId: null, changeNote: '' })
function openCreate() {
  Object.assign(cr, { visible: true, saving: false, fgItemId: null, pkItemId: null, changeNote: '' })
}
async function submitCreate() {
  if (!cr.fgItemId || !cr.pkItemId || !cr.changeNote.trim()) return ElMessage.warning('请选择成品和包材，并写明设计稿内容')
  cr.saving = true
  try {
    const x = await http.post('/artworks', { fgItemId: cr.fgItemId, pkItemId: cr.pkItemId, changeNote: cr.changeNote })
    ElMessage.success(`设计稿 ${x.artworkNo} 已保存，请上传文件`)
    cr.visible = false
    load()
    openDetail(x)
  } finally {
    cr.saving = false
  }
}

const dt = reactive({ visible: false, saving: false, row: null, checked: [], password: '' })
const a = computed(() => dt.row)
const ing = reactive({ data: null })
const ingReq = latestOnly()
async function openDetail(row) {
  Object.assign(dt, { visible: true, saving: false, row, checked: [], password: '' })
  ing.data = null
  const current = ingReq()
  const d = await http.get(`/compliance/products/${row.fgItemId}/ingredients`, { silent: true }).catch(() => ({ text: '', problems: ['读取成分表失败'] }))
  if (current()) ing.data = d
}
function toggle(code, v) {
  dt.checked = v ? [...new Set([...dt.checked, code])] : dt.checked.filter((x) => x !== code)
}
async function approve() {
  if (dt.checked.length < checklist.value.length) return ElMessage.warning('请逐项确认评审清单')
  if (!dt.password) return ElMessage.warning('请输入签批密码')
  dt.saving = true
  try {
    dt.row = await http.post(`/artworks/${a.value.id}/approve`, { checked: dt.checked, password: dt.password })
    dt.password = ''
    ElMessage.success('已批准')
    load()
  } finally {
    dt.saving = false
  }
}
async function reject() {
  let note
  try {
    note = (await ElMessageBox.prompt('写明退回原因。', `退回 ${a.value.artworkNo}`, { confirmButtonText: '退回', cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  dt.row = await http.post(`/artworks/${a.value.id}/reject`, { note })
  ElMessage.success('已退回')
  load()
}
</script>

<style scoped>
.late { color: var(--el-color-danger); }
.summary-line { margin: 0 0 6px; line-height: 1.8; }
.sub-head { margin: 14px 0 6px; }
.ing { line-height: 1.7; }
.checks { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 2px 16px; }
</style>
