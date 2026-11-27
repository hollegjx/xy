<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  Input as AInput,
  Pagination as APagination,
  Popconfirm as APopconfirm,
  Select as ASelect,
  Skeleton as ASkeleton,
  Table as ATable,
  Tag as ATag,
  message,
} from 'ant-design-vue'
import PageHeading from '@/components/PageHeading.vue'
import AppAdminDetailModal from './components/AppAdminDetailModal.vue'
import AppRenameModal from '@/pages/app/components/AppRenameModal.vue'
import { ApiError, errorMessage } from '@/request'
import { deleteAppForAdmin, listAppsForAdmin, updateAppFeatured } from '@/service/api/app'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { AppAdminQueryRequest, AppAdminVO, CodeGenType } from '@/types/api'
import { generationModes } from './generationModes'

const loginUserStore = useLoginUserStore()
const rows = ref<AppAdminVO[]>([])
const blankFilters = () => ({
  appName: '',
  userId: '',
  id: '',
  deployKey: '',
  codeGenType: undefined as CodeGenType | undefined,
  featured: undefined as 'yes' | 'no' | undefined,
})
const filters = reactive(blankFilters())
const appliedFilters = ref<Omit<AppAdminQueryRequest, 'current' | 'pageSize'>>({})
const moreFilters = ref(false)
const current = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const detailId = ref('')
const editingApp = ref<AppAdminVO | null>(null)
const deleteOpenId = ref('')
const deletingId = ref('')
const deleteError = ref('')
const failedDeleteApp = ref<AppAdminVO | null>(null)
const featuringId = ref('')
const featuredError = ref('')
const failedFeaturedChange = ref<{ app: AppAdminVO; featured: boolean } | null>(null)
const busy = computed(() => loading.value || deletingId.value !== '' || featuringId.value !== '')
const hasFilters = computed(() =>
  Object.values(appliedFilters.value).some((value) => value !== undefined),
)
const draftHasFilters = computed(() => Object.values(filters).some(Boolean))
const modeOptions = Object.entries(generationModes).map(([value, mode]) => ({
  value,
  label: mode.label,
}))
const featuredOptions = [
  { value: 'yes', label: '已精选' },
  { value: 'no', label: '未精选' },
]
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
  deleteOpenId.value = ''
  const params = {
    ...appliedFilters.value,
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
  if (busy.value) return
  deleteError.value = ''
  failedDeleteApp.value = null
  featuredError.value = ''
  failedFeaturedChange.value = null
  appliedFilters.value = {
    appName: filters.appName.trim() || undefined,
    userId: filters.userId.trim() || undefined,
    id: filters.id.trim() || undefined,
    deployKey: filters.deployKey.trim() || undefined,
    codeGenType: filters.codeGenType,
    featured: filters.featured === undefined ? undefined : filters.featured === 'yes',
  }
  current.value = 1
  void load()
}

function reset(): void {
  if (busy.value) return
  Object.assign(filters, blankFilters())
  search()
}

function changePage(page: number): void {
  if (busy.value) return
  deleteError.value = ''
  failedDeleteApp.value = null
  featuredError.value = ''
  failedFeaturedChange.value = null
  current.value = page
  void load()
}

function handleRenamed(): void {
  editingApp.value = null
  void load()
}

function refreshAfterMissingDetail(): void {
  detailId.value = ''
  void load()
}

function changeDeleteOpen(app: AppAdminVO, open: boolean): void {
  if (busy.value || loginUserStore.authAction !== null) return
  deleteOpenId.value = open ? app.id : ''
}

async function removeApp(app: AppAdminVO): Promise<void> {
  if (busy.value || loginUserStore.authAction !== null) return
  deletingId.value = app.id
  deleteError.value = ''
  failedDeleteApp.value = null
  featuredError.value = ''
  failedFeaturedChange.value = null
  try {
    await deleteAppForAdmin(app.id)
    if (!active) return
    void message.success('应用已删除')
    await load()
  } catch (cause) {
    if (!active) return
    deleteError.value = `应用「${app.appName}」（ID：${app.id}）：${errorMessage(cause)}`
    if (cause instanceof ApiError && (cause.code === 40100 || cause.code === 40300)) {
      await loginUserStore.loadUser()
    } else if (cause instanceof ApiError && (cause.code === 40400 || cause.code === 50001)) {
      await load()
    } else {
      failedDeleteApp.value = app
    }
  } finally {
    if (active) {
      deletingId.value = ''
      deleteOpenId.value = ''
    }
  }
}

function retryDelete(): void {
  if (busy.value || loginUserStore.authAction !== null) return
  const target = rows.value.find((app) => app.id === failedDeleteApp.value?.id)
  if (target) changeDeleteOpen(target, true)
  else void load()
}

async function saveFeatured(app: AppAdminVO, featured: boolean): Promise<void> {
  if (busy.value || loginUserStore.authAction !== null) return
  featuringId.value = app.id
  featuredError.value = ''
  failedFeaturedChange.value = null
  deleteOpenId.value = ''
  try {
    await updateAppFeatured(app.id, { featured })
    if (!active) return
    void message.success(featured ? '已设为精选' : '已取消精选')
    await load()
  } catch (cause) {
    if (!active) return
    featuredError.value = `应用「${app.appName}」（ID：${app.id}）：${errorMessage(cause)}`
    if (cause instanceof ApiError && (cause.code === 40100 || cause.code === 40300)) {
      await loginUserStore.loadUser()
    } else if (cause instanceof ApiError && (cause.code === 40400 || cause.code === 50001)) {
      await load()
    } else {
      failedFeaturedChange.value = { app, featured }
    }
  } finally {
    if (active) featuringId.value = ''
  }
}

function retryFeatured(): void {
  if (busy.value || loginUserStore.authAction !== null) return
  const change = failedFeaturedChange.value
  if (change) void saveFeatured(change.app, change.featured)
  else void load()
}

onMounted(load)
onBeforeUnmount(() => {
  active = false
})
</script>

<template>
  <section class="app-list" aria-label="应用管理">
    <PageHeading
      title="应用管理"
      description="查找应用，点击名称查看详情。已发布应用可设为精选。"
    />
    <form class="search-form" aria-label="查询应用" @submit.prevent="search">
      <label class="name-filter">
        应用名称
        <AInput
          v-model:value="filters.appName"
          placeholder="输入应用名称关键词"
          aria-label="应用名称"
          :disabled="busy"
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
          :disabled="busy"
          allow-clear
        />
      </label>
      <AButton
        type="link"
        :aria-expanded="moreFilters"
        aria-controls="app-extra-filters"
        @click="moreFilters = !moreFilters"
      >
        {{ moreFilters ? '收起筛选' : '更多筛选' }}
      </AButton>
      <div v-show="moreFilters" id="app-extra-filters" class="extra-filters">
        <label>
          应用 ID
          <AInput
            v-model:value="filters.id"
            aria-label="应用 ID"
            inputmode="numeric"
            placeholder="输入完整应用 ID"
            :disabled="busy"
            allow-clear
          />
        </label>
        <div>
          <label for="app-filter-mode">生成模式</label>
          <ASelect
            id="app-filter-mode"
            v-model:value="filters.codeGenType"
            :options="modeOptions"
            placeholder="全部模式"
            :disabled="busy"
            allow-clear
          />
        </div>
        <div>
          <label for="app-filter-featured">精选状态</label>
          <ASelect
            id="app-filter-featured"
            v-model:value="filters.featured"
            :options="featuredOptions"
            placeholder="全部状态"
            :disabled="busy"
            allow-clear
          />
        </div>
        <label>
          发布标识
          <AInput
            v-model:value="filters.deployKey"
            aria-label="发布标识"
            placeholder="公开作品网址的最后一段"
            :disabled="busy"
            allow-clear
          />
        </label>
      </div>
      <AButton
        type="primary"
        shape="round"
        html-type="submit"
        :loading="loading"
        :disabled="deletingId !== '' || featuringId !== ''"
        >查询</AButton
      >
      <AButton shape="round" :disabled="busy || (!draftHasFilters && !hasFilters)" @click="reset">
        重置
      </AButton>
    </form>
    <AAlert
      v-if="deleteError"
      class="action-error"
      type="error"
      message="应用删除失败"
      :description="deleteError"
      show-icon
      role="alert"
    >
      <template #action>
        <AButton :disabled="busy || loginUserStore.authAction !== null" @click="retryDelete">
          {{ failedDeleteApp ? '重试' : '刷新列表' }}
        </AButton>
      </template>
    </AAlert>
    <AAlert
      v-if="featuredError"
      class="action-error"
      type="error"
      message="精选设置失败"
      :description="featuredError"
      show-icon
      role="alert"
    >
      <template #action>
        <AButton :disabled="busy || loginUserStore.authAction !== null" @click="retryFeatured">
          {{ failedFeaturedChange ? '重试' : '刷新列表' }}
        </AButton>
      </template>
    </AAlert>
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
    <div v-else class="table-card surface-card">
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
            <button
              type="button"
              class="app-title cell-text"
              :aria-label="`查看「${text}」详情`"
              :disabled="busy || loginUserStore.authAction !== null"
              @click="detailId = record.id"
            >
              {{ text }}
            </button>
            <span class="muted cell-text">ID：{{ record.id }}</span>
            <span v-if="record.featured"><ATag>精选</ATag></span>
          </div>
          <ATag v-else-if="column.key === 'codeGenType'">
            {{ generationModes[(record as AppAdminVO).codeGenType].label }}
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
              :disabled="busy || loginUserStore.authAction !== null"
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
            <AButton
              v-if="record.deployment || record.featured"
              class="action-link"
              type="link"
              :loading="featuringId === record.id"
              :disabled="busy || loginUserStore.authAction !== null"
              @click="saveFeatured(record as AppAdminVO, !record.featured)"
            >
              {{ featuringId === record.id ? '保存中' : record.featured ? '取消精选' : '设为精选' }}
            </AButton>
            <APopconfirm
              :open="deleteOpenId === record.id"
              title="确认删除这个应用？"
              placement="bottomRight"
              :ok-text="deletingId === record.id ? '删除中' : '确认删除'"
              cancel-text="取消"
              :ok-button-props="{
                danger: true,
                loading: deletingId === record.id,
                disabled: loginUserStore.authAction !== null,
              }"
              :cancel-button-props="{ disabled: deletingId !== '' }"
              :disabled="busy || loginUserStore.authAction !== null"
              @open-change="(open: boolean) => changeDeleteOpen(record as AppAdminVO, open)"
              @confirm="removeApp(record as AppAdminVO)"
            >
              <template #description>
                <p class="delete-confirmation">
                  将删除应用「{{ record.appName }}」（ID：{{
                    record.id
                  }}），其工作台将无法访问，已发布作品将下线。
                </p>
              </template>
              <AButton
                class="action-link"
                type="link"
                danger
                :loading="deletingId === record.id"
                :disabled="busy || loginUserStore.authAction !== null"
              >
                删除
              </AButton>
            </APopconfirm>
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
          :disabled="busy"
          hide-on-single-page
          simple
          aria-label="应用分页"
          @change="changePage"
        />
      </div>
    </div>
    <AppAdminDetailModal
      v-if="detailId"
      :key="detailId"
      :app-id="detailId"
      @close="detailId = ''"
      @missing="refreshAfterMissingDetail"
    />
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
.owner-filter,
.extra-filters > * {
  display: grid;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
}
.table-card {
  overflow: hidden;
  padding: 4px 8px 0;
}
.name-filter {
  width: min(100%, 320px);
}
.owner-filter {
  width: min(100%, 240px);
}
.extra-filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr));
  gap: 12px;
  width: 100%;
}
.app-summary {
  display: grid;
  gap: 4px;
}
.cell-text {
  overflow-wrap: anywhere;
}
.app-title {
  justify-self: start;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-weight: 500;
  text-align: start;
  cursor: pointer;
}
.app-title:hover:not(:disabled) {
  color: var(--link);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.row-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}
.action-link {
  height: auto;
  padding: 2px 0;
}
.delete-confirmation {
  width: min(280px, calc(100vw - 96px));
  margin: 0;
  overflow-wrap: anywhere;
}
.action-error {
  margin-bottom: 16px;
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
  padding: 14px 8px 16px;
  border-top: 1px solid var(--border);
}
@media (max-width: 600px) {
  .name-filter,
  .owner-filter {
    width: 100%;
  }
}
</style>
