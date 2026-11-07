<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <header class="site-header">
    <RouterLink to="/" class="brand" aria-label="星云首页">
      <span class="brand-mark" aria-hidden="true">XY</span>
      <span>星云<span class="brand-subtitle">让创意成为应用</span></span>
    </RouterLink>
    <nav aria-label="主导航">
      <RouterLink to="/" class="nav-link">系统状态</RouterLink>
    </nav>
  </header>
  <main class="page-container">
    <RouterView />
  </main>
</template>
