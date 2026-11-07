<script setup lang="ts">
import { Card as ACard } from 'ant-design-vue'

defineProps<{ title: string; description: string }>()
</script>

<template>
  <section class="auth-page" :aria-label="title">
    <div class="page-heading">
      <p class="eyebrow">星云 · XY</p>
      <h1>{{ title }}</h1>
      <p class="description">{{ description }}</p>
    </div>
    <ACard :bordered="false"><slot /></ACard>
    <div v-if="$slots.footer" class="auth-footer"><slot name="footer" /></div>
  </section>
</template>

<style scoped>
.auth-page {
  max-width: 460px;
  margin: 0 auto;
}
.page-heading {
  margin-bottom: 28px;
  text-align: center;
}
.eyebrow {
  margin: 0 0 12px;
  color: var(--primary);
  font-size: 12px;
  letter-spacing: 2px;
}
h1 {
  margin: 0 0 12px;
  font-size: 32px;
  font-weight: 600;
  letter-spacing: -1px;
}
.description {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
}
.auth-footer {
  margin-top: 24px;
  text-align: center;
}
</style>
