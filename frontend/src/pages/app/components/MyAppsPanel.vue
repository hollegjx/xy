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
import { generationModes } from '../generationModes'

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
      <ul class="app-grid stagger">
        <li v-for="app in rows" :key="app.id" class="app-card lift">
          <div class="app-card-top">
            <span class="app-icon" :class="`mode-${app.codeGenType}`" aria-hidden="true">{{
              Array.from(app.appName)[0]
            }}</span>
            <span class="app-mode">{{ generationModes[app.codeGenType].label }}</span>
          </div>
          <h3>
            <RouterLink :to="{ name: 'app-workbench', params: { id: app.id } }" class="app-name">
              {{ app.appName }}
            </RouterLink>
          </h3>
          <p class="app-description">{{ app.initPrompt }}</p>
          <div class="app-meta muted">
            <span>创建于 {{ app.createTime.slice(0, 10) }}</span>
            <AButton
              v-if="app.deployment?.deployUrl"
              class="published-action"
              type="link"
              size="small"
              :href="app.deployment.deployUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              查看作品
            </AButton>
            <span v-else class="draft-badge">未发布</span>
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
  margin-top: 72px;
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
  align-items: flex-end;
  margin-bottom: 24px;
}
h2 {
  margin: 0 0 6px;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: var(--heading-tracking);
}
h3 {
  margin: 0 0 6px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
}
.muted {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}
.search-form {
  display: flex;
  gap: 8px;
  width: min(100%, 400px);
}
.search-form :deep(.ant-input-affix-wrapper) {
  border-radius: 999px;
  background: var(--surface);
  box-shadow: 0 0 0 1px var(--hairline);
}
.search-form :deep(.ant-btn) {
  border-radius: 999px;
}
.app-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.app-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 22px 22px 18px;
  border-radius: var(--radius-md);
  background: var(--surface);
  box-shadow: var(--shadow-card);
}
.app-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.app-icon {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border-radius: 12px;
  color: #ffffff;
  font-size: 18px;
  font-weight: 600;
  transition: transform 520ms var(--ease-spring);
}
.app-card:hover .app-icon {
  transform: rotate(-6deg) scale(1.06);
}
/* 生成模式用黑、深灰、浅灰三种底色区分，不用彩色 */
.mode-multi_file {
  background: #1d1d1f;
}
.mode-html {
  background: #636366;
}
.mode-vue_project {
  color: var(--ink);
  background: var(--surface-soft);
  box-shadow: inset 0 0 0 1.5px var(--ink);
}
.app-mode {
  padding: 3px 10px;
  border-radius: 999px;
  color: var(--muted);
  background: var(--fill);
  font-size: 12px;
}
.app-name {
  color: var(--ink);
  text-decoration: none;
  overflow-wrap: anywhere;
}
.app-name:hover {
  color: var(--ink);
}
/* 整张卡片可点：标题链接的点击区扩展到卡片，「查看作品」按钮仍在上层可单独点击 */
.app-name::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
}
.published-action {
  position: relative;
  z-index: 1;
}
.app-description {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  flex: 1;
  margin: 0 0 16px;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.app-meta {
  gap: 8px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}
.published-action {
  padding: 0;
  height: auto;
  font-size: 13px;
}
.draft-badge {
  color: var(--subtle);
  font-size: 12px;
}
.list-footer {
  margin-top: 20px;
}
@media (max-width: 900px) {
  .app-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .my-apps {
    margin-top: 48px;
  }
  h2 {
    font-size: 24px;
  }
  .app-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
