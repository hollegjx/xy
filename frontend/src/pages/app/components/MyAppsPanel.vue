<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  Input as AInput,
  Pagination as APagination,
  Skeleton as ASkeleton,
} from 'ant-design-vue'
import { ApiError, errorMessage } from '@/request'
import { listMyApps } from '@/service/api/app'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { AppVO } from '@/types/api'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const rows = ref<AppVO[]>([])
const keyword = ref('')
const appliedName = ref('')
const current = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const pageSize = 10
let revision = 0

async function load(): Promise<void> {
  const requestRevision = ++revision
  loading.value = true
  error.value = ''
  try {
    const result = await listMyApps({
      appName: appliedName.value,
      current: current.value,
      pageSize,
    })
    if (requestRevision !== revision) return
    const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
    if (current.value > lastPage) {
      current.value = lastPage
      await load()
      return
    }
    rows.value = result.records
    total.value = result.total
  } catch (cause) {
    if (requestRevision !== revision) return
    if (cause instanceof ApiError && cause.code === 40100) {
      loginUserStore.setUser(null)
      await router.replace({ path: '/user/login', query: { redirect: '/' } })
    } else {
      error.value = errorMessage(cause)
    }
  } finally {
    if (requestRevision === revision) loading.value = false
  }
}

function search(): void {
  appliedName.value = keyword.value.trim()
  current.value = 1
  void load()
}

function reset(): void {
  keyword.value = ''
  search()
}

function changePage(page: number): void {
  current.value = page
  void load()
}

onMounted(load)
onBeforeUnmount(() => {
  revision += 1
})
</script>

<template>
  <section class="my-apps" aria-labelledby="my-apps-title">
    <div class="list-heading">
      <div>
        <h2 id="my-apps-title">我的应用</h2>
        <p class="muted">继续修改已有应用，或查看已发布作品。</p>
      </div>
      <form class="search-form" aria-label="查询我的应用" @submit.prevent="search">
        <AInput
          v-model:value="keyword"
          placeholder="按应用名称查询"
          aria-label="应用名称"
          allow-clear
        />
        <AButton html-type="submit" :loading="loading">查询</AButton>
        <AButton :disabled="loading || (!keyword && !appliedName)" @click="reset">重置</AButton>
      </form>
    </div>
    <ASkeleton v-if="loading" active :paragraph="{ rows: 4 }" aria-label="正在读取我的应用" />
    <AAlert
      v-else-if="error"
      type="error"
      message="应用列表读取失败"
      :description="error"
      show-icon
      role="alert"
    >
      <template #action><AButton @click="load">重试</AButton></template>
    </AAlert>
    <AEmpty
      v-else-if="rows.length === 0"
      :image="AEmpty.PRESENTED_IMAGE_SIMPLE"
      :description="
        appliedName ? '没有找到匹配的应用，试试其他名称。' : '还没有应用，从上方的一个想法开始。'
      "
    >
      <AButton v-if="appliedName" @click="reset">清除筛选</AButton>
      <AButton v-else href="#create-app">开始创建</AButton>
    </AEmpty>
    <template v-else>
      <ul class="app-grid">
        <li v-for="app in rows" :key="app.id" class="app-card">
          <h3>
            <RouterLink :to="{ name: 'app-workbench', params: { id: app.id } }" class="app-name">
              {{ app.appName }}
            </RouterLink>
          </h3>
          <p class="app-description">{{ app.initPrompt }}</p>
          <div class="app-meta muted">
            <span>{{ app.codeGenType === 'html' ? '单 HTML' : '多文件' }}</span>
            <span>创建于 {{ app.createTime.slice(0, 10) }}</span>
          </div>
          <div v-if="app.deployment?.deployUrl" class="published-action">
            <AButton :href="app.deployment.deployUrl" target="_blank" rel="noopener noreferrer">
              查看作品
            </AButton>
          </div>
        </li>
      </ul>
      <div class="list-footer">
        <span class="muted">共 {{ total }} 个应用</span>
        <APagination
          :current="current"
          :page-size="pageSize"
          :total="total"
          :show-size-changer="false"
          hide-on-single-page
          simple
          aria-label="应用分页"
          @change="changePage"
        />
      </div>
    </template>
  </section>
</template>

<style scoped>
.my-apps {
  margin-top: 48px;
  padding-top: 32px;
  border-top: 1px solid var(--border);
}
.list-heading,
.list-footer,
.app-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.list-heading {
  margin-bottom: 24px;
}
h2,
h3 {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 600;
}
.muted {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}
.search-form {
  display: flex;
  gap: 8px;
  width: min(100%, 400px);
}
.app-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.app-card {
  min-width: 0;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
.app-name {
  color: var(--accent);
  overflow-wrap: anywhere;
}
.app-description {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  margin: 0 0 16px;
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.app-meta {
  gap: 8px;
}
.published-action,
.list-footer {
  margin-top: 16px;
}
@media (max-width: 900px) {
  .app-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .app-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
