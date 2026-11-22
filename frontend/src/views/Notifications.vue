<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>消息提醒</h2>
        <p>每天把总览里警告和紧急的待办汇总推送出去：账号填了邮箱的只收自己岗位的事项，配置的收件人和企业微信群收全部。渠道在服务器的环境变量里配置（见部署说明），这里只显示状态，不显示密钥。</p>
      </div>
      <div class="toolbar">
        <ExportButton report="notification-logs" />
        <el-button type="primary" :loading="sending" :disabled="!channelReady" @click="send">立即发送一次</el-button>
      </div>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <template v-else-if="s">
      <div class="kpi-grid">
        <div class="kpi-card" :class="{ warn: s.preview.items.length > 0 }">
          <div class="kpi-label">今日待推送事项</div>
          <div class="kpi-val mono">{{ s.preview.items.length }}</div>
          <div class="kpi-sub">紧急 {{ badCount }} · 警告 {{ warnCount }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">可触达内部成员</div>
          <div class="kpi-val mono">{{ s.people.length }} <span class="kpi-unit">人</span></div>
          <div class="kpi-sub">已配置邮箱且有对应岗位待办</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">待提醒供应商</div>
          <div class="kpi-val mono">{{ s.suppliers.length }} <span class="kpi-unit">家</span></div>
          <div class="kpi-sub">仅定时任务自动发送给供应商</div>
        </div>
        <div class="kpi-card" :class="{ danger: failCount > 0 }">
          <div class="kpi-label">近 {{ s.logs.length }} 次发送成功率</div>
          <div class="kpi-val mono">{{ successRateText }}</div>
          <div class="kpi-sub">成功 {{ okCount }} · 失败 {{ failCount }}</div>
        </div>
      </div>

      <el-card shadow="never">
        <template #header><b>渠道状态</b></template>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="定时发送">
            <el-tag :type="s.enabled ? 'success' : 'info'" size="small" disable-transitions>{{ s.enabled ? '已开启' : '未开启' }}</el-tag>
            <span class="mono small"> {{ s.cron }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="邮件">
            <el-tag :type="s.mailConfigured ? 'success' : 'info'" size="small" disable-transitions>{{ s.mailConfigured ? '已配置' : '未配置' }}</el-tag>
            <span v-if="s.mailTo.length" class="small"> 收件人 {{ s.mailTo.join('、') }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="企业微信群机器人">
            <el-tag :type="s.wecomConfigured ? 'success' : 'info'" size="small" disable-transitions>{{ s.wecomConfigured ? '已配置' : '未配置' }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header><b>今天的内容</b> <span class="muted small">{{ s.preview.subject }}</span></template>
        <el-table scrollbar-tabindex="0" :data="s.preview.items" empty-text="今天没有需要提醒的事项，定时任务不会发送">
          <el-table-column label="事项" width="190"><template #default="{ row }"><el-tag :type="row.level === 'bad' ? 'danger' : 'warning'" size="small" disable-transitions>{{ row.label }}</el-tag></template></el-table-column>
          <el-table-column prop="title" label="对象" min-width="200" />
          <el-table-column label="编号" width="170"><template #default="{ row }"><span class="mono small">{{ row.ref }}</span></template></el-table-column>
          <el-table-column prop="detail" label="说明" min-width="240" />
        </el-table>
      </el-card>

      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header><b>今天会收到个人邮件的人</b> <span class="muted small">在“账号与权限”里给账号填邮箱</span></template>
        <el-table scrollbar-tabindex="0" :data="s.people" empty-text="没有。账号没填邮箱、没有相关事项，或邮件服务未配置">
          <el-table-column prop="displayName" label="姓名" min-width="160" />
          <el-table-column prop="email" label="邮箱" min-width="200" />
          <el-table-column label="岗位" min-width="180"><template #default="{ row }">{{ row.roles.join('、') }}</template></el-table-column>
          <el-table-column label="事项数" width="80" align="right"><template #default="{ row }"><span class="mono">{{ row.itemCount }}</span></template></el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header><b>供应商提醒</b> <span class="muted small">定时任务按时发给这些供应商账号，内容只有本单位的待确认采购单、3 天内的交期和资质到期。“立即发送一次”不会发给供应商</span></template>
        <el-table scrollbar-tabindex="0" :data="s.suppliers" empty-text="没有。供应商账号没填邮箱、没有相关事项，或邮件服务未配置">
          <el-table-column prop="displayName" label="账号" min-width="180" />
          <el-table-column prop="email" label="邮箱" min-width="220" />
          <el-table-column prop="itemCount" label="事项" width="90" align="right" />
        </el-table>
      </el-card>
      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header><b>发送记录</b> <span class="muted small">最近 50 次</span></template>
        <el-table scrollbar-tabindex="0" :data="s.logs" empty-text="还没有发送过">
          <el-table-column label="时间" width="170"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.sentAt) }}</span></template></el-table-column>
          <el-table-column label="渠道" width="110"><template #default="{ row }">{{ row.channel === 'MAIL' ? '邮件' : '企业微信' }}</template></el-table-column>
          <el-table-column label="收件人" min-width="170"><template #default="{ row }">{{ row.recipient || '—' }}</template></el-table-column>
          <el-table-column label="结果" width="90"><template #default="{ row }"><el-tag :type="row.success ? 'success' : 'danger'" size="small" disable-transitions>{{ row.success ? '成功' : '失败' }}</el-tag></template></el-table-column>
          <el-table-column label="事项数" width="80" align="right"><template #default="{ row }"><span class="mono">{{ row.itemCount }}</span></template></el-table-column>
          <el-table-column label="触发" width="110"><template #default="{ row }">{{ row.triggered === 'schedule' ? '定时' : row.triggered }}</template></el-table-column>
          <el-table-column label="失败原因" min-width="220"><template #default="{ row }">{{ row.error || '' }}</template></el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import ExportButton from '../components/ExportButton.vue'
import LoadError from '../components/LoadError.vue'
import { fmtTime } from '../utils/format'
import { useLoader } from '../utils/loader'

const s = ref(null)
const sending = ref(false)
const channelReady = computed(() => s.value && (s.value.mailConfigured || s.value.wecomConfigured))
const badCount = computed(() => (s.value?.preview?.items || []).filter((i) => i.level === 'bad').length)
const warnCount = computed(() => (s.value?.preview?.items || []).filter((i) => i.level !== 'bad').length)
const okCount = computed(() => (s.value?.logs || []).filter((l) => l.success).length)
const failCount = computed(() => (s.value?.logs || []).filter((l) => !l.success).length)
const successRateText = computed(() => {
  const total = s.value?.logs?.length || 0
  return total ? `${Math.round((okCount.value / total) * 100)}%` : '—'
})
const loader = useLoader(async () => {
  s.value = await http.get('/notify/status')
})
const load = () => loader.run()

async function send() {
  sending.value = true
  try {
    const r = await http.post('/notify/send')
    if (r.failed) ElMessage.warning(`成功 ${r.sent} 个渠道，失败 ${r.failed} 个，原因见发送记录`)
    else ElMessage.success(`已发送到 ${r.sent} 个渠道，共 ${r.items} 项`)
    load()
  } finally {
    sending.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
.kpi-card { background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 12px 14px; }
.kpi-card.danger { border-color: var(--el-color-danger-light-5); background: var(--el-color-danger-light-9); }
.kpi-card.warn { border-color: var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); }
.kpi-label { font-size: 12px; color: var(--el-text-color-secondary); }
.kpi-val { font-size: 22px; font-weight: 700; margin: 4px 0 2px; line-height: 1.2; }
.kpi-unit { font-size: 12px; font-weight: 400; color: var(--el-text-color-secondary); }
.kpi-sub { font-size: 12px; color: var(--el-text-color-secondary); }
@media (max-width: 900px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
