<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, watchEffect } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  Alert as AAlert,
  Button as AButton,
  Result as AResult,
  Skeleton as ASkeleton,
  SkeletonButton as ASkeletonButton,
  Tag as ATag,
} from 'ant-design-vue'
import ParticleField from '@/components/ParticleField.vue'
import { errorMessage } from '@/request'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const logoutError = ref('')
const logoutBusy = computed(() => loginUserStore.authAction === 'logout')
const canAccessPage = computed(
  () =>
    (!route.meta.requiresAuth || Boolean(loginUserStore.user)) &&
    (!route.meta.adminOnly ||
      (loginUserStore.status === 'ready' && loginUserStore.user?.userRole === 'admin')),
)
const accountInitial = computed(() => {
  const user = loginUserStore.user
  return Array.from(user?.userName || user?.userAccount || '?')[0]?.toUpperCase() ?? '?'
})

const isAdmin = computed(
  () => loginUserStore.status === 'ready' && loginUserStore.user?.userRole === 'admin',
)
// 首页自带粒子；工作台是预览和对话的工作区，不加背景动效
const showBackdrop = computed(() => route.name !== 'home' && route.name !== 'app-workbench')

// 分段导航滑块：量出当前项的位置和宽度后弹性移动；首次定位不做动画
const navTrack = ref<globalThis.HTMLElement | null>(null)
const indicator = ref({ x: 0, width: 0, visible: false, animate: false })
let navObserver: globalThis.ResizeObserver | null = null

function measureNav(): void {
  const active = navTrack.value?.querySelector<globalThis.HTMLElement>('.nav-link.is-active')
  indicator.value = active
    ? { ...indicator.value, x: active.offsetLeft, width: active.offsetWidth, visible: true }
    : { ...indicator.value, visible: false }
}

watch(
  () => [route.name, isAdmin.value],
  async () => {
    await nextTick()
    measureNav()
    globalThis.requestAnimationFrame(
      () => (indicator.value = { ...indicator.value, animate: true }),
    )
  },
  { immediate: true },
)

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

onMounted(() => {
  loginUserStore.loadUser()
  if (navTrack.value) {
    navObserver = new globalThis.ResizeObserver(measureNav)
    navObserver.observe(navTrack.value)
  }
})
onBeforeUnmount(() => navObserver?.disconnect())

watchEffect(() => {
  if (route.meta.requiresAuth && loginUserStore.status === 'ready' && !loginUserStore.user) {
    void router.replace({ path: '/user/login', query: { redirect: route.fullPath } })
  }
})
</script>

<template>
  <ParticleField v-if="showBackdrop" class="app-backdrop" :density="0.5" :link-opacity="0.11" />
  <header class="site-header">
    <RouterLink to="/" class="brand" aria-label="星云首页">
      <span class="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="17" height="17">
          <path
            d="M12 3.5 13.9 10.1 20.5 12 13.9 13.9 12 20.5 10.1 13.9 3.5 12 10.1 10.1Z"
            fill="currentColor"
          />
          <circle cx="19" cy="5" r="1.6" fill="currentColor" opacity="0.85" />
        </svg>
      </span>
      <span>星云<span class="brand-subtitle">让灵感在此成形</span></span>
    </RouterLink>
    <nav class="site-nav" aria-label="主导航">
      <div ref="navTrack" class="nav-track">
        <span
          class="nav-indicator"
          aria-hidden="true"
          :data-animate="indicator.animate"
          :style="{
            width: `${indicator.width}px`,
            transform: `translateX(${indicator.x}px)`,
            opacity: indicator.visible ? 1 : 0,
          }"
        />
        <RouterLink to="/" class="nav-link" :class="{ 'is-active': route.name === 'home' }">
          首页
        </RouterLink>
        <RouterLink
          v-if="isAdmin"
          to="/admin/users"
          class="nav-link"
          :class="{ 'is-active': route.name === 'admin-users' }"
        >
          用户管理
        </RouterLink>
        <RouterLink
          v-if="isAdmin"
          to="/admin/apps"
          class="nav-link"
          :class="{ 'is-active': route.name === 'admin-apps' }"
        >
          应用管理
        </RouterLink>
        <RouterLink
          to="/system/status"
          class="nav-link"
          :class="{ 'is-active': route.name === 'system-status' }"
        >
          系统状态
        </RouterLink>
      </div>
      <ASkeletonButton
        v-if="loginUserStore.status === 'idle' || loginUserStore.status === 'loading'"
        active
        size="small"
        shape="round"
        aria-label="正在读取登录状态"
      />
      <template v-else-if="loginUserStore.status === 'ready'">
        <div v-if="loginUserStore.user" class="account-info" aria-label="当前账号">
          <span class="account-avatar" aria-hidden="true">{{ accountInitial }}</span>
          <span class="account-name" :title="loginUserStore.user.userAccount">
            {{ loginUserStore.user.userName || loginUserStore.user.userAccount }}
          </span>
          <ATag class="account-role">{{
            loginUserStore.user.userRole === 'admin' ? '管理员' : '普通用户'
          }}</ATag>
          <AButton
            class="logout-button"
            size="small"
            shape="round"
            :loading="logoutBusy"
            :disabled="loginUserStore.authAction === 'login'"
            @click="handleLogout"
          >
            {{ logoutBusy ? '退出中' : '退出登录' }}
          </AButton>
        </div>
        <template v-else-if="route.name !== 'user-register' && route.name !== 'user-login'">
          <RouterLink to="/user/login" class="nav-link nav-link-plain">登录</RouterLink>
          <RouterLink to="/user/register" class="nav-link nav-link-primary">注册账号</RouterLink>
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
    <RouterView
      v-if="canAccessPage"
      :key="route.meta.requiresAuth ? loginUserStore.user?.id : undefined"
    />
    <AResult
      v-else-if="route.meta.adminOnly && loginUserStore.status === 'ready' && loginUserStore.user"
      class="access-denied"
      status="403"
      sub-title="此页面仅管理员可访问。"
    >
      <template #title><h1 class="access-title">无权访问</h1></template>
      <template #extra>
        <AButton type="primary" shape="round" @click="router.replace('/')">返回首页</AButton>
      </template>
    </AResult>
    <ASkeleton
      v-else-if="loginUserStore.status !== 'error'"
      active
      :paragraph="{ rows: 4 }"
      aria-label="正在确认访问权限"
    />
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
.site-nav {
  gap: 8px;
}
.nav-link-plain {
  color: var(--ink);
}
.nav-link-plain:hover {
  background: var(--fill);
}
.nav-link-primary {
  color: var(--on-accent);
  background: var(--accent);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.14);
}
.nav-link-primary:hover {
  color: var(--on-accent);
  background: var(--accent-hover);
}
.account-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
  margin-left: 8px;
  padding-left: 12px;
  border-left: 1px solid var(--hairline);
}
.account-avatar {
  display: grid;
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 50%;
  color: #ffffff;
  background: var(--ink);
  font-size: 12px;
  font-weight: 600;
}
.account-name {
  max-width: 160px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
}
.account-role {
  margin: 0;
  flex-shrink: 0;
  font-size: 11px;
}
.logout-button {
  flex-shrink: 0;
  font-size: 12px;
}
.account-unknown {
  color: var(--muted);
  font-size: 12px;
}
.session-error {
  margin-bottom: 24px;
}
.access-denied {
  margin: 48px auto 0;
  max-width: 560px;
  border-radius: var(--radius-md);
  background: var(--surface);
  box-shadow: var(--shadow-card);
}
.access-title {
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: var(--heading-tracking);
}
@media (max-width: 600px) {
  .site-header {
    flex-wrap: wrap;
    row-gap: 4px;
  }
  .site-nav {
    flex: 1 1 100%;
    flex-wrap: nowrap;
    justify-content: space-between;
    gap: 6px;
  }
  .nav-track {
    min-width: 0;
    overflow-x: auto;
    scrollbar-width: none;
    -webkit-mask-image: linear-gradient(to right, #000 85%, transparent);
    mask-image: linear-gradient(to right, #000 85%, transparent);
  }
  /* 手机：账号区只留头像和退出，把横向空间让给导航 */
  .account-name,
  .account-role {
    display: none;
  }
  .nav-link {
    padding: 0 10px;
  }
  .account-info {
    margin-left: 4px;
    padding-left: 8px;
  }
  .account-name {
    max-width: 96px;
  }
}
</style>
