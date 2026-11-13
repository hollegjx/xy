<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  Skeleton as ASkeleton,
  Tag as ATag,
} from 'ant-design-vue'
import { ApiError, errorMessage } from '@/request'
import { getAppMessages } from '@/service/api/chat'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { ChatHistoryPageVO, GenerationStatus } from '@/types/api'

const props = defineProps<{ appId: string }>()
const loginUserStore = useLoginUserStore()
const history = ref<ChatHistoryPageVO | null>(null)
const loading = ref(false)
const error = ref('')
let revision = 0

const statusLabels: Record<GenerationStatus, string> = {
  running: '进行中',
  completed: '已完成',
  failed: '失败',
  cancelled: '已停止',
}
const statusColors: Record<GenerationStatus, string> = {
  running: 'processing',
  completed: 'success',
  failed: 'error',
  cancelled: 'default',
}

async function load(): Promise<void> {
  const currentRevision = ++revision
  loading.value = true
  error.value = ''
  history.value = null
  try {
    const result = await getAppMessages(props.appId)
    if (currentRevision === revision) history.value = result
  } catch (cause) {
    if (currentRevision !== revision) return
    if (cause instanceof ApiError && cause.code === 40100) {
      loginUserStore.setUser(null)
    } else {
      error.value = errorMessage(cause)
    }
  } finally {
    if (currentRevision === revision) loading.value = false
  }
}

watch(() => props.appId, load, { immediate: true })
onBeforeUnmount(() => {
  revision += 1
})
</script>

<template>
  <section class="history-panel" aria-label="生成记录" :aria-busy="loading">
    <div class="history-heading">
      <h2>生成记录</h2>
      <AButton v-if="!error" :loading="loading" :disabled="loading" @click="load">
        刷新状态
      </AButton>
    </div>
    <ASkeleton v-if="loading" active :paragraph="{ rows: 3 }" aria-label="正在读取生成记录" />
    <AAlert
      v-else-if="error"
      type="error"
      message="无法读取生成记录"
      :description="error"
      show-icon
      role="alert"
    >
      <template #action><AButton @click="load">重试</AButton></template>
    </AAlert>
    <template v-else-if="history">
      <AEmpty v-if="history.records.length === 0" description="还没有生成记录" />
      <template v-else>
        <p v-if="history.hasMore" class="history-hint">当前显示最近 10 条记录。</p>
        <ol class="message-list">
          <li v-for="item in history.records" :key="item.id" class="message-item">
            <div class="message-heading">
              <span>{{ item.messageRole === 'user' ? '你' : 'AI' }}</span>
              <ATag v-if="item.messageRole === 'assistant'" :color="statusColors[item.status]">
                {{ statusLabels[item.status] }}
              </ATag>
              <time :datetime="item.createTime">{{ item.createTime.replace('T', ' ') }}</time>
            </div>
            <p v-if="item.messageRole === 'user'" class="message-content">{{ item.content }}</p>
            <template v-else>
              <AAlert
                v-if="item.status === 'failed'"
                type="error"
                :message="item.errorMessage || '本次生成失败'"
                show-icon
              />
              <p v-else-if="item.status === 'running'" class="history-hint">
                生成仍在进行，点击「刷新状态」查看最新结果。
              </p>
              <p v-else-if="item.status === 'cancelled'" class="history-hint">本次生成已停止。</p>
              <details v-if="item.content" class="model-output">
                <summary>查看生成内容</summary>
                <pre>{{ item.content }}</pre>
              </details>
            </template>
          </li>
        </ol>
      </template>
    </template>
  </section>
</template>

<style scoped>
.history-panel {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid var(--border);
}
.history-heading,
.message-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.history-heading {
  justify-content: space-between;
  margin-bottom: 16px;
}
h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.message-list {
  display: grid;
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.message-item {
  display: grid;
  min-width: 0;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
time,
.history-hint {
  color: var(--muted);
  font-size: 12px;
}
.message-content,
.history-hint {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.75;
}
.history-hint + .message-list {
  margin-top: 16px;
}
summary {
  cursor: pointer;
}
pre {
  max-height: 320px;
  overflow: auto;
  margin: 12px 0 0;
  padding: 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-soft);
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
