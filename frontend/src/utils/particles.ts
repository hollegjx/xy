/**
 * 黑白粒子背景（不依赖框架）。
 * 细点缓慢漂移、近邻连出淡线、鼠标附近的粒子被轻轻推开并与光标相连；每个点有轻微明暗闪烁，大小深浅不一带出景深。
 * 纯装饰：画布不接收指针事件（在 window 上听 pointermove 再换算坐标），触屏不交互。
 * 离开视口或标签页隐藏时暂停；prefers-reduced-motion 时只画一帧静止图。
 *
 * 用法：
 *   const destroy = createParticleField(canvas, { tone: 'ink', density: 0.8 })
 *   // 卸载时 destroy()
 * 画布本身的定位与淡入样式见 theme.css 的 .particle-field（铺满父元素，父元素需 position: relative）。
 */

export type ParticleTone = 'ink' | 'paper'

export type ParticleOptions = {
  /** ink = 近黑粒子（浅色背景用）；paper = 浅色粒子（深色背景用） */
  tone?: ParticleTone
  /** 密度倍数：每 9000 平方像素 1 颗 × density，上限 maxParticles */
  density?: number
  /** 近邻连线的最大不透明度；光标连线约为它的 2.8 倍 */
  linkOpacity?: number
  maxParticles?: number
  linkDistance?: number
  pointerRadius?: number
}

type Particle = {
  x: number
  y: number
  vx: number
  vy: number
  r: number
  alpha: number
  phase: number
  depth: number
}

const RGB: Record<ParticleTone, string> = { ink: '29, 29, 31', paper: '245, 245, 247' }
const AREA_PER_PARTICLE = 9000

export function createParticleField(canvas: HTMLCanvasElement, options: ParticleOptions = {}) {
  const {
    tone = 'ink',
    density = 1,
    linkOpacity = 0.14,
    maxParticles = 150,
    linkDistance = 120,
    pointerRadius = 150,
  } = options
  const ctx = canvas.getContext('2d')
  if (!ctx) return () => {}

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const rgb = RGB[tone]
  const pointer = { x: 0, y: 0, active: false }
  let particles: Particle[] = []
  let width = 0
  let height = 0
  let frame = 0
  let inView = true
  let last = 0

  const seed = () => {
    const count = Math.round(
      Math.min(maxParticles, Math.max(16, ((width * height) / AREA_PER_PARTICLE) * density)),
    )
    particles = Array.from({ length: count }, () => {
      // depth 越大：越大、越不透明、漂得越快、被光标推得越远 —— 形成景深
      const depth = Math.random()
      const speed = 0.08 + depth * 0.22
      const angle = Math.random() * Math.PI * 2
      return {
        x: Math.random() * width,
        y: Math.random() * height,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        r: 0.6 + depth * 1.5,
        alpha: 0.16 + depth * 0.4,
        phase: Math.random() * Math.PI * 2,
        depth,
      }
    })
  }

  const draw = (time: number) => {
    ctx.clearRect(0, 0, width, height)
    ctx.lineWidth = 0.6

    // 两两连线：n ≤ 150 时约 1.1 万次距离判断，每帧开销很小，不需要空间网格
    for (let i = 0; i < particles.length; i++) {
      const a = particles[i]
      for (let j = i + 1; j < particles.length; j++) {
        const b = particles[j]
        const dx = a.x - b.x
        const dy = a.y - b.y
        const d2 = dx * dx + dy * dy
        if (d2 > linkDistance * linkDistance) continue
        const fade = 1 - Math.sqrt(d2) / linkDistance
        ctx.strokeStyle = `rgba(${rgb}, ${fade * linkOpacity})`
        ctx.beginPath()
        ctx.moveTo(a.x, a.y)
        ctx.lineTo(b.x, b.y)
        ctx.stroke()
      }
    }

    for (const p of particles) {
      let glow = 0
      if (pointer.active) {
        const d = Math.hypot(p.x - pointer.x, p.y - pointer.y)
        if (d < pointerRadius) {
          glow = 1 - d / pointerRadius
          ctx.strokeStyle = `rgba(${rgb}, ${glow * linkOpacity * 2.8})`
          ctx.beginPath()
          ctx.moveTo(p.x, p.y)
          ctx.lineTo(pointer.x, pointer.y)
          ctx.stroke()
        }
      }
      const twinkle = 0.72 + 0.28 * Math.sin(time * 0.0011 + p.phase)
      ctx.fillStyle = `rgba(${rgb}, ${Math.min(1, p.alpha * twinkle + glow * 0.35)})`
      ctx.beginPath()
      ctx.arc(p.x, p.y, p.r + glow * 0.8, 0, Math.PI * 2)
      ctx.fill()
    }
  }

  const step = (now: number) => {
    // 按实际帧间隔缩放位移，掉帧或高刷屏速度一致；最多补 3 帧，切回标签页时不会瞬移
    const dt = Math.min(3, (now - last) / 16.67)
    last = now
    const margin = 12
    for (const p of particles) {
      if (pointer.active) {
        const dx = p.x - pointer.x
        const dy = p.y - pointer.y
        const d = Math.hypot(dx, dy)
        if (d > 0.1 && d < pointerRadius) {
          const push = (1 - d / pointerRadius) * (0.5 + p.depth) * 0.9 * dt
          p.x += (dx / d) * push
          p.y += (dy / d) * push
        }
      }
      p.x += p.vx * dt
      p.y += p.vy * dt
      // 出界从对边回来，粒子总数恒定
      if (p.x < -margin) p.x = width + margin
      else if (p.x > width + margin) p.x = -margin
      if (p.y < -margin) p.y = height + margin
      else if (p.y > height + margin) p.y = -margin
    }
    draw(now)
    frame = requestAnimationFrame(step)
  }

  const start = () => {
    if (frame || reduceMotion || !inView || document.hidden) return
    last = performance.now()
    frame = requestAnimationFrame(step)
  }
  const stop = () => {
    cancelAnimationFrame(frame)
    frame = 0
  }

  const resize = () => {
    const rect = canvas.getBoundingClientRect()
    if (rect.width === width && rect.height === height) return
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    width = rect.width
    height = rect.height
    canvas.width = Math.round(width * dpr)
    canvas.height = Math.round(height * dpr)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    seed()
    draw(performance.now())
  }

  const onPointerMove = (event: PointerEvent) => {
    if (event.pointerType !== 'mouse') return
    const rect = canvas.getBoundingClientRect()
    pointer.x = event.clientX - rect.left
    pointer.y = event.clientY - rect.top
    pointer.active =
      pointer.x >= 0 && pointer.y >= 0 && pointer.x <= rect.width && pointer.y <= rect.height
  }
  const onPointerLeave = () => {
    pointer.active = false
  }
  const onVisibility = () => (document.hidden ? stop() : start())

  const resizer = new ResizeObserver(resize)
  resizer.observe(canvas)
  const viewport = new IntersectionObserver((entries) => {
    inView = entries.some((entry) => entry.isIntersecting)
    if (inView) start()
    else stop()
  })
  viewport.observe(canvas)
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  document.documentElement.addEventListener('pointerleave', onPointerLeave)
  document.addEventListener('visibilitychange', onVisibility)

  resize()
  start()
  canvas.dataset.ready = 'true' // 触发 .particle-field 的淡入

  return () => {
    stop()
    resizer.disconnect()
    viewport.disconnect()
    window.removeEventListener('pointermove', onPointerMove)
    document.documentElement.removeEventListener('pointerleave', onPointerLeave)
    document.removeEventListener('visibilitychange', onVisibility)
  }
}
