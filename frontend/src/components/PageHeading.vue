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
  margin-bottom: 28px;
}
h1 {
  margin: 0;
  font-size: 32px;
  font-weight: 600;
  line-height: 1.25;
  letter-spacing: var(--heading-tracking);
}
p {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.5;
}
@media (max-width: 600px) {
  h1 {
    font-size: 28px;
  }
}
</style>
