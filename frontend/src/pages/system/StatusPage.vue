<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  Alert as AAlert,
  Badge as ABadge,
  Button as AButton,
  Card as ACard,
  Empty as AEmpty,
  Skeleton as ASkeleton,
} from 'ant-design-vue'
import { getHealth } from '@/service/api/health'
import { errorMessage } from '@/request'
import type { HealthStatus } from '@/types/api'
import PageHeading from '@/components/PageHeading.vue'

const health = ref<HealthStatus | null>(null)
const loading = ref(false)
const error = ref('')
const checkedAt = ref('')

async function load() {
  if (loading.value) return
  loading.value = true
  health.value = null
  error.value = ''
  checkedAt.value = ''
  try {
    health.value = await getHealth()
    checkedAt.value = new Date().toLocaleTimeString('zh-CN')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="status-heading">
    <PageHeading title="连接检查" description="查看平台服务与数据库的连接状态。" />
    <AButton href="/api/doc.html" target="_blank" rel="noopener noreferrer">接口文档</AButton>
  </div>

  <ACard title="服务连接">
    <div class="status-content" aria-live="polite" :aria-busy="loading">
      <ASkeleton v-if="loading" active :paragraph="{ rows: 3 }" />
      <AAlert
        v-else-if="error"
        type="error"
        message="连接检查失败"
        :description="error"
        show-icon
      />
      <template v-else-if="health">
        <AAlert type="success" message="后端与数据库连接正常" show-icon />
        <dl class="connection-details">
          <div>
            <dt>服务状态</dt>
            <dd><ABadge status="success" text="运行正常" /></dd>
          </div>
          <div>
            <dt>当前数据库</dt>
            <dd>{{ health.database }}</dd>
          </div>
        </dl>
      </template>
      <AEmpty v-else description="尚无检查结果，点击下方按钮重新检查。" />
    </div>
    <div class="check-actions">
      <span class="description">{{
        loading ? '正在检查连接…' : checkedAt ? `检查时间 ${checkedAt}` : '等待检查成功'
      }}</span>
      <AButton type="primary" shape="round" :loading="loading" @click="load">{{
        error ? '重试' : '重新检查'
      }}</AButton>
    </div>
  </ACard>
</template>

<style scoped>
.status-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}
.description {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
}
.status-content {
  min-height: 176px;
}
.connection-details {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  margin: 24px 0;
}
dt {
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 12px;
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.check-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 24px;
  border-top: 1px solid var(--border);
}
@media (max-width: 600px) {
  .status-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 0;
    margin-bottom: 24px;
  }
  .connection-details {
    gap: 16px;
  }
}
</style>
