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
  message,
} from 'ant-design-vue'
import AuthShell from '@/components/AuthShell.vue'
import { errorMessage } from '@/request'
import { loginUser } from '@/service/api/user'
import { safeRedirect } from '@/utils/navigation'
import type { UserLoginRequest } from '@/types/api'

const route = useRoute()
const router = useRouter()
const form = reactive<UserLoginRequest>({ userAccount: '', userPassword: '' })
const busy = ref(false)
const error = ref('')
const destination = computed(() => safeRedirect(route.query.redirect))

async function submitLogin(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await loginUser({ ...form })
    form.userPassword = ''
    const target = router.resolve(destination.value)
    await router.replace(target.matched.length > 0 ? destination.value : '/')
    message.success('登录成功')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AuthShell title="登录星云" description="输入账号和密码，继续你的应用创作。">
    <AForm
      name="login"
      :model="form"
      layout="vertical"
      :disabled="busy"
      :aria-busy="busy"
      validate-trigger="blur"
      @finish="submitLogin"
    >
      <AAlert
        v-if="error"
        class="submit-error"
        type="error"
        message="登录失败"
        :description="error"
        show-icon
        role="alert"
      />
      <AFormItem
        label="账号"
        name="userAccount"
        :rules="[{ required: true, whitespace: true, message: '账号不能为空' }]"
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
          autocomplete="current-password"
          placeholder="请输入密码"
        />
      </AFormItem>
      <AButton
        type="primary"
        shape="round"
        html-type="submit"
        block
        :disabled="false"
        :loading="busy"
      >
        {{ busy ? '登录中' : error ? '重试' : '登录' }}
      </AButton>
    </AForm>
    <template v-if="!busy" #footer>
      还没有账号？
      <RouterLink
        :to="{ path: '/user/register', query: { redirect: destination } }"
        class="auth-link"
        >创建账号</RouterLink
      >
    </template>
  </AuthShell>
</template>

<style scoped>
.submit-error {
  margin-bottom: 24px;
}
.auth-link {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 4px;
}
</style>
