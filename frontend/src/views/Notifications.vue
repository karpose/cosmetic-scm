<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>消息提醒</h2>
        <p>每天把总览里警告和紧急的待办汇总，推送到邮件和企业微信群。渠道在服务器的环境变量里配置（见部署说明），这里只显示状态，不显示密钥。</p>
      </div>
      <el-button type="primary" :loading="sending" :disabled="!channelReady" @click="send">立即发送一次</el-button>
    </div>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
    <template v-else-if="s">
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
        <el-table :data="s.preview.items" empty-text="今天没有需要提醒的事项，定时任务不会发送">
          <el-table-column label="事项" width="190"><template #default="{ row }"><el-tag :type="row.level === 'bad' ? 'danger' : 'warning'" size="small" disable-transitions>{{ row.label }}</el-tag></template></el-table-column>
          <el-table-column prop="title" label="对象" min-width="200" />
          <el-table-column label="编号" width="170"><template #default="{ row }"><span class="mono small">{{ row.ref }}</span></template></el-table-column>
          <el-table-column prop="detail" label="说明" min-width="240" />
        </el-table>
      </el-card>

      <el-card shadow="never" body-style="padding:0 0 8px">
        <template #header><b>发送记录</b> <span class="muted small">最近 50 次</span></template>
        <el-table :data="s.logs" empty-text="还没有发送过">
          <el-table-column label="时间" width="170"><template #default="{ row }"><span class="mono small">{{ fmtTime(row.sentAt) }}</span></template></el-table-column>
          <el-table-column label="渠道" width="110"><template #default="{ row }">{{ row.channel === 'MAIL' ? '邮件' : '企业微信' }}</template></el-table-column>
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
import LoadError from '../components/LoadError.vue'
import { fmtTime } from '../utils/format'
import { useLoader } from '../utils/loader'

const s = ref(null)
const sending = ref(false)
const channelReady = computed(() => s.value && (s.value.mailConfigured || s.value.wecomConfigured))
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
