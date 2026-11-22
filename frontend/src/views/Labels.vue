<template>
  <div class="labels-page" :class="layout">
    <div class="bar no-print">
      <b>批次标签</b>
      <form class="add-form" @submit.prevent="addInput">
        <el-input v-model="batchInput" size="small" placeholder="输入批号（多个用逗号或空格分隔）" clearable style="width:240px" />
        <el-button size="small" native-type="submit" :loading="adding">添加</el-button>
      </form>
      <el-radio-group v-model="layout" size="small">
        <el-radio-button value="roll">标签纸 60×40 mm</el-radio-button>
        <el-radio-button value="a4">A4 纸（每页 21 张）</el-radio-button>
      </el-radio-group>
      <label class="small">每批 <el-input-number v-model="copies" :min="1" :max="50" size="small" :controls="false" style="width:56px" /> 张</label>
      <el-checkbox v-model="showSource" size="small">印来源/外部批号</el-checkbox>
      <el-button type="primary" :icon="Printer" :disabled="!cards.length" @click="print">打印（共 {{ totalLabels }} 张）</el-button>
      <el-button v-if="cards.length" size="small" text @click="cards = []; error = ''">清空</el-button>
    </div>
    <p v-if="error" class="no-print err">{{ error }}</p>
    <p v-if="!cards.length && !error" class="no-print muted small">在上方输入批号回车添加，或从“库存 / 批次追溯”跳转进入，二维码内容为系统批号。</p>

    <div class="sheet">
      <template v-for="c in cards" :key="c.batch.batchNo">
        <div v-for="n in copies" :key="n" class="label">
          <div class="qr" v-html="c.svg" />
          <div class="info">
            <div class="name">{{ c.batch.itemName }}</div>
            <div class="code">{{ c.batch.itemCode }}</div>
            <div class="bno">{{ c.batch.batchNo }}</div>
            <div class="dates">生产 {{ c.batch.mfgDate || '—' }}</div>
            <div class="dates">效期 {{ c.batch.expDate || '—' }}</div>
            <div v-if="showSource" class="src">{{ [c.batch.partnerName, c.batch.extBatchNo].filter(Boolean).join(' · ') }}</div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { Printer } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import http from '../api'

// 批次标签：支持 /labels?batches=批号1,批号2 或现场输入批号追加补打。状态会变，标签上不印状态
const route = useRoute()
const layout = ref('roll')
const copies = ref(1)
const showSource = ref(true)
const batchInput = ref('')
const adding = ref(false)
const cards = ref([])
const error = ref('')
const totalLabels = computed(() => cards.value.length * (copies.value || 0))

async function loadBatches(nos) {
  if (!nos.length) return
  adding.value = true
  try {
    for (const no of nos) {
      if (cards.value.some((c) => c.batch.batchNo === no)) continue
      try {
        const card = await http.get(`/scan/batches/${encodeURIComponent(no)}`, { silent: true })
        const svg = await QRCode.toString(no, { type: 'svg', margin: 0, errorCorrectionLevel: 'M' })
        cards.value.push({ batch: card.batch, svg })
      } catch (e) {
        error.value = `${error.value}批号 ${no}：${e.message}。`
      }
    }
  } finally {
    adding.value = false
  }
}

async function addInput() {
  const nos = batchInput.value.split(/[\s,，;；]+/).map((s) => s.trim()).filter(Boolean)
  if (!nos.length) return
  error.value = ''
  await loadBatches(nos)
  batchInput.value = ''
}

onMounted(async () => {
  const nos = String(route.query.batches || '').split(',').map((s) => s.trim()).filter(Boolean)
  if (nos.length) await loadBatches(nos)
})

// 纸张尺寸随排版切换：标签纸每张标签一页；A4 边距 10mm，可用 190×277mm，正好排 3 列 7 行。
const pageStyle = document.createElement('style')
watchEffect(() => {
  pageStyle.textContent = layout.value === 'roll' ? '@page { size: 60mm 40mm; margin: 0; }' : '@page { size: A4; margin: 10mm; }'
})
document.head.appendChild(pageStyle)
onUnmounted(() => pageStyle.remove())
const print = () => window.print()
</script>

<style scoped>
/* 工具栏跟随主题；标签本身永远白底黑字，屏幕上看到的就是打印出来的样子 */
.labels-page { min-height: 100%; padding: 16px; box-sizing: border-box; }
.bar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding-bottom: 14px; margin-bottom: 14px; border-bottom: 1px solid var(--lg-stroke); }
.add-form { display: inline-flex; gap: 6px; align-items: center; }
.err { color: var(--el-color-danger); }
.sheet { display: flex; flex-wrap: wrap; gap: 8px; }
.label { width: 60mm; height: 40mm; box-sizing: border-box; padding: 2.5mm; background: #fff; color: #111; border: 1px dashed #bbb; display: grid; grid-template-columns: 22mm 1fr; gap: 2.5mm; align-items: center; overflow: hidden; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; }
.qr :deep(svg) { width: 22mm; height: 22mm; display: block; }
.info { min-width: 0; line-height: 1.25; }
.name { font-size: 9pt; font-weight: 700; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.code { font-size: 7pt; color: #444; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bno { font-family: "SF Mono", Menlo, Consolas, monospace; font-size: 10.5pt; font-weight: 700; margin: 1mm 0; letter-spacing: 0.02em; overflow-wrap: anywhere; }
.dates, .src { font-size: 7pt; color: #333; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

@media print {
  .no-print { display: none !important; }
  .labels-page { padding: 0; }
  .sheet { gap: 0; }
  .label { border: 0; break-inside: avoid; }
  .roll .label { break-after: page; }
  .a4 .sheet { width: 186mm; }
  .a4 .label { width: 62mm; height: 39.5mm; }
}
</style>

<style>
@media print {
  body::before { display: none !important; }
  body { background: #fff !important; }
}
</style>
