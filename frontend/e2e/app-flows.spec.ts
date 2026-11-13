import { expect, test } from '@playwright/test'
import type { ChatHistoryVO } from '../src/types/api'

const user = { id: '42', userAccount: 'xy_e2e', userName: '星云用户', userRole: 'user' }
const app = {
  id: '9007199254740993',
  userId: user.id,
  appName: '一个摄影作品集',
  initPrompt: '一个摄影作品集，用简洁的网格展示照片。',
  codeGenType: 'multi_file',
  createTime: '2026-11-10T10:00:00',
  updateTime: '2026-11-10T10:00:00',
}

test('首页需求在登录后保留，创建后进入工作台，刷新只读取原应用', async ({ page }) => {
  let signedIn = false
  let creates = 0
  let reads = 0
  let records: ChatHistoryVO[] = []
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (path === '/api/users/me') {
        await route.fulfill({
          status: signedIn ? 200 : 401,
          json: signedIn
            ? { code: 0, data: user, message: 'ok' }
            : { code: 40100, data: null, message: '请先登录' },
        })
      } else if (path === '/api/users/login') {
        signedIn = true
        await route.fulfill({ json: { code: 0, data: user, message: 'ok' } })
      } else if (path === '/api/apps') {
        expect(signedIn).toBe(true)
        expect(request.method()).toBe('POST')
        expect(request.postDataJSON()).toEqual({
          initPrompt: app.initPrompt,
          codeGenType: 'multi_file',
        })
        creates += 1
        await route.fulfill({ json: { code: 0, data: app.id, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}`) {
        expect(request.method()).toBe('GET')
        reads += 1
        await route.fulfill({ json: { code: 0, data: app, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}/messages`) {
        expect(request.method()).toBe('GET')
        await route.fulfill({
          json: { code: 0, data: { records, hasMore: false, nextCursor: null }, message: 'ok' },
        })
      } else {
        await route.abort()
        throw new Error(`意外的 API 请求：${path}`)
      }
    },
  )

  await page.goto('/')
  await page.getByRole('button', { name: '创建应用', exact: true }).click()
  await expect(page.getByText('请描述你想创建的应用', { exact: true })).toBeVisible()
  await page.getByLabel('应用需求', { exact: true }).fill(app.initPrompt)
  await page.getByRole('button', { name: '创建应用', exact: true }).click()
  await expect(page).toHaveURL('/user/login?redirect=/')
  expect(creates).toBe(0)
  await page.getByLabel('账号', { exact: true }).fill(user.userAccount)
  await page.getByLabel('密码', { exact: true }).fill('Demo123456!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/')
  await expect(page.getByLabel('应用需求', { exact: true })).toHaveValue(app.initPrompt)
  await page.getByRole('button', { name: '创建应用', exact: true }).click()
  await expect(page).toHaveURL(`/apps/${app.id}`)
  await expect(page.getByRole('heading', { name: app.appName, exact: true })).toBeVisible()
  await expect(page.getByText(app.initPrompt, { exact: true })).toBeVisible()
  await expect(page.getByText('还没有生成记录', { exact: true })).toBeVisible()
  records = ['running', 'completed', 'failed', 'cancelled'].map((status, index) => ({
    id: String(101 + index),
    messageRole: 'assistant',
    content: status === 'running' ? '' : '<h1>摄影作品集</h1>',
    status: status as ChatHistoryVO['status'],
    errorMessage: status === 'failed' ? '模型返回的文件不完整，请重试' : null,
    createTime: '2026-11-13T10:00:00',
    updateTime: '2026-11-13T10:01:00',
  }))
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  const history = page.getByRole('region', { name: '生成记录', exact: true })
  for (const label of ['进行中', '已完成', '失败', '已停止']) {
    await expect(history.getByText(label, { exact: true })).toBeVisible()
  }
  await expect(history).toContainText('模型返回的文件不完整，请重试')
  await history.getByText('查看生成内容', { exact: true }).first().click()
  await expect(history.locator('pre').first()).toHaveText('<h1>摄影作品集</h1>')
  await expect(history.getByRole('heading', { name: '摄影作品集', exact: true })).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('heading', { name: app.appName, exact: true })).toBeVisible()
  await expect(history.getByText('已完成', { exact: true })).toBeVisible()
  expect(creates).toBe(1)
  expect(reads).toBe(2)
  await page.getByRole('link', { name: '返回首页', exact: true }).click()
  await expect(page.getByLabel('应用需求', { exact: true })).toHaveValue('')
})

test('单 HTML 创建失败保留输入，重试期间阻止重复提交，详情失败可以重试', async ({ page }) => {
  let creates = 0
  let reads = 0
  let finishCreate!: () => void
  const createResponse = new Promise<void>((resolve) => {
    finishCreate = resolve
  })
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/users/me') {
        await route.fulfill({ json: { code: 0, data: user, message: 'ok' } })
      } else if (path === '/api/apps') {
        expect(route.request().postDataJSON()).toEqual({
          initPrompt: app.initPrompt,
          codeGenType: 'html',
        })
        creates += 1
        if (creates > 1) await createResponse
        await route.fulfill({
          status: creates === 1 ? 503 : 200,
          json:
            creates === 1
              ? { code: 50000, data: null, message: '服务暂时不可用' }
              : { code: 0, data: app.id, message: 'ok' },
        })
      } else if (path === `/api/apps/${app.id}`) {
        reads += 1
        await route.fulfill({
          status: reads === 1 ? 503 : 200,
          json:
            reads === 1
              ? { code: 50000, data: null, message: '应用读取失败，请重试' }
              : { code: 0, data: { ...app, codeGenType: 'html' }, message: 'ok' },
        })
      } else if (path === `/api/apps/${app.id}/messages`) {
        await route.fulfill({
          json: { code: 0, data: { records: [], hasMore: false, nextCursor: null }, message: 'ok' },
        })
      } else {
        await route.abort()
      }
    },
  )

  await page.goto('/')
  await page.getByLabel('应用需求', { exact: true }).fill(app.initPrompt)
  await page.getByText('单 HTML', { exact: true }).click()
  await expect(page.getByRole('radio', { name: '单 HTML', exact: true })).toBeChecked()
  await page.getByRole('button', { name: '创建应用', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('服务暂时不可用')
  await expect(page.getByLabel('应用需求', { exact: true })).toHaveValue(app.initPrompt)
  await page.getByRole('button', { name: '重试', exact: true }).click()
  try {
    await expect(page.getByRole('button', { name: /创建中/ })).toBeDisabled()
    await expect(page.getByLabel('应用需求', { exact: true })).toBeDisabled()
    await expect.poll(() => creates).toBe(2)
  } finally {
    finishCreate()
  }
  await expect(page.getByRole('alert')).toContainText('应用读取失败，请重试')
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByText(app.initPrompt, { exact: true })).toBeVisible()
  await expect(page.getByText('单 HTML', { exact: true })).toBeVisible()
})

test('直接访问工作台先确认身份，登录后返回原地址并显示归属错误', async ({ page }) => {
  let finishIdentity!: () => void
  const identityResponse = new Promise<void>((resolve) => {
    finishIdentity = resolve
  })
  let signedIn = false
  let reads = 0
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/users/me') {
        await identityResponse
        await route.fulfill({ status: 401, json: { code: 40100, data: null, message: '请先登录' } })
      } else if (path === '/api/users/login') {
        signedIn = true
        await route.fulfill({ json: { code: 0, data: user, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}`) {
        expect(signedIn).toBe(true)
        reads += 1
        await route.fulfill({
          status: 403,
          json: { code: 40300, data: null, message: '无权访问该应用' },
        })
      } else {
        await route.abort()
      }
    },
  )

  await page.goto(`/apps/${app.id}`)
  await expect(page.getByLabel('正在确认访问权限', { exact: true })).toBeVisible()
  expect(reads).toBe(0)
  await expect(page).toHaveURL(`/apps/${app.id}`)
  finishIdentity()
  await expect(page).toHaveURL(`/user/login?redirect=/apps/${app.id}`)
  await page.getByLabel('账号', { exact: true }).fill(user.userAccount)
  await page.getByLabel('密码', { exact: true }).fill('Demo123456!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(`/apps/${app.id}`)
  await expect(page.getByRole('alert')).toContainText('无权访问该应用')
  await expect(page.getByText(app.initPrompt, { exact: true })).toHaveCount(0)
})

test('工作台逐段展示生成内容，完成后恢复记录，并可停止下一次生成', async ({ page }) => {
  let records: ChatHistoryVO[] = []
  let cancels = 0
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/users/me') {
        await route.fulfill({ json: { code: 0, data: user, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}`) {
        await route.fulfill({ json: { code: 0, data: app, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}/messages`) {
        await route.fulfill({
          json: { code: 0, data: { records, hasMore: false, nextCursor: null }, message: 'ok' },
        })
      } else if (path === `/api/apps/${app.id}/generations/102/cancel`) {
        expect(route.request().method()).toBe('POST')
        cancels += 1
        records.push({ ...records[0]!, id: '102', status: 'cancelled', content: '部分内容' })
        await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
      } else {
        await route.abort()
        throw new Error(`意外的 API 请求：${path}`)
      }
    },
  )
  // route.fulfill 会一次性返回响应；此替身用于实际验证浏览器逐段读取与中止。
  await page.addInitScript(
    ({ appId, prompt }) => {
      const originalFetch = window.fetch.bind(window)
      let generationId = 100
      window.fetch = async (input, init) => {
        if (input !== `/api/apps/${appId}/generations`) return originalFetch(input, init)
        if (init?.body !== JSON.stringify({ prompt })) throw new Error('生成需求与表单不一致')
        const id = String(++generationId)
        const encoder = new TextEncoder()
        return new Response(
          new ReadableStream({
            start(controller) {
              const send = (event: string, status: string, text?: string) =>
                controller.enqueue(
                  encoder.encode(
                    `event:${event}\ndata:${JSON.stringify({ generationId: id, status, text })}\n\n`,
                  ),
                )
              send('started', 'running')
              const receive = (event: Event) => {
                const { type, text } = (event as CustomEvent<{ type: string; text?: string }>)
                  .detail
                send(type, type === 'delta' ? 'running' : type, text)
                if (type !== 'delta') {
                  window.removeEventListener('xy-test-generation', receive)
                  controller.close()
                }
              }
              window.addEventListener('xy-test-generation', receive)
              init?.signal?.addEventListener(
                'abort',
                () => {
                  window.removeEventListener('xy-test-generation', receive)
                  controller.error(new DOMException('Aborted', 'AbortError'))
                },
                { once: true },
              )
            },
          }),
          { headers: { 'Content-Type': 'text/event-stream' } },
        )
      }
    },
    { appId: app.id, prompt: app.initPrompt },
  )

  await page.goto(`/apps/${app.id}`)
  await expect(page.getByLabel('本次需求', { exact: true })).toHaveValue(app.initPrompt)
  await page.getByRole('button', { name: '开始生成', exact: true }).click()
  const live = page.getByLabel('本次生成', { exact: true })
  await expect(page.getByRole('button', { name: /生成中/ })).toBeDisabled()
  await page.evaluate(() =>
    window.dispatchEvent(
      new CustomEvent('xy-test-generation', {
        detail: { type: 'delta', text: '<h1>星云🌟</h1>' },
      }),
    ),
  )
  await expect(live.locator('pre')).toHaveText('<h1>星云🌟</h1>')
  records = [
    {
      id: '101',
      messageRole: 'assistant',
      content: '<h1>星云🌟</h1>',
      status: 'completed',
      errorMessage: null,
      createTime: app.createTime,
      updateTime: app.updateTime,
    },
  ]
  await page.evaluate(() =>
    window.dispatchEvent(
      new CustomEvent('xy-test-generation', {
        detail: { type: 'completed' },
      }),
    ),
  )
  await expect(live).toContainText('已完成')
  await page.getByRole('button', { name: '开始生成', exact: true }).click()
  await page.getByRole('button', { name: '停止生成', exact: true }).click()
  await expect(live).toContainText('已停止')
  expect(cancels).toBe(1)
  await page.reload()
  await expect(page.getByRole('region', { name: '生成记录', exact: true })).toContainText('已停止')
  await expect(live).toHaveCount(0)
  await expect(page.getByRole('button', { name: '开始生成', exact: true })).toBeEnabled()
})
