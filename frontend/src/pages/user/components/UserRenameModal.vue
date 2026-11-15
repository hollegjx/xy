<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  Modal as AModal,
  message,
} from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import { ApiError, errorMessage } from '@/request'
import { updateUser } from '@/service/api/user'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { UserUpdateRequest, UserVO } from '@/types/api'

const props = defineProps<{ open: boolean; user: UserVO }>()
const emit = defineEmits<{ close: []; saved: [id: string, userName: string] }>()
const loginUserStore = useLoginUserStore()
const formRef = ref<FormInstance>()
const form = reactive<UserUpdateRequest>({ userName: '' })
const busy = ref(false)
const error = ref('')
let active = true

async function validateNameLength(): Promise<void> {
  if (Array.from(form.userName).length > 128) throw new Error('昵称不能超过 128 个字符')
}

async function save(): Promise<void> {
  if (busy.value || loginUserStore.authAction !== null) return
  busy.value = true
  error.value = ''
  const id = props.user.id
  const data = { ...form }
  try {
    await updateUser(id, data)
    if (!active) return
    emit('saved', id, data.userName)
    void message.success('昵称已更新')
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
    if (!open) return
    form.userName = props.user.userName
    error.value = ''
    formRef.value?.clearValidate()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  active = false
})
</script>

<template>
  <AModal
    :open="open"
    title="修改昵称"
    :width="480"
    :footer="null"
    :closable="!busy"
    :keyboard="!busy"
    :mask-closable="!busy"
    @cancel="emit('close')"
  >
    <p class="account-info">登录账号：{{ user.userAccount }}</p>
    <AForm
      ref="formRef"
      name="rename-user"
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
        message="昵称保存失败"
        :description="error"
        show-icon
        role="alert"
      />
      <AFormItem
        label="昵称"
        name="userName"
        extra="最多 128 个字符"
        validate-first
        :rules="[
          { required: true, whitespace: true, message: '昵称不能为空' },
          { validator: validateNameLength },
        ]"
      >
        <AInput v-model:value="form.userName" placeholder="请输入昵称" />
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
.account-info {
  margin: 0 0 24px;
  overflow-wrap: anywhere;
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
