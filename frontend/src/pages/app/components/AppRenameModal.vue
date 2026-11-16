<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
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
import { updateApp, updateAppForAdmin } from '@/service/api/app'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { AppUpdateRequest, AppVO } from '@/types/api'

const props = defineProps<{
  open: boolean
  app: Pick<AppVO, 'id' | 'appName'>
  mode?: 'owner' | 'admin'
}>()
const emit = defineEmits<{ close: []; saved: [appName: string] }>()
const loginUserStore = useLoginUserStore()
const formRef = ref<FormInstance>()
const form = reactive<AppUpdateRequest>({ appName: '' })
const busy = ref(false)
const error = ref('')

async function validateNameLength(): Promise<void> {
  if (Array.from(form.appName).length > 128) {
    throw new Error('应用名称不能超过 128 个字符')
  }
}

async function save(): Promise<void> {
  if (busy.value || loginUserStore.authAction !== null) return
  busy.value = true
  error.value = ''
  const data = { ...form }
  try {
    const update = props.mode === 'admin' ? updateAppForAdmin : updateApp
    await update(props.app.id, data)
    emit('saved', data.appName)
    void message.success('名称已更新')
  } catch (cause) {
    error.value = errorMessage(cause)
    if (cause instanceof ApiError) {
      if (props.mode === 'admin' && (cause.code === 40100 || cause.code === 40300)) {
        await loginUserStore.loadUser()
      } else if (cause.code === 40100) loginUserStore.setUser(null)
    }
  } finally {
    busy.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return
    form.appName = props.app.appName
    error.value = ''
    formRef.value?.clearValidate()
  },
  { immediate: true },
)
</script>

<template>
  <AModal
    :open="open"
    title="修改名称"
    :width="480"
    :footer="null"
    :closable="!busy"
    :keyboard="!busy"
    :mask-closable="!busy"
    @cancel="emit('close')"
  >
    <p v-if="mode === 'admin'" class="app-info">应用 ID：{{ app.id }}</p>
    <AForm
      ref="formRef"
      name="rename-app"
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
        message="名称保存失败"
        :description="error"
        show-icon
        role="alert"
      />
      <AFormItem
        label="应用名称"
        name="appName"
        extra="最多 128 个字符"
        validate-first
        :rules="[
          { required: true, whitespace: true, message: '应用名称不能为空' },
          { validator: validateNameLength },
        ]"
      >
        <AInput v-model:value="form.appName" placeholder="请输入应用名称" />
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
.app-info {
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
