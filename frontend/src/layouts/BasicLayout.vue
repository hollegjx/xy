<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'

const route = useRoute()
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
      <RouterLink
        v-if="route.name !== 'user-register' && route.name !== 'user-login'"
        to="/user/login"
        class="nav-link"
      >
        登录
      </RouterLink>
      <RouterLink
        v-if="route.name !== 'user-register' && route.name !== 'user-login'"
        to="/user/register"
        class="nav-link"
      >
        注册账号
      </RouterLink>
    </nav>
  </header>
  <main class="page-container">
    <RouterView />
  </main>
</template>
