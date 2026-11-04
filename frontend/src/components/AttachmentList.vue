<template>
  <div class="att">
    <div class="att-h">
      <span class="small muted">{{ title }}<template v-if="list.length">（{{ list.length }}）</template></span>
      <el-upload v-if="canUpload" :show-file-list="false" :http-request="upload" :accept="ACCEPT" :disabled="uploading">
        <el-button size="small" :loading="uploading" :icon="Upload">上传附件</el-button>
      </el-upload>
    </div>
    <div v-if="!list.length" class="muted small empty">{{ emptyText }}</div>
    <div v-for="a in list" :key="a.id" class="file">
      <el-icon class="ficon"><Document /></el-icon>
      <div class="fmeta">
        <a href="#" class="fname" @click.prevent="download(a)">{{ a.fileName }}</a>
        <div class="muted small">{{ sizeText(a.sizeBytes) }} · {{ a.uploadedBy }} · {{ fmtTime(a.uploadedAt) }}<template v-if="a.note"> · {{ a.note }}</template></div>
      </div>
      <el-tooltip :content="`SHA-256 ${a.sha256}（下载时会重新核对，文件被改动会拒绝下载）`" placement="top">
        <span class="mono small muted hash">{{ a.sha256.slice(0, 8) }}</span>
      </el-tooltip>
    </div>
    <p v-if="canUpload" class="muted small hint">PDF、图片、Excel、Word、CSV，单个不超过 20 MB。附件作为记录保存，上传后不能删除。</p>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Upload } from '@element-plus/icons-vue'
import http from '../api'
import { fmtTime } from '../utils/format'

// portal=true 时走代工厂门户接口（只能是本厂委托单）；否则走系统内接口
const props = defineProps({
  ownerType: { type: String, required: true },
  ownerId: { type: Number, required: true },
  canUpload: { type: Boolean, default: false },
  portal: { type: Boolean, default: false },
  title: { type: String, default: '附件' },
  emptyText: { type: String, default: '还没有附件' }
})
const ACCEPT = '.pdf,.jpg,.jpeg,.png,.webp,.xlsx,.docx,.csv,.txt'
const list = ref([])
const uploading = ref(false)

const listUrl = () => (props.portal ? `/portal/oem/orders/${props.ownerId}/attachments` : '/attachments')
const downloadUrl = (id) => (props.portal ? `/portal/oem/attachments/${id}/download` : `/attachments/${id}/download`)

async function load() {
  list.value = props.portal
    ? await http.get(listUrl())
    : await http.get(listUrl(), { params: { ownerType: props.ownerType, ownerId: props.ownerId } })
}

async function upload({ file }) {
  if (file.size > 20 * 1024 * 1024) return ElMessage.warning('文件超过 20 MB，请压缩后再传')
  const form = new FormData()
  form.append('file', file)
  if (!props.portal) {
    form.append('ownerType', props.ownerType)
    form.append('ownerId', props.ownerId)
  }
  uploading.value = true
  try {
    await http.post(listUrl(), form)
    ElMessage.success(`已上传 ${file.name}`)
    load()
  } finally {
    uploading.value = false
  }
}

async function download(a) {
  const blob = await http.get(downloadUrl(a.id), { responseType: 'blob' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = a.fileName
  link.click()
  URL.revokeObjectURL(url)
}

const sizeText = (n) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`)

watch(() => [props.ownerType, props.ownerId], load)
onMounted(load)
</script>

<style scoped>
.att { display: grid; gap: 6px; }
.att-h { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.empty { padding: 4px 0; }
.file { display: grid; grid-template-columns: auto 1fr auto; gap: 10px; align-items: center; padding: 8px 10px; border-radius: 12px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); }
.ficon { font-size: 18px; color: var(--el-color-primary); }
.fmeta { min-width: 0; }
.fname { color: var(--el-text-color-primary); text-decoration: none; font-size: 13px; overflow-wrap: anywhere; }
.fname:hover { color: var(--el-color-primary); }
.hash { cursor: help; }
.hint { margin: 2px 0 0; }
</style>
