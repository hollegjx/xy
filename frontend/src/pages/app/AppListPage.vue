<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  Input as AInput,
  Pagination as APagination,
  Skeleton as ASkeleton,
  Table as ATable,
  Tag as ATag,
} from 'ant-design-vue'
import PageHeading from '@/components/PageHeading.vue'
import AppRenameModal from '@/pages/app/components/AppRenameModal.vue'
import { ApiError, errorMessage } from '@/request'
import { listAppsForAdmin } from '@/service/api/app'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { AppAdminVO } from '@/types/api'

const loginUserStore = useLoginUserStore()
const rows = ref<AppAdminVO[]>([])
const filters = reactive({ appName: '', userId: '' })
const appliedFilters = ref({ appName: '', userId: '' })
const current = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const editingApp = ref<AppAdminVO | null>(null)
const hasFilters = computed(() =>
  Boolean(appliedFilters.value.appName || appliedFilters.value.userId),
)
const pageSize = 10
const columns = [
  { title: '应用', dataIndex: 'appName', key: 'appName', width: 220 },
  { title: '创建者 ID', dataIndex: 'userId', key: 'userId', width: 180 },
  { title: '生成模式', dataIndex: 'codeGenType', key: 'codeGenType', width: 110 },
  { title: '发布状态', key: 'deployment', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '更新时间', dataIndex: 'updateTime', key: 'updateTime', width: 170 },
  { title: '操作', key: 'actions', width: 120, fixed: 'right' as const },
]
let active = true

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  const params = {
    appName: appliedFilters.value.appName,
    userId: appliedFilters.value.userId || undefined,
    current: current.value,
    pageSize,
  }
  try {
    let result = await listAppsForAdmin(params)
    if (!active) return
    const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
    if (current.value > lastPage) {
      current.value = lastPage
      result = await listAppsForAdmin({ ...params, current: lastPage })
      if (!active) return
    }
    rows.value = result.records
    total.value = result.total
  } catch (cause) {
    if (!active) return
    error.value = errorMessage(cause)
    if (cause instanceof ApiError && (cause.code === 40100 || cause.code === 40300)) {
      await loginUserStore.loadUser()
    }
  } finally {
    if (active) loading.value = false
  }
}

function search(): void {
  if (loading.value) return
  appliedFilters.value = { appName: filters.appName.trim(), userId: filters.userId.trim() }
  current.value = 1
  void load()
}

function reset(): void {
  if (loading.value) return
  filters.appName = ''
  filters.userId = ''
  search()
}

function changePage(page: number): void {
  if (loading.value) return
  current.value = page
  void load()
}

function handleRenamed(): void {
  editingApp.value = null
  void load()
}

onMounted(load)
onBeforeUnmount(() => {
  active = false
})
</script>

<template>
  <section class="app-list" aria-label="应用管理">
    <PageHeading title="应用管理" description="按名称和创建者查找应用，维护名称并查看公开作品。" />
    <form class="search-form" aria-label="查询应用" @submit.prevent="search">
      <label class="name-filter">
        应用名称
        <AInput
          v-model:value="filters.appName"
          placeholder="输入应用名称关键词"
          aria-label="应用名称"
          :disabled="loading"
          allow-clear
        />
      </label>
      <label class="owner-filter">
        创建者 ID
        <AInput
          v-model:value="filters.userId"
          placeholder="输入完整创建者 ID"
          aria-label="创建者 ID"
          inputmode="numeric"
          :disabled="loading"
          allow-clear
        />
      </label>
      <AButton type="primary" shape="round" html-type="submit" :loading="loading">查询</AButton>
      <AButton
        :disabled="loading || (!filters.appName && !filters.userId && !hasFilters)"
        @click="reset"
      >
        重置
      </AButton>
    </form>
    <ASkeleton v-if="loading" active :paragraph="{ rows: 5 }" aria-label="正在读取应用列表" />
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
      :description="hasFilters ? '没有找到匹配的应用，试试其他筛选条件。' : '暂无应用记录。'"
    >
      <AButton v-if="hasFilters" @click="reset">清除筛选</AButton>
      <AButton v-else @click="load">重新查询</AButton>
    </AEmpty>
    <template v-else>
      <ATable
        :columns="columns"
        :data-source="rows"
        :pagination="false"
        :scroll="{ x: 1070 }"
        row-key="id"
        table-layout="fixed"
      >
        <template #bodyCell="{ column, text, record }">
          <div v-if="column.key === 'appName'" class="app-summary">
            <span class="cell-text">{{ text }}</span>
            <span class="muted cell-text">ID：{{ record.id }}</span>
          </div>
          <ATag v-else-if="column.key === 'codeGenType'">
            {{ text === 'html' ? '单 HTML' : '多文件' }}
          </ATag>
          <ATag
            v-else-if="column.key === 'deployment'"
            :color="record.deployment ? 'success' : undefined"
          >
            {{ record.deployment ? '已发布' : '未发布' }}
          </ATag>
          <time
            v-else-if="column.key === 'createTime' || column.key === 'updateTime'"
            :datetime="text"
          >
            {{ text.replace('T', ' ') }}
          </time>
          <div v-else-if="column.key === 'actions'" class="row-actions">
            <AButton
              class="action-link"
              type="link"
              :disabled="loading || loginUserStore.authAction !== null"
              @click="editingApp = record as AppAdminVO"
            >
              修改名称
            </AButton>
            <AButton
              v-if="record.deployment?.deployUrl"
              class="action-link"
              type="link"
              :href="record.deployment.deployUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              查看作品
            </AButton>
            <span v-else class="muted">{{ record.deployment ? '暂无公开地址' : '—' }}</span>
          </div>
          <span v-else class="cell-text">{{ text }}</span>
        </template>
      </ATable>
      <div class="list-footer">
        <span class="muted">共 {{ total }} 个应用</span>
        <APagination
          :current="current"
          :page-size="pageSize"
          :total="total"
          :show-size-changer="false"
          :disabled="loading"
          hide-on-single-page
          simple
          aria-label="应用分页"
          @change="changePage"
        />
      </div>
    </template>
    <AppRenameModal
      v-if="editingApp"
      :open="true"
      :app="editingApp"
      mode="admin"
      @close="editingApp = null"
      @saved="handleRenamed"
    />
  </section>
</template>

<style scoped>
.app-list {
  min-width: 0;
}
.search-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 8px;
  margin-bottom: 24px;
}
.name-filter,
.owner-filter {
  display: grid;
  gap: 8px;
}
.name-filter {
  width: min(100%, 320px);
}
.owner-filter {
  width: min(100%, 240px);
}
.app-summary {
  display: grid;
  gap: 4px;
}
.cell-text {
  overflow-wrap: anywhere;
}
.row-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}
.action-link {
  padding-inline: 0;
}
.muted {
  color: var(--muted);
  font-size: 12px;
}
.list-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-top: 16px;
}
@media (max-width: 600px) {
  .name-filter,
  .owner-filter {
    width: 100%;
  }
}
</style>
