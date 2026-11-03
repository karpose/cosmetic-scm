<template>
  <div class="verify">
    <div class="card glass">
      <div class="head">
        <span class="mark">批</span>
        <div>
          <h1>正品查询</h1>
          <p class="muted">输入包装上的防伪码，或直接扫描二维码</p>
        </div>
      </div>

      <form class="ask" @submit.prevent="lookup">
        <el-input v-model="code" size="large" placeholder="如 7K3M-Q9TX-2B8R" maxlength="20" clearable class="mono" />
        <el-button type="primary" size="large" native-type="submit" :loading="loading">查询</el-button>
      </form>

      <section v-if="result" class="result" :class="tone">
        <div class="verdict">
          <el-icon class="icon"><component :is="icon" /></el-icon>
          <div>
            <h2>{{ result.title }}</h2>
            <p>{{ result.message }}</p>
          </div>
        </div>
        <dl v-if="result.productName">
          <dt>产品</dt><dd>{{ result.productName }}<span v-if="result.netG" class="muted"> · {{ fmtQty(result.netG) }} g</span></dd>
          <dt>备案编号</dt><dd>{{ result.filingNo || '—' }}</dd>
          <dt>批号</dt><dd class="mono">{{ result.batchNo }}</dd>
          <dt>生产日期</dt><dd class="mono">{{ result.mfgDate || '—' }}</dd>
          <dt>有效期至</dt><dd class="mono">{{ result.expDate || '—' }}</dd>
          <dt>防伪码</dt><dd class="mono">{{ result.displayCode }}</dd>
          <dt>查询次数</dt><dd>{{ result.scanCount }} 次<span v-if="result.firstScanAt" class="muted">，首次 {{ fmtTime(result.firstScanAt) }}</span></dd>
        </dl>
      </section>

      <p class="foot muted small">本页只记录查询次数和时间，不收集您的个人信息。</p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CircleCheckFilled, CircleCloseFilled, QuestionFilled, WarningFilled } from '@element-plus/icons-vue'
import http from '../api'
import { fmtQty, fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()
const code = ref(route.params.code || '')
const result = ref(null)
const loading = ref(false)

// 颜色之外都配了图标和文字，色弱用户也能分辨
const TONES = {
  GENUINE: ['ok', CircleCheckFilled],
  REPEATED: ['warn', WarningFilled],
  EXPIRED: ['warn', WarningFilled],
  UNRELEASED: ['bad', CircleCloseFilled],
  RECALLED: ['bad', CircleCloseFilled],
  NOT_FOUND: ['bad', QuestionFilled]
}
const tone = computed(() => TONES[result.value?.status]?.[0] || 'bad')
const icon = computed(() => TONES[result.value?.status]?.[1] || QuestionFilled)

async function lookup() {
  if (!code.value.trim()) return
  loading.value = true
  try {
    result.value = await http.post('/public/verify', { code: code.value.trim() }, { public: true })
    if (route.params.code !== code.value.trim()) router.replace({ name: 'verify', params: { code: code.value.trim() } })
  } catch {
    result.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => { if (code.value) lookup() })
</script>

<style scoped>
.verify { min-height: 100%; display: grid; place-items: start center; padding: 40px 16px; box-sizing: border-box; }
.card { width: min(460px, 100%); padding: 26px 22px 18px; box-sizing: border-box; border-radius: 28px; background: var(--lg-surface-raised); -webkit-backdrop-filter: var(--lg-blur-strong); backdrop-filter: var(--lg-blur-strong); }
.head { display: flex; gap: 14px; align-items: center; margin-bottom: 20px; }
.mark { width: 44px; height: 44px; border-radius: 14px; flex: none; display: grid; place-items: center; color: #fff; font-weight: 700; font-size: 20px; background: linear-gradient(145deg, color-mix(in srgb, var(--el-color-primary) 70%, white), var(--el-color-primary)); box-shadow: inset 0 1px 0 rgba(255, 255, 255, .5), 0 10px 22px -8px var(--el-color-primary); }
h1 { font-size: 20px; margin: 0; }
.head p { margin: 4px 0 0; font-size: 13px; }
.ask { display: grid; grid-template-columns: 1fr auto; gap: 8px; }
.result { margin-top: 18px; padding: 16px; border-radius: 18px; border: 1px solid var(--lg-stroke); background: var(--lg-fill); }
.verdict { display: flex; gap: 12px; align-items: flex-start; }
.icon { font-size: 34px; flex: none; }
.ok .icon { color: var(--el-color-success); }
.warn .icon { color: var(--el-color-warning); }
.bad .icon { color: var(--el-color-danger); }
.ok { border-color: color-mix(in srgb, var(--el-color-success) 40%, transparent); }
.warn { border-color: color-mix(in srgb, var(--el-color-warning) 50%, transparent); }
.bad { border-color: color-mix(in srgb, var(--el-color-danger) 45%, transparent); }
h2 { margin: 2px 0 4px; font-size: 18px; }
.verdict p { margin: 0; font-size: 13.5px; line-height: 1.6; color: var(--el-text-color-regular); }
dl { display: grid; grid-template-columns: 76px 1fr; gap: 8px 10px; margin: 16px 0 0; font-size: 14px; }
dt { color: var(--el-text-color-secondary); }
dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
.foot { margin: 16px 0 0; text-align: center; }
</style>
