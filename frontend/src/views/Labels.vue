<template>
  <div class="labels-page" :class="layout">
    <div class="bar no-print">
      <b>批次标签</b>
      <el-radio-group v-model="layout" size="small">
        <el-radio-button value="roll">标签纸 60×40 mm</el-radio-button>
        <el-radio-button value="a4">A4 纸（每页 21 张）</el-radio-button>
      </el-radio-group>
      <label class="small">每批 <el-input-number v-model="copies" :min="1" :max="50" size="small" :controls="false" style="width:56px" /> 张</label>
      <el-button type="primary" :icon="Printer" :disabled="!cards.length" @click="print">打印</el-button>
      <span class="muted small">二维码内容是批号，用扫码枪或“扫码作业”页扫描即可。</span>
    </div>
    <p v-if="error" class="no-print err">{{ error }}</p>

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
            <div class="src">{{ c.batch.partnerName || '' }}<template v-if="c.batch.extBatchNo"> · {{ c.batch.extBatchNo }}</template></div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { Printer } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import http from '../api'

// 批次标签：/labels?batches=批号1,批号2。状态会变，所以标签上不印状态，扫码时实时查
const route = useRoute()
const layout = ref('roll')
const copies = ref(1)
const cards = ref([])
const error = ref('')

onMounted(async () => {
  const nos = String(route.query.batches || '').split(',').map((s) => s.trim()).filter(Boolean)
  if (!nos.length) {
    error.value = '没有指定批号。请从“库存”勾选批次，或在批次追溯页点“打印标签”。'
    return
  }
  const out = []
  for (const no of nos) {
    try {
      const card = await http.get(`/scan/batches/${encodeURIComponent(no)}`, { silent: true })
      const svg = await QRCode.toString(no, { type: 'svg', margin: 0, errorCorrectionLevel: 'M' })
      out.push({ batch: card.batch, svg })
    } catch (e) {
      error.value = `${error.value}批号 ${no}：${e.message}。`
    }
  }
  cards.value = out
})

// 纸张尺寸随排版切换：标签纸每张标签一页；A4 边距 10mm，可用 190×277mm，正好排 3 列 7 行。
// 样式一直留在页面上，这样按 Cmd/Ctrl+P 打印也是对的
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
.bar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding-bottom: 14px; margin-bottom: 14px; border-bottom: 1px solid var(--lg-stroke); }
.err { color: var(--el-color-danger); }
.sheet { display: flex; flex-wrap: wrap; gap: 8px; }
.label { width: 60mm; height: 40mm; box-sizing: border-box; padding: 2.5mm; background: #fff; color: #111; border: 1px dashed #bbb; display: grid; grid-template-columns: 22mm 1fr; gap: 2.5mm; align-items: center; overflow: hidden; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; }
.qr :deep(svg) { width: 22mm; height: 22mm; display: block; }
.info { min-width: 0; line-height: 1.25; }
.name { font-size: 9pt; font-weight: 700; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.code { font-size: 7pt; color: #444; }
.bno { font-family: "SF Mono", Menlo, Consolas, monospace; font-size: 10.5pt; font-weight: 700; margin: 1mm 0; letter-spacing: 0.02em; }
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
