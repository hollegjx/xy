<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
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
import UserAddModal from '@/pages/user/components/UserAddModal.vue'
import { ApiError, errorMessage } from '@/request'
import { listUsers } from '@/service/api/user'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { UserVO } from '@/types/api'

const loginUserStore = useLoginUserStore()
const rows = ref<UserVO[]>([])
const keyword = ref('')
const appliedAccount = ref('')
const current = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const addOpen = ref(false)
const pageSize = 10
const columns = [
  { title: '用户 ID', dataIndex: 'id', key: 'id', width: 180 },
  { title: '登录账号', dataIndex: 'userAccount', key: 'userAccount', width: 220 },
  { title: '昵称', dataIndex: 'userName', key: 'userName', width: 220 },
  { title: '角色', dataIndex: 'userRole', key: 'userRole', width: 120 },
]
let active = true

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await listUsers({
      userAccount: appliedAccount.value,
      current: current.value,
      pageSize,
    })
    if (!active) return
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
  appliedAccount.value = keyword.value.trim()
  current.value = 1
  void load()
}

function reset(): void {
  keyword.value = ''
  search()
}

function handleAdded(): void {
  addOpen.value = false
  reset()
}

function changePage(page: number): void {
  if (loading.value) return
  current.value = page
  void load()
}

onMounted(load)
onBeforeUnmount(() => {
  active = false
})
</script>

<template>
  <section class="user-list" aria-label="用户管理">
    <PageHeading title="用户管理" description="按账号查找用户，查看昵称与角色。" />
    <form class="search-form" aria-label="查询用户" @submit.prevent="search">
      <label class="account-filter">
        登录账号
        <AInput
          v-model:value="keyword"
          placeholder="输入账号关键词"
          aria-label="登录账号"
          :disabled="loading"
          allow-clear
        />
      </label>
      <AButton html-type="submit" :loading="loading">查询</AButton>
      <AButton :disabled="loading || (!keyword && !appliedAccount)" @click="reset">重置</AButton>
      <AButton
        type="primary"
        shape="round"
        class="add-user-button"
        :disabled="loading"
        @click="addOpen = true"
      >
        新增用户
      </AButton>
    </form>
    <ASkeleton v-if="loading" active :paragraph="{ rows: 5 }" aria-label="正在读取用户列表" />
    <AAlert
      v-else-if="error"
      type="error"
      message="用户列表读取失败"
      :description="error"
      show-icon
      role="alert"
    >
      <template #action><AButton @click="load">重试</AButton></template>
    </AAlert>
    <AEmpty
      v-else-if="rows.length === 0"
      :image="AEmpty.PRESENTED_IMAGE_SIMPLE"
      :description="appliedAccount ? '没有找到匹配的用户，试试其他账号。' : '暂无用户记录。'"
    >
      <AButton v-if="appliedAccount" @click="reset">清除筛选</AButton>
      <AButton v-else @click="reset">重新查询</AButton>
    </AEmpty>
    <template v-else>
      <ATable
        :columns="columns"
        :data-source="rows"
        :pagination="false"
        :scroll="{ x: 740 }"
        row-key="id"
        table-layout="fixed"
      >
        <template #bodyCell="{ column, text }">
          <ATag v-if="column.key === 'userRole'">{{
            text === 'admin' ? '管理员' : '普通用户'
          }}</ATag>
          <span v-else class="cell-text">{{ text }}</span>
        </template>
      </ATable>
      <div class="list-footer">
        <span class="muted">共 {{ total }} 个用户</span>
        <APagination
          :current="current"
          :page-size="pageSize"
          :total="total"
          :show-size-changer="false"
          hide-on-single-page
          simple
          aria-label="用户分页"
          @change="changePage"
        />
      </div>
    </template>
    <UserAddModal :open="addOpen" @close="addOpen = false" @saved="handleAdded" />
  </section>
</template>

<style scoped>
.user-list {
  min-width: 0;
}
.search-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 8px;
  margin-bottom: 24px;
}
.account-filter {
  display: grid;
  gap: 8px;
  width: min(100%, 360px);
}
.add-user-button {
  margin-left: auto;
}
.cell-text {
  overflow-wrap: anywhere;
}
.list-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-top: 16px;
}
.muted {
  color: var(--muted);
  font-size: 12px;
}
@media (max-width: 600px) {
  .account-filter {
    width: 100%;
  }
}
</style>
