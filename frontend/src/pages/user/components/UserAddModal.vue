<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  InputPassword as AInputPassword,
  Modal as AModal,
  message,
} from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import { ApiError, errorMessage } from '@/request'
import { addUser } from '@/service/api/user'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { UserAddRequest } from '@/types/api'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const loginUserStore = useLoginUserStore()
const formRef = ref<FormInstance>()
const form = reactive<UserAddRequest>({ userAccount: '', userPassword: '', userName: '' })
const busy = ref(false)
const error = ref('')
let active = true

async function validateLength(_rule: unknown, value?: string): Promise<void> {
  if (Array.from(value ?? '').length > 128) throw new Error('最多填写 128 个字符')
}

async function save(): Promise<void> {
  if (busy.value || loginUserStore.authAction !== null) return
  busy.value = true
  error.value = ''
  try {
    await addUser({ ...form })
    if (!active) return
    form.userPassword = ''
    emit('saved')
    void message.success('用户已新增')
  } catch (cause) {
    if (!active) return
    error.value = errorMessage(cause)
    if (cause instanceof ApiError && (cause.code === 40100 || cause.code === 40300)) {
      await loginUserStore.loadUser()
    }
  } finally {
    if (active) busy.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    form.userPassword = ''
    if (!open) return
    form.userAccount = ''
    form.userName = ''
    error.value = ''
    formRef.value?.clearValidate()
  },
)

onBeforeUnmount(() => {
  active = false
})
</script>

<template>
  <AModal
    :open="open"
    title="新增用户"
    :width="480"
    :footer="null"
    :closable="!busy"
    :keyboard="!busy"
    :mask-closable="!busy"
    @cancel="emit('close')"
  >
    <p class="description">新增账号为普通用户，昵称留空时使用账号。</p>
    <AForm
      ref="formRef"
      name="add-user"
      :model="form"
      layout="vertical"
      :disabled="busy || loginUserStore.authAction !== null"
      :aria-busy="busy"
      validate-trigger="blur"
      @finish="save"
    >
      <AAlert
        v-if="error"
        class="submit-error"
        type="error"
        message="新增用户失败"
        :description="error"
        show-icon
        role="alert"
      />
      <AFormItem
        label="账号"
        name="userAccount"
        validate-first
        :rules="[
          { required: true, whitespace: true, message: '账号不能为空' },
          { validator: validateLength },
        ]"
      >
        <AInput
          v-model:value="form.userAccount"
          autocomplete="off"
          autocapitalize="none"
          :spellcheck="false"
          placeholder="请输入登录账号"
        />
      </AFormItem>
      <AFormItem
        label="初始密码"
        name="userPassword"
        :rules="[{ required: true, whitespace: true, message: '初始密码不能为空' }]"
      >
        <AInputPassword
          v-model:value="form.userPassword"
          autocomplete="new-password"
          placeholder="请输入初始密码"
        />
      </AFormItem>
      <AFormItem label="昵称" name="userName" :rules="[{ validator: validateLength }]">
        <AInput v-model:value="form.userName" placeholder="可选，留空时使用账号" />
      </AFormItem>
      <div class="form-actions">
        <AButton :disabled="busy" @click="emit('close')">取消</AButton>
        <AButton
          type="primary"
          shape="round"
          html-type="submit"
          :loading="busy"
          :disabled="loginUserStore.authAction !== null"
        >
          {{ busy ? '保存中' : error ? '重试' : '保存' }}
        </AButton>
      </div>
    </AForm>
  </AModal>
</template>

<style scoped>
.description {
  margin: 0 0 24px;
  color: var(--muted);
  font-size: 14px;
}
.submit-error {
  margin-bottom: 24px;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
