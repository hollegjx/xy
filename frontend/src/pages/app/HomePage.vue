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
import ParticleField from '@/components/ParticleField.vue'
import HomeDemo from '@/pages/app/components/HomeDemo.vue'
import { vReveal } from '@/utils/reveal'
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
const steps = [
  { title: '描述需求', hint: '用一段话说清想要的网站，选择单 HTML、多文件或 Vue 工程。' },
  { title: '实时生成', hint: '流式展示规划、写文件和质检的每一步，随时可以停止。' },
  { title: '选中修改', hint: '在预览里点选页面元素，描述改动，只更新需要变的部分。' },
  { title: '发布分享', hint: '一键发布获得访问地址，生成封面，也能下载完整源码。' },
]
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

function focusPrompt(): void {
  const field =
    globalThis.document.querySelector<globalThis.HTMLTextAreaElement>('#create-app textarea')
  field?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  field?.focus({ preventScroll: true })
}

onBeforeUnmount(() => {
  active = false
})
</script>

<template>
  <section class="creation-page home-page">
    <div class="home-hero full-bleed">
      <ParticleField class="hero-particles" :density="0.8" />
      <header class="creation-heading">
        <p class="eyebrow hero-in hero-in-1">让灵感在此成形</p>
        <h1 class="hero-in hero-in-2">从一个想法开始</h1>
        <p class="introduction hero-in hero-in-3">描述你想做的网站，为它创建一个专属工作台。</p>
      </header>
      <HomeDemo />
      <AForm
        id="create-app"
        class="creation-form surface-card hero-in hero-in-5"
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
    </div>
    <div class="home-lists">
      <MyAppsPanel
        v-if="loginUserStore.status === 'ready' && loginUserStore.user"
        :key="loginUserStore.user.id"
      />
      <FeaturedAppsPanel />
    </div>
    <section class="home-flow full-bleed" aria-labelledby="home-flow-title">
      <ParticleField tone="paper" class="flow-particles" :density="0.75" :link-opacity="0.22" />
      <div v-reveal class="flow-heading">
        <p class="flow-kicker">工作流程</p>
        <h2 id="home-flow-title">从一句话，<br />到上线的网站。</h2>
      </div>
      <ol class="flow-steps">
        <li v-for="(step, index) in steps" :key="step.title" v-reveal="index * 120">
          <span class="flow-num">{{ String(index + 1).padStart(2, '0') }}</span>
          <h3>{{ step.title }}</h3>
          <p>{{ step.hint }}</p>
        </li>
      </ol>
      <div v-reveal class="flow-cta">
        <button type="button" class="flow-button" @click="focusPrompt">开始描述需求</button>
      </div>
    </section>
  </section>
</template>

<style scoped>
.creation-page {
  padding: 0;
}
/* 首页不用通用的区块依次入场，标题区有自己的四行入场序列 */
.page-container > .home-page > * {
  animation: none;
}
.home-hero {
  margin-top: -40px;
  padding: 88px 24px 0;
  overflow: hidden;
  background: var(--surface);
}
.home-hero > :not(.particle-field) {
  position: relative;
  z-index: 1;
}
.hero-particles {
  -webkit-mask-image: linear-gradient(to bottom, #000 0%, #000 45%, transparent 80%);
  mask-image: linear-gradient(to bottom, #000 0%, #000 45%, transparent 80%);
}
.hero-in {
  animation: xy-rise 1s var(--ease-out) backwards;
}
.hero-in-1 {
  animation-delay: 80ms;
}
.hero-in-2 {
  animation-delay: 200ms;
}
.hero-in-3 {
  animation-delay: 320ms;
}
.hero-in-5 {
  animation-delay: 700ms;
}
.creation-heading {
  margin-bottom: 44px;
  text-align: center;
}
.eyebrow {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 15px;
  font-weight: 600;
}
h1 {
  margin: 0;
  font-size: clamp(44px, 7.2vw, 88px);
  font-weight: 700;
  line-height: 1.04;
  letter-spacing: -0.045em;
}
.introduction {
  margin: 20px 0 0;
  color: var(--muted);
  font-size: clamp(17px, 1.8vw, 21px);
  line-height: 1.5;
}
.creation-form {
  max-width: 680px;
  margin: 0 auto 72px;
  padding: 28px 28px 24px;
  border-radius: 28px;
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.05),
    0 4px 12px rgba(0, 0, 0, 0.04),
    0 24px 56px rgba(0, 0, 0, 0.08);
}
.creation-form :deep(.ant-form-item-label > label) {
  font-weight: 500;
}
.creation-form .prompt-input {
  padding: 14px 16px;
  border-radius: var(--radius-md);
  font-size: 16px;
  line-height: 1.6;
  resize: vertical;
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
.home-lists {
  padding-top: 24px;
  animation: xy-rise 900ms var(--ease-out) 600ms backwards;
}

/* ── 黑色流程区 ── */
.home-flow {
  margin-top: 96px;
  margin-bottom: -96px;
  padding: 120px max(24px, calc((100vw - 1160px) / 2)) 128px;
  overflow: hidden;
  color: #f5f5f7;
  background: #000000;
}
.home-flow > :not(.particle-field) {
  position: relative;
  z-index: 1;
}
.flow-particles {
  -webkit-mask-image: linear-gradient(to bottom, transparent, #000 18%, #000 82%, transparent);
  mask-image: linear-gradient(to bottom, transparent, #000 18%, #000 82%, transparent);
}
.flow-kicker {
  margin: 0;
  color: #86868b;
  font-size: 15px;
  font-weight: 600;
}
.home-flow h2 {
  margin: 10px 0 0;
  font-size: clamp(36px, 5.6vw, 64px);
  font-weight: 700;
  line-height: 1.06;
  letter-spacing: -0.04em;
}
.flow-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin: 64px 0 0;
  padding: 0;
  list-style: none;
}
.flow-steps li {
  display: flex;
  flex-direction: column;
  min-height: 280px;
  padding: 28px;
  border-radius: 28px;
  background: #1d1d1f;
}
@media (hover: hover) {
  .flow-steps li:hover {
    background: #262628;
  }
  .flow-steps li:hover .flow-num {
    color: #f5f5f7;
  }
}
.flow-num {
  color: #424245;
  font-size: 52px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.04em;
  transition: color 640ms var(--ease-out);
}
.flow-steps h3 {
  margin: auto 0 0;
  color: #f5f5f7;
  font-size: 22px;
  font-weight: 600;
}
.flow-steps p {
  margin: 10px 0 0;
  color: #a1a1a6;
  font-size: 14px;
  line-height: 1.6;
}
.flow-cta {
  margin-top: 48px;
  text-align: center;
}
.flow-button {
  height: 44px;
  padding: 0 26px;
  border: 0;
  border-radius: 999px;
  color: #1d1d1f;
  background: #f5f5f7;
  font: inherit;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
}
.flow-button:hover {
  background: #ffffff;
}
@media (max-width: 1000px) {
  .flow-steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .flow-steps li {
    min-height: 220px;
  }
}
@media (max-width: 600px) {
  .home-hero {
    margin-top: -24px;
    padding: 48px 16px 0;
  }
  .creation-heading {
    margin-bottom: 28px;
  }
  .creation-form {
    padding: 20px 16px 16px;
    border-radius: 22px;
  }
  .home-flow {
    margin-top: 64px;
    padding: 80px 20px 88px;
  }
  .flow-steps {
    grid-template-columns: minmax(0, 1fr);
    margin-top: 40px;
  }
  .flow-steps li {
    min-height: 0;
    padding: 24px;
  }
}
</style>
