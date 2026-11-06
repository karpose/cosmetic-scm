<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>扫码作业</h2>
        <p>扫批次标签上的二维码（扫码枪、手机摄像头或手工输入批号回车都可以）。发料、出库时逐个扫码核对，扫到不是本单的批次当场拦下；核对结果记入审计日志。</p>
      </div>
    </div>

    <el-tabs v-model="mode" class="seg" @tab-change="reset">
      <el-tab-pane label="批次查询" name="lookup" />
      <el-tab-pane label="发料核对" name="MO" />
      <el-tab-pane label="出库核对" name="SO" />
    </el-tabs>

    <el-card v-if="mode !== 'lookup'" shadow="never" body-style="padding:14px 18px">
      <div class="pick">
        <el-select aria-label="选择单据" v-model="refId" filterable :placeholder="mode === 'MO' ? '选择生产 / 委托单' : '选择出库单'" style="width:min(420px, 100%)" @change="loadRef">
          <el-option v-for="r in refs" :key="r.id" :value="r.id" :label="r.label" />
        </el-select>
        <span v-if="expected.length" class="muted small">应扫 {{ expected.length }} 个批次，已核对 {{ matchedCount }} 个</span>
      </div>
    </el-card>

    <div class="scanbar glass">
      <el-input ref="input" v-model="code" size="large" placeholder="扫码枪对准标签，或输入批号后回车" clearable class="mono"
                :disabled="mode !== 'lookup' && (!refId || refLoading)" @keyup.enter="onScan(code)" />
      <el-button v-if="cameraOk" size="large" :icon="Camera" :disabled="mode !== 'lookup' && (!refId || refLoading)" @click="openCamera">摄像头</el-button>
    </div>

    <!-- 最近一次扫码结果：颜色之外都有图标和文字 -->
    <div v-if="last" class="flash" :class="last.level">
      <el-icon class="ficon"><component :is="ICONS[last.level]" /></el-icon>
      <div>
        <b>{{ last.title }}</b>
        <div class="small">{{ last.detail }}</div>
      </div>
    </div>

    <!-- 批次查询 -->
    <el-card v-if="mode === 'lookup' && card" shadow="never">
      <template #header>
        <div class="ch">
          <span><BatchNo :no="card.batch.batchNo" /> <b>{{ card.batch.itemName }}</b> <span class="muted small">{{ card.batch.itemCode }}</span></span>
          <el-button size="small" :icon="Printer" @click="$router.push({ path: '/labels', query: { batches: card.batch.batchNo } })">打印标签</el-button>
        </div>
      </template>
      <el-descriptions :column="narrow ? 1 : 2" border size="small">
        <el-descriptions-item label="状态">{{ card.batch.statusLabel }}</el-descriptions-item>
        <el-descriptions-item label="剩余效期"><ShelfBar :ratio="card.batch.shelfRatio" /></el-descriptions-item>
        <el-descriptions-item label="生产 / 有效期">{{ card.batch.mfgDate || '—' }} → {{ card.batch.expDate || '—' }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ card.batch.partnerName || '—' }} · {{ card.batch.sourceNo }}</el-descriptions-item>
        <el-descriptions-item label="库存" :span="2">
          <span v-for="s in card.stock" :key="s.warehouseName" class="stk">{{ s.warehouseName }} <b class="mono">{{ fmtQty(s.qty) }}</b> {{ card.batch.unit }}</span>
          <span v-if="!card.stock.length" class="muted">无</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 核对清单 -->
    <el-card v-if="mode !== 'lookup' && refId" shadow="never" body-style="padding:0 0 8px">
      <el-table :data="checkRows" empty-text="这张单没有需要核对的批次" :row-class-name="({ row }) => row.state">
        <el-table-column label="" width="44"><template #default="{ row }"><el-icon :class="row.state"><component :is="ROW_ICONS[row.state]" /></el-icon></template></el-table-column>
        <el-table-column label="批号" width="160"><template #default="{ row }"><span class="mono">{{ row.batchNo }}</span></template></el-table-column>
        <el-table-column prop="itemName" label="物料 / 成品" min-width="160" />
        <el-table-column label="数量" width="110" align="right"><template #default="{ row }"><span class="mono">{{ row.qty == null ? '—' : fmtQty(row.qty) }}</span></template></el-table-column>
        <el-table-column label="核对" width="150"><template #default="{ row }">{{ STATE_LABEL[row.state] }}</template></el-table-column>
      </el-table>
      <div class="foot">
        <el-button @click="reset">重新开始</el-button>
        <el-button type="primary" :disabled="!scanned.length" :loading="saving" @click="submit">提交核对结果</el-button>
      </div>
    </el-card>

    <el-dialog v-model="cam.visible" title="摄像头扫码" width="min(480px, 94vw)" @closed="stopCamera">
      <video ref="video" class="video" playsinline muted />
      <p class="muted small">把二维码放进画面里，识别到会自动关闭。</p>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Camera, CircleCheckFilled, CircleCloseFilled, Printer, Remove, WarningFilled } from '@element-plus/icons-vue'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ShelfBar from '../components/ShelfBar.vue'
import { fmtQty } from '../utils/format'
import { latestOnly } from '../utils/latest'

const ICONS = { ok: CircleCheckFilled, warn: WarningFilled, bad: CircleCloseFilled }
const ROW_ICONS = { ok: CircleCheckFilled, todo: Remove, bad: CircleCloseFilled }
const STATE_LABEL = { ok: '已扫，一致', todo: '待扫', bad: '不是本单的批次' }

const mode = ref('lookup')
const code = ref('')
const input = ref(null)
const last = ref(null)
const card = ref(null)
const refs = ref([])
const refId = ref(null)
const refLoading = ref(false)
const refReq = latestOnly()
const lookupReq = latestOnly()
const refNo = ref('')
const expected = ref([])        // [{ batchNo, itemName, qty }]
const scanned = ref([])         // 已扫批号，按顺序
const saving = ref(false)
const narrow = window.innerWidth < 640 // 手持设备上批次卡片排一列

const focus = () => nextTick(() => input.value?.focus())

// ---------- 扫码 ----------
async function onScan(raw) {
  const no = String(raw || '').trim()
  code.value = ''
  focus()
  if (!no) return
  // 等响应期间换了单据或页签，这次扫码不算数，不能记到新单据上
  const context = `${mode.value}:${refId.value}`
  const stillHere = () => context === `${mode.value}:${refId.value}`
  const latest = lookupReq()
  let c
  try {
    c = await http.get(`/scan/batches/${encodeURIComponent(no)}`, { silent: true })
  } catch (e) {
    if (!stillHere()) return
    return flash('bad', `查不到批号 ${no}`, e.message)
  }
  if (!stillHere()) return
  if (mode.value === 'lookup') {
    if (!latest()) return
    card.value = c
    return flash(c.level, `${c.batch.batchNo} · ${c.batch.itemName}`, c.warnings.join('；') || '状态正常，可以发料 / 出库')
  }
  if (scanned.value.includes(c.batch.batchNo)) return flash('warn', `${c.batch.batchNo} 已经扫过`, '同一批次不用重复扫')
  scanned.value.push(c.batch.batchNo)
  if (!expected.value.some((e) => e.batchNo === c.batch.batchNo)) {
    return flash('bad', `${c.batch.batchNo} 不是本单的批次`, `${c.batch.itemName}：请放回，按系统分配的批次取货`)
  }
  if (c.level === 'bad') return flash('bad', `${c.batch.batchNo} 不能发出`, c.warnings.join('；'))
  flash('ok', `${c.batch.batchNo} 一致`, `${c.batch.itemName}，还剩 ${expected.value.length - matchedCount.value} 个批次待扫`)
}

function flash(level, title, detail) {
  last.value = { level, title, detail }
  if (level === 'bad' && navigator.vibrate) navigator.vibrate([120, 60, 120])
}

// ---------- 核对单 ----------
const checkRows = computed(() => {
  const rows = expected.value.map((e) => ({ ...e, state: scanned.value.includes(e.batchNo) ? 'ok' : 'todo' }))
  for (const s of scanned.value) {
    if (!expected.value.some((e) => e.batchNo === s)) rows.push({ batchNo: s, itemName: '—', qty: null, state: 'bad' })
  }
  return rows
})
const matchedCount = computed(() => expected.value.filter((e) => scanned.value.includes(e.batchNo)).length)

async function loadRefs() {
  refs.value = []
  if (mode.value === 'MO') {
    const list = await http.get('/mfg-orders')
    refs.value = list.filter((m) => m.status === 'PLANNED' || m.status === 'ISSUED')
      .map((m) => ({ id: m.id, no: m.moNo, label: `${m.moNo} · ${m.fgName} × ${fmtQty(m.plannedQty)}（${m.statusLabel}）` }))
  } else if (mode.value === 'SO') {
    const list = await http.get('/sales-orders')
    refs.value = list.map((s) => ({ id: s.id, no: s.soNo, label: `${s.soNo} · ${s.customerName} · ${s.orderDate}`, so: s }))
  }
}

async function loadRef() {
  const current = refReq()
  scanned.value = []
  last.value = null
  // 新单据的应扫清单到手之前不能核对：还留着上一张的清单，扫到它的批次会显示“一致”
  expected.value = []
  const r = refs.value.find((x) => x.id === refId.value)
  refNo.value = r?.no || ''
  if (mode.value === 'MO') {
    // 已计划的单子取先到期先出的预分配，已发料的取实际发料批次；只核对品牌供料
    refLoading.value = true
    try {
      const d = await http.get(`/mfg-orders/${refId.value}`)
      if (!current()) return
      expected.value = d.requirements.filter((q) => q.supplyMode === 'BRAND')
        .flatMap((q) => q.picks.map((p) => ({ batchNo: p.batchNo, itemName: q.itemName, qty: p.qty })))
    } finally {
      if (current()) refLoading.value = false
    }
    // 输入框刚从禁用恢复，等界面更新后才能聚焦
    await nextTick()
  } else {
    expected.value = (r?.so.allocations || []).map((a) => ({ batchNo: a.batchNo, itemName: a.itemName, qty: a.qty }))
  }
  focus()
}

async function submit() {
  saving.value = true
  try {
    const res = await http.post('/scan/checks', { refType: mode.value, refNo: refNo.value, expected: expected.value.map((e) => e.batchNo), scanned: scanned.value })
    if (res.passed) ElMessage.success('核对一致，已记录')
    else ElMessage.warning(`已记录：${res.missing.length ? `漏扫 ${res.missing.join('、')} ` : ''}${res.unexpected.length ? `扫错 ${res.unexpected.join('、')}` : ''}`)
    reset()
  } finally {
    saving.value = false
  }
}

function reset() {
  refReq()
  refLoading.value = false
  refId.value = null
  refNo.value = ''
  expected.value = []
  scanned.value = []
  last.value = null
  card.value = null
  loadRefs()
  focus()
}

// ---------- 摄像头（浏览器自带 BarcodeDetector，不支持时隐藏按钮，扫码枪和手输照常可用） ----------
const cameraOk = typeof window !== 'undefined' && 'BarcodeDetector' in window && !!navigator.mediaDevices?.getUserMedia
const cam = reactive({ visible: false, stream: null, timer: null })
const video = ref(null)

async function openCamera() {
  cam.visible = true
  await nextTick()
  try {
    cam.stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
  } catch {
    cam.visible = false
    return ElMessage.warning('无法打开摄像头，请检查浏览器权限，或改用扫码枪 / 手工输入')
  }
  video.value.srcObject = cam.stream
  await video.value.play()
  const detector = new window.BarcodeDetector({ formats: ['qr_code', 'code_128'] })
  cam.timer = setInterval(async () => {
    const found = await detector.detect(video.value).catch(() => [])
    if (found.length) {
      cam.visible = false
      onScan(found[0].rawValue)
    }
  }, 300)
}

function stopCamera() {
  clearInterval(cam.timer)
  cam.stream?.getTracks().forEach((t) => t.stop())
  cam.stream = null
}

onMounted(() => { loadRefs(); focus() })
onBeforeUnmount(stopCamera)
</script>

<style scoped>
.pick { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.scanbar { display: grid; grid-template-columns: 1fr auto; gap: 8px; padding: 12px; border-radius: 18px; }
/* 手机上输入框独占一行，提示文字才显示得全，摄像头按钮也更好按 */
@media (max-width: 640px) { .scanbar { grid-template-columns: minmax(0, 1fr); } }
.flash { display: flex; gap: 12px; align-items: center; padding: 14px 18px; border-radius: 18px; border: 1px solid var(--lg-stroke); background: var(--lg-surface); }
.flash b { font-size: 16px; }
.ficon { font-size: 30px; flex: none; }
.flash.ok { border-color: color-mix(in srgb, var(--el-color-success) 45%, transparent); }
.flash.ok .ficon, .el-icon.ok { color: var(--el-color-success); }
.flash.warn { border-color: color-mix(in srgb, var(--el-color-warning) 50%, transparent); }
.flash.warn .ficon { color: var(--el-color-warning); }
.flash.bad { border-color: color-mix(in srgb, var(--el-color-danger) 50%, transparent); background: color-mix(in srgb, var(--el-color-danger) 8%, var(--lg-surface)); }
.flash.bad .ficon, .el-icon.bad { color: var(--el-color-danger); }
.el-icon.todo { color: var(--el-text-color-secondary); }
.ch { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
.stk { margin-right: 14px; }
.foot { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px 4px; }
.video { width: 100%; border-radius: 14px; background: #000; }
</style>
