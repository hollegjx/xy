<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  Skeleton as ASkeleton,
} from 'ant-design-vue'
import { ApiError, errorMessage } from '@/request'
import { getAppPreviewUrl, hasAppPreview } from '@/service/api/app'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const props = defineProps<{ appId: string; version: string | null }>()
const loginUserStore = useLoginUserStore()
const loading = ref(false)
const error = ref('')
const available = ref(false)
const frameKey = ref(0)
let revision = 0

async function load(): Promise<void> {
  const currentRevision = ++revision
  loading.value = true
  error.value = ''
  try {
    // 先确认 HTML 可用，再让 iframe 直接加载带服务端 CSP 的地址。
    const result = await hasAppPreview(props.appId)
    if (currentRevision !== revision) return
    available.value = result
    if (result) frameKey.value += 1
  } catch (cause) {
    if (currentRevision !== revision) return
    if (cause instanceof ApiError && cause.code === 40100) loginUserStore.setUser(null)
    else {
      if (cause instanceof ApiError && cause.code === 40300) available.value = false
      error.value = errorMessage(cause)
    }
  } finally {
    if (currentRevision === revision) loading.value = false
  }
}

watch(() => [props.appId, props.version], load, { immediate: true })
onBeforeUnmount(() => {
  revision += 1
})
</script>

<template>
  <div class="preview-content" role="region" aria-label="网站预览">
    <div class="preview-heading">
      <h2>网站预览</h2>
      <AButton :loading="loading" :disabled="loading" @click="load">{{
        error ? '重试' : '刷新预览'
      }}</AButton>
    </div>
    <AAlert
      v-if="error"
      type="error"
      message="预览读取失败"
      :description="error"
      show-icon
      role="alert"
    />
    <ASkeleton
      v-if="loading && !available"
      active
      :paragraph="{ rows: 6 }"
      aria-label="正在读取预览"
    />
    <iframe
      v-if="available"
      :key="frameKey"
      :src="getAppPreviewUrl(appId)"
      title="应用网站预览"
      sandbox="allow-scripts"
      referrerpolicy="no-referrer"
    />
    <div v-else-if="!loading && !error" class="preview-empty">
      <AEmpty description="还没有可预览的网站"><p>生成成功后，网站将在这里展示。</p></AEmpty>
    </div>
  </div>
</template>

<style scoped>
.preview-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  flex: 1;
}
.preview-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
iframe {
  width: 100%;
  height: 640px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
}
.preview-empty {
  display: grid;
  flex: 1;
  min-height: 320px;
  padding: 32px 0;
  place-items: center;
  color: var(--muted);
}
p {
  margin: 0;
  font-size: 12px;
}
@media (max-width: 760px) {
  iframe {
    height: 560px;
  }
}
</style>
