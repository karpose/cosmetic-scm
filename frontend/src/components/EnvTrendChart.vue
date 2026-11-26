<template>
  <div class="env-trend viz-env">
    <div class="head">
      <b>{{ label }}（{{ unit }}）</b>
      <span class="muted small">{{ summary }}</span>
    </div>
    <div ref="box" class="plot" role="group" tabindex="0" :aria-label="`${label}趋势图：${summary}。用左右方向键逐条查看`"
         @pointermove="onMove" @pointerleave="hover = -1" @keydown="onKey" @blur="hover = -1">
      <svg v-if="width > 0 && pts.length" :width="width" :height="H" :viewBox="`0 0 ${width} ${H}`" aria-hidden="true">
        <!-- 网格和纵轴刻度 -->
        <g class="grid">
          <line v-for="t in yTicks" :key="`g${t}`" :x1="L" :x2="width - R" :y1="y(t)" :y2="y(t)" />
        </g>
        <g class="axis">
          <text v-for="t in yTicks" :key="`y${t}`" :x="L - 6" :y="y(t) + 4" text-anchor="end">{{ fmtNum(t) }}</text>
          <text v-for="t in xTicks" :key="`x${t.v}`" :x="x(t.v)" :y="H - 6" text-anchor="middle">{{ t.label }}</text>
        </g>
        <!-- 范围：虚线 -->
        <g class="limit">
          <template v-for="l in limits" :key="l.name">
            <line :x1="L" :x2="width - R" :y1="y(l.v)" :y2="y(l.v)" />
            <text :x="width - R - 4" :y="y(l.v) - 5" text-anchor="end">{{ l.name }} {{ fmtNum(l.v) }}{{ unit }}</text>
          </template>
        </g>
        <path class="line" :d="path" />
        <!-- 超限的点 -->
        <circle v-for="p in outPts" :key="`o${p.i}`" class="out" :cx="x(p.t)" :cy="y(p.v)" r="4" />
        <template v-if="hover >= 0">
          <line class="cross" :x1="x(pts[hover].t)" :x2="x(pts[hover].t)" :y1="T" :y2="H - B" />
          <circle :class="pts[hover].inRange ? 'dot' : 'out'" :cx="x(pts[hover].t)" :cy="y(pts[hover].v)" r="5" />
        </template>
      </svg>
      <p v-else-if="!pts.length" class="muted small empty">这段时间没有{{ label }}记录</p>
      <div v-if="hover >= 0" class="tip" :style="tipStyle" aria-live="polite">
        <div class="tip-v">{{ fmtNum(pts[hover].v) }}{{ unit }}</div>
        <div class="small">{{ pts[hover].label }}</div>
        <div class="small" :class="pts[hover].inRange ? 'muted' : 'bad'">{{ pts[hover].inRange ? '在范围内' : '超出范围' }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

// 单个指标（温度或湿度）的趋势：一条线、范围虚线、超限的点。一个图一个量，不用双纵轴；只有一条线，标题就是它的名字，不要图例
const props = defineProps({
  label: { type: String, required: true },
  unit: { type: String, required: true },
  // [{ t: '2026-11-20T08:00:00', v: 22.5, inRange: true }]
  points: { type: Array, default: () => [] },
  min: { type: Number, default: null },
  max: { type: Number, default: null },
  from: { type: String, required: true },
  to: { type: String, required: true }
})

const H = 190
const L = 44
const R = 12
const T = 12
const B = 26
// 相隔超过 12 小时的两条记录之间断开，漏记的时段一眼能看出来
const GAP_MS = 12 * 3600 * 1000

const box = ref(null)
const width = ref(0)
const hover = ref(-1)
let ro = null
onMounted(() => {
  width.value = box.value?.clientWidth || 0
  ro = new ResizeObserver(() => { width.value = box.value?.clientWidth || 0 })
  if (box.value) ro.observe(box.value)
})
onBeforeUnmount(() => ro?.disconnect())

const ms = (s) => new Date(String(s).length === 10 ? `${s}T00:00:00` : s).getTime()
const pts = computed(() => props.points.filter((p) => p.v !== null && p.v !== undefined)
  .map((p, i) => ({ i, t: ms(p.t), v: Number(p.v), inRange: p.inRange, label: String(p.t).replace('T', ' ').slice(0, 16) })))
const outPts = computed(() => pts.value.filter((p) => !p.inRange))
const summary = computed(() => {
  const n = pts.value.length
  if (!n) return '没有记录'
  const vs = pts.value.map((p) => p.v)
  return `${n} 条记录，最低 ${fmtNum(Math.min(...vs))}${props.unit}，最高 ${fmtNum(Math.max(...vs))}${props.unit}${outPts.value.length ? `，超限 ${outPts.value.length} 条` : ''}`
})

const x0 = computed(() => ms(props.from))
const x1 = computed(() => ms(props.to) + 24 * 3600 * 1000)
const domain = computed(() => {
  const vs = [...pts.value.map((p) => p.v), props.min, props.max].filter((v) => v !== null && v !== undefined)
  if (!vs.length) return [0, 1]
  let lo = Math.min(...vs)
  let hi = Math.max(...vs)
  if (hi - lo < 1) { lo -= 1; hi += 1 }
  const pad = (hi - lo) * 0.12
  return [lo - pad, hi + pad]
})
const x = (t) => L + ((t - x0.value) / (x1.value - x0.value)) * (width.value - L - R)
const y = (v) => T + (1 - (v - domain.value[0]) / (domain.value[1] - domain.value[0])) * (H - T - B)

const yTicks = computed(() => {
  const [lo, hi] = domain.value
  const raw = (hi - lo) / 4
  const mag = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 5, 10].map((m) => m * mag).find((s) => s >= raw) || raw
  const out = []
  for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) out.push(Math.round(v * 100) / 100)
  return out
})
const xTicks = computed(() => {
  const days = Math.round((x1.value - x0.value) / 86400000)
  const every = Math.max(1, Math.ceil(days / Math.max(2, Math.floor((width.value - L - R) / 70))))
  const out = []
  for (let i = 0; i <= days; i += every) {
    const t = x0.value + i * 86400000
    const d = new Date(t)
    out.push({ v: t, label: `${d.getMonth() + 1}-${d.getDate()}` })
  }
  return out
})
const limits = computed(() => [
  props.max !== null && props.max !== undefined ? { name: '上限', v: props.max } : null,
  props.min !== null && props.min !== undefined ? { name: '下限', v: props.min } : null
].filter(Boolean))
const path = computed(() => {
  let d = ''
  let prev = null
  for (const p of pts.value) {
    d += `${prev === null || p.t - prev > GAP_MS ? 'M' : 'L'}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`
    prev = p.t
  }
  return d
})

function onMove(e) {
  if (!pts.value.length || !box.value) return
  const px = e.clientX - box.value.getBoundingClientRect().left
  let best = 0
  let dist = Infinity
  // 点按时间排好，二分找最近的
  let lo = 0
  let hi = pts.value.length - 1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    const d = x(pts.value[mid].t) - px
    if (Math.abs(d) < dist) { dist = Math.abs(d); best = mid }
    if (d < 0) lo = mid + 1
    else hi = mid - 1
  }
  hover.value = best
}
function onKey(e) {
  if (!pts.value.length) return
  if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
    e.preventDefault()
    const step = e.key === 'ArrowRight' ? 1 : -1
    hover.value = hover.value < 0 ? (step > 0 ? 0 : pts.value.length - 1) : Math.min(pts.value.length - 1, Math.max(0, hover.value + step))
  } else if (e.key === 'Escape') {
    hover.value = -1
  }
}
const tipStyle = computed(() => {
  const p = pts.value[hover.value]
  const left = x(p.t)
  return left > width.value - 150 ? { right: `${width.value - left + 10}px`, top: '8px' } : { left: `${left + 10}px`, top: '8px' }
})
const fmtNum = (v) => (Math.round(v * 10) / 10).toString()
</script>

<style scoped>
.viz-env { --s1: #2a78d6; }
:global(html.dark) .viz-env { --s1: #3987e5; }
.head { display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; margin-bottom: 4px; }
.plot { position: relative; min-height: 60px; outline-offset: 2px; border-radius: 8px; }
.plot:focus-visible { outline: 2px solid var(--el-color-primary); }
.empty { padding: 20px 0; margin: 0; }
svg { display: block; overflow: visible; }
.grid line { stroke: var(--el-border-color-lighter); stroke-width: 1; }
.axis text { fill: var(--el-text-color-secondary); font-size: 11px; }
.limit line { stroke: var(--el-text-color-secondary); stroke-width: 1; stroke-dasharray: 4 4; }
.limit text { fill: var(--el-text-color-secondary); font-size: 11px; }
.line { fill: none; stroke: var(--s1); stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
.out { fill: var(--el-color-danger); stroke: var(--el-bg-color); stroke-width: 2; }
.dot { fill: var(--s1); stroke: var(--el-bg-color); stroke-width: 2; }
.cross { stroke: var(--el-text-color-secondary); stroke-width: 1; }
.tip { position: absolute; pointer-events: none; padding: 6px 10px; border-radius: 10px; background: var(--el-bg-color-overlay); border: 1px solid var(--el-border-color);
  box-shadow: var(--el-box-shadow-light); white-space: nowrap; z-index: 2; }
.tip-v { font-weight: 700; font-size: 15px; color: var(--el-text-color-primary); }
.bad { color: var(--el-color-danger); }
</style>
