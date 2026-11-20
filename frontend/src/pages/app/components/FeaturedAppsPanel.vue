<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  Skeleton as ASkeleton,
} from 'ant-design-vue'
import { errorMessage } from '@/request'
import { listFeaturedApps } from '@/service/api/app'
import type { AppFeaturedVO } from '@/types/api'
import { generationModes } from '../generationModes'

const rows = ref<AppFeaturedVO[]>([])
const loading = ref(false)
const error = ref('')
const brokenCovers = ref(new Set<string>())
let active = true

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await listFeaturedApps()
    if (active) {
      rows.value = result
      brokenCovers.value.clear()
    }
  } catch (cause) {
    if (active) error.value = errorMessage(cause)
  } finally {
    if (active) loading.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => {
  active = false
})
</script>

<template>
  <section class="featured-apps" aria-labelledby="featured-apps-title">
    <header class="list-heading">
      <div>
        <h2 id="featured-apps-title">精选作品</h2>
        <p class="muted">看看灵感如何成形，浏览最近发布的精选作品。</p>
      </div>
      <AButton :loading="loading" @click="load">刷新</AButton>
    </header>
    <ASkeleton v-if="loading" active :paragraph="{ rows: 3 }" aria-label="正在读取精选作品" />
    <AAlert
      v-else-if="error"
      type="error"
      message="精选作品读取失败"
      :description="error"
      show-icon
      role="alert"
    >
      <template #action><AButton @click="load">重试</AButton></template>
    </AAlert>
    <AEmpty
      v-else-if="rows.length === 0"
      :image="AEmpty.PRESENTED_IMAGE_SIMPLE"
      description="还没有精选作品，期待灵感在此成形。"
    />
    <ul v-else class="featured-grid">
      <li v-for="app in rows" :key="app.id" class="featured-card">
        <img
          v-if="app.coverUrl && !brokenCovers.has(app.coverUrl)"
          class="work-cover"
          :src="app.coverUrl"
          alt=""
          width="960"
          height="540"
          loading="lazy"
          @error="brokenCovers.add(app.coverUrl)"
        />
        <h3>{{ app.appName }}</h3>
        <p class="muted">{{ generationModes[app.codeGenType].label }}</p>
        <AButton :href="app.deployUrl" target="_blank" rel="noopener noreferrer">查看作品</AButton>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.featured-apps {
  margin-top: 48px;
  padding-top: 32px;
  border-top: 1px solid var(--border);
}
.list-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
h2,
h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.muted {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 12px;
}
.featured-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.featured-card {
  min-width: 0;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
.featured-card .muted {
  margin-bottom: 16px;
}
.work-cover {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  margin-bottom: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
@media (max-width: 900px) {
  .featured-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .featured-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
