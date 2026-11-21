<template>
  <div class="print-page">
    <div class="bar no-print">
      <b>系统上线前检查签字确认表（SOP-09）</b>
      <el-button type="primary" :icon="Printer" :disabled="!data" @click="print">打印 / 另存 PDF</el-button>
      <el-button @click="$router.back()">返回</el-button>
    </div>
    <p v-if="error" class="no-print err">{{ error }}</p>
    <article v-if="data" class="doc">
      <h1>化妆品批次主线系统 · 上线前检查签字确认表（SOP-09）</h1>
      <table class="meta">
        <tr>
          <th>检查时间</th><td class="mono">{{ printedAt }}</td>
          <th>检查结论</th>
          <td>
            <b v-if="data.blocking">未就绪（{{ data.blocking }} 项必须处理，{{ data.warnings }} 项建议处理）</b>
            <b v-else-if="data.warnings">条件就绪（必须项已通过，{{ data.warnings }} 项建议处理）</b>
            <b v-else>全部通过，可按 SOP-09 开展人工复核与正式上线</b>
          </td>
        </tr>
        <tr>
          <th>总检查项</th><td class="mono">{{ data.checks.length }} 项</td>
          <th>通过 / 建议 / 阻塞</th><td class="mono">{{ okCount }} / {{ data.warnings }} / {{ data.blocking }}</td>
        </tr>
      </table>

      <h2>一、系统自动检查明细</h2>
      <table>
        <thead>
          <tr>
            <th style="width:12%">分组</th>
            <th style="width:20%">检查项</th>
            <th style="width:12%">状态</th>
            <th>检查结果与说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in data.checks" :key="`${c.group}-${c.title}`">
            <td>{{ c.group }}</td>
            <td><b>{{ c.title }}</b></td>
            <td>
              <span :class="['st', c.state]">{{ c.state === 'BLOCK' ? '必须处理' : c.state === 'WARN' ? '建议处理' : '通过' }}</span>
            </td>
            <td>{{ c.detail }}</td>
          </tr>
        </tbody>
      </table>

      <h2>二、上线前人工复核项（线下核对后勾选）</h2>
      <table>
        <thead><tr><th style="width:6%">核对</th><th style="width:24%">复核事项</th><th>核对要点</th></tr></thead>
        <tbody>
          <tr><td class="c">☐</td><td>期初实物盘点一致</td><td>各仓在库批次数量、批号与效期与财务盘点表核对一致</td></tr>
          <tr><td class="c">☐</td><td>生产与备案资料归档</td><td>全量成品备案/注册证、安全评估、标签样稿与稳定性依据已完成线下归档</td></tr>
          <tr><td class="c">☐</td><td>正式环境隔离与备份</td><td>示例数据已关闭，数据库每日自动备份与恢复演练已确认</td></tr>
        </tbody>
      </table>

      <table class="sign">
        <tr>
          <td><b>质量安全负责人（QSO）审批：</b><div class="sig-space" /></td>
          <td><b>质量保证部（QA）复核：</b><div class="sig-space" /></td>
          <td><b>系统管理员确认：</b><div class="sig-space" /></td>
        </tr>
      </table>
      <p class="foot">打印时间 {{ printedAt }}。本签字确认表由系统实时核查生成，请签字后随 SOP-09 上线评审记录归档保存。</p>
    </article>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Printer } from '@element-plus/icons-vue'
import http from '../api'

const data = ref(null)
const error = ref('')
const printedAt = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16)
const okCount = computed(() => (data.value?.checks || []).filter((c) => c.state === 'OK').length)

const pageStyle = document.createElement('style')
pageStyle.textContent = '@page { size: A4 portrait; margin: 12mm; }'
document.head.appendChild(pageStyle)
onUnmounted(() => pageStyle.remove())
const print = () => window.print()

onMounted(async () => {
  try {
    data.value = await http.get('/golive', { silent: true })
  } catch (e) {
    error.value = e.message
  }
})
</script>

<style scoped>
.print-page { min-height: 100%; padding: 16px; box-sizing: border-box; }
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 14px; }
.err { color: var(--el-color-danger); }
.doc { background: #fff; color: #111; padding: 20px; max-width: 900px; font-size: 12px; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; border-radius: 6px; }
h1 { font-size: 17px; margin: 0 0 14px; text-align: center; }
h2 { font-size: 13px; margin: 14px 0 6px; }
table { width: 100%; border-collapse: collapse; }
th, td { border: 1px solid #999; padding: 5px 7px; text-align: left; vertical-align: top; }
th { background: #f3f3f3; font-weight: 600; }
.meta th { width: 14%; }
.c { text-align: center; }
.st { font-weight: 600; }
.st.BLOCK { color: #c00; }
.st.WARN { color: #b26a00; }
.st.OK { color: #1a7f37; }
.sign { margin-top: 18px; }
.sign td { width: 33.3%; padding: 10px 12px; }
.sig-space { min-height: 44px; }
.foot { margin: 12px 0 0; color: #555; font-size: 11px; }
@media print {
  .no-print { display: none !important; }
  .print-page { padding: 0; }
  .doc { padding: 0; max-width: none; border-radius: 0; }
  tr { break-inside: avoid; }
}
</style>

<style>
@media print {
  body::before { display: none !important; }
  body { background: #fff !important; }
}
</style>
