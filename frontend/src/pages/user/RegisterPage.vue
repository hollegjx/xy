<script setup lang="ts">
import { reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  Alert as AAlert,
  Button as AButton,
  Card as ACard,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  InputPassword as AInputPassword,
  Result as AResult,
} from 'ant-design-vue'
import { registerUser } from '@/service/api/user'
import { errorMessage } from '@/request'
import type { UserRegisterRequest } from '@/types/api'

const form = reactive<UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})
const busy = ref(false)
const error = ref('')
const registered = ref(false)

async function validateAccountLength(): Promise<void> {
  // 与后端保持一致：按 Unicode 码点计数，emoji 不按两个字符计算。
  if (Array.from(form.userAccount).length > 128) {
    throw new Error('账号长度不能超过 128 个字符')
  }
}

async function validateCheckPassword(): Promise<void> {
  if (form.checkPassword && form.checkPassword !== form.userPassword) {
    throw new Error('两次输入的密码不一致')
  }
}

async function submitRegistration(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await registerUser({ ...form })
    registered.value = true
    form.userPassword = ''
    form.checkPassword = ''
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="register-page" aria-labelledby="register-title">
    <div class="page-heading">
      <p class="eyebrow">星云 · XY</p>
      <h1 id="register-title">创建账号</h1>
      <p class="description">注册星云，让创意成为应用。</p>
    </div>

    <ACard :bordered="false">
      <div v-if="registered" aria-live="polite">
        <AResult status="success" title="注册成功" sub-title="你的星云账号已创建。">
          <template #extra>
            <RouterLink to="/">
              <AButton type="primary">返回首页</AButton>
            </RouterLink>
          </template>
        </AResult>
      </div>
      <AForm
        v-else
        name="register"
        :model="form"
        layout="vertical"
        :disabled="busy"
        :aria-busy="busy"
        validate-trigger="blur"
        @finish="submitRegistration"
      >
        <AAlert
          v-if="error"
          class="submit-error"
          type="error"
          message="注册失败"
          :description="error"
          show-icon
          role="alert"
        />
        <AFormItem
          label="账号"
          name="userAccount"
          extra="最多 128 个字符"
          validate-first
          :rules="[
            { required: true, whitespace: true, message: '账号不能为空' },
            { validator: validateAccountLength },
          ]"
        >
          <AInput
            v-model:value="form.userAccount"
            autocomplete="username"
            autocapitalize="none"
            :spellcheck="false"
            placeholder="请输入账号"
            size="large"
          />
        </AFormItem>
        <AFormItem
          label="密码"
          name="userPassword"
          :rules="[{ required: true, whitespace: true, message: '密码不能为空' }]"
        >
          <AInputPassword
            v-model:value="form.userPassword"
            autocomplete="new-password"
            placeholder="请输入密码"
            size="large"
          />
        </AFormItem>
        <AFormItem
          label="确认密码"
          name="checkPassword"
          validate-first
          :rules="[
            { required: true, whitespace: true, message: '确认密码不能为空' },
            { validator: validateCheckPassword },
          ]"
        >
          <AInputPassword
            v-model:value="form.checkPassword"
            autocomplete="new-password"
            placeholder="请再次输入密码"
            size="large"
          />
        </AFormItem>
        <AButton type="primary" html-type="submit" size="large" block :loading="busy">
          {{ busy ? '正在注册…' : '注册账号' }}
        </AButton>
      </AForm>
    </ACard>
  </section>
</template>

<style scoped>
.register-page {
  max-width: 460px;
  margin: 0 auto;
}
.page-heading {
  margin-bottom: 28px;
  text-align: center;
}
.eyebrow {
  margin: 0 0 12px;
  color: #356859;
  font-size: 12px;
  letter-spacing: 2px;
}
h1 {
  margin: 0 0 12px;
  font-size: 32px;
  font-weight: 600;
  letter-spacing: -1px;
}
.description {
  margin: 0;
  color: #748178;
  font-size: 14px;
}
.submit-error {
  margin-bottom: 24px;
}
</style>
