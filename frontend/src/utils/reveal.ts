import type { Directive } from 'vue'

const observers = new WeakMap<HTMLElement, IntersectionObserver>()

/**
 * 滚动揭示：元素进入视口后上浮淡入，只播一次。
 * 用法：<div v-reveal>…</div>，需要错开时 <div v-reveal="120">（毫秒）。样式见 theme.css 的 .reveal。
 */
export const vReveal: Directive<HTMLElement, number | undefined> = {
  mounted(el, binding) {
    el.classList.add('reveal')
    if (binding.value) el.style.setProperty('--reveal-delay', `${binding.value}ms`)
    if (typeof IntersectionObserver === 'undefined') {
      el.dataset.visible = 'true'
      return
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return
        el.dataset.visible = 'true'
        observer.disconnect()
      },
      { threshold: 0.12, rootMargin: '0px 0px -6% 0px' },
    )
    observer.observe(el)
    observers.set(el, observer)
  },
  unmounted(el) {
    observers.get(el)?.disconnect()
    observers.delete(el)
  },
}
