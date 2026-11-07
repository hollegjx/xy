<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  Alert as AAlert,
  Button as AButton,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  InputPassword as AInputPassword,
} from 'ant-design-vue'
import { registerUser } from '@/service/api/user'
import { errorMessage } from '@/request'
import AuthShell from '@/components/AuthShell.vue'
import { safeRedirect } from '@/utils/navigation'
import type { UserRegisterRequest } from '@/types/api'

const route = useRoute()
const router = useRouter()
const form = reactive<UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})
const busy = ref(false)
const error = ref('')
const registered = ref(false)
const loginLocation = computed(() => ({
  path: '/user/login',
  query: { redirect: safeRedirect(route.query.redirect) },
}))

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
  <AuthShell title="创建账号" description="填写账号和密码，两次密码须一致。">
    <div v-if="registered" aria-live="polite">
      <AAlert type="success" message="注册成功" description="账号已创建。" show-icon />
      <div class="success-actions">
        <AButton type="primary" shape="round" block @click="router.push(loginLocation)"
          >去登录</AButton
        >
      </div>
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
        />
      </AFormItem>
      <!-- loading 阻止重复点击，同时保留提交中文案的可读性。 -->
      <AButton
        type="primary"
        shape="round"
        html-type="submit"
        block
        :disabled="false"
        :loading="busy"
      >
        {{ busy ? '注册中' : error ? '重试' : '注册账号' }}
      </AButton>
    </AForm>
    <template v-if="!registered && !busy" #footer>
      已有账号？<RouterLink :to="loginLocation" class="auth-link">去登录</RouterLink>
    </template>
  </AuthShell>
</template>

<style scoped>
.submit-error {
  margin-bottom: 24px;
}
.success-actions {
  margin-top: 24px;
}
.auth-link {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 4px;
}
</style>
