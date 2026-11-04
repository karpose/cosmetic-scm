<template>
  <el-dialog v-model="visible" :title="`批量导入${spec.title}`" width="820px" :close-on-click-modal="false" @closed="reset">
    <ol class="steps">
      <li :class="{ on: step >= 1 }">下载模板，在 Excel 里填写</li>
      <li :class="{ on: step >= 2 }">另存为 CSV 后上传</li>
      <li :class="{ on: step >= 3 }">预检无误后确认导入</li>
    </ol>

    <div class="pick glass">
      <div>
        <b>{{ fileName || '还没有选择文件' }}</b>
        <p v-if="spec.hint" class="muted small">{{ spec.hint }}</p>
        <p v-else class="muted small">编码已存在的行按“修改”处理（文件里没有的列保留原值，有列但留空则清空），不存在的新增。任何一行有问题都不会写入，改好后重新上传即可。Excel 请“另存为 → CSV UTF-8（逗号分隔）”；普通 CSV（GBK）也能识别。</p>
      </div>
      <div class="toolbar">
        <el-button @click="downloadTemplate">下载模板</el-button>
        <el-button type="primary" plain @click="fileInput.click()">选择 CSV 文件</el-button>
        <input ref="fileInput" type="file" accept=".csv,text/csv" hidden @change="onFile" />
      </div>
    </div>

    <template v-if="rows.length">
      <div class="summary">
        <span>共 <b class="mono">{{ rows.length }}</b> 行</span>
        <template v-if="result">
          <el-tag v-if="!issues.length" type="success" disable-transitions>预检通过：{{ spec.sign ? `${result.created} 个批次` : `新增 ${result.created}，修改 ${result.updated}` }}</el-tag>
          <el-tag v-else type="danger" disable-transitions>{{ issues.length }} 处问题，修改文件后重新上传</el-tag>
        </template>
        <el-tag v-else-if="parseErrors.length" type="danger" disable-transitions>{{ parseErrors.length }} 处格式问题</el-tag>
      </div>

      <el-table v-if="issues.length" :data="issues" size="small" max-height="240" class="issues">
        <el-table-column label="Excel 行" width="80"><template #default="{ row }"><span class="mono">{{ row.line }}</span></template></el-table-column>
        <el-table-column label="编码" width="140"><template #default="{ row }"><span class="mono">{{ row.code || '—' }}</span></template></el-table-column>
        <el-table-column prop="message" label="问题" min-width="300" />
      </el-table>

      <el-table v-else :data="rows.slice(0, 50)" size="small" max-height="260">
        <el-table-column v-for="c in spec.columns.slice(0, 7)" :key="c.key" :label="c.header" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ c.show ? c.show(row[c.key]) : row[c.key] ?? '' }}</template>
        </el-table-column>
      </el-table>
      <p v-if="!issues.length && rows.length > 50" class="muted small">仅预览前 50 行。</p>
    </template>

    <template #footer>
      <el-input v-if="spec.sign && canApply" id="im-pwd" v-model="password" type="password" show-password placeholder="签批密码" autocomplete="current-password" style="width:160px;margin-right:10px" />
      <el-button @click="visible = false">取消</el-button>
      <el-button :disabled="!rows.length || parseErrors.length > 0" :loading="busy === 'dry'" @click="send(true)">预检</el-button>
      <el-button type="primary" :disabled="!canApply" :loading="busy === 'apply'" @click="send(false)">确认导入 {{ rows.length || '' }} 行</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { ITEM_TYPE, PARTNER_KIND } from '../utils/format'

const visible = defineModel({ type: Boolean, default: false })
const props = defineProps({ kind: { type: String, required: true } })
const emit = defineEmits(['done'])

// ---------- 两种模板的列定义：表头 → 字段，以及单元格到接口值的转换 ----------
const yes = (v) => ['是', 'y', 'yes', 'true', '1', '√'].includes(String(v ?? '').trim().toLowerCase())
const yesDefault = (d) => (v) => (String(v ?? '').trim() === '' ? d : yes(v))
const text = (v) => (String(v ?? '').trim() === '' ? null : String(v).trim())
const num = (label) => (v) => {
  const s = String(v ?? '').replace(/[,%\s]/g, '')
  if (s === '') return null
  const n = Number(s)
  if (Number.isNaN(n)) throw new Error(`${label}“${v}”不是数字`)
  return n
}
const int = (label) => (v) => {
  const n = num(label)(v)
  if (n !== null && !Number.isInteger(n)) throw new Error(`${label}“${v}”应为整数`)
  return n
}
const enumOf = (label, map, required) => (v) => {
  const s = String(v ?? '').trim()
  if (!s) {
    if (required) throw new Error(`请填写${label}`)
    return null
  }
  const hit = Object.entries(map).find(([k, name]) => k === s.toUpperCase() || name === s)
  if (!hit) throw new Error(`${label}“${s}”无法识别，可填：${Object.values(map).join(' / ')}`)
  return hit[0]
}
const date = (label) => (v) => {
  const s = String(v ?? '').trim()
  if (!s) return null
  const m = s.match(/^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?$/)
  if (!m) throw new Error(`${label}“${s}”格式应为 2027-03-01`)
  return `${m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}`
}
const yn = (v) => (v ? '是' : '否')

const SPECS = {
  items: {
    title: '物料', url: '/import/items', listUrl: '/items', file: '物料导入模板.csv',
    columns: [
      { header: '编码', key: 'code', conv: text, example: 'RM-GLY-010' },
      { header: '名称', key: 'name', conv: text, example: '甘油' },
      { header: '类型', key: 'itemType', conv: enumOf('类型', ITEM_TYPE, true), show: (v) => ITEM_TYPE[v], example: '原料' },
      { header: '单位', key: 'unit', conv: text, example: 'kg' },
      { header: 'INCI 名称', key: 'inci', conv: text, example: 'Glycerin' },
      { header: '限用浓度上限（%）', key: 'limitPct', conv: num('限用浓度上限'), example: '' },
      { header: '禁用', key: 'banned', conv: yes, show: yn, example: '否' },
      { header: '保质期（月）', key: 'shelfMonths', conv: int('保质期'), example: '36' },
      { header: '备案编号', key: 'filingNo', conv: text, example: '' },
      { header: '净含量（g）', key: 'netG', conv: num('净含量'), example: '' },
      { header: '启用', key: 'active', conv: yesDefault(true), show: yn, example: '是' },
      { header: '标准成本（元）', key: 'stdCost', conv: num('标准成本'), example: '12' },
      { header: '指导价（元）', key: 'listPrice', conv: num('指导价'), example: '' }
    ]
  },
  opening: {
    title: '期初库存', url: '/opening-stock', file: '期初库存导入模板.csv', sign: true, codeKey: 'batchNo', required: ['itemCode', 'batchNo', 'mfgDate', 'warehouseCode', 'qty', 'status'],
    hint: '上线时录入仓库现有的批次，全成或全不成。同一批号在多个仓库就写多行。已放行的批次相当于一次放行决定，要填检验报告编号，成品还要填留样；导入时需要签批。',
    columns: [
      { header: '物料编码', key: 'itemCode', conv: text, example: 'RM-GLY-010' },
      { header: '批号', key: 'batchNo', conv: text, example: 'R-260901-01' },
      { header: '状态', key: 'status', conv: enumOf('状态', { RELEASED: '已放行', QUARANTINE: '待检', PENDING_RELEASE: '待上市放行' }, true), show: (v) => ({ RELEASED: '已放行', QUARANTINE: '待检', PENDING_RELEASE: '待上市放行' }[v]), example: '已放行' },
      { header: '仓库编码', key: 'warehouseCode', conv: text, example: 'WH-MAIN' },
      { header: '数量', key: 'qty', conv: num('数量'), example: '25' },
      { header: '生产日期', key: 'mfgDate', conv: date('生产日期'), example: '2026-09-01' },
      { header: '有效期', key: 'expDate', conv: date('有效期'), example: '' },
      { header: '外部批号', key: 'extBatchNo', conv: text, example: 'SUP-2026-09' },
      { header: '来源单位编码', key: 'partnerCode', conv: text, example: '' },
      { header: '检验报告编号', key: 'reportNo', conv: text, example: 'COA-260901' },
      { header: '留样数量', key: 'retainQty', conv: int('留样数量'), example: '' },
      { header: '留样地点', key: 'retainLocation', conv: text, example: '' }
    ]
  },
  partners: {
    title: '往来单位', url: '/import/partners', listUrl: '/partners', file: '往来单位导入模板.csv',
    columns: [
      { header: '编码', key: 'code', conv: text, example: 'SUP-NEW' },
      { header: '名称', key: 'name', conv: text, example: '某某原料有限公司' },
      { header: '类别', key: 'kind', conv: enumOf('类别', PARTNER_KIND, true), show: (v) => PARTNER_KIND[v], example: '物料供应商' },
      { header: '资质编号', key: 'licenseNo', conv: text, example: 'ISO9001-2024-001' },
      { header: '资质有效期', key: 'licenseExpiry', conv: date('资质有效期'), example: '2027-03-01' },
      { header: '许可范围', key: 'licenseScope', conv: text, example: '' },
      { header: '批号前缀', key: 'batchPrefix', conv: text, example: '' },
      { header: '渠道', key: 'channel', conv: text, example: '' },
      { header: '最低剩余效期（%）', key: 'minShelfPct', conv: int('最低剩余效期'), example: '' },
      { header: '联系人', key: 'contact', conv: text, example: '张经理 139****0000' },
      { header: '地址', key: 'address', conv: text, example: '' },
      { header: '合格名录', key: 'qualified', conv: yesDefault(true), show: yn, example: '是' },
      { header: '关键供应商', key: 'criticalSupplier', conv: yes, show: yn, example: '否' }
    ]
  }
}
const spec = computed(() => SPECS[props.kind])

// ---------- 状态 ----------
const fileInput = ref(null)
const fileName = ref('')
const rows = ref([])
const parseErrors = ref([])
const result = ref(null)
const busy = ref('')
const password = ref('')
const step = computed(() => (result.value && !issues.value.length ? 3 : rows.value.length ? 2 : 1))
const issues = computed(() => [...parseErrors.value, ...(result.value?.errors || [])])
const canApply = computed(() => result.value && !issues.value.length && !busy.value)

function reset() {
  fileName.value = ''
  rows.value = []
  parseErrors.value = []
  result.value = null
  password.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

// ---------- CSV ----------
const csvCell = (v) => (/[",\n\r]/.test(v) ? `"${String(v).replace(/"/g, '""')}"` : v)

function downloadTemplate() {
  const cols = spec.value.columns
  const lines = [cols.map((c) => csvCell(c.header)).join(','), cols.map((c) => csvCell(c.example)).join(',')]
  // 带 BOM，Excel 双击打开不会乱码
  const blob = new Blob(['﻿' + lines.join('\r\n') + '\r\n'], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = spec.value.file
  a.click()
  URL.revokeObjectURL(a.href)
}

function parseCsv(textIn) {
  const out = []
  let row = []
  let cell = ''
  let quoted = false
  const s = textIn.replace(/^﻿/, '')
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if (quoted) {
      if (ch === '"' && s[i + 1] === '"') { cell += '"'; i++ } else if (ch === '"') quoted = false
      else cell += ch
    } else if (ch === '"') quoted = true
    else if (ch === ',') { row.push(cell); cell = '' } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && s[i + 1] === '\n') i++
      row.push(cell); out.push(row); row = []; cell = ''
    } else cell += ch
  }
  if (cell !== '' || row.length) { row.push(cell); out.push(row) }
  return out.filter((r) => r.some((c) => String(c).trim() !== ''))
}

async function decode(file) {
  const buf = await file.arrayBuffer()
  const utf8 = new TextDecoder('utf-8').decode(buf)
  // 出现替换字符说明不是 UTF-8，按 Excel 中文版默认的 GBK 再解一次
  return utf8.includes('�') ? new TextDecoder('gbk').decode(buf) : utf8
}

async function onFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  reset()
  fileName.value = file.name
  const table = parseCsv(await decode(file))
  if (table.length < 2) {
    parseErrors.value = [{ line: 1, code: '', message: '文件里没有数据行（第 1 行应为表头）' }]
    return
  }
  const header = table[0].map((h) => h.trim())
  const cols = spec.value.columns
  const index = Object.fromEntries(cols.map((c) => [c.key, header.indexOf(c.header)]))
  const requiredKeys = spec.value.required || ['code', 'name', 'itemType', 'unit', 'kind']
  const missing = cols.filter((c) => requiredKeys.includes(c.key) && index[c.key] < 0)
  if (missing.length) {
    parseErrors.value = [{ line: 1, code: '', message: `表头缺少：${missing.map((c) => c.header).join('、')}。请用“下载模板”的表头` }]
    return
  }
  const errs = []
  rows.value = table.slice(1).map((cells, i) => {
    const obj = {}
    for (const c of cols) {
      const raw = index[c.key] >= 0 ? cells[index[c.key]] : ''
      try {
        obj[c.key] = c.conv(raw)
      } catch (err) {
        errs.push({ line: i + 2, code: text(cells[index[spec.value.codeKey || 'code']]), message: err.message })
      }
    }
    return obj
  })
  parseErrors.value = errs
  // 文件里没有的列：已存在的编码保留原值，避免只导部分列时把其它字段清空
  const absent = cols.filter((c) => index[c.key] < 0).map((c) => c.key)
  if (absent.length && spec.value.listUrl) {
    const existing = new Map((await http.get(spec.value.listUrl)).map((r) => [r.code, r]))
    for (const r of rows.value) {
      const old = existing.get(r.code)
      if (old) absent.forEach((k) => { r[k] = old[k] ?? null })
    }
  }
  if (!errs.length) send(true)
}

async function send(dryRun) {
  busy.value = dryRun ? 'dry' : 'apply'
  try {
    if (!dryRun && spec.value.sign && !password.value) {
      ElMessage.warning('请输入签批密码')
      return
    }
    const res = await http.post(spec.value.url, { rows: rows.value, dryRun, ...(spec.value.sign ? { password: password.value } : {}) })
    result.value = res
    if (!dryRun && res.applied) {
      ElMessage.success(spec.value.sign ? `已导入 ${res.created} 个批次` : `已导入：新增 ${res.created}，修改 ${res.updated}`)
      emit('done')
      visible.value = false
    }
  } finally {
    busy.value = ''
  }
}
</script>

<style scoped>
.steps { display: flex; gap: 8px; list-style: none; padding: 0; margin: 0 0 14px; counter-reset: s; flex-wrap: wrap; }
.steps li { counter-increment: s; flex: 1; min-width: 150px; padding: 8px 12px; border-radius: 12px; font-size: 12.5px; color: var(--el-text-color-secondary); background: var(--lg-fill); border: 1px solid var(--lg-stroke); }
.steps li::before { content: counter(s) " · "; font-weight: 700; }
.steps li.on { color: var(--el-color-primary); background: var(--lg-tint); }
.pick { display: flex; justify-content: space-between; align-items: center; gap: 14px; padding: 14px 16px; border-radius: 16px; flex-wrap: wrap; }
.pick > div:first-child { flex: 1; min-width: 260px; }
.pick p { margin: 4px 0 0; line-height: 1.6; }
.summary { display: flex; align-items: center; gap: 10px; margin: 14px 0 8px; flex-wrap: wrap; }
</style>
