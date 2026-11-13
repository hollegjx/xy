<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  RadioButton as ARadioButton,
  RadioGroup as ARadioGroup,
  Skeleton as ASkeleton,
} from 'ant-design-vue'
import PageHeading from '@/components/PageHeading.vue'
import ChatHistoryPanel from '@/pages/app/components/ChatHistoryPanel.vue'
import { ApiError, errorMessage } from '@/request'
import { getApp } from '@/service/api/app'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { AppVO } from '@/types/api'

const route = useRoute()
const loginUserStore = useLoginUserStore()
const app = ref<AppVO | null>(null)
const loading = ref(false)
const error = ref('')
const panel = ref('prompt')
let revision = 0

async function load(): Promise<void> {
  const currentRevision = ++revision
  const id = String(route.params.id)
  app.value = null
  loading.value = true
  error.value = ''
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

watch(() => route.params.id, load, { immediate: true })
onBeforeUnmount(() => {
  revision += 1
})
</script>

<template>
  <div class="workbench">
    <RouterLink to="/" class="back-link">返回首页</RouterLink>
    <PageHeading :title="app?.appName ?? '应用工作台'" description="查看应用需求与网站预览。" />
    <ASkeleton v-if="loading" active :paragraph="{ rows: 6 }" aria-label="正在读取应用" />
    <AAlert
      v-else-if="error"
      type="error"
      message="无法读取应用"
      :description="error"
      show-icon
      role="alert"
    >
      <template #action><AButton @click="load">重试</AButton></template>
    </AAlert>
    <template v-else-if="app">
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
              <dd>{{ app.codeGenType === 'html' ? '单 HTML' : '多文件' }}</dd>
            </div>
            <div>
              <dt>创建时间</dt>
              <dd>{{ app.createTime.replace('T', ' ') }}</dd>
            </div>
          </dl>
          <p class="prompt-content">{{ app.initPrompt }}</p>
          <ChatHistoryPanel :key="app.id" :app-id="app.id" :init-prompt="app.initPrompt" />
        </section>
        <section
          class="workspace-panel preview-panel"
          :class="{ 'mobile-hidden': panel !== 'preview' }"
        >
          <h2>网站预览</h2>
          <div class="preview-empty">
            <AEmpty description="预览将在这里展示">
              <p>可先在生成记录中查看已保存的内容。</p>
            </AEmpty>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.workbench {
  overflow-wrap: anywhere;
}
.back-link {
  display: inline-block;
  margin-bottom: 24px;
  color: var(--accent);
  text-underline-offset: 4px;
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
.preview-empty {
  display: grid;
  flex: 1;
  min-height: 320px;
  padding: 32px 0;
  place-items: center;
  color: var(--muted);
}
.preview-empty p {
  margin: 0;
  font-size: 12px;
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
