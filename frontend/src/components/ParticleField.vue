<script setup lang="ts">
/**
 * Vue 3 包装。父元素需要 position: relative。
 * 例：<ParticleField class="hero-particles" :density="0.8" />
 *     <ParticleField tone="paper" class="dark-particles" :density="0.75" :link-opacity="0.22" />
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createParticleField, type ParticleTone } from '@/utils/particles'

const props = withDefaults(
  defineProps<{ tone?: ParticleTone; density?: number; linkOpacity?: number }>(),
  { tone: 'ink', density: 1, linkOpacity: 0.14 },
)
const canvas = ref<globalThis.HTMLCanvasElement | null>(null)
let destroy: (() => void) | null = null

function mount() {
  destroy?.()
  if (canvas.value) destroy = createParticleField(canvas.value, { ...props })
}

onMounted(mount)
watch(() => [props.tone, props.density, props.linkOpacity], mount)
onBeforeUnmount(() => destroy?.())
</script>

<template>
  <canvas ref="canvas" class="particle-field" aria-hidden="true" />
</template>
