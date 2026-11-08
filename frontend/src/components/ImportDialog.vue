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
        <p v-else class="muted small">编码已存在的行按“修改”处理：留空的格和文件里没有的列都保留原值，要清空请到主数据页面改；不存在的新增。预检会列出每一处修改。任何一行有问题都不会写入，改好后重新上传即可。Excel 请“另存为 → CSV UTF-8（逗号分隔）”；普通 CSV（GBK）也能识别。</p>
      </div>
      <div class="toolbar">
        <el-button @click="downloadTemplate">下载模板</el-button>
        <el-button type="primary" plain @click="fileInput.click()">选择 CSV 文件</el-button>
        <input ref="fileInput" type="file" accept=".csv,.txt,text/csv" hidden @change="onFile" />
      </div>
    </div>

    <!-- 文件本身有问题时没有数据行，也要把原因显示出来 -->
    <template v-if="rows.length || parseErrors.length">
      <div class="summary">
        <span v-if="rows.length">共 <b class="mono">{{ rows.length }}</b> 行</span>
        <template v-if="result">
          <el-tag v-if="!issues.length" type="success" disable-transitions>预检通过：{{ spec.unit ? `${result.created} ${spec.unit}` : spec.sign ? `${result.created} 个批次` : `新增 ${result.created}，修改 ${result.updated}` }}</el-tag>
          <el-tag v-else type="danger" disable-transitions>{{ issues.length }} 处问题，修改文件后重新上传</el-tag>
        </template>
        <el-tag v-else-if="parseErrors.length" type="danger" disable-transitions>{{ parseErrors.length }} 处格式问题</el-tag>
      </div>

      <p v-if="ignoredHeaders.length" class="ignored small">这些列系统不认识，不会导入：{{ ignoredHeaders.join('、') }}。如果是要导入的列，请改成模板里的名字。</p>

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

      <!-- 修改已有数据时只给一个行数，看不出禁用、合格名录这类字段有没有被改 -->
      <template v-if="!issues.length && changes.length">
        <p class="change-head"><b>会修改已有数据 {{ changes.length }} 处</b>，确认导入前请核对：</p>
        <el-table :data="changes.slice(0, 200)" size="small" max-height="220" class="changes">
          <el-table-column label="编码" min-width="130"><template #default="{ row }"><span class="mono">{{ row.code }}</span></template></el-table-column>
          <el-table-column prop="field" label="字段" min-width="130" />
          <el-table-column prop="from" label="原值" min-width="120" />
          <el-table-column prop="to" label="改为" min-width="120" />
        </el-table>
        <p v-if="changes.length > 200" class="muted small">仅显示前 200 处。</p>
      </template>
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
import http, { LONG_TIMEOUT } from '../api'
import { ITEM_TYPE, PARTNER_KIND } from '../utils/format'

const visible = defineModel({ type: Boolean, default: false })
const props = defineProps({ kind: { type: String, required: true } })
const emit = defineEmits(['done'])

// ---------- 两种模板的列定义：表头 → 字段，以及单元格到接口值的转换 ----------
// 是否类的格：认不出的要报错。曾把“禁用”两个字当成“否”，禁用原料就成了可用
const YES_WORDS = ['是', 'y', 'yes', 'true', '1', '√', '✓']
const NO_WORDS = ['否', 'n', 'no', 'false', '0', '×', '✗']
const flag = (label, blank) => (v) => {
  const s = String(v ?? '').trim().toLowerCase()
  if (s === '') return blank
  if (YES_WORDS.includes(s)) return true
  if (NO_WORDS.includes(s)) return false
  throw new Error(`${label}“${v}”无法识别，请填 是 或 否`)
}
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
  // Excel 把日期设成日期时间格式时，会带上 0:00
  const m = s.match(/^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?$/)
  if (!m) throw new Error(`${label}“${s}”格式应为 2027-03-01`)
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])]
  const real = new Date(y, mo - 1, d)
  // 2 月 30 日这种日期，Date 会顺延到 3 月，比较一下才知道不存在
  if (real.getMonth() !== mo - 1 || real.getDate() !== d) throw new Error(`${label}“${s}”这一天不存在`)
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
      { header: '禁用', key: 'banned', conv: flag('禁用', false), show: yn, example: '否' },
      { header: '保质期（月）', key: 'shelfMonths', conv: int('保质期'), example: '36' },
      { header: '备案编号', key: 'filingNo', conv: text, example: '' },
      { header: '净含量（g）', key: 'netG', conv: num('净含量'), example: '' },
      { header: '启用', key: 'active', conv: flag('启用', true), show: yn, example: '是' },
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
  boms: {
    title: '首版配方', url: '/import/boms', file: '配方导入模板.csv', codeKey: 'fgCode', required: ['fgCode', 'itemCode', 'qtyPer', 'supplyMode'], unit: '个成品的配方',
    hint: '一个成品写多行，一起作为它的首版配方，保存后立即生效。单耗按物料单位填（原料通常是 kg/件）。走和界面相同的合规校验；已有生效配方的成品要走配方变更，这里会拒绝。',
    columns: [
      { header: '成品编码', key: 'fgCode', conv: text, example: 'FG-001' },
      { header: '物料编码', key: 'itemCode', conv: text, example: 'RM-GLY-010' },
      { header: '单耗', key: 'qtyPer', conv: num('单耗'), example: '0.0015' },
      { header: '供料方式', key: 'supplyMode', conv: enumOf('供料方式', { BRAND: '品牌供料', OEM: '工厂代采' }, true), show: (v) => ({ BRAND: '品牌供料', OEM: '工厂代采' }[v]), example: '品牌供料' }
    ]
  },
  regulated: {
    title: '法规目录', url: '/regulatory/import', file: '法规目录导入模板.csv', codeKey: 'inciName', required: ['catalog', 'inciName', 'source'], unit: '条法规目录',
    hint: '请按官方发布的版本填写，来源必填。全成或全不成；INCI 已存在的按修改处理。类别、上限和别名会影响校验，别名用分号分隔，不能和别的条目重复。下面的示例不是法规内容。',
    columns: [
      { header: '类别', key: 'catalog', conv: enumOf('类别', { BANNED: '禁用', RESTRICTED: '限用' }, true), show: (v) => ({ BANNED: '禁用', RESTRICTED: '限用' }[v]), example: '限用' },
      { header: 'INCI 名称', key: 'inciName', conv: text, example: 'Example Substance' },
      { header: '中文名', key: 'cnName', conv: text, example: '示例物质' },
      { header: 'CAS 号', key: 'casNo', conv: text, example: '' },
      { header: '浓度上限（%）', key: 'maxPct', conv: num('浓度上限'), example: '1' },
      { header: '使用条件', key: 'conditions', conv: text, example: '' },
      { header: '来源文件和版本', key: 'source', conv: text, example: '请填文件名和版本' },
      { header: '别名（其它 INCI 写法，分号分隔）', key: 'aliases', conv: text, example: '' }
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
      { header: '合格名录', key: 'qualified', conv: flag('合格名录', true), show: yn, example: '是' },
      { header: '关键供应商', key: 'criticalSupplier', conv: flag('关键供应商', false), show: yn, example: '否' }
    ]
  }
}
const spec = computed(() => SPECS[props.kind])

// ---------- 状态 ----------
const fileInput = ref(null)
const fileName = ref('')
const rows = ref([])
const parseErrors = ref([])
const ignoredHeaders = ref([])
const changes = ref([])
const result = ref(null)
const busy = ref('')
const password = ref('')
const step = computed(() => (result.value && !issues.value.length ? 3 : rows.value.length ? 2 : 1))
// 后端按“第几条数据”报行号，换算成 Excel 行号（文件中间可能有空行）
const lineOf = ref([])
const issues = computed(() => [...parseErrors.value, ...(result.value?.errors || []).map((e) => ({ ...e, line: lineOf.value[e.line - 2] ?? e.line }))])
const canApply = computed(() => result.value && !issues.value.length && !busy.value)

function reset() {
  fileName.value = ''
  rows.value = []
  parseErrors.value = []
  ignoredHeaders.value = []
  changes.value = []
  lineOf.value = []
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

// 第一行只有制表符或分号时按它分列：Excel 的“Unicode 文本”是制表符，部分地区设置的 CSV 是分号
function delimiterOf(textIn) {
  const first = textIn.replace(/^\ufeff/, '').split(/\r?\n/, 1)[0]
  if (first.includes(',')) return ','
  if (first.includes('\t')) return '\t'
  return first.includes(';') ? ';' : ','
}

// 从网页、微信复制来的文字可能带零宽字符，看不见，却让编码对不上
const clean = (v) => String(v ?? '').replace(/[\u200b-\u200d\u2060\ufeff]/g, '').trim()
// 表头：全角半角括号、空格不同都算同一列。否则“保质期(月)”这一列会被当成没有，静默丢掉
const normHeader = (h) => clean(h).replace(/（/g, '(').replace(/）/g, ')').replace(/\s+/g, '').toLowerCase()
// Excel 保存的是屏幕上显示的文字：错误值和列太窄的 ###，以及长数字的科学计数，原值已经丢了
const EXCEL_ERROR = /^(#+|#N\/A|#REF!|#VALUE!|#DIV\/0!|#NAME\?|#NUM!|#NULL!)$/i
const SCIENTIFIC = /^\d(\.\d+)?E\+\d+$/i

function parseCsv(textIn) {
  const delimiter = delimiterOf(textIn)
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
    else if (ch === delimiter) { row.push(cell); cell = '' } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && s[i + 1] === '\n') i++
      row.push(cell); out.push(row); row = []; cell = ''
    } else cell += ch
  }
  if (cell !== '' || row.length) { row.push(cell); out.push(row) }
  // 空行要过滤，但报错要按 Excel 行号：单元格里换行不算新行，所以按第几条记录数，不按物理行
  out.forEach((r, i) => { r.line = i + 1 })
  return out.filter((r) => r.some((c) => clean(c) !== ''))
}

async function decode(file) {
  const buf = await file.arrayBuffer()
  const head = new Uint8Array(buf.slice(0, 4))
  // xlsx 是 zip 包（PK 开头），当文本解析只会得到乱码
  if (head[0] === 0x50 && head[1] === 0x4b) throw new Error('这是 Excel 工作簿（.xlsx），请在 Excel 里“另存为”CSV 后再上传')
  if (head[0] === 0xd0 && head[1] === 0xcf) throw new Error('这是旧版 Excel 工作簿（.xls），请在 Excel 里“另存为”CSV 后再上传')
  // Excel 的“Unicode 文本”
  if (head[0] === 0xff && head[1] === 0xfe) return new TextDecoder('utf-16le').decode(buf)
  if (head[0] === 0xfe && head[1] === 0xff) return new TextDecoder('utf-16be').decode(buf)
  const utf8 = new TextDecoder('utf-8').decode(buf)
  // 出现替换字符说明不是 UTF-8，按 Excel 中文版默认的 GBK 再解一次
  return utf8.includes('�') ? new TextDecoder('gbk').decode(buf) : utf8
}

const sameValue = (a, b) => {
  const [x, y] = [a ?? '', b ?? '']
  if (x === '' || y === '') return x === y
  return typeof x === 'number' || typeof y === 'number' ? Number(x) === Number(y) : String(x) === String(y)
}

// 列出已存在的编码里，每个真正变了的字段
function diffRows(found, cols) {
  const display = (c, v) => (v === null || v === undefined || v === '' ? '（空）' : c.show ? c.show(v) : String(v))
  return found.flatMap(([r, old]) => cols.filter((c) => !sameValue(old[c.key], r[c.key]))
    .map((c) => ({ code: r.code, field: c.header, from: display(c, old[c.key]), to: display(c, r[c.key]) })))
}

async function onFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  reset()
  fileName.value = file.name
  let table
  try {
    table = parseCsv(await decode(file))
  } catch (err) {
    parseErrors.value = [{ line: 1, code: '', message: err.message }]
    return
  }
  if (table.length < 2) {
    parseErrors.value = [{ line: 1, code: '', message: '文件里没有数据行（第 1 行应为表头）' }]
    return
  }
  const header = table[0].map(normHeader)
  const cols = spec.value.columns
  const known = new Set(cols.map((c) => normHeader(c.header)))
  ignoredHeaders.value = table[0].filter((h) => clean(h) && !known.has(normHeader(h))).map(clean)
  const index = Object.fromEntries(cols.map((c) => [c.key, header.indexOf(normHeader(c.header))]))
  const requiredKeys = spec.value.required || ['code', 'name', 'itemType', 'unit', 'kind']
  const missing = cols.filter((c) => requiredKeys.includes(c.key) && index[c.key] < 0)
  if (missing.length) {
    parseErrors.value = [{ line: 1, code: '', message: `表头缺少：${missing.map((c) => c.header).join('、')}。请用“下载模板”的表头` }]
    return
  }
  const errs = []
  const dataRows = table.slice(1)
  lineOf.value = dataRows.map((r) => r.line)
  // 每行留空的字段（文件里没有的列也算），已存在的编码要保留原值
  const blanks = []
  rows.value = dataRows.map((cells) => {
    const obj = {}
    const blank = []
    for (const c of cols) {
      const raw = index[c.key] >= 0 ? clean(cells[index[c.key]]) : ''
      if (raw === '') blank.push(c.key)
      try {
        if (EXCEL_ERROR.test(raw)) throw new Error(`${c.header}“${raw}”是 Excel 的错误值或列太窄显示的，请在 Excel 里改好再保存`)
        if (c.conv === text && SCIENTIFIC.test(raw)) throw new Error(`${c.header}“${raw}”被 Excel 显示成了科学计数，后面的数字已经丢了。请把这一列设成文本格式，重新填写`)
        obj[c.key] = c.conv(raw)
      } catch (err) {
        errs.push({ line: cells.line, code: text(clean(cells[index[spec.value.codeKey || 'code']])), message: err.message })
      }
    }
    blanks.push(blank)
    return obj
  })
  parseErrors.value = errs
  if (spec.value.listUrl) {
    let list
    try {
      list = await http.get(spec.value.listUrl)
    } catch (err) {
      // 不知道现有数据，就分不清留空的格要保留什么，不能往下走
      parseErrors.value = [...errs, { line: 1, code: '', message: `读取现有数据失败，请重新选择文件：${err.message}` }]
      return
    }
    const existing = new Map(list.map((r) => [r.code, r]))
    const found = []
    rows.value.forEach((r, i) => {
      const old = existing.get(r.code)
      if (!old) return
      blanks[i].forEach((k) => { r[k] = old[k] ?? null })
      found.push([r, old])
    })
    changes.value = diffRows(found, cols)
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
    const res = await http.post(spec.value.url, { rows: rows.value, dryRun, ...(spec.value.sign ? { password: password.value } : {}) }, { timeout: LONG_TIMEOUT })
    result.value = res
    // 法规目录的变化由后端算：留空的上限是“无上限”，不是保留原值，放宽了也要让人看到
    if (dryRun && Array.isArray(res.changes)) changes.value = res.changes
    if (!dryRun && res.applied) {
      ElMessage.success(spec.value.unit ? `已导入 ${res.created} ${spec.value.unit}` : spec.value.sign ? `已导入 ${res.created} 个批次` : `已导入：新增 ${res.created}，修改 ${res.updated}`)
      emit('done')
      visible.value = false
    }
  } finally {
    busy.value = ''
  }
}
</script>

<style scoped>
.ignored { margin: 0 0 8px; color: var(--el-color-warning); }
.change-head { margin: 14px 0 6px; }
.steps { display: flex; gap: 8px; list-style: none; padding: 0; margin: 0 0 14px; counter-reset: s; flex-wrap: wrap; }
.steps li { counter-increment: s; flex: 1; min-width: 150px; padding: 8px 12px; border-radius: 12px; font-size: 12.5px; color: var(--el-text-color-secondary); background: var(--lg-fill); border: 1px solid var(--lg-stroke); }
.steps li::before { content: counter(s) " · "; font-weight: 700; }
.steps li.on { color: var(--el-color-primary); background: var(--lg-tint); }
.pick { display: flex; justify-content: space-between; align-items: center; gap: 14px; padding: 14px 16px; border-radius: 16px; flex-wrap: wrap; }
.pick > div:first-child { flex: 1; min-width: 260px; }
.pick p { margin: 4px 0 0; line-height: 1.6; }
.summary { display: flex; align-items: center; gap: 10px; margin: 14px 0 8px; flex-wrap: wrap; }
</style>
