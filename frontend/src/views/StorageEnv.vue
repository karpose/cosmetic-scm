<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>贮存和运输</h2>
        <p>仓库各区域和留样室的温湿度范围和记录、成品的运输条件和运输记录，对应检查要点委托生产版序号 16（留样区域贮存条件）和序号 20（贮存和运输，GMP 第五十九条）。范围由质量部门按产品要求设定，系统不内置；超出范围的记录自动生成偏差，同一区域偏差还没关闭、相隔 {{ mergeHours }} 小时以内的超限算一次。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <ExportButton v-if="tab === 'excursions'" report="env-excursions" />
        <ExportButton v-else-if="tab !== 'transport'" report="env-records" :params="{ from: range[0], to: range[1] }" :label="`导出 ${range[0]} 至 ${range[1]} 的记录`" />
        <el-button v-if="canArea && tab !== 'transport'" type="primary" @click="openArea()">新增区域</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <el-tabs v-else v-model="tab" class="seg">
      <el-tab-pane :label="`区域（${areas.length}）${missingCount ? `· ${missingCount} 个昨天漏记` : ''}`" name="areas">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <el-table :data="areas" empty-text="还没有登记贮存区域：请质量部门新增仓库各区域和留样室，设好温湿度范围" scrollbar-tabindex="0">
            <el-table-column label="区域" min-width="180">
              <template #default="{ row }">
                <b>{{ row.name }}</b> <el-tag size="small" :type="row.kind === 'RETAIN' ? 'warning' : 'info'" disable-transitions>{{ row.kindLabel }}</el-tag>
                <el-tag v-if="!row.active" size="small" type="info" disable-transitions>已停用</el-tag>
                <div class="muted small"><span class="mono">{{ row.code }}</span><template v-if="row.warehouseName"> · {{ row.warehouseName }}</template></div>
                <div v-if="narrow" class="small">{{ row.limitText }}</div>
              </template>
            </el-table-column>
            <el-table-column v-if="!narrow" label="范围" min-width="160">
              <template #default="{ row }">{{ row.limitText }}<div class="muted small">{{ row.dailyRequired ? `每天记录，从 ${row.monitorFrom} 起` : '不要求每天记录' }}</div></template>
            </el-table-column>
            <el-table-column label="最近一条" min-width="170">
              <template #default="{ row }">
                <template v-if="row.lastAt">
                  <el-tag :type="row.lastInRange ? 'success' : 'danger'" size="small" disable-transitions>{{ row.lastInRange ? '正常' : '超限' }}</el-tag>
                  {{ reading(row.lastTemp, row.lastRh) }}
                  <div class="muted small">{{ fmtTime(row.lastAt) }}</div>
                </template>
                <span v-else class="muted small">还没有记录</span>
              </template>
            </el-table-column>
            <el-table-column label="今天 / 昨天" width="120">
              <template #default="{ row }">
                {{ row.todayCount }} 条
                <div v-if="row.missing"><el-tag type="warning" size="small" disable-transitions>昨天漏记</el-tag></div>
                <div v-else-if="row.dailyRequired" class="muted small">{{ row.recordedYesterday ? '昨天已记' : '还没开始' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="" width="200" :fixed="narrow ? false : 'right'">
              <template #default="{ row }">
                <el-button v-if="canRecord && row.active" size="small" text type="primary" @click="openRecord(row)">录入</el-button>
                <el-button v-if="canRecord && row.active" size="small" text @click="openImport(row)">导入</el-button>
                <el-button size="small" text @click="showRecords(row)">记录</el-button>
                <el-button v-if="canArea" size="small" text @click="openArea(row)">修改</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="`记录${recs ? `（${recs.total}）` : ''}`" name="records">
        <div class="filters">
          <el-select v-model="areaId" aria-label="区域" placeholder="全部区域" clearable filterable style="width:220px" @change="loadRecords">
            <el-option v-for="a in areas" :key="a.id" :value="a.id" :label="`${a.name}（${a.code}）`" />
          </el-select>
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"
                          aria-label="日期范围" :shortcuts="shortcuts" :clearable="false" :disabled-date="(d) => d > endOfToday" style="width:260px" @change="loadRecords" />
        </div>
        <el-card v-if="areaId" shadow="never" class="trend-card">
          <p v-if="trendError" class="muted small">{{ trendError }}</p>
          <template v-else-if="trend">
            <p class="muted small trend-note">{{ trend.areaName }}：范围 {{ trend.limitText }}。悬停或用方向键看每条记录；相隔 12 小时以上的记录之间断开。</p>
            <div class="charts">
              <EnvTrendChart v-if="trend.tempMin !== null || trend.tempMax !== null || trend.points.some((p) => p.temp !== null)" label="温度" unit="℃"
                             :points="trend.points.map((p) => ({ t: p.t, v: p.temp, inRange: p.inRange }))" :min="trend.tempMin" :max="trend.tempMax" :from="trend.from" :to="trend.to" />
              <EnvTrendChart v-if="trend.rhMin !== null || trend.rhMax !== null || trend.points.some((p) => p.rh !== null)" label="相对湿度" unit="%"
                             :points="trend.points.map((p) => ({ t: p.t, v: p.rh, inRange: p.inRange }))" :min="trend.rhMin" :max="trend.rhMax" :from="trend.from" :to="trend.to" />
            </div>
          </template>
        </el-card>
        <p v-else class="muted small">选一个区域可以看温湿度趋势图。</p>
        <el-card shadow="never" body-style="padding:0 0 8px">
          <el-table v-loading="recLoading" :data="recs?.rows || []" empty-text="这段时间没有温湿度记录" scrollbar-tabindex="0">
            <el-table-column label="时间" width="150">
              <template #default="{ row }"><span class="mono">{{ fmtTime(row.recordedAt) }}</span><div v-if="narrow || !areaId" class="muted small">{{ row.areaName }}</div></template>
            </el-table-column>
            <el-table-column label="温度 / 湿度" width="130">
              <template #default="{ row }">{{ reading(row.temp, row.rh) }}</template>
            </el-table-column>
            <el-table-column label="结果" min-width="200">
              <template #default="{ row }">
                <el-tag v-if="row.voided" type="info" size="small" disable-transitions>已作废</el-tag>
                <el-tag v-else :type="row.inRange ? 'success' : 'danger'" size="small" disable-transitions>{{ row.inRange ? '正常' : '超限' }}</el-tag>
                <span v-if="row.problem" class="small"> {{ row.problem }}</span>
                <div v-if="row.excursionNo" class="small"><a href="#" class="mono" @click.prevent="openExcursion(row.excursionId)">{{ row.excursionNo }}</a>
                  <template v-if="row.deviationNo"> · 偏差 <router-link to="/qms?tab=deviations" class="mono">{{ row.deviationNo }}</router-link></template></div>
                <div v-if="row.voided" class="muted small">{{ row.voidedBy }} 作废：{{ row.voidReason }}</div>
              </template>
            </el-table-column>
            <el-table-column v-if="!narrow" label="来源" width="130">
              <template #default="{ row }">{{ row.sourceLabel }}<div v-if="row.device" class="muted small">{{ row.device }}</div></template>
            </el-table-column>
            <el-table-column v-if="!narrow" label="记录人和备注" min-width="160">
              <template #default="{ row }">{{ row.createdBy }}<div v-if="row.note" class="muted small">{{ row.note }}</div></template>
            </el-table-column>
            <el-table-column v-if="canVoid" label="" width="80" :fixed="narrow ? false : 'right'">
              <template #default="{ row }"><el-button v-if="!row.voided" size="small" text type="danger" @click="voidRecord(row)">作废</el-button></template>
            </el-table-column>
          </el-table>
          <p v-if="recs && recs.total > recs.rows.length" class="muted small cap">这段时间共 {{ recs.total }} 条，这里显示最近 {{ recs.rows.length }} 条；缩小日期范围或用“导出”查看全部。</p>
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="`超限${openExcursions ? `（${openExcursions} 次偏差未关闭）` : ''}`" name="excursions">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <el-table :data="excursions" empty-text="没有超限记录" scrollbar-tabindex="0">
            <el-table-column label="编号" width="160">
              <template #default="{ row }"><span class="mono">{{ row.excursionNo }}</span><div class="muted small">{{ row.areaName }}</div></template>
            </el-table-column>
            <el-table-column label="时间" min-width="170">
              <template #default="{ row }">{{ fmtTime(row.startedAt) }}<div v-if="row.lastAt !== row.startedAt" class="muted small">到 {{ fmtTime(row.lastAt) }}，{{ row.readingCount }} 条超限</div></template>
            </el-table-column>
            <el-table-column v-if="!narrow" label="超限读数" min-width="160">
              <template #default="{ row }">{{ row.peakText }}<div class="muted small">范围 {{ row.limitText }}</div></template>
            </el-table-column>
            <el-table-column label="偏差" width="150">
              <template #default="{ row }">
                <router-link v-if="row.deviationNo" to="/qms?tab=deviations" class="mono">{{ row.deviationNo }}</router-link>
                <div><el-tag v-if="row.deviationStatus" :type="row.deviationStatus === 'OPEN' ? 'warning' : 'success'" size="small" disable-transitions>{{ row.deviationStatusLabel }}</el-tag></div>
              </template>
            </el-table-column>
            <el-table-column label="现在" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.voided" type="info" size="small" disable-transitions>记录已作废</el-tag>
                <el-tag v-else-if="row.stillOut" type="danger" size="small" disable-transitions>仍超限</el-tag>
                <span v-else class="muted small">已恢复</span>
              </template>
            </el-table-column>
            <el-table-column label="" width="80" :fixed="narrow ? false : 'right'">
              <template #default="{ row }"><el-button size="small" text @click="openExcursion(row.id)">详情</el-button></template>
            </el-table-column>
          </el-table>
          <ListCapHint :count="excursions.length" />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="运输" name="transport">
        <TransportPane ref="transportPane" />
      </el-tab-pane>
    </el-tabs>

    <!-- 新增 / 修改区域 -->
    <el-dialog v-model="ar.visible" :title="ar.id ? `修改区域 ${ar.code}` : '新增贮存区域'" width="620px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-row">
          <el-form-item label="区域编码"><el-input id="sa-code" v-model="ar.code" maxlength="40" placeholder="如 WH-MAIN-A" /></el-form-item>
          <el-form-item label="区域名称"><el-input id="sa-name" v-model="ar.name" maxlength="100" placeholder="如 华东主仓常温区、留样室" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="类型">
            <el-radio-group v-model="ar.kind">
              <el-radio-button v-for="k in KINDS" :key="k.code" :value="k.code">{{ k.label }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="所在仓库（选填）">
            <el-select id="sa-wh" v-model="ar.warehouseId" placeholder="超限时列出这个仓库的批次" clearable style="width:100%">
              <el-option v-for="w in rd.s.warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
        </div>
        <p v-if="ar.kind === 'RETAIN'" class="muted small hint">留样区超限时，按留样台账的“留样地点”列出受影响的留样：地点以区域名称开头（如区域“品质部留样室”，地点“品质部留样室 B-01”）或等于区域编码。</p>
        <div class="form-row">
          <el-form-item label="温度范围（℃）">
            <div class="pair">
              <el-input-number id="sa-tmin" v-model="ar.tempMin" :precision="1" :step="1" :min="-80" :max="100" controls-position="right" placeholder="下限" />
              <span>至</span>
              <el-input-number v-model="ar.tempMax" aria-label="温度上限" :precision="1" :step="1" :min="-80" :max="100" controls-position="right" placeholder="上限" />
            </div>
          </el-form-item>
          <el-form-item label="相对湿度范围（%）">
            <div class="pair">
              <el-input-number id="sa-rhmin" v-model="ar.rhMin" :precision="1" :step="5" :min="0" :max="100" controls-position="right" placeholder="下限" />
              <span>至</span>
              <el-input-number v-model="ar.rhMax" aria-label="湿度上限" :precision="1" :step="5" :min="0" :max="100" controls-position="right" placeholder="上限" />
            </div>
          </el-form-item>
        </div>
        <p class="muted small hint">按产品标签和质量标准的贮存条件填写，不需要的留空（如只控温度上限就只填上限）。改范围不影响已有记录的判定。</p>
        <div class="form-row">
          <el-form-item label="每天至少记录一次"><el-switch v-model="ar.dailyRequired" aria-label="每天至少记录一次" /></el-form-item>
          <el-form-item label="从哪天开始算漏记"><el-date-picker id="sa-from" v-model="ar.monitorFrom" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item v-if="ar.id" label="启用"><el-switch v-model="ar.active" aria-label="启用" /></el-form-item>
        </div>
        <el-form-item label="备注（选填）"><el-input id="sa-note" v-model="ar.note" maxlength="300" placeholder="如：记录仪编号、存放的产品" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ar.visible = false">取消</el-button>
        <el-button type="primary" :loading="ar.saving" @click="saveArea">{{ ar.id ? '保存修改' : '新增区域' }}</el-button>
      </template>
    </el-dialog>

    <!-- 录入 -->
    <el-dialog v-model="rc.visible" :title="`录入温湿度：${rc.area?.name || ''}`" width="480px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small hint">范围：{{ rc.area?.limitText }}</p>
        <el-form-item label="记录时间"><el-date-picker id="er-time" v-model="rc.recordedAt" type="datetime" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
        <div class="form-row">
          <el-form-item label="温度（℃）"><el-input-number id="er-temp" v-model="rc.temp" :precision="1" :step="0.5" :min="-80" :max="100" controls-position="right" style="width:100%" @keydown.enter.prevent="saveRecord" /></el-form-item>
          <el-form-item label="相对湿度（%）"><el-input-number id="er-rh" v-model="rc.rh" :precision="1" :step="1" :min="0" :max="100" controls-position="right" style="width:100%" @keydown.enter.prevent="saveRecord" /></el-form-item>
        </div>
        <p v-if="rcProblem" class="bad small">{{ rcProblem }}，保存后会生成偏差（同一区域偏差还没关闭、{{ mergeHours }} 小时内的超限并入同一次）。</p>
        <div class="form-row">
          <el-form-item label="记录仪编号（选填）"><el-input id="er-device" v-model="rc.device" maxlength="60" /></el-form-item>
          <el-form-item label="备注（选填）"><el-input id="er-note" v-model="rc.note" maxlength="300" placeholder="超限时写明当时的处理" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="rc.visible = false">取消</el-button>
        <el-button type="primary" :loading="rc.saving" @click="saveRecord">保存记录</el-button>
      </template>
    </el-dialog>

    <ImportDialog v-model="im.visible" kind="env" :url="im.area ? `/storage-env/areas/${im.area.id}/records/import` : ''"
                  :title="`导入温湿度记录：${im.area?.name || ''}`" @done="afterImport" />

    <!-- 超限详情 -->
    <el-dialog v-model="ex.visible" :title="`温湿度超限 ${ex.data?.excursion.excursionNo || ''}`" width="720px">
      <template v-if="ex.data">
        <p class="summary-line">
          <el-tag v-if="ex.data.excursion.voided" type="info" size="small" disable-transitions>超限记录已作废</el-tag>
          <el-tag v-else-if="ex.data.excursion.stillOut" type="danger" size="small" disable-transitions>仍超限</el-tag>
          <el-tag v-else type="success" size="small" disable-transitions>已恢复</el-tag>
          {{ ex.data.excursion.areaName }} · {{ fmtTime(ex.data.excursion.startedAt) }}<template v-if="ex.data.excursion.lastAt !== ex.data.excursion.startedAt"> 到 {{ fmtTime(ex.data.excursion.lastAt) }}</template>
        </p>
        <p class="small"><b>超限读数：</b>{{ ex.data.excursion.peakText }}（范围 {{ ex.data.excursion.limitText }}）</p>
        <p class="small"><b>偏差：</b><router-link to="/qms?tab=deviations" class="mono">{{ ex.data.excursion.deviationNo }}</router-link>（{{ ex.data.excursion.deviationStatusLabel }}），在“质量体系 → 偏差”里调查、评估影响和关闭。</p>
        <h4>超限记录（{{ ex.data.readings.length }}）</h4>
        <el-table :data="ex.data.readings" size="small" max-height="220" scrollbar-tabindex="0">
          <el-table-column label="时间" width="150"><template #default="{ row }"><span class="mono">{{ fmtTime(row.recordedAt) }}</span></template></el-table-column>
          <el-table-column label="读数" width="120"><template #default="{ row }">{{ reading(row.temp, row.rh) }}</template></el-table-column>
          <el-table-column prop="problem" label="超出情况" min-width="200" />
        </el-table>
        <h4>可能受影响的批次（{{ ex.data.affected.length }}）</h4>
        <p class="muted small">{{ ex.data.affectedNote }}</p>
        <el-table v-if="ex.data.affected.length" :data="ex.data.affected" size="small" max-height="240" scrollbar-tabindex="0">
          <el-table-column label="批号" width="150"><template #default="{ row }"><span class="mono">{{ row.batchNo }}</span></template></el-table-column>
          <el-table-column label="物料" min-width="160"><template #default="{ row }">{{ row.itemName }}<div class="muted small mono">{{ row.itemCode }}</div></template></el-table-column>
          <el-table-column prop="qty" label="数量" width="110" />
          <el-table-column prop="status" label="状态" width="120" />
        </el-table>
      </template>
      <template #footer><el-button @click="ex.visible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import EnvTrendChart from '../components/EnvTrendChart.vue'
import ExportButton from '../components/ExportButton.vue'
import ImportDialog from '../components/ImportDialog.vue'
import ListCapHint from '../components/ListCapHint.vue'
import TransportPane from '../components/TransportPane.vue'
import LoadError from '../components/LoadError.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { useLoader } from '../utils/loader'
import { latestOnly } from '../utils/latest'
import { daysFromToday, fmtTime, today } from '../utils/format'

const TABS = ['areas', 'records', 'excursions', 'transport']
const KINDS = [{ code: 'WAREHOUSE', label: '仓储区' }, { code: 'RETAIN', label: '留样区' }, { code: 'OTHER', label: '其他' }]
const auth = useAuth()
const route = useRoute()
const rd = useRefData()
const canArea = computed(() => auth.hasAny('ADMIN', 'QA', 'QSO'))
const canRecord = computed(() => auth.hasAny('ADMIN', 'WAREHOUSE', 'QA', 'QSO'))
const canVoid = computed(() => auth.hasAny('QA', 'QSO'))
const endOfToday = new Date(`${today()}T23:59:59`)
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
// 合并窗口在后端配置（scm.storage-env.excursion-merge-hours），这里只用来显示说明
const mergeHours = 24

const tab = ref(TABS.includes(route.query.tab) ? route.query.tab : 'areas')
const areas = ref([])
const excursions = ref([])
const missingCount = computed(() => areas.value.filter((a) => a.missing).length)
const openExcursions = computed(() => excursions.value.filter((e) => !e.voided && e.deviationStatus === 'OPEN').length)

const loader = useLoader(async () => {
  const [a, e] = await Promise.all([http.get('/storage-env/areas'), http.get('/storage-env/excursions')])
  areas.value = a
  excursions.value = e
})
const transportPane = ref(null)
async function load() {
  await loader.run()
  loadRecords()
  transportPane.value?.load()
}
onMounted(() => { load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const reading = (t, h) => [t !== null && t !== undefined ? `${t}℃` : null, h !== null && h !== undefined ? `${h}%` : null].filter(Boolean).join(' / ') || '—'

// ---------- 记录和趋势：区域、日期会变，用 latestOnly 丢掉晚回来的旧结果 ----------
const areaId = ref(null)
const range = ref([daysFromToday(-6), today()])
const shortcuts = [
  { text: '最近 7 天', value: () => [new Date(`${daysFromToday(-6)}T00:00:00`), new Date(`${today()}T00:00:00`)] },
  { text: '最近 30 天', value: () => [new Date(`${daysFromToday(-29)}T00:00:00`), new Date(`${today()}T00:00:00`)] },
  { text: '最近 90 天', value: () => [new Date(`${daysFromToday(-89)}T00:00:00`), new Date(`${today()}T00:00:00`)] }
]
const recs = ref(null)
const recLoading = ref(false)
const trend = ref(null)
const trendError = ref('')
const recReq = latestOnly()
const trendReq = latestOnly()
async function loadRecords() {
  const params = { areaId: areaId.value || undefined, from: range.value[0], to: range.value[1] }
  const current = recReq()
  recLoading.value = true
  try {
    const r = await http.get('/storage-env/records', { params })
    if (current()) recs.value = r
  } finally {
    if (current()) recLoading.value = false
  }
  loadTrend()
}
async function loadTrend() {
  const current = trendReq()
  if (!areaId.value) {
    trend.value = null
    return
  }
  try {
    const t = await http.get(`/storage-env/areas/${areaId.value}/trend`, { params: { from: range.value[0], to: range.value[1] }, silent: true })
    if (current()) { trend.value = t; trendError.value = '' }
  } catch (e) {
    if (current()) { trend.value = null; trendError.value = e.message }
  }
}
function showRecords(row) {
  areaId.value = row.id
  tab.value = 'records'
  loadRecords()
}

async function voidRecord(row) {
  let reason
  try {
    reason = (await ElMessageBox.prompt(`${row.areaName} ${fmtTime(row.recordedAt)} ${reading(row.temp, row.rh)}。作废后记录保留、标为作废，同一时间可以重录；超限记录全作废了，对应的偏差请评估后关闭。`,
      '作废温湿度记录', { confirmButtonText: '作废', cancelButtonText: '不了', inputPlaceholder: '作废原因，如：录错区域', inputValidator: (v) => !!v?.trim() || '请写明作废原因' })).value
  } catch {
    return
  }
  await http.post(`/storage-env/areas/${row.areaId}/records/${row.id}/void`, { reason })
  ElMessage.success('已作废')
  load()
}

// ---------- 区域 ----------
const ar = reactive({ visible: false, saving: false, id: null, code: '', name: '', kind: 'WAREHOUSE', warehouseId: null, tempMin: null, tempMax: null, rhMin: null, rhMax: null,
  dailyRequired: true, monitorFrom: '', active: true, note: '' })
async function openArea(row) {
  await rd.ready()
  if (row) {
    Object.assign(ar, { visible: true, saving: false, id: row.id, code: row.code, name: row.name, kind: row.kind, warehouseId: row.warehouseId, tempMin: row.tempMin, tempMax: row.tempMax,
      rhMin: row.rhMin, rhMax: row.rhMax, dailyRequired: row.dailyRequired, monitorFrom: row.monitorFrom, active: row.active, note: row.note || '' })
  } else {
    Object.assign(ar, { visible: true, saving: false, id: null, code: '', name: '', kind: 'WAREHOUSE', warehouseId: null, tempMin: null, tempMax: null, rhMin: null, rhMax: null,
      dailyRequired: true, monitorFrom: today(), active: true, note: '' })
  }
}
const num = (v) => (v === null || v === undefined || v === '' ? null : v)
async function saveArea() {
  if (!ar.code.trim() || !ar.name.trim()) return ElMessage.warning('请填写区域编码和名称')
  if ([ar.tempMin, ar.tempMax, ar.rhMin, ar.rhMax].every((v) => num(v) === null)) return ElMessage.warning('请至少设定温度或湿度的一个上限或下限')
  if (!ar.monitorFrom) return ElMessage.warning('请选择开始记录的日期')
  ar.saving = true
  const body = { code: ar.code, name: ar.name, kind: ar.kind, warehouseId: ar.warehouseId, tempMin: num(ar.tempMin), tempMax: num(ar.tempMax), rhMin: num(ar.rhMin), rhMax: num(ar.rhMax),
    dailyRequired: ar.dailyRequired, monitorFrom: ar.monitorFrom, active: ar.active, note: ar.note }
  try {
    const r = ar.id ? await http.put(`/storage-env/areas/${ar.id}`, body) : await http.post('/storage-env/areas', body)
    ElMessage.success(ar.id ? `区域 ${r.name} 已修改` : `区域 ${r.name} 已新增`)
    ar.visible = false
    tab.value = 'areas'
    load()
  } finally {
    ar.saving = false
  }
}

// ---------- 录入 ----------
const nowText = () => new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16).replace(' ', 'T') + ':00'
const rc = reactive({ visible: false, saving: false, area: null, recordedAt: '', temp: null, rh: null, device: '', note: '' })
function openRecord(row) {
  Object.assign(rc, { visible: true, saving: false, area: row, recordedAt: nowText(), temp: null, rh: null, device: '', note: '' })
}
// 保存前先提示会不会超限（判定以后端为准）
const rcProblem = computed(() => {
  const a = rc.area
  if (!a) return ''
  const out = []
  if (num(rc.temp) !== null && num(a.tempMax) !== null && rc.temp > a.tempMax) out.push(`温度高于上限 ${a.tempMax}℃`)
  if (num(rc.temp) !== null && num(a.tempMin) !== null && rc.temp < a.tempMin) out.push(`温度低于下限 ${a.tempMin}℃`)
  if (num(rc.rh) !== null && num(a.rhMax) !== null && rc.rh > a.rhMax) out.push(`湿度高于上限 ${a.rhMax}%`)
  if (num(rc.rh) !== null && num(a.rhMin) !== null && rc.rh < a.rhMin) out.push(`湿度低于下限 ${a.rhMin}%`)
  return out.join('，')
})
async function saveRecord() {
  if (rc.saving) return
  if (!rc.recordedAt) return ElMessage.warning('请选择记录时间')
  if (num(rc.temp) === null && num(rc.rh) === null) return ElMessage.warning('请填写温度或湿度')
  rc.saving = true
  try {
    const r = await http.post(`/storage-env/areas/${rc.area.id}/records`, { recordedAt: rc.recordedAt, temp: num(rc.temp), rh: num(rc.rh), device: rc.device, note: rc.note })
    if (r.inRange) ElMessage.success('已保存，在范围内')
    else ElMessage.warning(`已保存。超限：${r.problem}${r.deviationNo ? `，偏差 ${r.deviationNo}` : ''}`)
    rc.visible = false
    load()
  } finally {
    rc.saving = false
  }
}

// ---------- 导入 ----------
const im = reactive({ visible: false, area: null })
function openImport(row) {
  Object.assign(im, { visible: true, area: row })
}
function afterImport() {
  areaId.value = im.area?.id || areaId.value
  load()
}

// ---------- 超限详情 ----------
const ex = reactive({ visible: false, data: null })
async function openExcursion(id) {
  ex.data = await http.get(`/storage-env/excursions/${id}`)
  ex.visible = true
}
</script>

<style scoped>
.filters { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.trend-card { margin-bottom: 12px; }
.trend-note { margin: 0 0 8px; }
.charts { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 16px; }
.form-row { display: flex; flex-wrap: wrap; gap: 0 12px; }
.form-row > * { flex: 1 1 160px; }
.pair { display: flex; align-items: center; gap: 6px; width: 100%; }
.pair :deep(.el-input-number) { flex: 1; min-width: 0; }
.hint { margin: -6px 0 12px; }
.summary-line { margin: 0 0 8px; line-height: 1.8; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.bad { color: var(--el-color-danger); }
.cap { margin: 8px 16px 0; }
</style>
