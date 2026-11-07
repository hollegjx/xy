<script setup lang="ts">
defineProps<{ title: string; description: string }>()
</script>

<template>
  <header class="page-heading">
    <h1>{{ title }}</h1>
    <p>{{ description }}</p>
  </header>
</template>

<style scoped>
.page-heading {
  margin-bottom: 24px;
}
h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.5;
  letter-spacing: var(--heading-tracking);
}
p {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.5;
}
</style>
