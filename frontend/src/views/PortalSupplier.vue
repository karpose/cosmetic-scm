<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>采购订单协同</h2>
        <p>{{ auth.user?.partnerName }}：这里列出品牌方下给贵司的采购单。请确认订单并回复交期，发货时填送货通知，并上传检验报告。</p>
      </div>
      <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
    </div>

    <el-tabs v-model="tab" class="seg">
      <el-tab-pane :label="`进行中（${active.length}）`" name="active" />
      <el-tab-pane :label="`已结束（${finished.length}）`" name="done" />
      <el-tab-pane label="本单位资料" name="profile" />
    </el-tabs>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />

    <template v-else-if="tab !== 'profile'">
      <el-empty v-if="!shown.length && !loader.loading.value" :description="tab === 'active' ? '目前没有进行中的采购单' : '还没有已结束的采购单'" />
      <div class="cards">
        <section v-for="o in shown" :key="o.id" class="order glass">
          <header class="oh">
            <div>
              <h3 class="mono">{{ o.poNo }}</h3>
              <div class="muted small">下单 {{ o.orderDate }}<template v-if="o.expectedDate"> · 期望到货 {{ o.expectedDate }}</template></div>
              <div v-if="o.promisedDate" class="small">贵司交期 <b class="mono">{{ o.promisedDate }}</b></div>
            </div>
            <div class="tags">
              <StatusTag :status="o.status" :label="o.statusLabel" />
              <el-tag v-if="isOpen(o) && !o.confirmedAt" type="warning" size="small" disable-transitions>待确认</el-tag>
            </div>
          </header>

          <div class="lines">
            <div v-for="l in o.lines" :key="l.id" class="mat">
              <span>{{ l.itemName }} <span class="muted small mono">{{ l.itemCode }}</span></span>
              <span class="small mono">订购 {{ fmtQty(l.qty) }} · 已收 {{ fmtQty(l.receivedQty) }}<template v-if="Number(l.notifiedQty) > 0"> · 已通知 {{ fmtQty(l.notifiedQty) }}</template> {{ l.unit }}</span>
            </div>
          </div>

          <div v-if="o.shipments.length">
            <h4>送货通知</h4>
            <div v-for="s in o.shipments" :key="s.id" class="mat">
              <span class="small">
                <span class="mono">{{ s.shipmentNo }}</span> · 发货 {{ s.shipDate }}
                <el-tag size="small" :type="SHIP_TAG[s.status]" disable-transitions>{{ s.statusLabel }}</el-tag>
                <div v-for="l in s.lines" :key="l.poLineId" class="muted">{{ l.itemName }} {{ fmtQty(l.qty) }} {{ l.unit }} · 批号 <span class="mono">{{ l.extBatchNo }}</span> · 生产 {{ l.mfgDate }}<template v-if="l.expDate"> · 有效期 {{ l.expDate }}</template></div>
                <div v-if="s.note" class="muted">{{ s.note }}</div>
              </span>
              <el-button v-if="s.status === 'OPEN'" size="small" text @click="cancelShipment(s)">取消通知</el-button>
            </div>
          </div>

          <div v-if="o.promises.length > 1">
            <h4>交期记录</h4>
            <div v-for="(p, i) in o.promises" :key="i" class="small"><span class="mono">{{ p.promisedDate }}</span><span class="muted"> · {{ p.createdBy }} · {{ fmtTime(p.createdAt) }}</span><template v-if="p.note"> · {{ p.note }}</template></div>
          </div>

          <AttachmentList owner-type="PURCHASE_ORDER" :owner-id="o.id" :list-path="`/portal/supplier/orders/${o.id}/attachments`" download-prefix="/portal/supplier/attachments"
                          :can-upload="o.status !== 'CANCELLED'" title="附件（COA、检验报告）" empty-text="发货时请上传 COA 或检验报告"
                          notice="这里只列贵司上传的文件，上传后品牌方能看到。" />

          <footer v-if="isOpen(o)" class="toolbar">
            <el-button :type="o.confirmedAt ? '' : 'primary'" @click="openPromise(o)">{{ o.confirmedAt ? '修改交期' : '确认订单' }}</el-button>
            <el-button :type="o.confirmedAt ? 'primary' : ''" :disabled="!o.confirmedAt || !canNotify(o)" @click="openShip(o)">填写送货通知</el-button>
            <span v-if="!o.confirmedAt" class="muted small">确认订单后才能发送货通知</span>
            <span v-else-if="!canNotify(o)" class="muted small">未收的数量都已通知</span>
          </footer>
        </section>
      </div>
    </template>

    <template v-else>
      <el-alert v-if="profile && !profile.qualified" type="error" :closable="false" show-icon title="贵司目前不在合格供应商名录内，品牌方不能下新单。请联系品牌方采购。" />
      <el-alert v-else-if="profile && licenseSoon" :type="profile.licenseDaysLeft < 0 ? 'error' : 'warning'" :closable="false" show-icon
                :title="profile.licenseDaysLeft < 0 ? `资质已于 ${profile.licenseExpiry} 过期，品牌方不能下新单。请尽快上传新证。` : `资质还有 ${profile.licenseDaysLeft} 天到期（${profile.licenseExpiry}），请提前上传新证。`" />
      <el-card v-if="profile" shadow="never">
        <template #header><b>{{ profile.name }}</b> <span class="mono muted small">{{ profile.code }}</span></template>
        <el-descriptions :column="narrow ? 1 : 2" border>
          <el-descriptions-item label="资质编号">{{ profile.licenseNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="资质有效期"><span class="mono">{{ profile.licenseExpiry || '—' }}</span></el-descriptions-item>
          <el-descriptions-item label="许可范围">{{ profile.licenseScope || '—' }}</el-descriptions-item>
          <el-descriptions-item label="合格名录">{{ profile.qualified ? '在名录内' : '不在名录内' }}</el-descriptions-item>
          <el-descriptions-item label="最近审核"><span class="mono">{{ profile.lastAuditDate || '—' }}</span> {{ profile.lastAuditResult || '' }}</el-descriptions-item>
          <el-descriptions-item label="下次审核"><span class="mono">{{ profile.nextAuditDate || '—' }}</span></el-descriptions-item>
          <el-descriptions-item label="近 12 个月来料"><span class="mono">{{ profile.received }}</span> 批，拒收 <span class="mono">{{ profile.rejected }}</span> 批</el-descriptions-item>
          <el-descriptions-item label="近 12 个月交货"><template v-if="profile.dueTotal">到期 <span class="mono">{{ profile.dueTotal }}</span> 单，按时 <span class="mono">{{ profile.onTime }}</span> 单</template><template v-else>—</template></el-descriptions-item>
        </el-descriptions>
        <p class="muted small">资质信息由品牌方维护。有新证时请上传，品牌方核对后更新。交货按贵司第一次回复的交期计算。</p>
        <AttachmentList owner-type="PARTNER" :owner-id="0" list-path="/portal/supplier/profile/attachments" download-prefix="/portal/supplier/attachments"
                        can-upload title="资质文件" empty-text="还没有上传资质文件" notice="这里只列贵司上传的文件，上传后品牌方能看到。" />
      </el-card>
    </template>

    <el-dialog v-model="pr.visible" :title="pr.order?.confirmedAt ? `修改交期：${pr.order?.poNo}` : `确认订单：${pr.order?.poNo}`" width="460px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="交期（预计到达品牌方仓库的日期）">
          <el-date-picker id="sp-promise" v-model="pr.promisedDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d < startOfToday" />
        </el-form-item>
        <el-form-item :label="pr.order?.confirmedAt ? '修改原因' : '备注（选填）'">
          <el-input id="sp-promise-note" v-model="pr.note" type="textarea" :rows="2" maxlength="300" show-word-limit />
        </el-form-item>
        <p v-if="pr.order?.confirmedAt" class="muted small">修改会留下记录。准时交货仍按第一次回复的交期计算。</p>
      </el-form>
      <template #footer>
        <el-button @click="pr.visible = false">取消</el-button>
        <el-button type="primary" :loading="pr.saving" @click="submitPromise">{{ pr.order?.confirmedAt ? '保存交期' : '确认订单' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="sh.visible" :title="`送货通知：${sh.order?.poNo || ''}`" width="760px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="发货日期"><el-date-picker id="sp-ship-date" v-model="sh.shipDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="备注（物流单号等，选填）"><el-input id="sp-ship-note" v-model="sh.note" maxlength="300" /></el-form-item>
        </div>
        <p class="muted small">同一物料有多个批号时，请分开发送货通知。不发的物料把数量留空。</p>
        <el-card v-for="l in sh.lines" :key="l.poLineId" shadow="never" class="ship-line">
          <div class="ship-h"><b>{{ l.itemName }}</b> <span class="muted small">还能通知 {{ fmtQty(l.left) }} {{ l.unit }}</span></div>
          <div class="form-grid">
            <el-form-item label="本次数量"><el-input-number :id="`sp-qty-${l.poLineId}`" v-model="l.qty" :min="0" :max="l.left" :precision="4" :controls="false" style="width:100%" /></el-form-item>
            <el-form-item label="贵司批号"><el-input :id="`sp-batch-${l.poLineId}`" v-model="l.extBatchNo" maxlength="60" /></el-form-item>
            <el-form-item label="生产日期"><el-date-picker :id="`sp-mfg-${l.poLineId}`" v-model="l.mfgDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
            <el-form-item label="有效期至（选填）"><el-date-picker :id="`sp-exp-${l.poLineId}`" v-model="l.expDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          </div>
        </el-card>
      </el-form>
      <template #footer>
        <el-button @click="sh.visible = false">取消</el-button>
        <el-button type="primary" :loading="sh.saving" @click="submitShip">发送送货通知</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { useLoader } from '../utils/loader'

const auth = useAuth()
const orders = ref([])
const profile = ref(null)
const tab = ref('active')
const loader = useLoader(async () => {
  const [o, p] = await Promise.all([http.get('/portal/supplier/orders'), http.get('/portal/supplier/profile')])
  orders.value = o
  profile.value = p
})
const load = () => loader.run()

const SHIP_TAG = { OPEN: 'primary', RECEIVED: 'success', CANCELLED: 'info' }
const isOpen = (o) => o.status === 'OPEN' || o.status === 'PARTIAL'
const active = computed(() => orders.value.filter(isOpen))
const finished = computed(() => orders.value.filter((o) => !isOpen(o)))
const shown = computed(() => (tab.value === 'active' ? active.value : finished.value))
const leftOf = (l) => Math.max(0, Number(l.remaining) - Number(l.notifiedQty))
const canNotify = (o) => o.lines.some((l) => leftOf(l) > 0)
const licenseSoon = computed(() => profile.value?.licenseDaysLeft !== null && profile.value?.licenseDaysLeft !== undefined && profile.value.licenseDaysLeft <= 30)
const startOfToday = startOfBusinessDay()

const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { window.addEventListener('resize', onResize); load() })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

// 接口返回更新后的这张单，就地替换，不用整页刷新
function replace(view) {
  const i = orders.value.findIndex((o) => o.id === view.id)
  if (i >= 0) orders.value[i] = view
}

// ---------- 确认订单 / 修改交期 ----------
const pr = reactive({ visible: false, saving: false, order: null, promisedDate: null, note: '' })
function openPromise(o) {
  Object.assign(pr, { visible: true, saving: false, order: o, promisedDate: o.promisedDate || o.expectedDate, note: '' })
}
async function submitPromise() {
  if (!pr.promisedDate) return ElMessage.warning('请选择交期')
  if (pr.order.confirmedAt && !pr.note.trim()) return ElMessage.warning('修改交期要写明原因')
  pr.saving = true
  try {
    const first = !pr.order.confirmedAt
    replace(await http.post(`/portal/supplier/orders/${pr.order.id}/promise`, { promisedDate: pr.promisedDate, note: pr.note }))
    ElMessage.success(first ? '订单已确认' : '交期已修改')
    pr.visible = false
  } finally {
    pr.saving = false
  }
}

// ---------- 送货通知 ----------
const sh = reactive({ visible: false, saving: false, order: null, shipDate: null, note: '', lines: [] })
function openShip(o) {
  Object.assign(sh, {
    visible: true, saving: false, order: o, shipDate: today(), note: '',
    lines: o.lines.filter((l) => leftOf(l) > 0).map((l) => ({
      poLineId: l.id, itemName: l.itemName, unit: l.unit, left: leftOf(l), qty: leftOf(l), extBatchNo: '', mfgDate: null, expDate: null
    }))
  })
}
async function submitShip() {
  const lines = sh.lines.filter((l) => l.qty > 0)
  if (!sh.shipDate) return ElMessage.warning('请选择发货日期')
  if (!lines.length) return ElMessage.warning('请至少填一行数量')
  if (lines.some((l) => !l.extBatchNo.trim() || !l.mfgDate)) return ElMessage.warning('请填写每一行的批号和生产日期')
  sh.saving = true
  try {
    replace(await http.post(`/portal/supplier/orders/${sh.order.id}/shipments`, {
      shipDate: sh.shipDate, note: sh.note,
      lines: lines.map(({ poLineId, qty, extBatchNo, mfgDate, expDate }) => ({ poLineId, qty, extBatchNo, mfgDate, expDate }))
    }))
    ElMessage.success('送货通知已发送')
    sh.visible = false
  } finally {
    sh.saving = false
  }
}
async function cancelShipment(s) {
  try {
    await ElMessageBox.confirm(`取消送货通知 ${s.shipmentNo}？`, '取消送货通知', { confirmButtonText: '取消通知', cancelButtonText: '不取消', type: 'warning' })
  } catch {
    return
  }
  replace(await http.post(`/portal/supplier/shipments/${s.id}/cancel`))
  ElMessage.success('送货通知已取消')
}
</script>

<style scoped>
.cards { display: grid; gap: 16px; }
.order { padding: 18px 20px; display: grid; gap: 14px; }
.oh { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: flex-start; }
.oh h3 { margin: 0 0 4px; font-size: 18px; letter-spacing: -0.01em; }
.tags { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
h4 { margin: 0 0 8px; font-size: 13px; color: var(--el-text-color-secondary); font-weight: 600; }
.mat { display: flex; justify-content: space-between; gap: 8px; padding: 6px 0; border-bottom: 1px solid var(--lg-hairline); flex-wrap: wrap; }
.mat:last-child { border-bottom: 0; }
.ship-line { margin-bottom: 12px; }
.ship-h { margin-bottom: 8px; }
footer { padding-top: 4px; }
</style>
