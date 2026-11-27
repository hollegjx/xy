<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Alert as AAlert,
  Button as AButton,
  Checkbox as ACheckbox,
  Form as AForm,
  FormItem as AFormItem,
  RadioButton as ARadioButton,
  RadioGroup as ARadioGroup,
  Textarea as ATextarea,
} from 'ant-design-vue'
import { ApiError, errorMessage } from '@/request'
import MyAppsPanel from '@/pages/app/components/MyAppsPanel.vue'
import FeaturedAppsPanel from '@/pages/app/components/FeaturedAppsPanel.vue'
import { createApp } from '@/service/api/app'
import { generationModes } from './generationModes'
import { useAppDraftStore } from '@/stores/useAppDraftStore'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const draftStore = useAppDraftStore()
const form = draftStore.draft
const creationModes = {
  auto: { label: '自动选择', hint: '根据这次需求选择生成模式，创建后可在工作台查看结果。' },
  ...generationModes,
}
const busy = ref(false)
const error = ref('')
const disabled = computed(() => busy.value || loginUserStore.authAction !== null)
let active = true

async function validatePromptLength(): Promise<void> {
  if (Array.from(form.initPrompt).length > 16383) {
    throw new Error('需求长度不能超过 16383 个字符')
  }
}

async function goToLogin(): Promise<void> {
  await router.push({ path: '/user/login', query: { redirect: '/' } })
}

async function submit(): Promise<void> {
  if (disabled.value || loginUserStore.status !== 'ready') return
  if (!loginUserStore.user) {
    await goToLogin()
    return
  }
  busy.value = true
  error.value = ''
  try {
    const id = await createApp({ initPrompt: form.initPrompt, codeGenType: form.codeGenType })
    if (!active) return
    draftStore.prepareFirstGeneration(id)
    await router.push({ name: 'app-workbench', params: { id } })
  } catch (cause) {
    if (!active) return
    if (cause instanceof ApiError && cause.code === 40100) {
      loginUserStore.setUser(null)
      await goToLogin()
    } else {
      error.value = errorMessage(cause)
    }
  } finally {
    busy.value = false
  }
}

onBeforeUnmount(() => {
  active = false
})
</script>

<template>
  <section class="creation-page">
    <header class="creation-heading">
      <p class="eyebrow">让灵感在此成形</p>
      <h1>从一个想法开始</h1>
      <p class="introduction">描述你想做的网站，为它创建一个专属工作台。</p>
    </header>
    <AForm
      id="create-app"
      class="creation-form surface-card"
      name="create-app"
      :model="form"
      layout="vertical"
      :disabled="disabled"
      :aria-busy="busy"
      validate-trigger="blur"
      @finish="submit"
    >
      <AAlert
        v-if="error"
        class="submit-error"
        type="error"
        message="创建失败"
        :description="error"
        show-icon
        role="alert"
      />
      <AFormItem
        label="应用需求"
        name="initPrompt"
        :rules="[
          { required: true, whitespace: true, message: '请描述你想创建的应用' },
          { validator: validatePromptLength },
        ]"
      >
        <ATextarea
          v-model:value="form.initPrompt"
          class="prompt-input"
          :rows="5"
          placeholder="例如：一个摄影作品集，用简洁的网格展示照片，包含关于我和联系方式。"
        />
      </AFormItem>
      <AFormItem label="生成模式" name="codeGenType">
        <ARadioGroup v-model:value="form.codeGenType" aria-label="生成模式">
          <ARadioButton v-for="(mode, key) in creationModes" :key="key" :value="key">
            {{ mode.label }}
          </ARadioButton>
        </ARadioGroup>
        <p class="mode-help" aria-live="polite">
          {{
            busy && form.codeGenType === 'auto'
              ? '正在根据需求选择生成模式，请稍候。'
              : creationModes[form.codeGenType ?? 'multi_file'].hint
          }}
        </p>
      </AFormItem>
      <AFormItem name="qualityCheck">
        <ACheckbox v-model:checked="form.qualityCheck" aria-describedby="first-quality-hint">
          AI 质检与修复
        </ACheckbox>
        <p id="first-quality-hint" class="mode-help">
          首次生成前先准备所需素材，完成后检查页面，有问题会尝试修复一次。
        </p>
      </AFormItem>
      <AButton
        type="primary"
        shape="round"
        size="large"
        html-type="submit"
        block
        :loading="busy"
        :disabled="disabled || loginUserStore.status !== 'ready'"
      >
        {{
          busy
            ? form.codeGenType === 'auto'
              ? '正在选择模式'
              : '创建中'
            : error
              ? '重试'
              : '创建应用'
        }}
      </AButton>
      <p v-if="loginUserStore.status === 'ready' && !loginUserStore.user" class="login-hint">
        登录后即可创建，已填写的需求会在登录跳转时保留。
      </p>
    </AForm>
    <MyAppsPanel
      v-if="loginUserStore.status === 'ready' && loginUserStore.user"
      :key="loginUserStore.user.id"
    />
    <FeaturedAppsPanel />
  </section>
</template>

<style scoped>
.creation-page {
  padding: 40px 0 24px;
}
.creation-heading {
  margin-bottom: 40px;
  text-align: center;
}
.eyebrow {
  display: inline-block;
  margin: 0 0 12px;
  color: transparent;
  background: var(--brand-gradient);
  -webkit-background-clip: text;
  background-clip: text;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
}
h1 {
  margin: 0;
  font-size: 56px;
  font-weight: 700;
  line-height: 1.08;
  letter-spacing: -0.03em;
}
.introduction {
  margin: 16px 0 0;
  color: var(--muted);
  font-size: 19px;
  line-height: 1.5;
}
.creation-form {
  max-width: 680px;
  margin: 0 auto;
  padding: 28px 28px 24px;
  border-radius: var(--radius-lg);
}
.creation-form :deep(.ant-form-item-label > label) {
  font-weight: 500;
}
.creation-form .prompt-input {
  padding: 14px 16px;
  border-color: transparent;
  border-radius: var(--radius-md);
  background: var(--surface-soft);
  font-size: 16px;
  line-height: 1.6;
  resize: vertical;
}
.creation-form .prompt-input:hover {
  border-color: transparent;
  background: #efeff2;
}
.creation-form .prompt-input:focus {
  border-color: var(--accent);
  background: var(--surface);
}
.submit-error {
  margin-bottom: 24px;
}
.mode-help,
.login-hint {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.5;
}
.login-hint {
  margin-top: 14px;
  text-align: center;
}
@media (max-width: 600px) {
  .creation-page {
    padding: 16px 0;
  }
  .creation-heading {
    margin-bottom: 28px;
  }
  h1 {
    font-size: 40px;
  }
  .introduction {
    font-size: 17px;
  }
  .creation-form {
    padding: 20px 16px 16px;
    border-radius: var(--radius-md);
  }
}
</style>
