<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Checkbox as ACheckbox,
  Empty as AEmpty,
  Form as AForm,
  FormItem as AFormItem,
  Skeleton as ASkeleton,
  Tag as ATag,
  Textarea as ATextarea,
} from 'ant-design-vue'
import { ApiError, errorMessage } from '@/request'
import { getAppMessages } from '@/service/api/chat'
import { cancelGeneration, generateApp } from '@/service/api/generation'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type {
  ChatHistoryPageVO,
  CodeGenType,
  GenerationEventVO,
  GenerationStatus,
} from '@/types/api'

const props = defineProps<{
  appId: string
  initPrompt: string
  codeGenType: CodeGenType
  autoGenerate?: boolean
  selection?: string
}>()
const emit = defineEmits<{ completed: [generationId: string] }>()
const loginUserStore = useLoginUserStore()
const history = ref<ChatHistoryPageVO | null>(null)
const loading = ref(false)
const error = ref('')
const loadingMore = ref(false)
const moreError = ref('')
const form = reactive({ prompt: '' })
const qualityCheck = ref(false)
const generating = ref(false)
const stopping = ref(false)
const generationError = ref('')
const live = ref<GenerationEventVO | null>(null)
const output = ref('')
let controller: globalThis.AbortController | null = null
let active = true
let revision = 0
let promptInitialized = false
let submittedPrompt: string | null = null

const runningId = computed(() =>
  generating.value && live.value?.status === 'running'
    ? live.value.generationId
    : history.value?.records.find(
        (item) => item.messageRole === 'assistant' && item.status === 'running',
      )?.id,
)
const canGenerate = computed(
  () =>
    history.value !== null &&
    !loading.value &&
    !generating.value &&
    !stopping.value &&
    !runningId.value &&
    loginUserStore.authAction === null,
)
const submitLabel = computed(() => {
  if (generating.value) return '生成中'
  const status = live.value?.status ?? history.value?.records.at(-1)?.status
  if (generationError.value || status === 'failed' || status === 'cancelled') return '重试生成'
  return history.value?.records.length ? '提交需求' : '开始生成'
})

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
  loadingMore.value = false
  moreError.value = ''
  history.value = null
  try {
    const result = await getAppMessages(props.appId)
    if (currentRevision !== revision) return
    history.value = result
    if (!promptInitialized) {
      const latest = result.records.at(-1)
      const previous = result.records.at(-2)
      if (!latest) form.prompt = props.initPrompt
      else if (
        latest.messageRole === 'assistant' &&
        (latest.status === 'failed' || latest.status === 'cancelled') &&
        previous?.messageRole === 'user'
      ) {
        form.prompt = previous.content
      }
      // 只在首次成功读取历史时恢复输入，刷新状态和翻页不能覆盖正在编辑的需求。
      promptInitialized = true
    }
    const completed = [...result.records]
      .reverse()
      .find((item) => item.messageRole === 'assistant' && item.status === 'completed')
    if (completed) emit('completed', completed.id)
    const saved = result.records.find((item) => item.id === live.value?.generationId)
    if (saved && saved.status !== 'running') {
      live.value = {
        generationId: saved.id,
        status: saved.status,
        message: saved.errorMessage ?? undefined,
      }
      output.value = saved.content
      generationError.value = ''
      if (saved.status === 'completed') clearCompletedPrompt()
    }
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

async function loadMore(): Promise<void> {
  const current = history.value
  if (
    !current?.hasMore ||
    !current.nextCursor ||
    loading.value ||
    loadingMore.value ||
    generating.value ||
    stopping.value
  )
    return
  const currentRevision = revision
  loadingMore.value = true
  moreError.value = ''
  try {
    const result = await getAppMessages(props.appId, { beforeId: current.nextCursor })
    // 刷新或离开工作台后，旧分页响应不能混入新列表。
    if (currentRevision !== revision) return
    history.value = { ...result, records: [...result.records, ...current.records] }
  } catch (cause) {
    if (currentRevision !== revision) return
    if (cause instanceof ApiError && cause.code === 40100) loginUserStore.setUser(null)
    else moreError.value = errorMessage(cause)
  } finally {
    if (currentRevision === revision) loadingMore.value = false
  }
}

const generationPrompt = computed(() =>
  props.selection
    ? `${form.prompt}\n\n选中元素（标签、位置、页面文字）：${props.selection}`
    : form.prompt,
)

async function validatePromptLength(): Promise<void> {
  if (Array.from(generationPrompt.value).length > 16383)
    throw new Error('需求及选中元素合计不能超过 16383 个字符')
}

function showGenerationError(cause: unknown): void {
  if (cause instanceof ApiError && cause.code === 40100) loginUserStore.setUser(null)
  else generationError.value = errorMessage(cause)
}

function clearCompletedPrompt(): void {
  if (form.prompt === submittedPrompt) form.prompt = ''
  submittedPrompt = null
}

async function generate(): Promise<void> {
  if (!canGenerate.value) return
  const currentController = new globalThis.AbortController()
  controller = currentController
  generating.value = true
  generationError.value = ''
  live.value = null
  output.value = ''
  submittedPrompt = form.prompt
  let refreshHistory = true
  try {
    await generateApp(
      props.appId,
      generationPrompt.value,
      currentController.signal,
      (event) => {
        if (!active || currentController.signal.aborted) return
        live.value = event
        output.value += event.text ?? ''
        if (event.status === 'completed') {
          clearCompletedPrompt()
          emit('completed', event.generationId)
        }
      },
      qualityCheck.value || undefined,
    )
  } catch (cause) {
    refreshHistory = !(cause instanceof ApiError && (cause.code === 40000 || cause.code === 42900))
    if (active && !currentController.signal.aborted) showGenerationError(cause)
  } finally {
    if (active) {
      generating.value = false
      controller = null
      // 入参被拒或账号限流都发生在开流前，没有新增消息需要刷新。
      if (refreshHistory) await load()
    }
  }
}

async function stop(): Promise<void> {
  const id = runningId.value
  if (!id || stopping.value) return
  stopping.value = true
  generationError.value = ''
  try {
    await cancelGeneration(props.appId, id)
    if (!active) return
    if (generating.value && live.value?.generationId === id) {
      live.value = { generationId: id, status: 'cancelled' }
      controller?.abort()
    } else await load()
  } catch (cause) {
    if (active) {
      showGenerationError(cause)
      if (cause instanceof ApiError && cause.code === 50001) await load()
    }
  } finally {
    if (active) stopping.value = false
  }
}

watch(
  () => props.appId,
  async () => {
    await load()
    if (active && props.autoGenerate && history.value?.records.length === 0) void generate()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  active = false
  controller?.abort()
  revision += 1
})
</script>

<template>
  <section class="generation-panel" aria-label="生成网站">
    <AForm :model="form" layout="vertical" name="generate-app" @finish="generate">
      <p v-if="selection" class="history-hint" aria-live="polite">已选元素：{{ selection }}</p>
      <AFormItem
        label="本次需求"
        name="prompt"
        :rules="[
          { required: true, whitespace: true, message: '请填写本次需求' },
          { validator: validatePromptLength },
        ]"
      >
        <ATextarea
          v-model:value="form.prompt"
          :rows="4"
          :disabled="loading || !history || generating || stopping"
          placeholder="描述本次需求，例如：把背景改为黑色，保留原有内容和按钮。"
        />
      </AFormItem>
      <p class="history-hint">已有网站会基于最近成功版本修改，只需描述这次想调整的内容。</p>
      <div class="quality-option">
        <ACheckbox v-model:checked="qualityCheck" :disabled="!canGenerate">
          AI 质检与修复
        </ACheckbox>
        <p class="history-hint">
          {{
            codeGenType === 'vue_project'
              ? '生成后检查工程源码，必要时自动修复一次。'
              : '有问题时自动修复一次，检查通过后更新预览。'
          }}
        </p>
      </div>
      <div class="generation-actions">
        <AButton
          type="primary"
          shape="round"
          html-type="submit"
          :loading="generating"
          :disabled="!canGenerate"
        >
          {{ submitLabel }}
        </AButton>
        <AButton v-if="runningId" :loading="stopping" :disabled="stopping" @click="stop"
          >停止生成</AButton
        >
      </div>
    </AForm>
    <AAlert v-if="generationError" type="error" :message="generationError" show-icon role="alert" />
    <div v-if="live" class="live-output" aria-label="本次生成">
      <ATag
        :color="!generating && live.status === 'running' ? 'default' : statusColors[live.status]"
      >
        {{ !generating && live.status === 'running' ? '状态待确认' : statusLabels[live.status] }}
      </ATag>
      <AAlert
        v-if="live.status === 'failed'"
        type="error"
        :message="live.message || '本次生成失败，可再次提交需求'"
        show-icon
      />
      <pre v-if="output">{{ output }}</pre>
      <p v-else-if="generating" class="history-hint">正在等待生成内容…</p>
    </div>
  </section>
  <section class="history-panel" aria-label="生成记录" :aria-busy="loading || loadingMore">
    <div class="history-heading">
      <h2>生成记录</h2>
      <AButton
        v-if="!error"
        :loading="loading"
        :disabled="loading || generating || stopping"
        @click="load"
      >
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
        <div class="history-pagination">
          <AAlert
            v-if="moreError"
            type="error"
            message="更早消息读取失败"
            :description="moreError"
            show-icon
            role="alert"
          />
          <div class="history-controls">
            <p class="history-hint">
              已显示 {{ history.records.length }} 条记录{{
                history.hasMore ? '' : '，已全部加载'
              }}。
            </p>
            <AButton
              v-if="history.hasMore"
              :loading="loadingMore"
              :disabled="loadingMore || generating || stopping"
              @click="loadMore"
            >
              {{ moreError ? '重试' : '加载更早消息' }}
            </AButton>
          </div>
        </div>
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
.generation-panel {
  display: grid;
  gap: 16px;
  margin-top: 24px;
}
.quality-option {
  margin-top: 12px;
}
.generation-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}
.live-output {
  min-width: 0;
}
.history-panel {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid var(--border);
}
.history-heading,
.history-controls,
.message-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.history-heading {
  margin-bottom: 16px;
}
.history-heading,
.history-controls {
  justify-content: space-between;
}
.history-pagination {
  display: grid;
  gap: 12px;
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
