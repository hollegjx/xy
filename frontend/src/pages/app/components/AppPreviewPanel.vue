<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  Skeleton as ASkeleton,
} from 'ant-design-vue'
import { ApiError, errorMessage } from '@/request'
import { getAppPreviewUrl, getPreviewUnavailableReason } from '@/service/api/app'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const props = defineProps<{ appId: string; version: string | null }>()
const emit = defineEmits<{ selection: [description: string] }>()
const picking = ref(false)
const frame = ref<globalThis.HTMLIFrameElement | null>(null)
function syncPicking(): void {
  // 沙箱来源不透明，消息只发给当前 iframe，内容仅是点选开关。
  frame.value?.contentWindow?.postMessage({ type: 'xy:set-picking', enabled: picking.value }, '*')
}
function setPicking(value: boolean): void {
  picking.value = value
  syncPicking()
  emit('selection', '')
}
function receiveSelection(event: globalThis.MessageEvent): void {
  if (
    !picking.value ||
    !frame.value ||
    event.source !== frame.value.contentWindow ||
    event.origin !== 'null'
  )
    return
  const data = event.data
  if (
    data?.type !== 'xy:element-picked' ||
    typeof data.tag !== 'string' ||
    typeof data.selector !== 'string' ||
    typeof data.text !== 'string' ||
    !data.tag ||
    !data.selector ||
    data.tag.length > 48 ||
    data.selector.length > 512 ||
    data.text.length > 100
  )
    return
  emit('selection', `${data.tag} · ${data.selector}${data.text ? ` · ${data.text}` : ''}`)
}
const loginUserStore = useLoginUserStore()
const loading = ref(false)
const error = ref('')
const available = ref(false)
const unavailableReason = ref('')
const frameKey = ref(0)
let revision = 0

async function load(): Promise<void> {
  const currentRevision = ++revision
  setPicking(false)
  loading.value = true
  error.value = ''
  try {
    // 先确认 HTML 可用，再让 iframe 直接加载带服务端 CSP 的地址。
    const reason = await getPreviewUnavailableReason(props.appId)
    if (currentRevision !== revision) return
    unavailableReason.value = reason ?? ''
    available.value = reason === null
    if (available.value) frameKey.value += 1
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
onMounted(() => globalThis.window.addEventListener('message', receiveSelection))
onBeforeUnmount(() => {
  globalThis.window.removeEventListener('message', receiveSelection)
  revision += 1
})
</script>

<template>
  <div class="preview-content" role="region" aria-label="网站预览">
    <div class="preview-heading">
      <h2>网站预览</h2>
      <AButton v-if="available && !loading" :aria-pressed="picking" @click="setPicking(!picking)">
        {{ picking ? '退出选择' : '选择元素' }}
      </AButton>
      <AButton :loading="loading" :disabled="loading" @click="load">{{
        error ? '重试' : '刷新预览'
      }}</AButton>
    </div>
    <p v-if="picking" role="status">点击预览中的元素，再在「本次需求」里描述要改什么。</p>
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
      ref="frame"
      :key="frameKey"
      :src="getAppPreviewUrl(appId, true)"
      title="应用网站预览"
      sandbox="allow-scripts"
      referrerpolicy="no-referrer"
      @load="syncPicking"
    />
    <div v-else-if="!loading && !error" class="preview-empty">
      <AEmpty description="还没有可预览的网站"
        ><p>{{ unavailableReason }}</p></AEmpty
      >
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
