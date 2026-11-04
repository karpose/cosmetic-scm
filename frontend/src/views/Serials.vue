<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>一物一码</h2>
        <p>为成品批次按件生成随机防伪溯源码，导出 CSV 交给印刷厂做二维码（链接指向消费者扫码页）。消费者扫码能看到产品、批号和效期；召回或冻结中的批次会提示停止使用，同一个码被多次查询会提示谨防仿冒。</p>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:14px 18px">
      <form class="ask" @submit.prevent="lookup">
        <el-input v-model="q" placeholder="输入防伪码查扫码记录，如 7K3M-Q9TX-2B8R" clearable class="mono" />
        <el-button native-type="submit" :loading="looking">查询</el-button>
      </form>
      <div v-if="found" class="found">
        <span class="mono"><b>{{ found.displayCode }}</b></span>
        <span>{{ found.itemName }} · <BatchNo :no="found.batchNo" /> · 第 {{ found.seq }} 号</span>
        <span class="muted small">查询 {{ found.scanCount }} 次<template v-if="found.firstScanAt">，首次 {{ fmtTime(found.firstScanAt) }}，最近 {{ fmtTime(found.lastScanAt) }}</template></span>
        <router-link :to="{ name: 'verify', params: { code: found.code } }" target="_blank" class="small">打开消费者扫码页</router-link>
      </div>
    </el-card>

    <el-card shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="only" size="small">
            <el-radio-button value="">全部成品批次</el-radio-button>
            <el-radio-button value="has">已生码</el-radio-button>
          </el-radio-group>
          <el-input v-model="t.keyword.value" placeholder="搜索批号、产品" clearable />
        </div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loader.loading.value" empty-text="没有成品批次">
        <el-table-column label="批号" width="150"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
        <el-table-column prop="itemName" label="产品" min-width="160" />
        <el-table-column prop="statusLabel" label="状态" width="110" />
        <el-table-column label="入库数量" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qtyIn) }}</span></template></el-table-column>
        <el-table-column label="已生码" width="150">
          <template #default="{ row }">
            <span class="mono">{{ fmtQty(row.generated) }}</span>
            <el-progress v-if="row.generated" :percentage="Math.min(100, Math.round(row.generated / Number(row.qtyIn) * 100))" :stroke-width="6" :show-text="false" />
          </template>
        </el-table-column>
        <el-table-column label="被扫码 / 扫码次数" width="150" align="right">
          <template #default="{ row }"><span class="mono">{{ fmtQty(row.scannedCodes) }} / {{ fmtQty(row.scans) }}</span></template>
        </el-table-column>
        <el-table-column label="" width="190" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canGenerate && canGenerateFor(row)" size="small" @click="openGenerate(row)">生成码</el-button>
            <el-button v-if="canGenerate && row.generated" size="small" type="primary" plain :loading="exporting === row.batchId" @click="exportCsv(row)">导出 CSV</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <el-dialog v-model="g.visible" :title="`生成防伪码：${g.row?.batchNo || ''}`" width="440px">
      <p class="muted small" style="margin-top:0">{{ g.row?.itemName }}，入库 {{ fmtQty(g.row?.qtyIn) }} 件，已生成 {{ fmtQty(g.row?.generated) }} 个，最多还能生成 <b>{{ fmtQty(remaining) }}</b> 个。单次最多 20000 个。</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="本次生成数量"><el-input-number v-model="g.qty" :min="1" :max="Math.min(remaining, 20000)" :precision="0" :controls="false" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="g.visible = false">取消</el-button>
        <el-button type="primary" :loading="g.saving" :disabled="!remaining" @click="generate">生成防伪码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime } from '../utils/format'
import { useLoader } from '../utils/loader'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const list = ref([])
const only = ref('')
const q = ref('')
const found = ref(null)
const looking = ref(false)
const exporting = ref(null)
const canGenerate = computed(() => auth.hasAny('ADMIN', 'PRODUCTION', 'QA', 'QSO'))
const canGenerateFor = (row) => ['已放行', '待上市放行'].includes(row.statusLabel)

const loader = useLoader(async () => { list.value = await http.get('/serials/batches') })
const load = () => loader.run()
const t = usePaged(list, { match: textMatch('batchNo', 'itemName'), extra: (r) => !only.value || r.generated > 0 })

const g = reactive({ visible: false, saving: false, row: null, qty: 100 })
const remaining = computed(() => (g.row ? Math.max(0, Number(g.row.qtyIn) - g.row.generated) : 0))

function openGenerate(row) {
  Object.assign(g, { visible: true, saving: false, row, qty: Math.min(Math.max(0, Number(row.qtyIn) - row.generated), 1000) || 1 })
}

async function generate() {
  g.saving = true
  try {
    const r = await http.post('/serials/generate', { batchNo: g.row.batchNo, qty: g.qty })
    ElMessage.success(`已生成，${r.batchNo} 累计 ${r.generated} 个码`)
    g.visible = false
    load()
  } finally {
    g.saving = false
  }
}

async function exportCsv(row) {
  exporting.value = row.batchId
  try {
    const blob = await http.get(`/serials/batches/${row.batchId}/export`, { responseType: 'blob' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `防伪码-${row.batchNo}.csv`
    a.click()
    URL.revokeObjectURL(a.href)
  } finally {
    exporting.value = null
  }
}

async function lookup() {
  found.value = null
  if (!q.value.trim()) return
  looking.value = true
  try {
    found.value = await http.get(`/serials/codes/${encodeURIComponent(q.value.trim())}`)
  } finally {
    looking.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.ask { display: grid; grid-template-columns: minmax(0, 420px) auto; gap: 8px; }
.found { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; margin-top: 12px; padding: 10px 14px; border-radius: 14px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); }
</style>
