<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>投诉与召回</h2>
        <p>投诉登记后由质量人员调查；不良反应按严重程度在 7 日 / 30 日内完成监测上报后才能关闭。召回由质量安全负责人发起：同时冻结批次，并按出库记录列出每个收货客户，逐个通知、登记回收，全部通知到位后才能关闭。</p>
      </div>
      <div class="toolbar">
        <ExportButton v-if="tab === 'complaints'" report="complaints" />
        <el-button v-if="tab === 'complaints' && canLog" type="primary" @click="openComplaint">登记投诉</el-button>
        <el-button v-if="tab === 'recalls' && isQso" type="danger" @click="openRecall()">发起召回</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" class="seg">
      <!-- ================= 投诉 ================= -->
      <el-tab-pane :label="`投诉（${openComplaintCount} 件处理中）`" name="complaints">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header>
            <div class="filters">
              <el-radio-group v-model="cf.status" size="small">
                <el-radio-button value="OPEN">处理中</el-radio-button>
                <el-radio-button value="CLOSED">已关闭</el-radio-button>
                <el-radio-button value="">全部</el-radio-button>
              </el-radio-group>
              <el-select aria-label="按类别筛选" v-model="cf.category" placeholder="全部类别" clearable size="default">
                <el-option v-for="(v, k) in COMPLAINT_CATEGORY" :key="k" :value="k" :label="v" />
              </el-select>
              <el-input v-model="ct.keyword.value" placeholder="搜索编号、产品、批号、内容" clearable />
            </div>
          </template>
          <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
          <el-table v-else :data="ct.paged.value" v-loading="loader.loading.value" row-key="id" empty-text="没有符合条件的投诉">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="detail">
                  <div><span class="muted">投诉内容：</span>{{ row.description }}</div>
                  <div v-if="row.consumer"><span class="muted">投诉人：</span>{{ row.consumer }}</div>
                  <div v-if="row.reportNo"><span class="muted">监测上报：</span>{{ row.reportNo }}（{{ row.reportedDate }}）</div>
                  <div v-if="row.conclusion"><span class="muted">调查结论：</span>{{ row.conclusion }}</div>
                  <div v-if="row.capa"><span class="muted">纠正预防措施：</span>{{ row.capa }}</div>
                  <div v-if="row.closedBy" class="muted small">{{ row.closedBy }} 于 {{ fmtTime(row.closedAt) }} 关闭</div>
                  <AttachmentList owner-type="COMPLAINT" :owner-id="row.id" :can-upload="auth.canAttach()" title="投诉附件（照片、聊天记录、留样复检报告）" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="编号" width="140">
              <template #default="{ row }"><span class="mono">{{ row.complaintNo }}</span><div class="muted small mono">{{ row.receivedDate }}</div></template>
            </el-table-column>
            <el-table-column label="产品 / 批号" min-width="180">
              <template #default="{ row }">{{ row.itemName }}<div><BatchNo :no="row.batchNo" /></div></template>
            </el-table-column>
            <el-table-column label="类别" width="150">
              <template #default="{ row }">
                <el-tag :type="row.category === 'ADVERSE' ? 'danger' : 'info'" size="small" disable-transitions>{{ row.categoryLabel }}</el-tag>
                <el-tag v-if="row.severity === 'SERIOUS'" type="danger" effect="dark" size="small" disable-transitions style="margin-left:4px">严重</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源" min-width="140">
              <template #default="{ row }">{{ row.channel }}<div class="muted small">{{ row.customerName || '—' }}</div></template>
            </el-table-column>
            <el-table-column prop="description" label="内容" min-width="180" show-overflow-tooltip />
            <el-table-column label="监测上报" width="140">
              <template #default="{ row }">
                <span v-if="row.reportState === 'NONE'" class="muted">—</span>
                <el-tag v-else-if="row.reportState === 'DONE'" type="success" size="small" disable-transitions>已上报</el-tag>
                <template v-else>
                  <el-tag :type="row.reportState === 'OVERDUE' ? 'danger' : 'warning'" size="small" disable-transitions>
                    {{ row.reportState === 'OVERDUE' ? `超期 ${-row.reportDaysLeft} 天` : `剩 ${row.reportDaysLeft} 天` }}
                  </el-tag>
                  <div class="muted small mono">截止 {{ row.reportDeadline }}</div>
                </template>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <StatusTag :status="row.status" :label="row.statusLabel" />
                <div v-if="row.recallNo"><el-link type="danger" class="small" @click="showRecall(row.recallId)">召回 {{ row.recallNo }}</el-link></div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'OPEN'">
                  <el-button v-if="isQa && row.category === 'ADVERSE' && row.reportState !== 'DONE'" size="small" @click="openReport(row)">登记上报</el-button>
                  <el-button v-if="isQso && !row.recallId && row.batchNo" size="small" type="danger" plain @click="openRecall(row)">召回</el-button>
                  <el-button v-if="isQa" size="small" type="primary" plain @click="openClose(row)">关闭</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <ListCapHint :count="complaints.length" />
          <ListPager v-model:page="ct.page.value" v-model:size="ct.size.value" :total="ct.total.value" />
        </el-card>
      </el-tab-pane>

      <!-- ================= 召回 ================= -->
      <el-tab-pane :label="`召回（${openRecallCount} 个进行中）`" name="recalls">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header>
            <div class="filters">
              <el-radio-group v-model="rf.status" size="small">
                <el-radio-button value="">全部</el-radio-button>
                <el-radio-button value="OPEN">召回中</el-radio-button>
                <el-radio-button value="CLOSED">已关闭</el-radio-button>
              </el-radio-group>
              <el-input v-model="rt.keyword.value" placeholder="搜索召回单、产品、批号" clearable />
            </div>
          </template>
          <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
          <el-table v-else :data="rt.paged.value" v-loading="loader.loading.value" empty-text="还没有召回">
            <el-table-column label="召回单" width="150">
              <template #default="{ row }"><span class="mono">{{ row.recallNo }}</span><div class="muted small mono">{{ fmtTime(row.openedAt) }}</div></template>
            </el-table-column>
            <el-table-column label="产品 / 批号" min-width="180">
              <template #default="{ row }">{{ row.itemName }}<div><BatchNo :no="row.batchNo" /> <span class="muted small">{{ row.batchStatusLabel }}</span></div></template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
            <el-table-column label="进度" min-width="220">
              <template #default="{ row }">
                <div class="prog"><span class="muted small">通知</span><el-progress aria-label="已通知客户比例" :percentage="pctOf(row.notifiedCount, row.notices.length)" :stroke-width="8" :show-text="false" /><span class="mono small">{{ row.notifiedCount }}/{{ row.notices.length }}</span></div>
                <div class="prog"><span class="muted small">回收</span><el-progress aria-label="已回收数量比例" :percentage="pctOf(row.recoveredQty, outstanding(row))" :stroke-width="8" :show-text="false" status="success" /><span class="mono small">{{ fmtQty(row.recoveredQty) }}/{{ fmtQty(outstanding(row)) }}</span></div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'OPEN' ? 'danger' : 'info'" size="small" disable-transitions>{{ row.statusLabel }}</el-tag></template></el-table-column>
            <el-table-column label="" width="100" fixed="right"><template #default="{ row }"><el-button size="small" @click="showRecall(row.id)">详情</el-button></template></el-table-column>
          </el-table>
          <ListPager v-model:page="rt.page.value" v-model:size="rt.size.value" :total="rt.total.value" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 登记投诉 -->
    <el-dialog v-model="cd.visible" title="登记投诉" width="680px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="受理日期"><el-date-picker v-model="cd.receivedDate" type="date" value-format="YYYY-MM-DD" :disabled-date="(d) => d > new Date()" style="width:100%" /></el-form-item>
          <el-form-item label="来源渠道">
            <el-select v-model="cd.channel" filterable allow-create style="width:100%">
              <el-option v-for="ch in COMPLAINT_CHANNELS" :key="ch" :value="ch" :label="ch" />
            </el-select>
          </el-form-item>
          <el-form-item label="客户渠道（可选）">
            <el-select v-model="cd.customerId" clearable filterable style="width:100%">
              <el-option v-for="cu in rd.customers.value" :key="cu.id" :value="cu.id" :label="cu.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="投诉人及联系方式"><el-input v-model="cd.consumer" maxlength="100" placeholder="如：王女士 138****0000" /></el-form-item>
          <el-form-item label="产品">
            <el-select v-model="cd.itemId" filterable style="width:100%" @change="cd.batchNo = ''">
              <el-option v-for="f in rd.finished.value" :key="f.id" :value="f.id" :label="`${f.code} ${f.name}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="批号（瓶身或包装上的批号）">
            <el-select v-model="cd.batchNo" filterable allow-create clearable :disabled="!cd.itemId" placeholder="选择或输入" style="width:100%">
              <el-option v-for="b in batchesOfItem" :key="b.id" :value="b.batchNo" :label="`${b.batchNo}（${b.statusLabel}）`" />
            </el-select>
          </el-form-item>
          <el-form-item label="类别">
            <el-select v-model="cd.category" style="width:100%">
              <el-option v-for="(v, k) in COMPLAINT_CATEGORY" :key="k" :value="k" :label="v" />
            </el-select>
          </el-form-item>
          <el-form-item label="严重程度">
            <el-radio-group v-model="cd.severity"><el-radio v-for="(v, k) in SEVERITY" :key="k" :value="k">{{ v }}</el-radio></el-radio-group>
          </el-form-item>
        </div>
        <el-alert v-if="cd.category === 'ADVERSE'" type="warning" :closable="false" show-icon style="margin-bottom:12px"
                  :title="`不良反应须在受理后 ${cd.severity === 'SERIOUS' ? 7 : 30} 日内完成监测上报。`" />
        <el-form-item label="投诉内容"><el-input v-model="cd.description" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="症状、使用情况、包装问题等" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cd.visible = false">取消</el-button>
        <el-button type="primary" :loading="cd.saving" @click="submitComplaint">登记投诉</el-button>
      </template>
    </el-dialog>

    <!-- 登记上报 -->
    <el-dialog v-model="rp.visible" title="登记不良反应监测上报" width="440px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="监测报告编号"><el-input v-model="rp.reportNo" maxlength="60" /></el-form-item>
        <el-form-item label="上报日期"><el-date-picker v-model="rp.reportedDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rp.visible = false">取消</el-button>
        <el-button type="primary" :loading="rp.saving" @click="submitReport">登记上报</el-button>
      </template>
    </el-dialog>

    <!-- 关闭投诉 -->
    <el-dialog v-model="cl.visible" title="关闭投诉" width="560px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="调查结论"><el-input v-model="cl.conclusion" type="textarea" :rows="3" maxlength="1000" placeholder="原因分析：是否与产品质量有关、留样复检结果等" /></el-form-item>
        <el-form-item label="纠正预防措施（CAPA，可选）"><el-input v-model="cl.capa" type="textarea" :rows="2" maxlength="1000" /></el-form-item>
        <el-form-item label="签批密码（本人登录密码）"><el-input v-model="cl.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cl.visible = false">取消</el-button>
        <el-button type="primary" :loading="cl.saving" @click="submitClose">签批关闭</el-button>
      </template>
    </el-dialog>

    <!-- 发起召回 -->
    <el-dialog v-model="rc.visible" title="发起召回" width="600px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="成品批号">
          <el-input v-model="rc.batchNo" placeholder="如 A3-261103-01" :disabled="!!rc.complaint" @change="previewRecall" />
        </el-form-item>
        <div v-if="rc.complaint" class="muted small" style="margin:-8px 0 12px">来自投诉 {{ rc.complaint.complaintNo }}：{{ rc.complaint.description }}</div>
        <div v-if="rc.preview" class="impact glass">
          <b>{{ rc.preview.batch.itemName }} {{ rc.preview.batch.batchNo }}</b>
          <span class="muted small">（{{ rc.preview.batch.statusLabel }}，在库 {{ fmtQty(rc.preview.batch.onHand) }}）</span>
          <div v-if="impact.customers.length">将冻结该批次，并通知 <b>{{ impact.customers.length }}</b> 个客户，共出库 <b class="mono">{{ fmtQty(impact.total) }}</b>：</div>
          <div v-else-if="rc.preview.source.type === '期初导入'">这是期初导入的批次，系统里没有发货记录。上线前如果已发货，可以发起召回；通知名单请到旧系统或台账核对。</div>
          <div v-else class="muted">该批次还没有出库记录，在库冻结即可，无需召回。</div>
          <div class="muted small">{{ impact.customers.join('、') }}</div>
        </div>
        <el-form-item label="召回原因"><el-input v-model="rc.reason" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="签批密码（本人登录密码）"><el-input v-model="rc.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rc.visible = false">取消</el-button>
        <el-button type="danger" :loading="rc.saving" :disabled="!impact.customers.length && rc.preview?.source.type !== '期初导入'" @click="submitRecall">签批发起召回</el-button>
      </template>
    </el-dialog>

    <!-- 召回详情 -->
    <el-drawer v-model="dr.visible" :title="dr.recall ? `召回 ${dr.recall.recallNo}` : ''" size="min(720px, 100%)">
      <template v-if="dr.recall">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="产品">{{ dr.recall.itemName }}</el-descriptions-item>
          <el-descriptions-item label="批号"><BatchNo :no="dr.recall.batchNo" />（{{ dr.recall.batchStatusLabel }}）</el-descriptions-item>
          <el-descriptions-item label="发起">{{ dr.recall.openedByName }} · {{ fmtTime(dr.recall.openedAt) }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="dr.recall.status === 'OPEN' ? 'danger' : 'info'" size="small" disable-transitions>{{ dr.recall.statusLabel }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="原因" :span="2">{{ dr.recall.reason }}</el-descriptions-item>
          <el-descriptions-item v-if="dr.recall.complaintNo" label="来源投诉" :span="2">{{ dr.recall.complaintNo }}</el-descriptions-item>
          <el-descriptions-item v-if="dr.recall.conclusion" label="召回总结" :span="2">{{ dr.recall.conclusion }}（{{ dr.recall.closedByName }} · {{ fmtTime(dr.recall.closedAt) }}）</el-descriptions-item>
        </el-descriptions>

        <el-alert v-if="dr.recall.openingBatch" type="warning" show-icon :closable="false" style="margin-bottom:12px"
                  title="这是期初导入的批次：上线前发给哪些客户不在系统里，下面的名单可能不全。请到旧系统或台账核对，线下通知后写进召回总结。" />
        <h4 class="sec">收货客户（已回收 {{ fmtQty(dr.recall.recoveredQty) }} / 在外 {{ fmtQty(outstanding(dr.recall)) }}<template v-if="Number(dr.recall.returnedQty)">，另有 {{ fmtQty(dr.recall.returnedQty) }} 按普通退货退回</template>）</h4>
        <el-table :data="dr.recall.notices" size="small">
          <el-table-column label="客户" min-width="150"><template #default="{ row }">{{ row.customerName }}<div class="muted small">{{ row.channel }} · {{ row.contact || '—' }}</div></template></el-table-column>
          <el-table-column label="已回收 / 在外" width="120" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.recoveredQty) }} / {{ fmtQty(outstanding(row)) }}</span></template></el-table-column>
          <el-table-column label="通知" min-width="150">
            <template #default="{ row }">
              <template v-if="row.notifiedAt"><el-tag type="success" size="small" disable-transitions>已通知</el-tag><div class="muted small">{{ row.notifiedBy }} · {{ fmtTime(row.notifiedAt) }}</div><div v-if="row.note" class="muted small">{{ row.note }}</div></template>
              <el-tag v-else type="warning" size="small" disable-transitions>未通知</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="dr.recall.status === 'OPEN'" label="" width="170">
            <template #default="{ row }">
              <el-button v-if="!row.notifiedAt && canNotify" size="small" @click="notify(row)">标记已通知</el-button>
              <el-button v-if="canReturn && Number(row.recoveredQty) < outstanding(row)" size="small" type="primary" plain @click="goRecover(row)">登记回收</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4 class="sec">附件</h4>
        <AttachmentList owner-type="RECALL" :owner-id="dr.recall.id" :can-upload="auth.canAttach()" title="召回附件（通知函、回收与销毁记录）" />

        <template v-if="dr.recall.status === 'OPEN' && isQso">
          <h4 class="sec">关闭召回</h4>
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="召回总结"><el-input v-model="dr.conclusion" type="textarea" :rows="3" maxlength="1000" placeholder="回收情况、未回收部分的处理、批次最终处置" /></el-form-item>
            <el-form-item label="签批密码（本人登录密码）"><el-input v-model="dr.password" type="password" show-password autocomplete="current-password" /></el-form-item>
            <el-button type="primary" :loading="dr.saving" @click="closeRecall">签批关闭召回</el-button>
          </el-form>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import AttachmentList from '../components/AttachmentList.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import { useAuth } from '../stores/auth'
import { COMPLAINT_CATEGORY, COMPLAINT_CHANNELS, SEVERITY, fmtQty, fmtTime, today } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const rd = useRefData()
const tab = ref(route.query.tab === 'recalls' ? 'recalls' : 'complaints')
const complaints = ref([])
const recalls = ref([])
const fgBatches = ref([])

const isQa = computed(() => auth.hasAny('QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const canLog = computed(() => auth.hasAny('ADMIN', 'SALES', 'QA', 'QSO'))
const canNotify = computed(() => auth.hasAny('QA', 'QSO', 'SALES'))
const canReturn = computed(() => auth.hasAny('ADMIN', 'SALES', 'WAREHOUSE'))

const loader = useLoader(async () => {
  const [cs, rs] = await Promise.all([http.get('/complaints'), http.get('/recalls')])
  complaints.value = cs
  recalls.value = rs
  if (dr.recall) dr.recall = rs.find((r) => r.id === dr.recall.id) || null
})
const load = () => loader.run()

const cf = reactive({ status: 'OPEN', category: '' })
const ct = usePaged(complaints, {
  match: textMatch('complaintNo', 'itemName', 'batchNo', 'description', 'channel', 'customerName'),
  extra: (r) => (!cf.status || r.status === cf.status) && (!cf.category || r.category === cf.category)
})
const rf = reactive({ status: '' })
const rt = usePaged(recalls, {
  match: textMatch('recallNo', 'itemName', 'batchNo', 'reason'),
  extra: (r) => !rf.status || r.status === rf.status
})
const openComplaintCount = computed(() => complaints.value.filter((c) => c.status === 'OPEN').length)
const openRecallCount = computed(() => recalls.value.filter((r) => r.status === 'OPEN').length)
// 还在外面的量：发出减去不是这次召回收回的退货。否则退货转可售再发出的，回收率永远到不了 100%
const outstanding = (r) => Number(r.shippedQty) - Number(r.returnedQty || 0)
const pctOf = (a, b) => (Number(b) > 0 ? Math.min(100, Math.round((Number(a) / Number(b)) * 100)) : 0)

// ---------- 投诉 ----------
const cd = reactive({ visible: false, saving: false })
const batchesOfItem = computed(() => fgBatches.value.filter((b) => b.itemId === cd.itemId))

async function openComplaint() {
  Object.assign(cd, {
    visible: true, saving: false, receivedDate: today(), channel: '电商平台', customerId: null, consumer: '',
    itemId: null, batchNo: '', category: 'QUALITY', severity: 'NORMAL', description: ''
  })
  if (!fgBatches.value.length) fgBatches.value = (await http.get('/batches')).filter((b) => b.itemType === 'FG')
}

async function submitComplaint() {
  cd.saving = true
  try {
    const { receivedDate, channel, customerId, consumer, itemId, batchNo, category, severity, description } = cd
    const c = await http.post('/complaints', { receivedDate, channel, customerId, consumer, itemId, batchNo, category, severity, description })
    ElMessage.success(`投诉 ${c.complaintNo} 已登记`)
    cd.visible = false
    cf.status = 'OPEN'
    load()
  } finally {
    cd.saving = false
  }
}

const rp = reactive({ visible: false, saving: false, row: null, reportNo: '', reportedDate: '' })
function openReport(row) {
  Object.assign(rp, { visible: true, saving: false, row, reportNo: '', reportedDate: today() })
}
async function submitReport() {
  rp.saving = true
  try {
    await http.post(`/complaints/${rp.row.id}/report`, { reportNo: rp.reportNo, reportedDate: rp.reportedDate })
    ElMessage.success('已登记监测上报')
    rp.visible = false
    load()
  } finally {
    rp.saving = false
  }
}

const cl = reactive({ visible: false, saving: false, row: null, conclusion: '', capa: '', password: '' })
function openClose(row) {
  Object.assign(cl, { visible: true, saving: false, row, conclusion: '', capa: '', password: '' })
}
async function submitClose() {
  cl.saving = true
  try {
    await http.post(`/complaints/${cl.row.id}/close`, { conclusion: cl.conclusion, capa: cl.capa, password: cl.password })
    ElMessage.success('投诉已关闭')
    cl.visible = false
    load()
  } finally {
    cl.saving = false
  }
}

// ---------- 召回 ----------
const rc = reactive({ visible: false, saving: false, complaint: null, batchNo: '', reason: '', password: '', preview: null })
const impact = computed(() => {
  const byCustomer = {}
  for (const s of rc.preview?.shipments || []) byCustomer[s.customerName] = (byCustomer[s.customerName] || 0) + Number(s.qty)
  return { customers: Object.keys(byCustomer), total: Object.values(byCustomer).reduce((a, b) => a + b, 0) }
})

function openRecall(complaint = null) {
  Object.assign(rc, {
    visible: true, saving: false, complaint, batchNo: complaint?.batchNo || '', password: '', preview: null,
    reason: complaint ? `${complaint.categoryLabel}投诉 ${complaint.complaintNo}：${complaint.description}` : ''
  })
  previewRecall()
}

const previewReq = latestOnly()

async function previewRecall() {
  const current = previewReq()
  rc.preview = null
  if (!rc.batchNo.trim()) return
  try {
    const tr = await http.get(`/trace/${encodeURIComponent(rc.batchNo.trim())}`, { silent: true })
    // 预览决定签批人看到的客户名单，不能是上一个批号的
    if (!current()) return
    if (tr.batch.itemType !== 'FG') return ElMessage.warning('召回针对成品批次；物料问题请在批次追溯里冻结受影响批次')
    rc.preview = tr
  } catch (e) {
    if (current()) ElMessage.warning(e.message)
  }
}

async function submitRecall() {
  rc.saving = true
  try {
    const r = await http.post('/recalls', { batchNo: rc.batchNo.trim(), complaintId: rc.complaint?.id || null, reason: rc.reason, password: rc.password })
    ElMessage.success(`召回 ${r.recallNo} 已发起，批次已冻结`)
    rc.visible = false
    tab.value = 'recalls'
    await load()
    showRecall(r.id)
  } finally {
    rc.saving = false
  }
}

const dr = reactive({ visible: false, saving: false, recall: null, conclusion: '', password: '' })
function showRecall(id) {
  const r = recalls.value.find((x) => x.id === id)
  if (!r) return
  Object.assign(dr, { visible: true, saving: false, recall: r, conclusion: '', password: '' })
}

async function notify(row) {
  let note
  try {
    const res = await ElMessageBox.prompt(`确认已通知 ${row.customerName} 停止销售并退回批次 ${dr.recall.batchNo}？`, '标记已通知', {
      inputPlaceholder: '通知方式和对接人（可选），如：电话通知采购张经理', confirmButtonText: '标记已通知', cancelButtonText: '取消'
    })
    note = res.value
  } catch {
    return
  }
  await http.post(`/recalls/notices/${row.id}/notify`, { note })
  ElMessage.success('已记录通知')
  load()
}

function goRecover(row) {
  router.push({ path: '/returns', query: { customerId: row.customerId, recallId: dr.recall.id } })
}

async function closeRecall() {
  dr.saving = true
  try {
    await http.post(`/recalls/${dr.recall.id}/close`, { conclusion: dr.conclusion, password: dr.password })
    ElMessage.success('召回已关闭')
    load()
  } finally {
    dr.saving = false
  }
}

onMounted(async () => {
  await Promise.all([load(), rd.load()])
  if (route.query.recall) showRecall(Number(route.query.recall))
})
</script>

<style scoped>
.detail { display: grid; gap: 6px; padding: 4px 56px 8px; line-height: 1.6; }
.prog { display: grid; grid-template-columns: 32px 1fr auto; gap: 8px; align-items: center; }
.impact { padding: 12px 16px; margin-bottom: 14px; display: grid; gap: 4px; border-radius: 14px; }
.sec { margin: 20px 0 10px; font-size: 14px; }
</style>
