<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  Alert as AAlert,
  Button as AButton,
  Popconfirm as APopconfirm,
  RadioButton as ARadioButton,
  RadioGroup as ARadioGroup,
  Skeleton as ASkeleton,
  message,
} from 'ant-design-vue'
import PageHeading from '@/components/PageHeading.vue'
import ChatHistoryPanel from '@/pages/app/components/ChatHistoryPanel.vue'
import AppPreviewPanel from '@/pages/app/components/AppPreviewPanel.vue'
import AppRenameModal from '@/pages/app/components/AppRenameModal.vue'
import { ApiError, errorMessage } from '@/request'
import { deleteApp, deployApp, getApp, getAppSource } from '@/service/api/app'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { useAppDraftStore } from '@/stores/useAppDraftStore'
import type { AppVO } from '@/types/api'
import { generationModes } from './generationModes'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const draftStore = useAppDraftStore()
const app = ref<AppVO | null>(null)
const loading = ref(false)
const error = ref('')
const renameOpen = ref(false)
const deleteOpen = ref(false)
const deleting = ref(false)
const deleteError = ref('')
const panel = ref('prompt')
const selection = ref('')
function selectElement(description: string): void {
  selection.value = description
  if (description) panel.value = 'prompt'
}
const autoGenerate = ref(false)
const initialQualityCheck = ref(false)
const previewVersion = ref<string | null>(null)
const downloading = ref(false)
const downloadError = ref('')
const publishing = ref(false)
const publishError = ref('')
const copying = ref(false)
const copyError = ref('')
let revision = 0

async function load(startFirstGeneration = false): Promise<void> {
  const currentRevision = ++revision
  const id = String(route.params.id)
  autoGenerate.value = startFirstGeneration
  previewVersion.value = null
  selection.value = ''
  app.value = null
  loading.value = true
  error.value = ''
  renameOpen.value = false
  deleteOpen.value = false
  deleting.value = false
  deleteError.value = ''
  downloading.value = false
  downloadError.value = ''
  publishing.value = false
  publishError.value = ''
  copying.value = false
  copyError.value = ''
  try {
    const result = await getApp(id)
    if (currentRevision === revision) app.value = result
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

function handleRenamed(appName: string): void {
  if (app.value) app.value.appName = appName
  renameOpen.value = false
}

function changeDeleteOpen(open: boolean): void {
  if (!deleting.value) deleteOpen.value = open
}

async function removeApp(): Promise<void> {
  if (!app.value || deleting.value || loginUserStore.authAction !== null) return
  const currentRevision = revision
  deleting.value = true
  deleteError.value = ''
  try {
    await deleteApp(app.value.id)
    if (currentRevision !== revision) return
    void message.success('应用已删除')
    await router.replace('/')
  } catch (cause) {
    if (currentRevision !== revision) return
    if (cause instanceof ApiError && cause.code === 40100) loginUserStore.setUser(null)
    else deleteError.value = errorMessage(cause)
  } finally {
    if (currentRevision === revision) {
      deleting.value = false
      deleteOpen.value = false
    }
  }
}

async function downloadSource(): Promise<void> {
  if (!app.value || downloading.value || loginUserStore.authAction !== null) return
  const id = app.value.id
  const currentRevision = revision
  downloading.value = true
  downloadError.value = ''
  try {
    const source = await getAppSource(id)
    if (currentRevision !== revision) return
    const url = globalThis.URL.createObjectURL(source)
    const link = globalThis.document.createElement('a')
    link.href = url
    link.download = `xy-app-${id}.zip`
    globalThis.document.body.append(link)
    link.click()
    link.remove()
    // 给浏览器处理下载的时间，再释放临时文件地址。
    globalThis.setTimeout(() => globalThis.URL.revokeObjectURL(url), 1000)
    void message.success('已开始下载')
  } catch (cause) {
    if (currentRevision !== revision) return
    if (cause instanceof ApiError && cause.code === 40100) loginUserStore.setUser(null)
    else downloadError.value = errorMessage(cause)
  } finally {
    if (currentRevision === revision) downloading.value = false
  }
}

async function publish(): Promise<void> {
  if (!app.value || publishing.value || loginUserStore.authAction !== null) return
  const currentRevision = revision
  const id = app.value.id
  publishing.value = true
  publishError.value = ''
  try {
    const result = await deployApp(id)
    if (currentRevision !== revision || !app.value) return
    app.value.deployment = result
    copyError.value = ''
    void message.success('发布成功')
  } catch (cause) {
    if (currentRevision !== revision) return
    if (cause instanceof ApiError && cause.code === 40100) loginUserStore.setUser(null)
    else publishError.value = errorMessage(cause)
  } finally {
    if (currentRevision === revision) publishing.value = false
  }
}

async function copyLink(): Promise<void> {
  const url = app.value?.deployment?.deployUrl
  if (!url || copying.value) return
  const currentRevision = revision
  copying.value = true
  copyError.value = ''
  try {
    await globalThis.navigator.clipboard.writeText(url)
    if (currentRevision === revision) void message.success('链接已复制')
  } catch {
    if (currentRevision === revision) copyError.value = '无法自动复制，请手动选择下方链接复制。'
  } finally {
    if (currentRevision === revision) copying.value = false
  }
}

watch(
  () => route.params.id,
  (id) => {
    const first = draftStore.consumeFirstGeneration(String(id))
    initialQualityCheck.value = first?.qualityCheck ?? false
    void load(first !== null)
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  revision += 1
})
</script>

<template>
  <div class="workbench">
    <div class="workbench-navigation">
      <RouterLink to="/" class="back-link">返回首页</RouterLink>
      <div v-if="app && !loading && !error" class="app-actions">
        <AButton
          :disabled="deleting || loginUserStore.authAction !== null"
          @click="renameOpen = true"
        >
          修改名称
        </AButton>
        <APopconfirm
          :open="deleteOpen"
          title="确认删除这个应用？"
          placement="bottomRight"
          :ok-text="deleting ? '删除中' : '确认删除'"
          cancel-text="取消"
          :ok-button-props="{
            danger: true,
            loading: deleting,
            disabled: loginUserStore.authAction !== null,
          }"
          :cancel-button-props="{ disabled: deleting }"
          :disabled="loginUserStore.authAction !== null"
          @open-change="changeDeleteOpen"
          @confirm="removeApp"
        >
          <template #description>
            <p class="delete-confirmation">删除后无法继续访问该应用，已发布链接也会失效。</p>
          </template>
          <AButton danger :loading="deleting" :disabled="loginUserStore.authAction !== null">
            {{ deleting ? '删除中' : '删除应用' }}
          </AButton>
        </APopconfirm>
      </div>
    </div>
    <PageHeading :title="app?.appName ?? '应用工作台'" description="生成、预览并分享你的应用。" />
    <ASkeleton v-if="loading" active :paragraph="{ rows: 6 }" aria-label="正在读取应用" />
    <AAlert
      v-else-if="error"
      type="error"
      message="无法读取应用"
      :description="error"
      show-icon
      role="alert"
    >
      <template #action><AButton @click="load()">重试</AButton></template>
    </AAlert>
    <template v-else-if="app">
      <AAlert
        v-if="deleteError"
        class="delete-error"
        type="error"
        message="应用删除失败"
        :description="deleteError"
        show-icon
        role="alert"
      >
        <template #action>
          <AButton
            :disabled="deleting || loginUserStore.authAction !== null"
            @click="deleteOpen = true"
          >
            重试
          </AButton>
        </template>
      </AAlert>
      <AppRenameModal
        :key="app.id"
        :open="renameOpen"
        :app="app"
        @close="renameOpen = false"
        @saved="handleRenamed"
      />
      <div class="source-actions" role="region" aria-label="发布与下载">
        <div class="delivery-heading">
          <div>
            <h2>发布与分享</h2>
            <p v-if="app.deployment" class="source-hint">
              上次发布 {{ app.deployment.deployedAt.replace('T', ' ') }}，新修改需重新发布。
            </p>
            <p v-else-if="app.codeGenType === 'vue_project'" class="source-hint">
              尚未发布。构建成功后可分享作品，工程源码可单独下载。
            </p>
            <p v-else class="source-hint">尚未发布。将最近成功生成的网站分享给别人。</p>
          </div>
          <div class="source-download">
            <AButton
              type="primary"
              shape="round"
              :loading="publishing"
              :disabled="publishing || loginUserStore.authAction !== null"
              @click="publish"
            >
              {{
                publishing
                  ? '发布中'
                  : publishError
                    ? '重试发布'
                    : app.deployment
                      ? '重新发布'
                      : '发布应用'
              }}
            </AButton>
            <AButton
              :loading="downloading"
              :disabled="downloading || loginUserStore.authAction !== null"
              @click="downloadSource"
            >
              {{ downloading ? '下载中' : downloadError ? '重试下载' : '下载源码' }}
            </AButton>
          </div>
        </div>
        <AAlert
          v-if="publishError"
          type="error"
          message="应用发布失败"
          :description="publishError"
          show-icon
          role="alert"
        />
        <AAlert v-if="copyError" type="warning" :message="copyError" show-icon role="alert" />
        <div v-if="app.deployment?.deployUrl" class="published-link">
          <span class="share-url">{{ app.deployment.deployUrl }}</span>
          <div class="source-download">
            <AButton :href="app.deployment.deployUrl" target="_blank" rel="noopener noreferrer">
              查看作品
            </AButton>
            <AButton :loading="copying" :disabled="copying" @click="copyLink">复制链接</AButton>
          </div>
        </div>
        <p v-else-if="app.deployment" class="source-hint">已保存发布记录，当前暂无访问地址。</p>
        <AAlert
          v-if="downloadError"
          type="error"
          message="源码下载失败"
          :description="downloadError"
          show-icon
          role="alert"
        />
      </div>
      <ARadioGroup v-model:value="panel" class="panel-switch" aria-label="工作台视图">
        <ARadioButton value="prompt">应用需求</ARadioButton>
        <ARadioButton value="preview">网站预览</ARadioButton>
      </ARadioGroup>
      <div class="workspace">
        <section class="workspace-panel" :class="{ 'mobile-hidden': panel !== 'prompt' }">
          <h2>初始需求</h2>
          <dl class="app-details">
            <div>
              <dt>生成模式</dt>
              <dd>{{ generationModes[app.codeGenType].label }}</dd>
            </div>
            <div>
              <dt>创建时间</dt>
              <dd>{{ app.createTime.replace('T', ' ') }}</dd>
            </div>
          </dl>
          <p class="prompt-content">{{ app.initPrompt }}</p>
          <ChatHistoryPanel
            :key="app.id"
            :app-id="app.id"
            :init-prompt="app.initPrompt"
            :code-gen-type="app.codeGenType"
            :auto-generate="autoGenerate"
            :initial-quality-check="initialQualityCheck"
            :selection="selection"
            @completed="previewVersion = $event"
          />
        </section>
        <section
          class="workspace-panel preview-panel"
          :class="{ 'mobile-hidden': panel !== 'preview' }"
        >
          <AppPreviewPanel
            :key="app.id"
            :app-id="app.id"
            :version="previewVersion"
            @selection="selectElement"
          />
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.workbench {
  overflow-wrap: anywhere;
}
.workbench-navigation {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
.back-link {
  color: var(--accent);
  text-underline-offset: 4px;
}
.app-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.delete-confirmation {
  max-width: 240px;
  margin: 0;
}
.delete-error {
  margin-bottom: 24px;
}
.source-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 24px;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--border);
}
.delivery-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.delivery-heading h2 {
  margin-bottom: 8px;
}
.source-download {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.source-hint {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}
.published-link {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
}
.share-url {
  flex: 1 1 240px;
  min-width: 0;
  font-size: 14px;
  user-select: text;
}
.workspace {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  gap: 24px;
}
.workspace-panel {
  min-width: 0;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.app-details {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 24px;
  margin: 24px 0;
  font-size: 12px;
}
dt {
  margin-bottom: 8px;
  color: var(--muted);
}
dd {
  margin: 0;
}
.prompt-content {
  max-height: 480px;
  overflow: auto;
  margin: 0;
  padding-top: 24px;
  border-top: 1px solid var(--border);
  white-space: pre-wrap;
  line-height: 1.75;
}
.preview-panel {
  display: flex;
  flex-direction: column;
  background: var(--surface-soft);
}
.panel-switch {
  display: none;
}
@media (max-width: 760px) {
  .workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  .workspace-panel {
    padding: 16px;
  }
  .panel-switch {
    display: inline-flex;
    margin-bottom: 16px;
  }
  .mobile-hidden {
    display: none;
  }
}
</style>
