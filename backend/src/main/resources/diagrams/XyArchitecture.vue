<script setup>
import { useId } from 'vue'
import diagram from './xy-architecture.json'

const marker = 'xy-arrow-' + useId()
const nodes = diagram.nodes.map((name, index) => {
  const chars = Array.from(name)
  const lines = []
  for (let i = 0; i < chars.length; i += 12) lines.push(chars.slice(i, i + 12).join(''))
  return { name, index, lines, left: 0, right: 0 }
})
const byName = new Map(nodes.map(node => [node.name, node]))
const tracks = { left: 0, right: 0 }
const directSources = new Set()
const edges = diagram.edges.map(edge => {
  const a = byName.get(edge.from), b = byName.get(edge.to)
  // 相邻节点可垂直直连；分支、回连和自环独占侧边轨道与端口。
  if (b.index === a.index + 1 && !directSources.has(a)) {
    directSources.add(a)
    return { ...edge, a, b }
  }
  const side = b.index < a.index ? 'left' : 'right'
  return { ...edge, a, b, side, track: ++tracks[side], start: ++a[side], end: ++b[side] }
})
const box = 192, gap = 18, x = 14 + tracks.left * gap, width = x + box + tracks.right * gap + 14
let y = 12
for (const n of nodes) {
  n.y = y
  n.height = Math.max(48, n.lines.length * 20 + 16, (Math.max(n.left, n.right) + 1) * 12)
  y += n.height + 32
}
const height = y - 20
function port(n, side, slot) { return n.y + n.height * slot / (n[side] + 1) }
function route(e) {
  if (!e.side) return `M ${x + box / 2} ${e.a.y + e.a.height} V ${e.b.y - 2}`
  const right = e.side === 'right', wall = right ? x + box : x, sign = right ? 1 : -1
  return `M ${wall} ${port(e.a, e.side, e.start)} H ${wall + sign * gap * e.track} V ${port(e.b, e.side, e.end)} H ${wall + sign * 2}`
}
const describe = e => e.from + ' → ' + e.to + (e.label ? '：' + e.label : '')
const labeled = edges.filter(edge => edge.label)
</script>

<template>
  <figure class="xy-architecture">
    <div class="diagram-scroll">
      <svg :width="width" :height="height" :viewBox="`0 0 ${width} ${height}`" role="img" aria-label="系统架构图">
        <desc>{{ edges.map(describe).join('；') }}</desc>
        <defs>
          <marker :id="marker" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="5" markerHeight="5" orient="auto">
            <path d="M0 0 L8 4 L0 8 Z" fill="#44675f" />
          </marker>
        </defs>
        <g fill="none" stroke="#44675f" stroke-width="2" stroke-linejoin="round">
          <path v-for="(edge, i) in edges" :key="i" :d="route(edge)" :marker-end="`url(#${marker})`">
            <title>{{ describe(edge) }}</title>
          </path>
        </g>
        <g v-for="node in nodes" :key="node.name">
          <rect :x="x" :y="node.y" :width="box" :height="node.height" rx="10" fill="#edf4f0" stroke="#66867c" />
          <text v-for="(line, i) in node.lines" :key="i" :x="x + box / 2"
            :y="node.y + node.height / 2 + (i - (node.lines.length - 1) / 2) * 20 + 5"
            text-anchor="middle" font-family="system-ui, sans-serif" font-size="14" fill="#243b35">{{ line }}</text>
        </g>
      </svg>
    </div>
    <figcaption v-if="labeled.length">
      <ul><li v-for="(edge, i) in labeled" :key="i">{{ describe(edge) }}</li></ul>
    </figcaption>
  </figure>
</template>

<style scoped>
.xy-architecture { margin: 0; min-width: 0; max-width: 100%; }
.diagram-scroll { overflow-x: auto; }
.diagram-scroll svg { display: block; max-width: none; margin: 0 auto; }
figcaption ul { margin: 8px 0 0; padding-inline-start: 1.5em; font: 0.875rem/1.6 system-ui, sans-serif; overflow-wrap: anywhere; }
</style>
