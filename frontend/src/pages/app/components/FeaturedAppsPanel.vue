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
const current = ref(1)
const hasNext = ref(false)
const requested = ref(1)
const loading = ref(false)
const error = ref('')
const brokenCovers = ref(new Set<string>())
let active = true

async function load(page: number): Promise<void> {
  if (loading.value) return
  requested.value = page
  loading.value = true
  error.value = ''
  try {
    let result = await listFeaturedApps(page)
    if (active && page > 1 && !result.records.length) {
      page = 1
      result = await listFeaturedApps(page)
    }
    if (active) {
      rows.value = result.records
      current.value = page
      hasNext.value = result.hasNext
      brokenCovers.value.clear()
    }
  } catch (cause) {
    if (active) error.value = errorMessage(cause)
  } finally {
    if (active) loading.value = false
  }
}

onMounted(() => load(1))
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
      <AButton :loading="loading" @click="load(current)">刷新</AButton>
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
      <template #action><AButton @click="load(requested)">重试</AButton></template>
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
        <div
          v-else
          class="work-cover cover-placeholder"
          :class="`mode-${app.codeGenType}`"
          aria-hidden="true"
        >
          <span>{{ Array.from(app.appName)[0] }}</span>
        </div>
        <div class="featured-body">
          <div class="featured-text">
            <h3>{{ app.appName }}</h3>
            <p class="muted">{{ generationModes[app.codeGenType].label }}</p>
          </div>
          <AButton
            type="primary"
            size="small"
            shape="round"
            :href="app.deployUrl"
            target="_blank"
            rel="noopener noreferrer"
            >查看作品</AButton
          >
        </div>
      </li>
    </ul>
    <nav v-if="current > 1 || hasNext" class="featured-pages" aria-label="精选作品分页">
      <AButton :disabled="loading || current === 1" @click="load(current - 1)">上一页</AButton>
      <span aria-live="polite">第 {{ current }} 页</span>
      <AButton :disabled="loading || !hasNext" @click="load(current + 1)">下一页</AButton>
    </nav>
  </section>
</template>

<style scoped>
.featured-apps {
  margin-top: 72px;
}
.list-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
h2 {
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: var(--heading-tracking);
}
h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
  overflow-wrap: anywhere;
}
.muted {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 13px;
}
.list-heading :deep(.ant-btn) {
  border-radius: 999px;
}
.featured-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.featured-card {
  min-width: 0;
  overflow: hidden;
  border-radius: var(--radius-md);
  background: var(--surface);
  box-shadow: var(--shadow-card);
  transition:
    transform var(--motion-duration) var(--motion-ease),
    box-shadow var(--motion-duration) var(--motion-ease);
}
.featured-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-lift);
}
.featured-pages {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 24px;
  color: var(--muted);
  font-size: 14px;
}
.work-cover {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  background: var(--surface-soft);
}
.cover-placeholder {
  display: grid;
  place-items: center;
}
.cover-placeholder span {
  color: rgba(255, 255, 255, 0.92);
  font-size: 48px;
  font-weight: 600;
  text-shadow: 0 6px 24px rgba(0, 0, 0, 0.15);
}
.mode-multi_file {
  background: linear-gradient(140deg, #5e5ce6, #0a84ff 60%, #64d2ff);
}
.mode-html {
  background: linear-gradient(140deg, #ff9f0a, #ff375f);
}
.mode-vue_project {
  background: linear-gradient(140deg, #30d158, #00a6a6);
}
.featured-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px 18px;
}
.featured-text {
  min-width: 0;
}
@media (max-width: 900px) {
  .featured-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .featured-apps {
    margin-top: 48px;
  }
  h2 {
    font-size: 24px;
  }
  .featured-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
