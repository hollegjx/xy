<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  Empty as AEmpty,
  Modal as AModal,
  Skeleton as ASkeleton,
} from 'ant-design-vue'
import { ApiError, errorMessage } from '@/request'
import { getAppForAdmin } from '@/service/api/app'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { AppAdminVO } from '@/types/api'
import { generationModes } from '../generationModes'

const props = defineProps<{ appId: string }>()
const emit = defineEmits<{ close: []; missing: [] }>()
const loginUserStore = useLoginUserStore()
const detail = ref<AppAdminVO | null>(null)
const error = ref('')
const missing = ref(false)
const loading = ref(true)
const heading = useTemplateRef('heading')
const opener = globalThis.document.activeElement
let active = true

watch(heading, (element) => element?.focus({ preventScroll: true }), { flush: 'post' })

async function load(): Promise<void> {
  // 重试按钮会随加载状态消失，先把键盘焦点留在弹窗内。
  heading.value?.focus({ preventScroll: true })
  loading.value = true
  error.value = ''
  try {
    const result = await getAppForAdmin(props.appId)
    if (active) detail.value = result
  } catch (cause) {
    if (!active) return
    if (cause instanceof ApiError && cause.code === 40400) {
      missing.value = true
    } else {
      error.value = errorMessage(cause)
      if (cause instanceof ApiError && (cause.code === 40100 || cause.code === 40300)) {
        await loginUserStore.loadUser()
      }
    }
  } finally {
    if (active) loading.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => {
  active = false
  if (opener instanceof globalThis.HTMLElement && opener.isConnected)
    opener.focus({ preventScroll: true })
})
</script>

<template>
  <AModal open :footer="null" :width="520" @cancel="emit('close')">
    <template #title><span ref="heading" tabindex="-1">应用详情</span></template>
    <ASkeleton v-if="loading" active :paragraph="{ rows: 4 }" aria-label="正在读取应用详情" />
    <AEmpty
      v-else-if="missing"
      :image="AEmpty.PRESENTED_IMAGE_SIMPLE"
      description="应用不存在或已删除。"
    >
      <AButton @click="emit('missing')">刷新列表</AButton>
    </AEmpty>
    <AAlert
      v-else-if="error"
      type="error"
      message="详情读取失败"
      :description="error"
      show-icon
      role="alert"
    >
      <template #action><AButton @click="load">重试</AButton></template>
    </AAlert>
    <ADescriptions v-else-if="detail" :column="1" size="small" class="detail">
      <ADescriptionsItem label="应用名称">{{ detail.appName }}</ADescriptionsItem>
      <ADescriptionsItem label="应用 ID">{{ detail.id }}</ADescriptionsItem>
      <ADescriptionsItem label="创建者 ID">{{ detail.userId }}</ADescriptionsItem>
      <ADescriptionsItem label="生成模式">{{
        generationModes[detail.codeGenType].label
      }}</ADescriptionsItem>
      <ADescriptionsItem label="精选状态">{{
        detail.featured ? '已精选' : '未精选'
      }}</ADescriptionsItem>
      <ADescriptionsItem label="创建时间">{{
        detail.createTime.replace('T', ' ')
      }}</ADescriptionsItem>
      <ADescriptionsItem label="更新时间">{{
        detail.updateTime.replace('T', ' ')
      }}</ADescriptionsItem>
      <ADescriptionsItem label="发布状态">{{
        detail.deployment ? '已发布' : '未发布'
      }}</ADescriptionsItem>
      <template v-if="detail.deployment">
        <ADescriptionsItem label="发布版本">{{
          detail.deployment.publishedVersion
        }}</ADescriptionsItem>
        <ADescriptionsItem label="发布时间">{{
          detail.deployment.deployedAt.replace('T', ' ')
        }}</ADescriptionsItem>
        <ADescriptionsItem label="公开作品">
          <a
            v-if="detail.deployment.deployUrl"
            :href="detail.deployment.deployUrl"
            target="_blank"
            rel="noopener noreferrer"
            >查看作品</a
          >
          <span v-else>暂无公开地址</span>
        </ADescriptionsItem>
      </template>
    </ADescriptions>
  </AModal>
</template>

<style scoped>
.detail {
  overflow-wrap: anywhere;
}
</style>
