<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>询价比价</h2>
        <p>向合格供应商询价，供应商在门户报价，互相看不到。截止或开标前内部也看不到价格。定标要签批，定标人不能是询价人，定标后自动生成带价格的采购单。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <el-button v-if="canBuy" type="primary" @click="openCreate">发起询价</el-button>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters"><el-input v-model="t.keyword.value" placeholder="搜索单号、标题、物料" clearable /></div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loader.loading.value" empty-text="还没有询价">
        <el-table-column label="询价单" width="150"><template #default="{ row }"><span class="mono">{{ row.rfqNo }}</span><div class="muted small mono">截止 {{ row.deadline }}</div></template></el-table-column>
        <el-table-column label="标题和物料" min-width="240">
          <template #default="{ row }"><b>{{ row.title }}</b><div v-for="l in row.lines" :key="l.id" class="muted small">{{ l.itemName }} × {{ fmtQty(l.qty) }} {{ l.unit }}</div></template>
        </el-table-column>
        <el-table-column label="报价情况" min-width="210">
          <template #default="{ row }">
            <div v-for="s in row.suppliers" :key="s.supplierId" class="small">{{ s.supplierName }} <el-tag size="small" :type="s.quoted ? 'success' : 'info'" disable-transitions>{{ s.quoted ? '已报' : '未报' }}</el-tag></div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.closeReason" class="muted small">{{ row.closeReason }}</div></template></el-table-column>
        <el-table-column label="" width="190" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="row.sealed && canBuy" size="small" @click="openEarly(row)">提前开标</el-button>
            <el-button v-if="row.canAward && canBuy" size="small" type="primary" @click="openAward(row)">比价定标</el-button>
            <el-button v-if="row.status === 'AWARDED'" size="small" @click="openAward(row)">比价记录</el-button>
            <el-button v-if="(row.sealed || row.canAward) && canBuy" size="small" text @click="cancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListCapHint :count="rows.length" />
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" title="发起询价" width="640px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="标题"><el-input id="rq-title" v-model="cr.title" maxlength="120" placeholder="如：2027 年一季度甘油询价" /></el-form-item>
          <el-form-item label="报价截止日期（当天仍可报）"><el-date-picker id="rq-deadline" v-model="cr.deadline" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d < startOfToday" /></el-form-item>
        </div>
        <el-form-item label="邀请的供应商（建议至少 3 家，不足 3 家定标时要写原因）">
          <el-select id="rq-suppliers" v-model="cr.supplierIds" multiple filterable placeholder="选择供应商" style="width:100%">
            <el-option v-for="p in rd.suppliers.value" :key="p.id" :value="p.id" :disabled="!p.usable" :label="p.name + (p.usable ? '' : '（不在合格名录或资质过期）')" />
          </el-select>
        </el-form-item>
        <el-form-item label="物料与数量">
          <div style="width:100%">
            <div v-for="(l, i) in cr.lines" :key="i" class="line-row">
              <el-select aria-label="物料" :id="`rq-item-${i}`" v-model="l.itemId" placeholder="选择物料" filterable>
                <el-option v-for="m in rd.materials.value" :key="m.id" :value="m.id" :disabled="m.banned" :label="`${m.code} ${m.name}${m.banned ? '（禁用原料）' : ''}`" />
              </el-select>
              <el-input-number aria-label="数量" :id="`rq-qty-${i}`" v-model="l.qty" :min="0" :precision="4" :controls="false" placeholder="数量" style="width:100%" />
              <el-button text :disabled="cr.lines.length === 1" @click="cr.lines.splice(i, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="cr.lines.push({ itemId: null, qty: null })">添加一行</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" @click="submitCreate">发起询价</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="aw.visible" :title="`${aw.readonly ? '比价记录' : '比价定标'}：${aw.rfq?.title || ''}`" width="860px" :close-on-click-modal="false">
      <template v-if="aw.rfq">
        <div v-for="l in aw.rfq.lines" :key="l.id" class="award-line">
          <b>{{ l.itemName }}</b> <span class="muted small mono">× {{ fmtQty(l.qty) }} {{ l.unit }}</span>
          <el-button v-if="!aw.readonly && l.quotes.length" size="small" text @click="aw.picks[l.id] = null">本行不定标</el-button>
          <span v-if="!aw.readonly && l.quotes.length && !aw.picks[l.id]" class="late small">本行不定标，不生成采购单</span>
          <el-empty v-if="!l.quotes.length" description="没有供应商报价，这一行不定标" :image-size="40" />
          <el-table v-else :data="l.quotes" size="small" scrollbar-tabindex="0">
            <el-table-column v-if="!aw.readonly" label="" width="70">
              <template #default="{ row }"><el-radio v-model="aw.picks[l.id]" :value="row.supplierId" :aria-label="`定 ${row.supplierName}`">选</el-radio></template>
            </el-table-column>
            <el-table-column label="供应商" min-width="150"><template #default="{ row }">{{ row.supplierName }} <el-tag v-if="row.lowest" size="small" type="success" disable-transitions>最低</el-tag><el-tag v-if="row.awarded" size="small" type="primary" disable-transitions>中标</el-tag></template></el-table-column>
            <el-table-column label="含税单价" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.unitPrice) }}</span></template></el-table-column>
            <el-table-column label="税率" width="70" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.taxRate) }}%</span></template></el-table-column>
            <el-table-column label="金额" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template></el-table-column>
            <el-table-column label="交货" width="80" align="right"><template #default="{ row }">{{ row.leadDays }} 天</template></el-table-column>
            <el-table-column label="有效期至" width="110"><template #default="{ row }"><span class="mono" :class="{ late: row.validUntil < todayStr }">{{ row.validUntil }}</span></template></el-table-column>
            <el-table-column prop="note" label="备注" min-width="110" />
          </el-table>
        </div>
        <template v-if="aw.readonly">
          <p class="small">定标：{{ aw.rfq.awardedBy }} · {{ fmtTime(aw.rfq.awardedAt) }}<template v-if="aw.rfq.awardReason"> · 原因：{{ aw.rfq.awardReason }}</template></p>
          <p class="small muted">生成采购单：{{ awardedPos.join('、') }}</p>
        </template>
        <el-form v-else label-position="top" @submit.prevent>
          <el-alert v-if="needReason" type="warning" :closable="false" show-icon title="有物料不足 3 家报价、没有选最低价，或有报价却不定标，要写明定标原因" />
          <el-form-item label="定标原因"><el-input id="aw-reason" v-model="aw.reason" type="textarea" :rows="2" maxlength="300" show-word-limit /></el-form-item>
          <el-form-item label="签批密码"><el-input id="aw-password" v-model="aw.password" type="password" show-password autocomplete="current-password" /></el-form-item>
          <p v-if="aw.rfq.createdBy === auth.user?.username" class="late small">这张询价是您发起的，定标要由另一位有权限的同事签批。</p>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="aw.visible = false">{{ aw.readonly ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!aw.readonly" type="primary" :loading="aw.saving" @click="submitAward">签批定标</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtMoney, fmtQty, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const rd = useRefData()
const canBuy = computed(() => auth.hasAny('ADMIN', 'PURCHASE'))
const rows = ref([])
const loader = useLoader(async () => { rows.value = await http.get('/rfqs') })
const load = () => loader.run()
const t = usePaged(rows, { match: textMatch('rfqNo', 'title', (r) => r.lines.map((l) => l.itemName).join(' ')) })
const startOfToday = startOfBusinessDay()
const todayStr = today()
onMounted(() => { load(); rd.load() })
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const cr = reactive({ visible: false, saving: false, title: '', deadline: null, supplierIds: [], lines: [] })
async function openCreate() {
  await rd.ready()
  Object.assign(cr, { visible: true, saving: false, title: '', deadline: null, supplierIds: [], lines: [{ itemId: null, qty: null }] })
}
async function submitCreate() {
  const lines = cr.lines.filter((l) => l.itemId || l.qty)
  if (!cr.title.trim() || !cr.deadline) return ElMessage.warning('请填写标题和报价截止日期')
  if (!cr.supplierIds.length) return ElMessage.warning('请选择供应商')
  if (!lines.length || lines.some((l) => !l.itemId || !(l.qty > 0))) return ElMessage.warning('请把每一行的物料和数量填完整')
  cr.saving = true
  try {
    const r = await http.post('/rfqs', { title: cr.title, deadline: cr.deadline, supplierIds: cr.supplierIds, lines })
    ElMessage.success(`询价 ${r.rfqNo} 已发起`)
    cr.visible = false
    load()
  } finally {
    cr.saving = false
  }
}

async function openEarly(r) {
  try {
    await ElMessageBox.confirm('开标后所有报价可见，供应商不能再改价。要所有邀请的供应商都报完才能提前开标。', `提前开标 ${r.rfqNo}`, { confirmButtonText: '提前开标', cancelButtonText: '不开标', type: 'warning' })
  } catch {
    return
  }
  await http.post(`/rfqs/${r.id}/open`)
  ElMessage.success('已开标')
  load()
}

async function cancel(r) {
  let reason
  try {
    reason = (await ElMessageBox.prompt('写明原因。', `取消询价 ${r.rfqNo}`, { confirmButtonText: '取消询价', cancelButtonText: '不取消', inputValidator: (v) => !!v?.trim() || '请填写取消原因' })).value
  } catch {
    return
  }
  await http.post(`/rfqs/${r.id}/cancel`, { reason })
  ElMessage.success('询价已取消')
  load()
}

const aw = reactive({ visible: false, saving: false, readonly: false, rfq: null, picks: {}, reason: '', password: '' })
function openAward(r) {
  const picks = {}
  // 默认选最低价且报价没过期的
  for (const l of r.lines) picks[l.id] = (l.quotes.find((q) => q.lowest && q.validUntil >= todayStr) || l.quotes.find((q) => q.validUntil >= todayStr))?.supplierId ?? null
  Object.assign(aw, { visible: true, saving: false, readonly: r.status === 'AWARDED', rfq: r, picks, reason: '', password: '' })
}
// 和后端一致：没人报价的行不算；有报价的行不足 3 家、不是最低价或不定标都要写原因
const needReason = computed(() => (aw.rfq?.lines || []).some((l) => l.quotes.length > 0 && (l.quotes.length < 3 || !l.quotes.find((q) => q.supplierId === aw.picks[l.id])?.lowest)))
const awardedPos = computed(() => [...new Set((aw.rfq?.lines || []).flatMap((l) => l.quotes.filter((q) => q.awarded).map((q) => q.poNo)))])
async function submitAward() {
  const lines = aw.rfq.lines.filter((l) => aw.picks[l.id]).map((l) => ({ rfqLineId: l.id, supplierId: aw.picks[l.id] }))
  if (!lines.length) return ElMessage.warning('至少定标一行。都不要的话请取消询价')
  if (needReason.value && !aw.reason.trim()) return ElMessage.warning('请写明定标原因')
  if (!aw.password) return ElMessage.warning('请输入签批密码')
  aw.saving = true
  try {
    await http.post(`/rfqs/${aw.rfq.id}/award`, { lines, reason: aw.reason, password: aw.password })
    ElMessage.success('已定标，采购单已生成')
    aw.visible = false
    load()
  } finally {
    aw.saving = false
  }
}
</script>

<style scoped>
.award-line { margin-bottom: 16px; }
.late { color: var(--el-color-danger); }
</style>
