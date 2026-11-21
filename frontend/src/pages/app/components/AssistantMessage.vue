<script setup lang="ts">
import { onBeforeUnmount, shallowRef, watch } from 'vue'
import { renderAssistantMarkdown } from './assistantMarkdown'

const props = defineProps<{ content: string; streaming?: boolean }>()
const rendered = shallowRef('')
let timer: ReturnType<typeof globalThis.setTimeout> | undefined
function render() {
  globalThis.clearTimeout(timer)
  timer = undefined
  rendered.value = renderAssistantMarkdown(props.content)
}
watch(
  () => [props.content, props.streaming] as const,
  () => {
    if (!props.streaming || !rendered.value) render()
    else timer ??= globalThis.setTimeout(render, 120)
  },
  { immediate: true },
)
onBeforeUnmount(() => globalThis.clearTimeout(timer))
</script>

<template>
  <!-- 只插入禁用 HTML 与图片的 Markdown 渲染器输出。 -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="assistant-message" v-html="rendered"></div>
</template>

<style scoped>
.assistant-message {
  max-height: 320px;
  overflow: auto;
  overflow-wrap: anywhere;
  margin-top: 12px;
  padding: 12px;
  background: var(--surface-soft);
  border-radius: var(--radius-sm);
  line-height: 1.65;
}
.assistant-message :deep(:first-child) {
  margin-top: 0;
}
.assistant-message :deep(:last-child) {
  margin-bottom: 0;
}
.assistant-message :deep(:is(h1, h2, h3, h4, h5, h6)) {
  font-size: 16px;
}
.assistant-message :deep(pre) {
  margin: 8px 0;
  font-size: 12px;
  white-space: pre-wrap;
}
.assistant-message :deep(.hljs-keyword),
.assistant-message :deep(.hljs-name),
.assistant-message :deep(.hljs-attr) {
  color: var(--ink);
  font-weight: 700;
}
.assistant-message :deep(.hljs-string),
.assistant-message :deep(.hljs-comment) {
  color: var(--muted);
}
</style>
