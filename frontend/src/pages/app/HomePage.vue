<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Alert as AAlert,
  Button as AButton,
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
    const id = await createApp({ ...form })
    if (!active) return
    draftStore.clear()
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
      class="creation-form"
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
          :rows="6"
          placeholder="例如：一个摄影作品集，用简洁的网格展示照片，包含关于我和联系方式。"
        />
      </AFormItem>
      <AFormItem label="生成模式" name="codeGenType">
        <ARadioGroup v-model:value="form.codeGenType" aria-label="生成模式">
          <ARadioButton v-for="(mode, key) in generationModes" :key="key" :value="key">
            {{ mode.label }}
          </ARadioButton>
        </ARadioGroup>
        <p class="mode-help">
          {{ generationModes[form.codeGenType ?? 'multi_file'].hint }}
        </p>
      </AFormItem>
      <AButton
        type="primary"
        shape="round"
        html-type="submit"
        block
        :loading="busy"
        :disabled="disabled || loginUserStore.status !== 'ready'"
      >
        {{ busy ? '创建中' : error ? '重试' : '创建应用' }}
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
  padding: 48px 0;
}
.creation-heading {
  margin-bottom: 32px;
  text-align: center;
}
.eyebrow {
  margin: 0 0 16px;
  color: var(--muted);
  font-size: 12px;
}
h1 {
  margin: 0;
  font-size: 48px;
  line-height: 1.25;
  letter-spacing: var(--heading-tracking);
}
.introduction {
  margin: 16px 0 0;
  color: var(--muted);
  font-size: 16px;
  line-height: 1.5;
}
.creation-form {
  max-width: 560px;
  margin: 0 auto;
}
.submit-error {
  margin-bottom: 24px;
}
.mode-help,
.login-hint {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}
.login-hint {
  margin-top: 16px;
  text-align: center;
}
@media (max-width: 600px) {
  .creation-page {
    padding: 24px 0;
  }
  h1 {
    font-size: 40px;
  }
}
</style>
