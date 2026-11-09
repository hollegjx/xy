<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  Alert as AAlert,
  Button as AButton,
  SkeletonButton as ASkeletonButton,
  Tag as ATag,
} from 'ant-design-vue'
import { errorMessage } from '@/request'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const logoutError = ref('')
const logoutBusy = computed(() => loginUserStore.authAction === 'logout')

async function handleLogout(): Promise<void> {
  if (loginUserStore.authAction !== null) return
  logoutError.value = ''
  try {
    if (!(await loginUserStore.logout())) return
    await router.replace('/user/login')
  } catch (cause) {
    logoutError.value = errorMessage(cause)
  }
}

onMounted(() => loginUserStore.loadUser())
</script>

<template>
  <header class="site-header">
    <RouterLink to="/" class="brand" aria-label="星云首页">
      <span class="brand-mark" aria-hidden="true">XY</span>
      <span>星云<span class="brand-subtitle">让灵感在此成形</span></span>
    </RouterLink>
    <nav class="site-nav" aria-label="主导航">
      <RouterLink v-if="route.name !== 'system-status'" to="/" class="nav-link">
        系统状态
      </RouterLink>
      <ASkeletonButton
        v-if="loginUserStore.status === 'idle' || loginUserStore.status === 'loading'"
        active
        aria-label="正在读取登录状态"
      />
      <template v-else-if="loginUserStore.status === 'ready'">
        <div v-if="loginUserStore.user" class="account-info" aria-label="当前账号">
          <span class="account-name" :title="loginUserStore.user.userAccount">
            {{ loginUserStore.user.userName || loginUserStore.user.userAccount }}
          </span>
          <ATag class="account-role">{{
            loginUserStore.user.userRole === 'admin' ? '管理员' : '普通用户'
          }}</ATag>
          <AButton
            class="logout-button"
            :loading="logoutBusy"
            :disabled="loginUserStore.authAction === 'login'"
            @click="handleLogout"
          >
            {{ logoutBusy ? '退出中' : '退出登录' }}
          </AButton>
        </div>
        <template v-else-if="route.name !== 'user-register' && route.name !== 'user-login'">
          <RouterLink to="/user/login" class="nav-link">登录</RouterLink>
          <RouterLink to="/user/register" class="nav-link">注册账号</RouterLink>
        </template>
      </template>
      <span v-else class="account-unknown">账户状态未知</span>
    </nav>
  </header>
  <main class="page-container">
    <AAlert
      v-if="loginUserStore.status === 'error'"
      class="session-error"
      type="error"
      message="登录状态读取失败"
      :description="loginUserStore.error"
      show-icon
      role="alert"
    >
      <template #action>
        <AButton @click="loginUserStore.loadUser()">重试</AButton>
      </template>
    </AAlert>
    <AAlert
      v-if="logoutError"
      class="session-error"
      type="error"
      message="退出失败"
      :description="logoutError"
      show-icon
      role="alert"
    >
      <template #action>
        <AButton
          :loading="logoutBusy"
          :disabled="loginUserStore.authAction === 'login'"
          @click="handleLogout"
          >重试</AButton
        >
      </template>
    </AAlert>
    <RouterView />
  </main>
</template>

<style scoped>
.brand {
  flex-shrink: 0;
}
.site-nav {
  min-width: 0;
  align-items: center;
}
.account-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
}
.account-name {
  max-width: 160px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}
.account-role {
  margin: 0;
  flex-shrink: 0;
}
.logout-button {
  flex-shrink: 0;
}
.account-unknown {
  color: var(--muted);
  font-size: 12px;
}
.session-error {
  margin-bottom: 24px;
}
@media (max-width: 600px) {
  .site-header {
    flex-wrap: wrap;
  }
  .site-nav {
    width: 100%;
  }
}
</style>
