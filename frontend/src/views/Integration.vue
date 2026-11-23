<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>电商对接</h2>
        <p>电商 ERP 把平台订单推送到这里，系统按先到期先出和客户效期标准直接出库；出库失败（缺货、SKU 对不上）的订单留在这里，补货后可重试。具体 ERP 选定后只需写一层报文转换，接口见“接入说明”。</p>
      </div>
      <div class="toolbar">
        <el-radio-group v-model="healthDays" size="small" @change="loadHealth">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
        <ExportButton report="integration-logs" label="导出对接日志" />
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
      </div>
    </div>

    <div v-if="health" class="kpi-grid">
      <div class="kpi-card">
        <div class="muted small">近 {{ health.days }} 天接口调用</div>
        <div class="kpi-val mono">{{ health.totalCalls }} <span class="muted small">（成功 {{ health.successCalls }} / 失败 {{ health.failedCalls }}）</span></div>
      </div>
      <div class="kpi-card" :class="{ warn: health.successRatePct != null && health.successRatePct < 95 }">
        <div class="muted small">接口同步成功率</div>
        <div class="kpi-val mono">{{ health.successRatePct != null ? `${health.successRatePct}%` : '—' }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: health.failedOrdersBacklog > 0 }">
        <div class="muted small">待重试失败订单积压</div>
        <div class="kpi-val mono">{{ health.failedOrdersBacklog }} <span class="muted small">（待处理 {{ health.receivedOrdersBacklog }}）</span></div>
      </div>
      <div class="kpi-card">
        <div class="muted small">活跃对接平台</div>
        <div class="kpi-val mono">{{ health.byPlatform.length }}</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="seg">
      <el-tab-pane :label="`电商订单${failedCount ? `（${failedCount} 失败）` : ''}`" name="orders" />
      <el-tab-pane :label="`异常重试监控${exceptionBoard?.totalExceptions ? `（${exceptionBoard.totalExceptions}）` : ''}`" name="exceptions" />
      <el-tab-pane label="健康度与失败复盘" name="health" />
      <el-tab-pane label="对接日志" name="logs" />
      <el-tab-pane label="接入说明" name="docs" />
    </el-tabs>

    <LoadError v-if="loader.error.value && tab !== 'docs'" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />

    <el-card v-else-if="tab === 'orders'" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="state" size="small">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="FAILED">出库失败</el-radio-button>
            <el-radio-button value="SHIPPED">已出库</el-radio-button>
          </el-radio-group>
          <el-input v-model="t.keyword.value" placeholder="搜索平台订单号、客户、SKU" clearable />
        </div>
      </template>
      <el-table :data="t.paged.value" v-loading="loader.loading.value" empty-text="还没有电商订单">
        <el-table-column label="平台 / 订单号" min-width="170"><template #default="{ row }"><el-tag size="small" disable-transitions>{{ row.platform }}</el-tag> <span class="mono">{{ row.platformOrderNo }}</span></template></el-table-column>
        <el-table-column label="客户 / 发货仓" min-width="150"><template #default="{ row }">{{ row.customerName }}<div class="muted small">{{ row.warehouseName }}</div></template></el-table-column>
        <el-table-column label="明细" min-width="180">
          <template #default="{ row }"><div v-for="(l, i) in row.lines" :key="i"><span class="mono small">{{ l.sku }}</span> {{ l.itemName || '（未匹配）' }} × {{ fmtQty(l.qty) }}</div></template>
        </el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.attempts > 1" class="muted small">已尝试 {{ row.attempts }} 次</div></template></el-table-column>
        <el-table-column label="结果" min-width="220">
          <template #default="{ row }">
            <span v-if="row.soNo" class="mono">{{ row.soNo }}</span>
            <span v-else-if="row.error" class="small err">{{ row.error }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="接收时间" width="140"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.receivedAt) }}</span></template></el-table-column>
        <el-table-column label="" width="150" fixed="right">
          <template #default="{ row }">
            <template v-if="canOperate && (row.status === 'FAILED' || row.status === 'RECEIVED')">
              <el-button v-if="row.status === 'FAILED'" size="small" type="primary" plain :loading="busy === row.id" @click="retry(row)">重试出库</el-button>
              <el-button size="small" @click="cancel(row)">取消</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <div v-else-if="tab === 'exceptions' && exceptionBoard" class="exc-wrap">
      <div class="kpi-grid">
        <div class="kpi-card" :class="{ warn: exceptionBoard.totalExceptions > 0 }">
          <div class="muted small">异常待闭环订单</div>
          <div class="kpi-val mono">{{ exceptionBoard.totalExceptions }}</div>
        </div>
        <div class="kpi-card" :class="{ warn: exceptionBoard.criticalCount > 0 }">
          <div class="muted small">严重滞留（≥24h / 重试≥3次）</div>
          <div class="kpi-val mono">{{ exceptionBoard.criticalCount }}</div>
        </div>
        <div class="kpi-card" :class="{ warn: exceptionBoard.skuUnmappedCount > 0 }">
          <div class="muted small">SKU 未匹配 / 库存效期不足</div>
          <div class="kpi-val mono">{{ exceptionBoard.skuUnmappedCount }} / {{ exceptionBoard.stockShortageCount }}</div>
        </div>
        <div class="kpi-card" :class="{ warn: exceptionBoard.recent7dFailureRatePct != null && exceptionBoard.recent7dFailureRatePct >= 5 }">
          <div class="muted small">近 7 日接口失败率</div>
          <div class="kpi-val mono">{{ exceptionBoard.recent7dFailureRatePct != null ? `${exceptionBoard.recent7dFailureRatePct}%` : '—' }} <span class="muted small">（{{ exceptionBoard.recent7dFailedCalls }} 次）</span></div>
        </div>
      </div>

      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header>
          <div class="filters">
            <el-radio-group v-model="excRisk" size="small">
              <el-radio-button value="">全部风险</el-radio-button>
              <el-radio-button value="CRITICAL">严重滞留</el-radio-button>
              <el-radio-button value="HIGH">高风险</el-radio-button>
              <el-radio-button value="MEDIUM">待重试</el-radio-button>
            </el-radio-group>
            <el-select v-model="excCause" size="small" clearable placeholder="按异常归类筛选" style="width: 150px">
              <el-option label="SKU 未匹配" value="SKU_UNMAPPED" />
              <el-option label="库存/效期不足" value="STOCK_SHORTAGE" />
              <el-option label="待出库滞留" value="STUCK_RECEIVED" />
              <el-option label="其它出库异常" value="OTHER_ERROR" />
            </el-select>
            <el-input v-model="excKeyword" placeholder="搜索平台、订单号、客户、SKU" clearable style="max-width: 240px" />
            <ExportButton report="integration-exceptions" label="导出异常监控" />
          </div>
        </template>
        <el-table :data="filteredExceptions" v-loading="loader.loading.value" empty-text="当前无待处理电商异常订单">
          <el-table-column label="风险分级" width="100">
            <template #default="{ row }">
              <el-tag :type="row.riskLevel === 'CRITICAL' ? 'danger' : row.riskLevel === 'HIGH' ? 'warning' : 'info'" size="small" disable-transitions>{{ row.riskLabel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="异常归类" width="120">
            <template #default="{ row }"><el-tag type="info" effect="plain" size="small" disable-transitions>{{ row.causeLabel }}</el-tag></template>
          </el-table-column>
          <el-table-column label="平台 / 订单号" min-width="170">
            <template #default="{ row }"><el-tag size="small" disable-transitions>{{ row.platform }}</el-tag> <span class="mono">{{ row.platformOrderNo }}</span></template>
          </el-table-column>
          <el-table-column label="客户 / 发货仓" min-width="140">
            <template #default="{ row }">{{ row.customerName }}<div class="muted small">{{ row.warehouseName }}</div></template>
          </el-table-column>
          <el-table-column label="商品明细" min-width="160" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono small">{{ row.skuSummary || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="滞留 / 尝试" width="115" align="right">
            <template #default="{ row }">
              <div class="mono" :class="{ err: row.agingHours >= 24 }">{{ row.agingHours }} 小时</div>
              <div class="muted small">已试 {{ row.attempts }} 次</div>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="190" show-overflow-tooltip>
            <template #default="{ row }"><span class="small err">{{ row.error || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="recommendedAction" label="闭环处置建议" min-width="220" show-overflow-tooltip />
          <el-table-column label="" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="canOperate">
                <el-button v-if="row.status === 'FAILED'" size="small" type="primary" plain :loading="busy === row.orderId" @click="retry({ id: row.orderId, platformOrderNo: row.platformOrderNo })">重试出库</el-button>
                <el-button size="small" @click="cancel({ id: row.orderId, platformOrderNo: row.platformOrderNo })">取消</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <div v-else-if="tab === 'health' && health" class="health-grid">
      <el-card shadow="never" body-style="padding:0">
        <template #header><b>各平台近 {{ health.days }} 天同步健康度</b></template>
        <el-table :data="health.byPlatform" size="small" empty-text="该时间窗口内无对接记录">
          <el-table-column prop="platform" label="平台" width="110" />
          <el-table-column prop="totalCalls" label="总调用" width="80" align="right" />
          <el-table-column prop="successCalls" label="成功" width="75" align="right" />
          <el-table-column prop="failedCalls" label="失败" width="75" align="right" />
          <el-table-column label="成功率" width="90" align="right">
            <template #default="{ row }"><el-tag :type="row.successRatePct == null ? 'info' : row.successRatePct >= 95 ? 'success' : 'danger'" size="small" disable-transitions>{{ row.successRatePct != null ? `${row.successRatePct}%` : '—' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="最近调用" width="150"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.lastCallAt) }}</span></template></el-table-column>
        </el-table>
      </el-card>
      <el-card shadow="never" body-style="padding:0">
        <template #header><b>高频失败原因归类（Top 20）</b></template>
        <el-table :data="health.topFailures" size="small" empty-text="近窗口内无失败调用">
          <el-table-column prop="platform" label="平台" width="100" />
          <el-table-column prop="action" label="动作" width="110" />
          <el-table-column prop="message" label="失败原因" min-width="200" show-overflow-tooltip />
          <el-table-column prop="count" label="次数" width="75" align="right" />
          <el-table-column label="最近发生" width="150"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.lastAt) }}</span></template></el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-card v-else-if="tab === 'logs'" shadow="never" body-style="padding:0">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="logScope" size="small">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="ECOM">电商 ERP</el-radio-button>
            <el-radio-button v-if="canSeeFinance" value="FINANCE">外部财务</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-table :data="filteredLogs" v-loading="loader.loading.value" empty-text="还没有对接记录" max-height="560">
        <el-table-column label="时间" width="150"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
        <el-table-column label="方向" width="110"><template #default="{ row }">{{ row.direction === 'IN' ? '平台 → 本系统' : '本系统 → 平台' }}</template></el-table-column>
        <el-table-column prop="platform" label="平台" width="90" />
        <el-table-column prop="action" label="动作" width="120" />
        <el-table-column label="对象" width="150"><template #default="{ row }"><span class="mono small">{{ row.ref }}</span></template></el-table-column>
        <el-table-column label="结果" width="80"><template #default="{ row }"><el-tag :type="row.success ? 'success' : 'danger'" size="small" disable-transitions>{{ row.success ? '成功' : '失败' }}</el-tag></template></el-table-column>
        <el-table-column prop="message" label="说明" min-width="220" />
      </el-table>
    </el-card>

    <div v-else class="docs">
      <section class="glass doc">
        <h3>认证</h3>
        <p>所有对接接口都在 <code>{{ base }}</code> 下，请求头带 <code>X-Api-Key</code>。密钥在服务器环境变量 <code>SCM_INTEGRATION_API_KEY</code> 里配置，不在页面显示；未配置时对接接口整体关闭。</p>
      </section>
      <section class="glass doc">
        <h3>推送订单 <code>POST /orders</code></h3>
        <p><code>customerCode</code> 是主数据里该店铺对应的客户编码（决定效期接收标准）；SKU 按成品物料编码匹配；<code>warehouseCode</code> 不填则从第一个自有仓发货。同一平台同一订单号重复推送只处理一次，返回 <code>duplicate: true</code>。每单单独出库，某单失败不影响其它单。</p>
        <pre>{{ pushExample }}</pre>
        <p class="muted small">返回每单的结果：<code>status</code> 为 SHIPPED（已出库，带出库单号 soNo）或 FAILED（带 error 原因）。</p>
      </section>
      <section class="glass doc">
        <h3>取消订单 <code>POST /orders/cancel</code></h3>
        <p>未出库或出库失败的订单可取消；已出库的请走销售退货。</p>
        <pre>{ "platform": "TMALL", "orderNo": "TM-20261103-0001" }</pre>
      </section>
      <section class="glass doc">
        <h3>查询可售库存 <code>GET /stock?customerCode=CUS-TM</code></h3>
        <p>按该客户的效期接收标准计算每个 SKU 的可售数量（只算自有仓已放行库存，不含退货隔离区），供 ERP 同步到店铺。</p>
        <pre>{{ curlExample }}</pre>
      </section>
      <section class="glass doc">
        <h3>拉取应付凭证 <code>GET /finance/vouchers?pendingOnly=true</code></h3>
        <p>供外部财务系统拉取已审批/已结清的对账单（含往来单位财务编码、税号、开户行、分税率汇总、发票与批次明细）。支持 <code>syncStatus=PENDING|SYNCED|FAILED</code> 或 <code>pendingOnly=true</code>。</p>
      </section>
      <section class="glass doc">
        <h3>凭证回执 <code>POST /finance/vouchers/{statementNo}/ack</code></h3>
        <p>外部财务系统制证后回写外部凭证号或失败说明。</p>
        <pre>{ "success": true, "extVoucherNo": "AP-2026-0001", "note": "已生成应付凭证" }</pre>
      </section>
      <section class="glass doc">
        <h3>付款水单回调核销 <code>POST /finance/payments</code></h3>
        <p>按 <code>extPaymentNo</code> 幂等核销对账单付款或红字退款，重复推送直接返回 <code>duplicate: true</code>。</p>
        <pre>{ "statementNo": "ST-20261118-0001", "extPaymentNo": "BANK-20261118-01", "payDate": "2026-11-18", "amount": 1000.00, "referenceNo": "银企流水号" }</pre>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import ExportButton from '../components/ExportButton.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtQty, fmtTime } from '../utils/format'
import { useLoader } from '../utils/loader'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const tab = ref('orders')
const orders = ref([])
const logs = ref([])
const health = ref(null)
const exceptionBoard = ref(null)
const healthDays = ref(30)
const state = ref('')
const excRisk = ref('')
const excCause = ref('')
const excKeyword = ref('')
const logScope = ref('')
const busy = ref(null)
const canOperate = computed(() => auth.hasAny('ADMIN', 'SALES'))
const canSeeFinance = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'FINANCE'))
const filteredLogs = computed(() => logs.value.filter((l) => {
  if (logScope.value === 'FINANCE') return l.platform === 'FINANCE'
  if (logScope.value === 'ECOM') return l.platform !== 'FINANCE'
  return true
}))
const filteredExceptions = computed(() => {
  const rows = exceptionBoard.value?.rows || []
  const kw = excKeyword.value.trim().toLowerCase()
  return rows.filter((r) => {
    if (excRisk.value && r.riskLevel !== excRisk.value) return false
    if (excCause.value && r.causeCode !== excCause.value) return false
    if (kw && ![r.platform, r.platformOrderNo, r.customerName, r.skuSummary, r.error].some((v) => String(v || '').toLowerCase().includes(kw))) return false
    return true
  })
})
const base = `${location.origin}/api/integration`

const loader = useLoader(async () => {
  const [o, l, h, e] = await Promise.all([
    http.get('/channel-orders'),
    http.get('/channel-orders/logs'),
    http.get('/channel-orders/health', { params: { days: healthDays.value } }),
    http.get('/channel-orders/exception-board')
  ])
  orders.value = o
  logs.value = l
  health.value = h
  exceptionBoard.value = e
})
const load = () => loader.run()
async function loadHealth() {
  health.value = await http.get('/channel-orders/health', { params: { days: healthDays.value } })
}
const t = usePaged(orders, {
  match: textMatch('platformOrderNo', 'customerName', 'platform', (r) => r.lines.map((l) => l.sku).join(' ')),
  extra: (r) => !state.value || r.status === state.value
})
const failedCount = computed(() => orders.value.filter((o) => o.status === 'FAILED').length)

const pushExample = `{
  "platform": "TMALL",
  "customerCode": "CUS-TM",
  "warehouseCode": "WH-MAIN",
  "orders": [
    {
      "orderNo": "TM-20261103-0001",
      "orderedAt": "2026-11-03T10:00:00",
      "lines": [ { "sku": "FG-SER-001", "qty": 2 } ]
    }
  ]
}`
const curlExample = `curl -H "X-Api-Key: $SCM_INTEGRATION_API_KEY" \\
  "${base}/stock?customerCode=CUS-TM"`

async function retry(row) {
  busy.value = row.id
  try {
    const r = await http.post(`/channel-orders/${row.id}/retry`)
    if (r.status === 'SHIPPED') ElMessage.success(`已出库，出库单 ${r.soNo}`)
    else ElMessage.warning(`仍然失败：${r.error}`)
    load()
  } finally {
    busy.value = null
  }
}

async function cancel(row) {
  try {
    await ElMessageBox.confirm(`取消电商订单 ${row.platformOrderNo}？取消后不会再出库。`, '取消订单', { confirmButtonText: '取消订单', cancelButtonText: '不取消' })
  } catch {
    return
  }
  await http.post(`/channel-orders/${row.id}/cancel`)
  ElMessage.success('已取消')
  load()
}

onMounted(load)
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
.kpi-card { padding: 14px 16px; border-radius: 14px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); display: grid; gap: 4px; }
.kpi-card.warn { border-color: var(--el-color-danger-light-5); }
.kpi-val { font-size: 20px; font-weight: 600; }
.exc-wrap { display: grid; gap: 14px; }
.health-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(380px, 1fr)); gap: 14px; }
.err { color: var(--el-color-danger); }
.docs { display: grid; gap: 14px; }
.doc { padding: 16px 20px; }
.doc h3 { margin: 0 0 8px; font-size: 15px; }
.doc p { margin: 6px 0; line-height: 1.7; font-size: 13px; }
code { font-family: var(--scm-mono); font-size: 12px; padding: 1px 6px; border-radius: 6px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); }
pre { font-family: var(--scm-mono); font-size: 12px; line-height: 1.6; margin: 8px 0; padding: 12px 14px; border-radius: 12px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); overflow-x: auto; }
</style>
