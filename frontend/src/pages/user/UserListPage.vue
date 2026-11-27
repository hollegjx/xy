<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  Input as AInput,
  Pagination as APagination,
  Popconfirm as APopconfirm,
  Skeleton as ASkeleton,
  Table as ATable,
  Tag as ATag,
  message,
} from 'ant-design-vue'
import PageHeading from '@/components/PageHeading.vue'
import UserAddModal from '@/pages/user/components/UserAddModal.vue'
import UserRenameModal from '@/pages/user/components/UserRenameModal.vue'
import { ApiError, errorMessage } from '@/request'
import { deleteUser, listUsers } from '@/service/api/user'
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
const editingUser = ref<UserVO | null>(null)
const deleteOpenId = ref('')
const deletingId = ref('')
const deleteError = ref('')
const failedDeleteUser = ref<UserVO | null>(null)
const busy = computed(() => loading.value || deletingId.value !== '')
const pageSize = 10
const columns = [
  { title: '用户 ID', dataIndex: 'id', key: 'id', width: 180 },
  { title: '登录账号', dataIndex: 'userAccount', key: 'userAccount', width: 220 },
  { title: '昵称', dataIndex: 'userName', key: 'userName', width: 220 },
  { title: '角色', dataIndex: 'userRole', key: 'userRole', width: 120 },
  { title: '操作', key: 'actions', width: 170, fixed: 'right' as const },
]
let active = true

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  deleteOpenId.value = ''
  try {
    let result = await listUsers({
      userAccount: appliedAccount.value,
      current: current.value,
      pageSize,
    })
    if (!active) return
    const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
    if (current.value > lastPage) {
      current.value = lastPage
      result = await listUsers({
        userAccount: appliedAccount.value,
        current: current.value,
        pageSize,
      })
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
  failedDeleteUser.value = null
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

function handleRenamed(id: string, userName: string): void {
  editingUser.value = null
  if (loginUserStore.user?.id === id) {
    loginUserStore.setUser({ ...loginUserStore.user, userName })
  }
  void load()
}

function changePage(page: number): void {
  if (busy.value) return
  deleteError.value = ''
  failedDeleteUser.value = null
  current.value = page
  void load()
}

function changeDeleteOpen(user: UserVO, open: boolean): void {
  if (busy.value || loginUserStore.authAction !== null) return
  deleteOpenId.value = open ? user.id : ''
}

async function removeUser(user: UserVO): Promise<void> {
  if (busy.value || loginUserStore.authAction !== null) return
  deletingId.value = user.id
  deleteError.value = ''
  failedDeleteUser.value = null
  try {
    await deleteUser(user.id)
    if (!active) return
    void message.success('用户已删除')
    await load()
  } catch (cause) {
    if (!active) return
    deleteError.value = `账号「${user.userAccount}」：${errorMessage(cause)}`
    if (cause instanceof ApiError && (cause.code === 40100 || cause.code === 40300)) {
      await loginUserStore.loadUser()
    } else if (cause instanceof ApiError && (cause.code === 40400 || cause.code === 50001)) {
      await load()
    } else {
      failedDeleteUser.value = user
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
  const target = rows.value.find(
    (user) => user.id === failedDeleteUser.value?.id && user.userRole === 'user',
  )
  if (target) changeDeleteOpen(target, true)
  else void load()
}

onMounted(load)
onBeforeUnmount(() => {
  active = false
})
</script>

<template>
  <section class="user-list" aria-label="用户管理">
    <PageHeading title="用户管理" description="按账号查找用户，维护昵称或删除普通用户。" />
    <form class="search-form" aria-label="查询用户" @submit.prevent="search">
      <label class="account-filter">
        登录账号
        <AInput
          v-model:value="keyword"
          placeholder="输入账号关键词"
          aria-label="登录账号"
          :disabled="busy"
          allow-clear
        />
      </label>
      <AButton shape="round" html-type="submit" :loading="loading" :disabled="deletingId !== ''"
        >查询</AButton
      >
      <AButton shape="round" :disabled="busy || (!keyword && !appliedAccount)" @click="reset"
        >重置</AButton
      >
      <AButton
        type="primary"
        shape="round"
        class="add-user-button"
        :disabled="busy || loginUserStore.authAction !== null"
        @click="addOpen = true"
      >
        新增用户
      </AButton>
    </form>
    <AAlert
      v-if="deleteError"
      class="delete-error"
      type="error"
      message="用户删除失败"
      :description="deleteError"
      show-icon
      role="alert"
    >
      <template #action>
        <AButton :disabled="busy || loginUserStore.authAction !== null" @click="retryDelete">
          {{ failedDeleteUser ? '重试' : '刷新列表' }}
        </AButton>
      </template>
    </AAlert>
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
    <div v-else class="table-card surface-card">
      <ATable
        :columns="columns"
        :data-source="rows"
        :pagination="false"
        :scroll="{ x: 860 }"
        row-key="id"
        table-layout="fixed"
      >
        <template #bodyCell="{ column, text, record }">
          <ATag v-if="column.key === 'userRole'">{{
            text === 'admin' ? '管理员' : '普通用户'
          }}</ATag>
          <div v-else-if="column.key === 'actions'" class="row-actions">
            <AButton
              type="link"
              :disabled="busy || loginUserStore.authAction !== null"
              @click="editingUser = record as UserVO"
            >
              修改昵称
            </AButton>
            <APopconfirm
              v-if="record.userRole === 'user'"
              :open="deleteOpenId === record.id"
              title="确认删除这个用户？"
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
              @open-change="(open: boolean) => changeDeleteOpen(record as UserVO, open)"
              @confirm="removeUser(record as UserVO)"
            >
              <template #description>
                <p class="delete-confirmation">
                  将删除账号「{{
                    record.userAccount
                  }}」及其全部应用，并下线公开作品。删除后该账号将无法登录。
                </p>
              </template>
              <AButton
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
        <span class="muted">共 {{ total }} 个用户</span>
        <APagination
          :current="current"
          :page-size="pageSize"
          :total="total"
          :show-size-changer="false"
          :disabled="busy"
          hide-on-single-page
          simple
          aria-label="用户分页"
          @change="changePage"
        />
      </div>
    </div>
    <UserAddModal :open="addOpen" @close="addOpen = false" @saved="handleAdded" />
    <UserRenameModal
      v-if="editingUser"
      :open="true"
      :user="editingUser"
      @close="editingUser = null"
      @saved="handleRenamed"
    />
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
  gap: 6px;
  width: min(100%, 360px);
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
}
.table-card {
  overflow: hidden;
  padding: 4px 8px 0;
}
.add-user-button {
  margin-left: auto;
}
.cell-text {
  overflow-wrap: anywhere;
}
.row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0 12px;
}
.row-actions :deep(.ant-btn-link) {
  height: auto;
  padding: 0;
}
.delete-confirmation {
  width: min(280px, calc(100vw - 96px));
  margin: 0;
  overflow-wrap: anywhere;
}
.delete-error {
  margin-bottom: 16px;
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
