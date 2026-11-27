<script setup lang="ts">
/**
 * 首页演示窗：12 秒循环的纯 CSS 动画，演示「描述需求 → 实时生成 → 选中元素修改 → 发布」。
 * 只用于展示，不可交互，读屏跳过。做法见 mono-apple-ui skill 的 references/hero-demo.md。
 */
const tiles = [0, 1, 2, 3, 4, 5]
</script>

<template>
  <div class="xd-stage" aria-hidden="true">
    <div class="xd-window">
      <div class="xd-chrome">
        <i /><i /><i />
        <span>摄影作品集 · 工作台</span>
      </div>

      <div class="xd-chat">
        <p class="xd-label">生成记录</p>
        <div class="xd-bubble me xd-b1">做一个摄影作品集，用网格展示照片</div>
        <div class="xd-bubble ai xd-b2">好的，按多文件网站生成：</div>
        <div class="xd-step xd-s1"><b>✓</b>规划页面结构</div>
        <div class="xd-step xd-s2"><b>✓</b>生成 index.html</div>
        <div class="xd-step xd-s3"><b>✓</b>生成 style.css</div>
        <div class="xd-bubble me xd-b3">把标题区改成深色</div>
        <div class="xd-bubble ai xd-b4">已修改，预览已更新。</div>
      </div>

      <div class="xd-preview">
        <div class="xd-address">my-photos · 预览</div>
        <div class="xd-site">
          <p class="xd-empty">等待生成…</p>
          <div class="xd-nav"><i /><b /><b /><b /></div>
          <div class="xd-hero">
            <span class="xd-title" />
            <span class="xd-sub" />
            <span class="xd-cta" />
            <div class="xd-marquee">
              <span class="xd-chip">已选元素：标题区</span>
            </div>
          </div>
          <div class="xd-grid">
            <span v-for="tile in tiles" :key="tile" :class="`xd-tile t${tile}`" />
          </div>
          <svg class="xd-cursor" viewBox="0 0 18 18">
            <path
              d="M3 1.5v13l3.6-3.3 2.5 5.3 2.3-1.1-2.5-5.2h4.9L3 1.5Z"
              fill="#1d1d1f"
              stroke="#fff"
              stroke-width="1.2"
              stroke-linejoin="round"
            />
          </svg>
          <div class="xd-toast">已发布 · 链接已复制</div>
        </div>
      </div>

      <div class="xd-files">
        <p class="xd-label">文件</p>
        <div class="xd-file xd-f1"><i />index.html</div>
        <div class="xd-file xd-f2"><i />style.css</div>
        <div class="xd-file xd-f3"><i />script.js</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * 12 秒时间轴（所有元素共用周期，用关键帧百分比编排，不用 animation-delay）：
 *   （放在首屏标题与创建表单之间：先让访客看懂「一句话生成网站」，再给输入框）
 *   2% 用户需求 → 8% 助手回复
 *   12% 步骤「规划」✓ → 15% 预览导航条；18% 步骤「index.html」✓ + 文件 index.html
 *   21% 标题区出现；24% 步骤「style.css」✓ + 文件 style.css
 *   27–37% 照片网格从左向右展开；30% 文件 script.js
 *   38% 光标进场 → 45% 到达标题区 → 47% 点击 → 46–50% 选框与「已选元素」出现，保持到 64%
 *   50% 用户「改成深色」→ 56–62% 标题区由浅翻深 → 62% 助手确认
 *   68% 提示「已发布」→ 86% 收起
 *   92–97% 全部淡出 → 100% 回到「等待生成」
 */
.xd-stage {
  --loop: 12s;
  position: relative;
  max-width: 1060px;
  margin: 0 auto;
  padding: 0 0 56px;
  text-align: left;
  animation: xd-stage-in 1.4s var(--ease-out) 420ms backwards;
}
@keyframes xd-stage-in {
  from {
    opacity: 0;
    transform: translate3d(0, 48px, 0) scale(0.96);
  }
}
@supports (animation-timeline: view()) {
  .xd-window {
    animation: xd-settle linear both;
    animation-timeline: view();
    animation-range: entry 0% entry 60%;
  }
  @keyframes xd-settle {
    from {
      transform: perspective(1600px) rotateX(10deg) scale(0.94);
    }
    to {
      transform: none;
    }
  }
}

.xd-window {
  display: grid;
  grid-template-columns: 236px minmax(0, 1fr) 188px;
  grid-template-rows: 40px minmax(0, 1fr);
  height: 470px;
  overflow: hidden;
  border-radius: 22px;
  background: var(--surface);
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.06),
    var(--shadow-lg);
  font-size: 12px;
}
.xd-chrome {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 16px;
  border-bottom: 1px solid var(--hairline);
  background: var(--surface-soft);
}
.xd-chrome i {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: var(--border-strong);
}
.xd-chrome span {
  margin: 0 auto;
  color: var(--subtle);
  transform: translateX(-24px);
}
.xd-label {
  margin: 0 0 4px;
  color: var(--subtle);
  font-size: 11px;
  font-weight: 500;
}

/* ── 生成记录 ── */
.xd-chat {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 14px;
  border-right: 1px solid var(--hairline);
}
.xd-bubble,
.xd-step,
.xd-file {
  opacity: 0;
  animation: var(--loop) var(--ease-out) infinite;
}
.xd-bubble {
  max-width: 90%;
  padding: 7px 12px;
  border-radius: 16px;
  line-height: 1.45;
}
.xd-bubble.me {
  align-self: flex-end;
  border-bottom-right-radius: 6px;
  color: #ffffff;
  background: var(--ink);
}
.xd-bubble.ai {
  align-self: flex-start;
  border-bottom-left-radius: 6px;
  color: var(--ink);
  background: var(--surface-soft);
}
.xd-step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 4px;
  color: var(--muted);
}
.xd-step b {
  display: grid;
  width: 16px;
  height: 16px;
  place-items: center;
  border-radius: 50%;
  color: #ffffff;
  background: var(--ink);
  font-size: 10px;
}
.xd-b1 {
  animation-name: xd-b1;
}
.xd-b2 {
  animation-name: xd-b2;
}
.xd-s1 {
  animation-name: xd-s1;
}
.xd-s2 {
  animation-name: xd-s2;
}
.xd-s3 {
  animation-name: xd-s3;
}
.xd-b3 {
  animation-name: xd-b3;
}
.xd-b4 {
  animation-name: xd-b4;
}
@keyframes xd-b1 {
  0%,
  2% {
    opacity: 0;
    transform: translate3d(0, 10px, 0) scale(0.94);
  }
  6%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
@keyframes xd-b2 {
  0%,
  8% {
    opacity: 0;
    transform: translate3d(0, 10px, 0) scale(0.94);
  }
  12%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
@keyframes xd-s1 {
  0%,
  12% {
    opacity: 0;
    transform: translateX(-8px);
  }
  15%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
@keyframes xd-s2 {
  0%,
  18% {
    opacity: 0;
    transform: translateX(-8px);
  }
  21%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
@keyframes xd-s3 {
  0%,
  24% {
    opacity: 0;
    transform: translateX(-8px);
  }
  27%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
@keyframes xd-b3 {
  0%,
  50% {
    opacity: 0;
    transform: translate3d(0, 10px, 0) scale(0.94);
  }
  54%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
@keyframes xd-b4 {
  0%,
  62% {
    opacity: 0;
    transform: translate3d(0, 10px, 0) scale(0.94);
  }
  66%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}

/* ── 预览 ── */
.xd-preview {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  padding: 14px 18px 18px;
  background: var(--surface-soft);
}
.xd-address {
  align-self: center;
  padding: 4px 18px;
  border-radius: 999px;
  color: var(--subtle);
  background: var(--surface);
  font-size: 11px;
}
.xd-site {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  padding: 14px;
  border-radius: 14px;
  background: var(--surface);
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.04);
}
.xd-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  margin: 0;
  color: var(--subtle);
  animation: xd-empty var(--loop) var(--ease-out) infinite;
}
@keyframes xd-empty {
  0%,
  11% {
    opacity: 1;
  }
  14%,
  96% {
    opacity: 0;
  }
  100% {
    opacity: 1;
  }
}
.xd-nav {
  display: flex;
  align-items: center;
  gap: 10px;
  opacity: 0;
  animation: xd-nav var(--loop) var(--ease-out) infinite;
}
.xd-nav i {
  width: 16px;
  height: 16px;
  margin-right: auto;
  border-radius: 50%;
  background: var(--ink);
}
.xd-nav b {
  width: 30px;
  height: 6px;
  border-radius: 3px;
  background: var(--border-strong);
}
@keyframes xd-nav {
  0%,
  15% {
    opacity: 0;
    transform: translate3d(0, -8px, 0);
  }
  19%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
.xd-hero {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 9px;
  height: 118px;
  border-radius: 10px;
  background: var(--surface-soft);
  opacity: 0;
  animation:
    xd-hero-in var(--loop) var(--ease-out) infinite,
    xd-hero-bg var(--loop) var(--ease-in-out) infinite;
}
@keyframes xd-hero-in {
  0%,
  21% {
    opacity: 0;
    transform: translate3d(0, 14px, 0);
    filter: blur(6px);
  }
  25%,
  92% {
    opacity: 1;
    transform: none;
    filter: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
/* 最大的变化：标题区由浅翻深，保持到 90% 给人看清 */
@keyframes xd-hero-bg {
  0%,
  56% {
    background: #f5f5f7;
  }
  62%,
  90% {
    background: #1d1d1f;
  }
  97%,
  100% {
    background: #f5f5f7;
  }
}
.xd-hero span:not(.xd-chip) {
  display: block;
  border-radius: 999px;
}
.xd-title {
  width: 46%;
  height: 14px;
  background: #1d1d1f;
  animation: xd-ink var(--loop) var(--ease-in-out) infinite;
}
.xd-sub {
  width: 30%;
  height: 7px;
  background: #c7c7cc;
  animation: xd-sub var(--loop) var(--ease-in-out) infinite;
}
.xd-cta {
  width: 14%;
  height: 14px;
  margin-top: 4px;
  background: #1d1d1f;
  animation: xd-ink var(--loop) var(--ease-in-out) infinite;
}
@keyframes xd-ink {
  0%,
  56% {
    background: #1d1d1f;
  }
  62%,
  90% {
    background: #f5f5f7;
  }
  97%,
  100% {
    background: #1d1d1f;
  }
}
@keyframes xd-sub {
  0%,
  56% {
    background: #c7c7cc;
  }
  62%,
  90% {
    background: #636366;
  }
  97%,
  100% {
    background: #c7c7cc;
  }
}
/* 选框：蚂蚁线描边，从 1.06 收拢；左上角挂「已选元素」标签 */
.xd-marquee {
  position: absolute;
  inset: -6px;
  border-radius: 14px;
  background:
    linear-gradient(90deg, #1d1d1f 50%, transparent 0) repeat-x top / 10px 1.5px,
    linear-gradient(90deg, #1d1d1f 50%, transparent 0) repeat-x bottom / 10px 1.5px,
    linear-gradient(0deg, #1d1d1f 50%, transparent 0) repeat-y left / 1.5px 10px,
    linear-gradient(0deg, #1d1d1f 50%, transparent 0) repeat-y right / 1.5px 10px;
  opacity: 0;
  animation:
    xd-marquee var(--loop) var(--ease-out) infinite,
    xd-march 0.8s linear infinite;
}
@keyframes xd-march {
  to {
    background-position:
      10px 0,
      -10px 100%,
      0 -10px,
      100% 10px;
  }
}
@keyframes xd-marquee {
  0%,
  46% {
    opacity: 0;
    transform: scale(1.06);
  }
  50%,
  64% {
    opacity: 1;
    transform: none;
  }
  68%,
  100% {
    opacity: 0;
    transform: none;
  }
}
.xd-chip {
  position: absolute;
  top: 12px;
  left: 14px;
  padding: 2px 10px;
  border-radius: 999px;
  color: #ffffff;
  background: var(--ink);
  font-size: 11px;
  white-space: nowrap;
}
/* 照片网格：从左向右展开 */
.xd-grid {
  display: grid;
  flex: 1;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  min-height: 0;
  animation: xd-grid var(--loop) var(--ease-in-out) infinite;
}
@keyframes xd-grid {
  0%,
  27% {
    clip-path: inset(0 100% 0 0);
    opacity: 1;
  }
  37%,
  92% {
    clip-path: inset(0 0 0 0);
    opacity: 1;
  }
  97%,
  100% {
    clip-path: inset(0 0 0 0);
    opacity: 0;
  }
}
.xd-tile {
  border-radius: 8px;
  background: #e8e8ed;
}
.t1,
.t5 {
  background: #d2d2d7;
}
.t2 {
  background: #aeaeb2;
}
.t3 {
  background: #c7c7cc;
}
/* 光标：右下进场 → 到标题区 → 点一下 → 停留 → 离场 */
.xd-cursor {
  position: absolute;
  top: 96px;
  left: 56%;
  width: 18px;
  height: 18px;
  opacity: 0;
  animation: xd-cursor var(--loop) var(--ease-in-out) infinite;
}
@keyframes xd-cursor {
  0%,
  38% {
    opacity: 0;
    transform: translate(140px, 150px);
  }
  41% {
    opacity: 1;
  }
  45% {
    transform: translate(0, 0);
  }
  47% {
    transform: translate(0, 0) scale(0.82);
  }
  49% {
    transform: translate(0, 0) scale(1);
  }
  60% {
    opacity: 1;
    transform: translate(-8px, 4px);
  }
  64%,
  100% {
    opacity: 0;
    transform: translate(120px, 140px);
  }
}
.xd-toast {
  position: absolute;
  bottom: 18px;
  left: 50%;
  padding: 7px 14px;
  border-radius: 999px;
  color: #ffffff;
  background: var(--glass-dark);
  white-space: nowrap;
  opacity: 0;
  transform: translateX(-50%);
  animation: xd-toast var(--loop) var(--ease-spring) infinite;
}
@keyframes xd-toast {
  0%,
  68% {
    opacity: 0;
    transform: translate(-50%, 12px) scale(0.92);
  }
  72%,
  86% {
    opacity: 1;
    transform: translate(-50%, 0);
  }
  90%,
  100% {
    opacity: 0;
    transform: translate(-50%, 0);
  }
}

/* ── 文件 ── */
.xd-files {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 14px;
  border-left: 1px solid var(--hairline);
}
.xd-file {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border-radius: 10px;
  background: var(--surface-soft);
}
.xd-file i {
  width: 14px;
  height: 17px;
  flex: none;
  border-radius: 3px;
  background: var(--surface);
  box-shadow: inset 0 0 0 1.5px var(--ink);
}
.xd-f1 {
  animation-name: xd-f1;
}
.xd-f2 {
  animation-name: xd-f2;
}
.xd-f3 {
  animation-name: xd-f3;
}
@keyframes xd-f1 {
  0%,
  18% {
    opacity: 0;
    transform: translateX(16px);
  }
  22%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
@keyframes xd-f2 {
  0%,
  24% {
    opacity: 0;
    transform: translateX(16px);
  }
  28%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}
@keyframes xd-f3 {
  0%,
  30% {
    opacity: 0;
    transform: translateX(16px);
  }
  34%,
  92% {
    opacity: 1;
    transform: none;
  }
  97%,
  100% {
    opacity: 0;
  }
}

/* 减少动态效果：直接显示最终状态（深色标题区），隐藏光标、选框和提示 */
@media (prefers-reduced-motion: reduce) {
  .xd-bubble,
  .xd-step,
  .xd-file,
  .xd-nav,
  .xd-hero {
    opacity: 1;
  }
  .xd-hero {
    background: #1d1d1f;
  }
  .xd-title,
  .xd-cta {
    background: #f5f5f7;
  }
  .xd-sub {
    background: #636366;
  }
  .xd-empty,
  .xd-cursor,
  .xd-marquee,
  .xd-toast {
    display: none;
  }
}
@media (max-width: 960px) {
  .xd-window {
    grid-template-columns: 210px minmax(0, 1fr);
  }
  .xd-files {
    display: none;
  }
}
@media (max-width: 760px) {
  .xd-stage {
    padding-bottom: 32px;
  }
  .xd-window {
    grid-template-columns: minmax(0, 1fr);
    height: 320px;
  }
  .xd-chat {
    display: none;
  }
}
</style>
