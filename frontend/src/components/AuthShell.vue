<script setup lang="ts">
import PageHeading from '@/components/PageHeading.vue'

defineProps<{ title: string; description: string }>()
</script>

<template>
  <section class="auth-page" :aria-label="title">
    <aside class="auth-story" aria-label="星云">
      <p class="brand-caption">星云 · XY</p>
      <svg class="brand-motif" viewBox="0 0 160 96" fill="none" aria-hidden="true">
        <g stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <ellipse cx="80" cy="48" rx="66" ry="23" transform="rotate(-20 80 48)" />
          <path d="M80 22 86 42 106 48 86 54 80 74 74 54 54 48 74 42Z" />
          <circle cx="135" cy="26" r="4" />
        </g>
      </svg>
      <h2>让灵感<br />在此成形</h2>
      <p class="story-description">描述你的想法，逐步构建自己的应用。</p>
    </aside>
    <div class="auth-form">
      <PageHeading :title="title" :description="description" />
      <slot />
      <div v-if="$slots.footer" class="auth-footer"><slot name="footer" /></div>
    </div>
  </section>
</template>

<style scoped>
.auth-page {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  max-width: 1040px;
  min-height: 560px;
  margin: 48px auto;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
}
.auth-story {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 24px;
  padding: 48px;
  background: var(--surface-soft);
}
.brand-caption {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.12em;
}
.brand-motif {
  width: 160px;
  height: 96px;
  color: var(--muted);
}
h2 {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.5;
  letter-spacing: var(--heading-tracking);
}
.story-description {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.5;
}
.auth-form {
  min-width: 0;
  max-width: 560px;
  padding: 48px;
}
.auth-footer {
  margin-top: 24px;
  font-size: 14px;
  text-align: center;
}
@media (max-width: 768px) {
  .auth-page {
    display: block;
    max-width: 560px;
    min-height: 0;
    margin: 24px auto;
  }
  .auth-story {
    display: none;
  }
  .auth-form {
    padding: 24px;
  }
}
</style>
