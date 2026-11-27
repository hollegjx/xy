<script setup lang="ts">
import PageHeading from '@/components/PageHeading.vue'

defineProps<{ title: string; description: string }>()
</script>

<template>
  <section class="auth-page" :aria-label="title">
    <div class="auth-card">
      <svg class="auth-mark" viewBox="0 0 64 64" aria-hidden="true">
        <rect width="64" height="64" rx="18" fill="#1d1d1f" />
        <path d="M32 14 36.6 27.4 50 32 36.6 36.6 32 50 27.4 36.6 14 32 27.4 27.4Z" fill="#fff" />
        <circle cx="47" cy="17" r="3" fill="#fff" opacity="0.85" />
      </svg>
      <PageHeading :title="title" :description="description" />
      <slot />
      <div v-if="$slots.footer" class="auth-footer"><slot name="footer" /></div>
    </div>
    <p class="auth-note">星云 · 让灵感在此成形</p>
  </section>
</template>

<style scoped>
.auth-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 0 24px;
}
.auth-card {
  width: min(100%, 440px);
  padding: 40px 40px 32px;
  border-radius: 28px;
  background: var(--surface);
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.04),
    0 4px 12px rgba(0, 0, 0, 0.04),
    0 24px 56px rgba(0, 0, 0, 0.08);
}
.auth-mark {
  display: block;
  width: 56px;
  height: 56px;
  margin: 0 auto 20px;
  filter: drop-shadow(0 6px 16px rgba(0, 0, 0, 0.18));
  animation: auth-mark-in 760ms var(--ease-spring) 200ms backwards;
}
@keyframes auth-mark-in {
  from {
    opacity: 0;
    transform: scale(0.6) rotate(-20deg);
  }
}
.auth-card :deep(.page-heading) {
  margin-bottom: 28px;
  text-align: center;
}
.auth-footer {
  margin-top: 24px;
  color: var(--muted);
  font-size: 13px;
  text-align: center;
}
.auth-note {
  margin: 24px 0 0;
  color: var(--subtle);
  font-size: 12px;
}
@media (max-width: 600px) {
  .auth-page {
    padding-top: 8px;
  }
  .auth-card {
    padding: 32px 20px 24px;
  }
}
</style>
