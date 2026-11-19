<template>
  <div class="page viz">
    <div class="page-head">
      <div>
        <h2>经营看板</h2>
        <p>库存金额与健康、受托厂交付、放行周期、投诉和追溯响应。金额按物料标准成本估值（成品没填标准成本时按 BOM 物料成本估算），其余指标按数量统计。<span v-if="k" class="muted">数据生成于 {{ fmtTime(k.generatedAt) }}。</span></p>
      </div>
      <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />

    <template v-if="k">
      <!-- 金额（按标准成本；成品没填时按 BOM 物料成本估算） -->
      <div class="money glass">
        <div v-for="m in moneyTiles" :key="m.k" class="mt">
          <div class="hk">{{ m.k }}</div>
          <div class="mv mono">{{ fmtMoney(m.v) }}</div>
        </div>
        <div v-if="k.money.itemsWithoutCost" class="mnote small"><el-icon class="ti"><WarningFilled /></el-icon>{{ k.money.itemsWithoutCost }} 个在库物料没有成本，未计入金额，请在主数据补齐</div>
        <div v-else class="mnote small muted">按标准成本估值；成品没填标准成本时按 BOM 物料成本估算</div>
      </div>

      <!-- 头条数字 -->
      <div class="tiles">
        <div v-for="h in headline" :key="h.k" class="tile glass">
          <div class="hk">{{ h.k }}</div>
          <div class="hv mono">{{ h.v }}<small v-if="h.u">{{ h.u }}</small></div>
          <div class="hd">{{ h.d }}</div>
        </div>
      </div>

      <div class="grid2">
        <!-- 成品效期分布 -->
        <el-card shadow="never">
          <template #header>
            <div class="ch"><b>成品库存效期分布</b><el-button text size="small" @click="tables.shelf = !tables.shelf">{{ tables.shelf ? '看图' : '数据表' }}</el-button></div>
          </template>
          <div v-if="!k.shelf.length" class="muted small">没有在库的已放行成品。</div>
          <template v-else-if="!tables.shelf">
            <div class="legend">
              <span v-for="(b, i) in BUCKETS" :key="b.key"><i :style="{ background: `var(--b${i + 1})` }" />{{ b.label }}</span>
            </div>
            <div v-for="s in k.shelf" :key="s.itemName" class="hrow">
              <div class="hlabel">{{ s.itemName }}</div>
              <div class="hbar stacked" :style="{ width: `${(totalOf(s) / maxShelf) * 100}%` }">
                <span v-for="(b, i) in BUCKETS" v-show="Number(s[b.key]) > 0" :key="b.key" class="seg" :style="{ flex: Number(s[b.key]), background: `var(--b${i + 1})` }"
                      @mouseenter="tipOn($event, `${s.itemName} · ${b.label}：${fmtQty(s[b.key])}`)" @mousemove="tipMove" @mouseleave="tipOff" />
              </div>
              <div class="hval mono">{{ fmtQty(totalOf(s)) }}</div>
            </div>
          </template>
          <el-table v-else :data="k.shelf" size="small">
            <el-table-column prop="itemName" label="成品" min-width="140" />
            <el-table-column v-for="b in BUCKETS" :key="b.key" :label="b.label" width="96" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row[b.key]) }}</span></template></el-table-column>
          </el-table>
        </el-card>

        <!-- 可售天数 -->
        <el-card shadow="never">
          <template #header>
            <div class="ch"><b>成品可售天数</b><el-button text size="small" @click="tables.supply = !tables.supply">{{ tables.supply ? '看图' : '数据表' }}</el-button></div>
          </template>
          <template v-if="!tables.supply">
            <p class="muted small cap">按近 90 天日均出库算；虚线是默认再订货线（交付 30 天 + 安全 15 天）。</p>
            <div v-for="s in k.supply" :key="s.itemName" class="hrow">
              <div class="hlabel">{{ s.itemName }}</div>
              <div class="hbar track">
                <span v-if="s.daysOfSupply != null" class="bar" :style="{ width: pctOfMax(s.daysOfSupply) }"
                      @mouseenter="tipOn($event, `${s.itemName}：可售 ${fmtQty(s.available)}，日均 ${Number(s.dailyDemand).toFixed(1)}，约 ${s.daysOfSupply} 天`)" @mousemove="tipMove" @mouseleave="tipOff" />
                <span class="ref" :style="{ left: pctOfMax(REORDER_DAYS) }" />
              </div>
              <div class="hval mono">{{ s.daysOfSupply == null ? '无出库' : `${s.daysOfSupply} 天` }}</div>
            </div>
          </template>
          <el-table v-else :data="k.supply" size="small">
            <el-table-column prop="itemName" label="成品" min-width="140" />
            <el-table-column label="可售" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.available) }}</span></template></el-table-column>
            <el-table-column label="日均" width="80" align="right"><template #default="{ row }"><span class="mono">{{ Number(row.dailyDemand).toFixed(1) }}</span></template></el-table-column>
            <el-table-column label="可售天数" width="90" align="right"><template #default="{ row }"><span class="mono">{{ row.daysOfSupply ?? '—' }}</span></template></el-table-column>
            <el-table-column label="年周转次数" width="100" align="right"><template #default="{ row }"><span class="mono">{{ row.turnsPerYear ?? '—' }}</span></template></el-table-column>
          </el-table>
        </el-card>
      </div>

      <div class="grid2">
        <!-- 受托厂 / 自有工厂 -->
        <el-card shadow="never" body-style="padding:0">
          <template #header><div class="ch"><b>生产方交付（近 180 天完工）</b><span class="muted small">完工晚于计划 3 天内算准时</span></div></template>
          <el-table :data="k.factories" size="small" empty-text="近 180 天没有完工的生产单">
            <el-table-column label="生产方" min-width="140"><template #default="{ row }">{{ row.factoryName }}<div class="muted small">{{ row.kindLabel }}</div></template></el-table-column>
            <el-table-column label="完工单数" width="80" align="right"><template #default="{ row }"><span class="mono">{{ row.completed }}</span></template></el-table-column>
            <el-table-column label="准时率" width="90" align="right"><template #default="{ row }"><span class="mono">{{ row.onTimeRate == null ? '—' : `${row.onTimeRate}%` }}</span></template></el-table-column>
            <el-table-column label="平均收率" width="90" align="right"><template #default="{ row }"><span class="mono">{{ row.avgYieldPct }}%</span></template></el-table-column>
            <el-table-column label="平均周期" width="90" align="right"><template #default="{ row }"><span class="mono">{{ row.avgLeadDays }} 天</span></template></el-table-column>
          </el-table>
        </el-card>

        <!-- 投诉 -->
        <el-card shadow="never">
          <template #header>
            <div class="ch"><b>投诉（近 6 个月）</b><el-button text size="small" @click="tables.complaints = !tables.complaints">{{ tables.complaints ? '看图' : '数据表' }}</el-button></div>
          </template>
          <template v-if="!tables.complaints">
            <div class="legend"><span><i style="background: var(--s1)" />其他投诉</span><span><i style="background: var(--s2)" />不良反应</span></div>
            <div class="cols">
              <div v-for="mc in k.complaints.monthly" :key="mc.month" class="col">
                <div class="stack" @mouseenter="tipOn($event, `${mc.month}：共 ${mc.total} 件，其中不良反应 ${mc.adverse} 件`)" @mousemove="tipMove" @mouseleave="tipOff">
                  <span v-if="mc.total - mc.adverse > 0" class="cseg top" :style="{ height: colH(mc.total - mc.adverse), background: 'var(--s1)' }" />
                  <span v-if="mc.adverse > 0" class="cseg" :class="{ top: mc.total === mc.adverse }" :style="{ height: colH(mc.adverse), background: 'var(--s2)' }" />
                </div>
                <div class="cval mono">{{ mc.total || '' }}</div>
                <div class="clabel">{{ mc.month.slice(5) }}月</div>
              </div>
            </div>
          </template>
          <el-table v-else :data="k.complaints.monthly" size="small">
            <el-table-column prop="month" label="月份" />
            <el-table-column prop="total" label="投诉" align="right" />
            <el-table-column prop="adverse" label="其中不良反应" align="right" />
          </el-table>
          <div class="facts">
            <span>处理中 <b class="mono">{{ k.complaints.open }}</b></span>
            <span>不良反应超期未报 <b class="mono" :class="{ bad: k.complaints.adverseOverdue }">{{ k.complaints.adverseOverdue }}</b></span>
            <span>上报及时率 <b class="mono">{{ k.complaints.adverseReportOnTimeRate == null ? '—' : `${k.complaints.adverseReportOnTimeRate}%` }}</b></span>
          </div>
        </el-card>
      </div>

      <div class="grid2">
        <!-- 放行周期 + 追溯 -->
        <el-card shadow="never">
          <template #header><b>质量放行与追溯</b></template>
          <div class="facts col2">
            <div><div class="muted small">物料检验周期（{{ k.quality.materialDecisions }} 次）</div><b class="big mono">{{ k.quality.avgMaterialQcDays ?? '—' }}</b> 天</div>
            <div><div class="muted small">成品上市放行周期（{{ k.quality.releaseDecisions }} 次）</div><b class="big mono">{{ k.quality.avgReleaseDays ?? '—' }}</b> 天</div>
            <div><div class="muted small">物料检验合格率</div><b class="big mono">{{ k.quality.materialPassRate ?? '—' }}</b>%</div>
            <div>
              <div class="muted small">全链追溯（抽 {{ k.trace.sampled }} 个批次）</div>
              <b class="big mono">{{ k.trace.avgMs }}</b> 毫秒 <span class="muted small">最慢 {{ k.trace.maxMs }} 毫秒</span>
              <div class="small ok"><el-icon class="ti"><CircleCheckFilled /></el-icon>远低于一期验收门槛 2 小时</div>
            </div>
          </div>
        </el-card>

        <!-- 呆滞 -->
        <el-card shadow="never" body-style="padding:0">
          <template #header><div class="ch"><b>呆滞库存</b><span class="muted small">生产超过 60 天、近 60 天没有出库</span></div></template>
          <el-table :data="k.slowMoving" size="small" empty-text="没有呆滞批次">
            <el-table-column label="批号" width="140"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
            <el-table-column label="物料" min-width="130"><template #default="{ row }">{{ row.itemName }}<div class="muted small">{{ row.itemType }}</div></template></el-table-column>
            <el-table-column label="在库" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }} {{ row.unit }}</span></template></el-table-column>
            <el-table-column label="剩余效期" width="120"><template #default="{ row }"><ShelfBar :ratio="row.shelfRatio" /></template></el-table-column>
            <el-table-column label="金额" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.value) }}</span></template></el-table-column>
          </el-table>
        </el-card>
      </div>

      <!-- 供应商绩效考核台账 -->
      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header>
          <div class="ch">
            <b>供应商绩效考核台账</b>
            <div class="toolbar">
              <el-date-picker v-model="perf.from" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" clearable style="width:140px" @change="loadPerf" />
              <el-date-picker v-model="perf.to" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" clearable style="width:140px" @change="loadPerf" />
              <el-button size="small" @click="loadPerf">查询</el-button>
              <ExportButton report="supplier-performance" />
            </div>
          </div>
        </template>
        <el-table :data="perf.rows" v-loading="perf.loading" size="small" empty-text="暂无供应商绩效数据">
          <el-table-column label="供应商" min-width="160">
            <template #default="{ row }">
              {{ row.supplierName }}
              <div class="muted small mono">{{ row.supplierCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="准入" width="80">
            <template #default="{ row }"><el-tag size="small" :type="row.qualified ? 'success' : 'info'" disable-transitions>{{ row.qualified ? '合格' : '未批准' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="来料批数" width="95" align="right">
            <template #default="{ row }"><span class="mono">{{ row.releasedBatches }}/{{ row.totalBatches }}</span></template>
          </el-table-column>
          <el-table-column label="合格率" width="85" align="right">
            <template #default="{ row }"><span class="mono">{{ row.passRatePct == null ? '—' : `${row.passRatePct}%` }}</span></template>
          </el-table-column>
          <el-table-column label="准时交货率" width="95" align="right">
            <template #default="{ row }"><span class="mono">{{ row.onTimeRatePct == null ? '—' : `${row.onTimeRatePct}%` }}</span></template>
          </el-table-column>
          <el-table-column label="异议 / 退供" width="95" align="right">
            <template #default="{ row }"><span class="mono">{{ row.receiptDisputeCount }} / {{ row.returnCount }}</span></template>
          </el-table-column>
          <el-table-column label="有效协议价" width="95" align="right">
            <template #default="{ row }"><span class="mono">{{ row.activeContractCount }}</span></template>
          </el-table-column>
          <el-table-column label="综合得分" width="90" align="right">
            <template #default="{ row }"><b class="mono">{{ row.score ?? '—' }}</b></template>
          </el-table-column>
          <el-table-column label="等级" width="75" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.grade && row.grade !== '-'" size="small" :type="row.grade === 'A' ? 'success' : row.grade === 'B' ? 'primary' : row.grade === 'C' ? 'warning' : 'danger'" disable-transitions>{{ row.grade }}</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 生产批次实际成本核算（仅价格权限角色可见） -->
      <el-card v-if="canPrice" shadow="never" body-style="padding:0 0 8px">
        <template #header>
          <div class="ch">
            <b>生产批次实际成本核算</b>
            <div class="toolbar">
              <el-button size="small" @click="loadBatchCosts">刷新</el-button>
              <ExportButton report="batch-costs" />
            </div>
          </div>
        </template>
        <el-table :data="bc.rows" v-loading="bc.loading" size="small" empty-text="暂无已完工生产单成本数据">
          <el-table-column label="生产单 / 批号" width="165">
            <template #default="{ row }"><span class="mono">{{ row.moNo }}</span><div class="muted small mono">{{ row.outBatchNo }}</div></template>
          </el-table-column>
          <el-table-column label="成品 / 生产方" min-width="170">
            <template #default="{ row }">{{ row.fgName }}<div class="muted small">{{ row.moTypeLabel }} · {{ row.factoryName }}</div></template>
          </el-table-column>
          <el-table-column label="实际产量" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.actualQty) }}</span></template></el-table-column>
          <el-table-column label="领料成本" width="105" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.materialCost) }}</span></template></el-table-column>
          <el-table-column label="委外加工费" width="105" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.processingCost) }}</span></template></el-table-column>
          <el-table-column label="实际单位成本" width="115" align="right"><template #default="{ row }"><b class="mono">{{ fmtMoney(row.actualUnitCost) }}</b></template></el-table-column>
          <el-table-column label="标准成本" width="100" align="right"><template #default="{ row }"><span class="mono">{{ row.stdUnitCost != null ? fmtMoney(row.stdUnitCost) : '—' }}</span></template></el-table-column>
          <el-table-column label="偏差" width="90" align="right">
            <template #default="{ row }">
              <span v-if="row.variancePct != null" class="mono" :class="{ bad: Number(row.variancePct) > 5 }">{{ Number(row.variancePct) > 0 ? '+' : '' }}{{ row.variancePct }}%</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 毛利分析与现金流预测（仅价格权限角色可见） -->
      <div v-if="canPrice" class="grid2">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header>
            <div class="ch">
              <b>毛利分析（近 180 天）</b>
              <div class="toolbar">
                <el-radio-group v-model="mg.dim" size="small">
                  <el-radio-button value="item">按成品</el-radio-button>
                  <el-radio-button value="customer">按客户</el-radio-button>
                </el-radio-group>
                <ExportButton report="margins" />
              </div>
            </div>
            <div v-if="mg.data" class="muted small" style="margin-top:6px">
              销售额 <b class="mono">¥{{ fmtMoney(mg.data.totalRevenue) }}</b> · 成本 <b class="mono">¥{{ fmtMoney(mg.data.totalCogs) }}</b> · 毛利 <b class="mono">¥{{ fmtMoney(mg.data.totalGrossProfit) }}</b>（{{ mg.data.grossMarginPct ?? '—' }}%）
            </div>
          </template>
          <el-table :data="mg.dim === 'item' ? (mg.data?.byItem || []) : (mg.data?.byCustomer || [])" v-loading="mg.loading" size="small" empty-text="暂无带价销售出库数据">
            <el-table-column :label="mg.dim === 'item' ? '成品' : '客户'" min-width="140"><template #default="{ row }">{{ row.name }}<div class="muted small mono">{{ row.code }}</div></template></el-table-column>
            <el-table-column label="销售额" width="105" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.revenue) }}</span></template></el-table-column>
            <el-table-column label="毛利" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.grossProfit) }}</span></template></el-table-column>
            <el-table-column label="毛利率" width="80" align="right"><template #default="{ row }"><b class="mono">{{ row.grossMarginPct != null ? `${row.grossMarginPct}%` : '—' }}</b></template></el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header>
            <div class="ch">
              <b>现金流预测 & 库存损益</b>
              <div class="toolbar">
                <ExportButton report="cashflow-forecast" />
                <ExportButton report="inventory-variance" label="导出损益" />
              </div>
            </div>
            <div v-if="cf.data" class="muted small" style="margin-top:6px">
              预计流入 <b class="mono">¥{{ fmtMoney(cf.data.totalInflow) }}</b> · 预计流出 <b class="mono">¥{{ fmtMoney(cf.data.totalOutflow) }}</b> · 净现金流 <b class="mono" :class="{ bad: Number(cf.data.netCashFlow) < 0 }">¥{{ fmtMoney(cf.data.netCashFlow) }}</b>
            </div>
            <div v-if="iv.data" class="muted small" style="margin-top:4px">
              近 180 天库存损益：盘盈 ¥{{ fmtMoney(iv.data.gainValue) }} · 盘亏 ¥{{ fmtMoney(iv.data.lossValue) }} · 报废 ¥{{ fmtMoney(iv.data.scrapValue) }} · 净额 <b class="mono" :class="{ bad: Number(iv.data.netValue) < 0 }">¥{{ fmtMoney(iv.data.netValue) }}</b>
            </div>
          </template>
          <el-table :data="cf.data?.buckets || []" v-loading="cf.loading" size="small">
            <el-table-column prop="label" label="区间" width="100" />
            <el-table-column label="预计回款" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.inflow) }}</span></template></el-table-column>
            <el-table-column label="预计付款" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.outflow) }}</span></template></el-table-column>
            <el-table-column label="净现金流" min-width="110" align="right"><template #default="{ row }"><b class="mono" :class="{ bad: Number(row.netCashFlow) < 0 }">{{ fmtMoney(row.netCashFlow) }}</b></template></el-table-column>
          </el-table>
        </el-card>
      </div>
    </template>

    <div v-show="tip.show" class="tip glass" :style="{ left: `${tip.x}px`, top: `${tip.y}px` }">{{ tip.text }}</div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ExportButton from '../components/ExportButton.vue'
import LoadError from '../components/LoadError.vue'
import ShelfBar from '../components/ShelfBar.vue'
import { useAuth } from '../stores/auth'
import { fmtMoney, fmtQty, fmtTime } from '../utils/format'
import { useLoader } from '../utils/loader'

// 剩余效期分档：有序，用同一色相由浅到深（深色模式反过来），越“新鲜”越醒目
const BUCKETS = [
  { key: 'lt33', label: '< 1/3' },
  { key: 'lt50', label: '1/3–1/2' },
  { key: 'lt67', label: '1/2–2/3' },
  { key: 'ge67', label: '≥ 2/3' }
]
const REORDER_DAYS = 45

const k = ref(null)
const tables = reactive({ shelf: false, supply: false, complaints: false })
const loader = useLoader(async () => { k.value = await http.get('/analytics/kpi') })
const load = () => loader.run()

const moneyTiles = computed(() => {
  const m = k.value.money
  return [
    { k: '库存金额', v: m.inventoryValue },
    { k: '其中成品', v: m.fgValue },
    { k: '其中物料', v: m.materialValue },
    { k: '临期成品', v: m.nearExpiryValue },
    { k: '呆滞', v: m.slowMovingValue },
    { k: '退货隔离区', v: m.returnAreaValue }
  ]
})

const headline = computed(() => {
  const h = k.value.headline
  const pct = (v) => (v == null ? '—' : `${v}`)
  return [
    { k: '成品可售', v: fmtQty(h.fgOnHand), u: ' 件', d: '自有仓已放行' },
    { k: '临期占比', v: pct(h.nearExpiryShare), u: h.nearExpiryShare == null ? '' : '%', d: '剩余效期不足一半' },
    { k: '可售天数中位数', v: h.medianDaysOfSupply ?? '—', u: h.medianDaysOfSupply == null ? '' : ' 天', d: '按近 90 天日均出库' },
    { k: '准时交付率', v: pct(h.onTimeRate), u: h.onTimeRate == null ? '' : '%', d: '近 180 天完工' },
    { k: '上市放行周期', v: h.avgReleaseDays ?? '—', u: h.avgReleaseDays == null ? '' : ' 天', d: '完工入库到签批' },
    { k: '投诉率', v: pct(h.complaintsPer10k), u: h.complaintsPer10k == null ? '' : ' /万件', d: '近 90 天' },
    { k: '追溯响应', v: h.traceAvgMs, u: ' 毫秒', d: '验收门槛 2 小时' }
  ]
})

const totalOf = (s) => BUCKETS.reduce((a, b) => a + Number(s[b.key]), 0)
// 条长按各成品在库总量相对最大值，分段再按效期档拆开
const maxShelf = computed(() => Math.max(1, ...k.value.shelf.map(totalOf)))
const maxDays = computed(() => Math.max(REORDER_DAYS * 1.4, ...k.value.supply.map((s) => s.daysOfSupply || 0)))
const pctOfMax = (d) => `${Math.min(100, (d / maxDays.value) * 100)}%`
const maxMonth = computed(() => Math.max(1, ...k.value.complaints.monthly.map((m) => m.total)))
const colH = (n) => `${(n / maxMonth.value) * 100}%`

// 悬停提示：一个共享的浮层跟着鼠标走
const tip = reactive({ show: false, x: 0, y: 0, text: '' })
function tipOn(e, text) {
  tip.text = text
  tip.show = true
  tipMove(e)
}
function tipMove(e) {
  tip.x = Math.min(e.clientX + 14, window.innerWidth - 260)
  tip.y = e.clientY + 14
}
const tipOff = () => { tip.show = false }

const perf = reactive({ loading: false, from: null, to: null, rows: [] })
async function loadPerf() {
  perf.loading = true
  try {
    perf.rows = await http.get('/analytics/supplier-performance', {
      params: { from: perf.from || undefined, to: perf.to || undefined }
    })
  } finally {
    perf.loading = false
  }
}

const auth = useAuth()
const canPrice = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'FINANCE'))
const bc = reactive({ loading: false, rows: [] })
async function loadBatchCosts() {
  if (!canPrice.value) return
  bc.loading = true
  try { bc.rows = await http.get('/analytics/batch-costs') } finally { bc.loading = false }
}

const mg = reactive({ loading: false, dim: 'item', data: null })
const cf = reactive({ loading: false, data: null })
const iv = reactive({ loading: false, data: null })
async function loadFinanceInsights() {
  if (!canPrice.value) return
  mg.loading = cf.loading = iv.loading = true
  try {
    const [m, c, v] = await Promise.all([
      http.get('/analytics/margins'),
      http.get('/analytics/cashflow-forecast'),
      http.get('/analytics/inventory-variance')
    ])
    mg.data = m; cf.data = c; iv.data = v
  } finally {
    mg.loading = cf.loading = iv.loading = false
  }
}

onMounted(() => { load(); loadPerf(); loadBatchCosts(); loadFinanceInsights() })
</script>

<style scoped>
/* 图表配色：参考调色板的蓝色有序梯度与前两个类别色，已用校验脚本在浅色和深色下各验一遍 */
.viz { --b1: #86b6ef; --b2: #3987e5; --b3: #1c5cab; --b4: #0d366b; --s1: #2a78d6; --s2: #eb6834; }
:global(html.dark) .viz { --b1: #184f95; --b2: #2a78d6; --b3: #6da7ec; --b4: #b7d3f6; --s1: #3987e5; --s2: #d95926; }

.tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; }
.tile { padding: 14px 16px 12px; border-radius: 18px; }
.hk, .hd { font-size: 12px; color: var(--el-text-color-secondary); }
.hv { font-size: 28px; font-weight: 650; margin: 6px 0 2px; letter-spacing: -0.02em; }
.hv small { font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); }
.money { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 4px 18px; padding: 14px 18px; border-radius: 18px; }
.mt .mv { font-size: 20px; font-weight: 650; margin-top: 4px; letter-spacing: -0.01em; }
.mnote { grid-column: 1 / -1; padding-top: 4px; color: var(--el-color-warning); }
.mnote.muted { color: var(--el-text-color-secondary); }
.grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.ch { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
.cap { margin: -4px 0 10px; }

.legend { display: flex; gap: 14px; flex-wrap: wrap; margin-bottom: 12px; font-size: 12px; color: var(--el-text-color-secondary); }
.legend i { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 5px; vertical-align: -1px; }

.hrow { display: grid; grid-template-columns: minmax(80px, 140px) 1fr 76px; gap: 10px; align-items: center; margin: 10px 0; }
.hlabel { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hval { font-size: 12.5px; text-align: right; color: var(--el-text-color-regular); }
.hbar { height: 14px; position: relative; display: flex; }
.stacked { gap: 2px; }
.seg { min-width: 3px; cursor: default; }
.seg:first-of-type { border-radius: 4px 0 0 4px; }
.seg:last-of-type { border-radius: 0 4px 4px 0; }
.seg:only-of-type { border-radius: 4px; }
.track { background: var(--lg-hairline); border-radius: 4px; }
.bar { position: absolute; inset: 0 auto 0 0; background: var(--s1); border-radius: 0 4px 4px 0; }
.ref { position: absolute; top: -4px; bottom: -4px; width: 0; border-left: 2px dashed var(--el-text-color-secondary); }

.cols { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; align-items: end; height: 150px; }
.col { display: grid; grid-template-rows: 1fr auto auto; height: 100%; text-align: center; }
.stack { display: flex; flex-direction: column; justify-content: flex-end; gap: 2px; align-items: center; }
.cseg { width: 60%; max-width: 34px; display: block; }
.cseg.top { border-radius: 4px 4px 0 0; }
.cval { font-size: 12px; margin-top: 4px; }
.clabel { font-size: 12px; color: var(--el-text-color-secondary); }

.facts { display: flex; gap: 18px; flex-wrap: wrap; margin-top: 14px; font-size: 13px; }
.facts.col2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 0; }
.big { font-size: 24px; font-weight: 650; }
.bad { color: var(--el-color-danger); }
.ok { color: var(--el-color-success); margin-top: 4px; }
.ti { vertical-align: -2px; margin-right: 2px; }

.tip { position: fixed; z-index: 3000; pointer-events: none; padding: 8px 12px; border-radius: 12px; font-size: 12.5px; max-width: 260px; background: var(--lg-surface-pop); }
@media (max-width: 900px) { .grid2 { grid-template-columns: minmax(0, 1fr); } }
</style>
