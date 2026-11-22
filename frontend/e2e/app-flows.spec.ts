import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
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
  deployment: null,
}

const previewHtml = `<!doctype html><html><body>
  <h1>摄影作品集</h1><button id="count">查看照片 0</button>
  <script>let count = 0; document.getElementById('count').onclick = function () {
    this.textContent = '查看照片 ' + (++count)
  }</script>
</body></html>`
const previewHeaders = {
  'Content-Security-Policy':
    "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline' data:; style-src 'unsafe-inline' data:; frame-ancestors 'self'",
  'Cache-Control': 'no-store',
}

for (const [codeGenType, modeLabel] of [
  ['html', '单 HTML'],
  ['multi_file', '多文件'],
  ['vue_project', 'Vue 工程'],
  ['auto', '自动选择'],
] as const) {
  test(`${codeGenType}：登录保留需求，创建后自动生成并预览，刷新和重新进入不重复生成`, async ({
    page,
  }) => {
    const useWorkflow = codeGenType === 'vue_project' || codeGenType === 'auto'
    let signedIn = false
    let creates = 0
    let reads = 0
    let generations = 0
    let records: ChatHistoryVO[] = []
    let hasMore = false
    const olderQueries: string[] = []
    let finishOlder!: () => void
    const olderResponse = new Promise<void>((resolve) => {
      finishOlder = resolve
    })
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
        } else if (path === '/api/apps/featured') {
          await route.fulfill({ json: { code: 0, data: [], message: 'ok' } })
        } else if (path === '/api/apps/mine') {
          await route.fulfill({
            json: {
              code: 0,
              data: { records: [], total: 0, current: 1, pageSize: 10 },
              message: 'ok',
            },
          })
        } else if (path === '/api/apps') {
          expect(signedIn).toBe(true)
          expect(request.method()).toBe('POST')
          expect(request.postDataJSON()).toEqual({
            initPrompt: app.initPrompt,
            codeGenType,
          })
          creates += 1
          await route.fulfill({ json: { code: 0, data: app.id, message: 'ok' } })
        } else if (path === `/api/apps/${app.id}`) {
          expect(request.method()).toBe('GET')
          reads += 1
          const actualMode = codeGenType === 'auto' ? 'vue_project' : codeGenType
          await route.fulfill({
            json: { code: 0, data: { ...app, codeGenType: actualMode }, message: 'ok' },
          })
        } else if (path === `/api/apps/${app.id}/messages`) {
          expect(request.method()).toBe('GET')
          const cursor = new URL(request.url()).searchParams.get('beforeId')
          if (cursor) {
            olderQueries.push(cursor)
            expect(cursor).toBe('101')
            if (olderQueries.length === 3) await olderResponse
            await route.fulfill({
              status: olderQueries.length === 1 ? 503 : 200,
              json:
                olderQueries.length === 1
                  ? { code: 50000, data: null, message: '更早消息暂时不可用' }
                  : {
                      code: 0,
                      data: {
                        records: [
                          {
                            ...records[0]!,
                            id: '100',
                            messageRole: 'user',
                            status: 'completed',
                            content: '更早的需求：保留导航栏。',
                          },
                        ],
                        hasMore: false,
                        nextCursor: null,
                      },
                      message: 'ok',
                    },
            })
            return
          }
          await route.fulfill({
            json: {
              code: 0,
              data: { records, hasMore, nextCursor: hasMore ? records[0]!.id : null },
              message: 'ok',
            },
          })
        } else if (path === `/api/apps/${app.id}/generations`) {
          expect(request.method()).toBe('POST')
          expect(request.postDataJSON()).toEqual(
            useWorkflow
              ? { prompt: app.initPrompt, qualityCheck: true }
              : { prompt: app.initPrompt },
          )
          generations += 1
          records = [
            {
              id: '102',
              messageRole: 'assistant',
              content: '<h1>摄影作品集</h1>',
              status: 'completed',
              errorMessage: null,
              createTime: app.createTime,
              updateTime: app.updateTime,
            },
          ]
          await route.fulfill({
            contentType: 'text/event-stream',
            body:
              'event:started\ndata:{"generationId":"102","status":"running"}\n\n' +
              'event:completed\ndata:{"generationId":"102","status":"completed"}\n\n',
          })
        } else if (path === `/api/apps/${app.id}/deploy`) {
          expect(request.method()).toBe('POST')
          await route.fulfill({
            json: {
              code: 0,
              data: {
                deployUrl: 'https://sites.example.test/photo/',
                publishedVersion: '102',
                deployedAt: app.updateTime,
              },
              message: 'ok',
            },
          })
        } else if (path === `/api/apps/${app.id}/preview`) {
          if (generations === 0) {
            await route.fulfill({
              status: 409,
              json: { code: 50001, data: null, message: '尚无成功版本' },
            })
          } else {
            await route.fulfill({
              contentType: 'text/html;charset=UTF-8',
              headers: previewHeaders,
              body: previewHtml,
            })
          }
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
    await page.getByText(modeLabel, { exact: true }).click()
    const quality = page.getByRole('checkbox', { name: 'AI 质检与修复' })
    await expect(quality).not.toBeChecked()
    if (useWorkflow) await quality.check()
    await page.getByRole('button', { name: '创建应用', exact: true }).click()
    await expect(page).toHaveURL('/user/login?redirect=/')
    expect(creates).toBe(0)
    await page.getByLabel('账号', { exact: true }).fill(user.userAccount)
    await page.getByLabel('密码', { exact: true }).fill('Demo123456!')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL('/')
    await expect(page.getByLabel('应用需求', { exact: true })).toHaveValue(app.initPrompt)
    await expect(page.getByRole('radio', { name: modeLabel, exact: true })).toBeChecked()
    await expect(quality).toBeChecked({ checked: useWorkflow })
    await page.getByRole('button', { name: '创建应用', exact: true }).click()
    await expect(page).toHaveURL(`/apps/${app.id}`)
    await expect(page.getByRole('heading', { name: app.appName, exact: true })).toBeVisible()
    await expect(page.getByText(app.initPrompt, { exact: true })).toBeVisible()
    await expect(
      page.getByText(codeGenType === 'auto' ? 'Vue 工程' : modeLabel, { exact: true }),
    ).toBeVisible()
    const frame = page.frameLocator('iframe[title="应用网站预览"]')
    await expect(frame.getByRole('heading', { name: '摄影作品集' })).toBeVisible()
    await expect(quality).toBeChecked({ checked: useWorkflow })
    if (codeGenType === 'vue_project') {
      await page.getByRole('button', { name: '发布应用' }).click()
      await expect(page.getByRole('link', { name: '查看作品' })).toHaveAttribute(
        'href',
        'https://sites.example.test/photo/',
      )
      await expect(page.getByRole('button', { name: '重新发布' })).toBeEnabled()
      await expect(page.getByRole('button', { name: '下载源码' })).toBeEnabled()
    }
    await expect(page.locator('iframe')).toHaveAttribute('sandbox', 'allow-scripts')
    await frame.getByRole('button', { name: '查看照片 0' }).click()
    await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()
    expect(generations).toBe(1)
    records = ['running', 'completed', 'failed', 'cancelled'].map((status, index) => ({
      id: String(101 + index),
      messageRole: 'assistant',
      content: status === 'running' ? '' : '<h1>摄影作品集</h1>',
      status: status as ChatHistoryVO['status'],
      errorMessage: status === 'failed' ? '模型返回的文件不完整，请重试' : null,
      createTime: '2026-11-13T10:00:00',
      updateTime: '2026-11-13T10:01:00',
    }))
    hasMore = true
    await page.getByRole('button', { name: '刷新状态', exact: true }).click()
    const history = page.getByRole('region', { name: '生成记录', exact: true })
    for (const label of ['进行中', '已完成', '失败', '已停止']) {
      await expect(history.getByText(label, { exact: true })).toBeVisible()
    }
    await expect(history).toContainText('模型返回的文件不完整，请重试')
    await history.getByText('查看生成内容', { exact: true }).first().click()
    await expect(history.locator('pre').first()).toHaveText('<h1>摄影作品集</h1>')
    await expect(history.locator('pre').first().locator('.hljs-name').first()).toHaveText('h1')
    await expect(history.getByRole('heading', { name: '摄影作品集', exact: true })).toHaveCount(0)
    await history.getByRole('button', { name: '加载更早消息', exact: true }).click()
    await expect(history).toContainText('更早消息暂时不可用')
    await expect(history.getByRole('listitem')).toHaveCount(4)
    await history.getByRole('button', { name: '重试', exact: true }).click()
    await expect(history.getByRole('listitem')).toHaveCount(5)
    await expect(history.getByRole('listitem').first()).toContainText('更早的需求：保留导航栏。')
    await expect(history).toContainText('已全部加载')
    await expect(history.getByRole('button', { name: '加载更早消息', exact: true })).toHaveCount(0)
    expect(olderQueries).toEqual(['101', '101'])
    await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()
    // 刷新回到最新一页；随后用延迟分页响应确认旧结果不会混入刷新后的列表。
    await history.getByRole('button', { name: '刷新状态', exact: true }).click()
    await expect(history.getByRole('listitem')).toHaveCount(4)
    await history.getByRole('button', { name: '加载更早消息', exact: true }).click()
    await expect.poll(() => olderQueries.length).toBe(3)
    await expect(history.getByRole('button', { name: /加载更早消息/ })).toBeDisabled()
    await history.getByRole('button', { name: '刷新状态', exact: true }).click()
    await expect(history.getByRole('listitem')).toHaveCount(4)
    const lateResponse = page.waitForResponse((response) => response.url().includes('beforeId=101'))
    finishOlder()
    await (await lateResponse).finished()
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())),
    )
    await expect(history.getByText('更早的需求：保留导航栏。', { exact: true })).toHaveCount(0)
    await page.reload()
    await expect(page.getByRole('heading', { name: app.appName, exact: true })).toBeVisible()
    await expect(history.getByText('已完成', { exact: true })).toBeVisible()
    await expect(quality).not.toBeChecked()
    expect(creates).toBe(1)
    expect(reads).toBe(2)
    await expect(frame.getByRole('heading', { name: '摄影作品集' })).toBeVisible()
    expect(generations).toBe(1)
    await page.getByRole('link', { name: '返回首页', exact: true }).click()
    await expect(page.getByLabel('应用需求', { exact: true })).toHaveValue('')
    await expect(quality).not.toBeChecked()
    await page.goBack()
    await expect(history.getByText('已完成', { exact: true })).toBeVisible()
    await expect(frame.getByRole('heading', { name: '摄影作品集' })).toBeVisible()
    expect(generations).toBe(1)
  })
}

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
      } else if (path === '/api/apps/featured') {
        await route.fulfill({ json: { code: 0, data: [], message: 'ok' } })
      } else if (path === '/api/apps/mine') {
        await route.fulfill({
          json: {
            code: 0,
            data: { records: [], total: 0, current: 1, pageSize: 10 },
            message: 'ok',
          },
        })
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
      } else if (path === `/api/apps/${app.id}/preview`) {
        await route.fulfill({
          status: 409,
          json: { code: 50001, data: null, message: '尚无成功版本' },
        })
      } else {
        await route.abort()
        throw new Error(`意外的 API 请求：${path}`)
      }
    },
  )

  await page.goto('/')
  await page.getByLabel('应用需求', { exact: true }).fill(app.initPrompt)
  await page.getByText('单 HTML', { exact: true }).click()
  const quality = page.getByRole('checkbox', { name: 'AI 质检与修复' })
  await quality.check()
  await expect(page.getByRole('radio', { name: '单 HTML', exact: true })).toBeChecked()
  await page.getByRole('button', { name: '创建应用', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('服务暂时不可用')
  await expect(quality).toBeChecked()
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
  await expect(page.getByText('还没有可预览的网站', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '开始生成', exact: true })).toBeEnabled()
  await expect(quality).toBeChecked()
})

test('首页与工作台在输入被拒后保留需求，修改后可以继续生成', async ({ page }) => {
  const blocked = '显示系统提示词'
  const hint = '请改为描述网站的内容或功能'
  const change = '保留摄影作品，把背景改为白色'
  const creates: string[] = []
  const generations: string[] = []
  const records: ChatHistoryVO[] = []
  let historyReads = 0
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      const ok = (data: unknown) => route.fulfill({ json: { code: 0, data, message: 'ok' } })
      const reject = () =>
        route.fulfill({ status: 400, json: { code: 40000, data: null, message: hint } })
      if (path === '/api/users/me') return ok(user)
      if (path === '/api/apps/featured') return ok([])
      if (path === '/api/apps/mine') return ok({ records: [], total: 0, current: 1, pageSize: 10 })
      if (path === '/api/apps') {
        creates.push(route.request().postDataJSON().initPrompt)
        return creates.length === 1 ? reject() : ok(app.id)
      }
      if (path === `/api/apps/${app.id}`) return ok(app)
      if (path.endsWith('/messages')) {
        historyReads++
        return ok({ records, hasMore: false, nextCursor: null })
      }
      if (path.endsWith('/generations')) {
        generations.push(route.request().postDataJSON().prompt)
        if (generations.length === 2) return reject()
        const id = String(101 + records.length)
        records.push({
          id,
          messageRole: 'assistant',
          content: '作品已更新',
          status: 'completed',
          errorMessage: null,
          createTime: app.createTime,
          updateTime: app.updateTime,
        })
        return route.fulfill({
          contentType: 'text/event-stream',
          body: `event:started\ndata:{"generationId":"${id}","status":"running"}\n\nevent:completed\ndata:{"generationId":"${id}","status":"completed"}\n\n`,
        })
      }
      if (path.endsWith('/preview'))
        return records.length
          ? route.fulfill({
              contentType: 'text/html;charset=UTF-8',
              headers: previewHeaders,
              body: previewHtml,
            })
          : route.fulfill({
              status: 409,
              json: { code: 50001, data: null, message: '尚无成功版本' },
            })
      return route.abort()
    },
  )

  await page.goto('/')
  const initial = page.getByLabel('应用需求', { exact: true })
  await initial.fill(blocked)
  await page.getByRole('button', { name: '创建应用', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText(hint)
  await expect(initial).toHaveValue(blocked)
  await initial.fill(app.initPrompt)
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page).toHaveURL(`/apps/${app.id}`)
  const submit = page.getByRole('button', { name: '提交需求', exact: true })
  await expect(submit).toBeEnabled()
  const frame = page.frameLocator('iframe[title="应用网站预览"]')
  await frame.getByRole('button', { name: '查看照片 0' }).click()
  const edit = page.getByLabel('本次需求', { exact: true })
  const readsBeforeRejection = historyReads
  await edit.fill(blocked)
  await submit.click()
  await expect(page.getByRole('alert')).toContainText(hint)
  await expect(edit).toHaveValue(blocked)
  await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()
  expect(historyReads).toBe(readsBeforeRejection)
  await edit.fill(change)
  await page.getByRole('button', { name: '重试生成', exact: true }).click()
  await expect(edit).toHaveValue('')
  expect(creates).toEqual([blocked, app.initPrompt])
  expect(generations).toEqual([app.initPrompt, blocked, change])
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

test('Vue 工作台可选质检与修复并展示生成阶段，停止或失败保留旧预览', async ({ page }) => {
  let records: ChatHistoryVO[] = []
  let cancels = 0
  let previewError = false
  let sourceOnly = false
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/users/me') {
        await route.fulfill({ json: { code: 0, data: user, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}`) {
        await route.fulfill({
          json: { code: 0, data: { ...app, codeGenType: 'vue_project' }, message: 'ok' },
        })
      } else if (path === `/api/apps/${app.id}/messages`) {
        await route.fulfill({
          json: { code: 0, data: { records, hasMore: false, nextCursor: null }, message: 'ok' },
        })
      } else if (path === `/api/apps/${app.id}/generations/102/cancel`) {
        expect(route.request().method()).toBe('POST')
        cancels += 1
        records.push({ ...records[0]!, id: '102', status: 'cancelled', content: '部分内容' })
        await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}/preview`) {
        if (previewError) {
          await route.fulfill({
            status: 500,
            json: { code: 50000, data: null, message: '预览读取失败，请重试' },
          })
        } else if (sourceOnly || records.length === 0) {
          await route.fulfill({
            status: 409,
            json: {
              code: 50001,
              data: null,
              message: sourceOnly
                ? '该版本尚无构建产物，请启用 Vue 构建后重新生成；源码仍可下载'
                : '尚无成功版本',
            },
          })
        } else {
          await route.fulfill({
            contentType: 'text/html;charset=UTF-8',
            headers: previewHeaders,
            body: previewHtml,
          })
        }
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
        if (init?.body !== JSON.stringify({ prompt, qualityCheck: true }))
          throw new Error('生成需求或质检选项与表单不一致')
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
  await expect(page.getByText('还没有可预览的网站', { exact: true })).toBeVisible()
  await expect(page.getByLabel('本次需求', { exact: true })).toHaveValue(app.initPrompt)
  const qualityCheck = page.getByRole('checkbox', { name: 'AI 质检与修复' })
  await expect(qualityCheck).not.toBeChecked()
  await qualityCheck.check()
  await page.getByRole('button', { name: '开始生成', exact: true }).click()
  await expect(qualityCheck).toBeDisabled()
  const live = page.getByLabel('本次生成', { exact: true })
  await expect(page.getByRole('button', { name: /生成中/ })).toBeDisabled()
  const assetProgress = '已将 2 项素材加入生成提示：内容照片、品牌 Logo。'
  const logoReady = '已生成品牌 Logo。'
  for (const text of [
    '正在并行准备内容配图和本地素材…',
    logoReady,
    assetProgress,
    '已写入：src/App.vue',
    '正在检查 Vue 工程…',
    '发现问题，正在自动修复（最多一次）…',
  ]) {
    await page.evaluate(
      (text) =>
        window.dispatchEvent(
          new CustomEvent('xy-test-generation', {
            detail: { type: 'delta', text: `\n${text}\n` },
          }),
        ),
      text,
    )
    await expect(live).toContainText(text)
    if (text === logoReady) {
      await expect(page.getByRole('button', { name: '停止生成', exact: true })).toBeEnabled()
      await expect(page.locator('iframe')).toHaveCount(0)
    }
  }
  await expect(page.getByLabel('本次需求', { exact: true })).toHaveValue(app.initPrompt)
  await page.evaluate(() =>
    window.dispatchEvent(
      new CustomEvent('xy-test-generation', {
        detail: { type: 'delta', text: '\n源码已保存，正在构建 Vue 工程…\n' },
      }),
    ),
  )
  await expect(live).toContainText('正在构建 Vue 工程')
  await expect(page.locator('iframe')).toHaveCount(0)
  records = [
    {
      id: '101',
      messageRole: 'assistant',
      content:
        assetProgress +
        '\n已写入：src/App.vue\n源码已保存，正在构建 Vue 工程…\nVue 工程构建完成，可在线预览或下载源码。',
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
  const frame = page.frameLocator('iframe[title="应用网站预览"]')
  await frame.getByRole('button', { name: '查看照片 0' }).click()
  await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()
  await page.getByLabel('本次需求', { exact: true }).fill(app.initPrompt)
  await page.getByRole('button', { name: '提交需求', exact: true }).click()
  await page.getByRole('button', { name: '停止生成', exact: true }).click()
  await expect(live).toContainText('已停止')
  expect(cancels).toBe(1)
  await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()
  await expect(page.getByLabel('本次需求', { exact: true })).toHaveValue(app.initPrompt)
  await page.getByRole('button', { name: '重试生成', exact: true }).click()
  await expect(page.getByRole('button', { name: '停止生成', exact: true })).toBeEnabled()
  records.push({ ...records[0]!, id: '103', status: 'failed', errorMessage: '模型返回文件不完整' })
  await page.evaluate(() =>
    window.dispatchEvent(
      new CustomEvent('xy-test-generation', {
        detail: { type: 'failed' },
      }),
    ),
  )
  await expect(live).toContainText('模型返回文件不完整')
  await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()
  previewError = true
  const preview = page.getByRole('region', { name: '网站预览', exact: true })
  await preview.getByRole('button', { name: '刷新预览', exact: true }).click()
  await expect(preview.getByRole('alert')).toContainText('预览读取失败，请重试')
  await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()
  previewError = false
  await preview.getByRole('button', { name: '重试', exact: true }).click()
  await expect(preview.getByRole('alert')).toHaveCount(0)
  await expect(frame.getByRole('button', { name: '查看照片 0' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('region', { name: '生成记录', exact: true })).toContainText('已停止')
  await expect(live).toHaveCount(0)
  await expect(page.getByRole('button', { name: '重试生成', exact: true })).toBeEnabled()
  sourceOnly = true
  await preview.getByRole('button', { name: '刷新预览' }).click()
  await expect(preview).toContainText('该版本尚无构建产物，请启用 Vue 构建后重新生成；源码仍可下载')
  await expect(page.locator('iframe')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '下载源码' })).toBeEnabled()
})

for (const mode of ['html', 'multi_file', 'vue_project'] as const) {
  test(`${mode}：工作台首次生成、失败后恢复需求重试，刷新后可以继续修改`, async ({ page }) => {
    await checkFollowUp(page, mode)
  })
}

async function checkFollowUp(page: Page, mode: 'html' | 'multi_file' | 'vue_project') {
  const change = '把背景改为黑色，保留原有照片和按钮。'
  const nextChange = '把按钮文字改成浏览作品，保留黑色背景。'
  const selected = 'button · #detail · 查看原图'
  const qualityFailure = '自动修复一次后仍未通过 AI 质检，请重试'
  const detailPreview = previewHtml.replace(
    '</body>',
    `<details><summary>打开详情</summary>
    <button id="detail" onclick="this.parentElement.open=false">查看原图</button></details></body>`,
  )
  const expectedPrompts = [
    app.initPrompt,
    change,
    change,
    `${nextChange}\n\n选中元素（标签、位置、页面文字）：${selected}`,
  ]
  const picker = readFileSync('../backend/src/main/resources/preview/element-picker.html', 'utf8')
  const updatedApp = { ...app, codeGenType: mode }
  const sent: string[] = []
  const records: ChatHistoryVO[] = []
  let historyReads = 0
  let rateLimitNext = false
  let currentPreview = ''
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (path === '/api/users/me') {
        await route.fulfill({ json: { code: 0, data: user, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}`) {
        await route.fulfill({ json: { code: 0, data: updatedApp, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}/messages`) {
        historyReads += 1
        await route.fulfill({
          json: { code: 0, data: { records, hasMore: false, nextCursor: null }, message: 'ok' },
        })
      } else if (path === `/api/apps/${app.id}/generations`) {
        expect(request.method()).toBe('POST')
        if (rateLimitNext) {
          rateLimitNext = false
          await route.fulfill({
            status: 429,
            json: {
              code: 42900,
              data: null,
              message: '每个账号 60 秒内最多发起 5 次生成，请在 12 秒后重试',
            },
          })
          return
        }
        const prompt = expectedPrompts[sent.length]!
        expect(request.postDataJSON()).toEqual(
          sent.length === 1 ? { prompt, qualityCheck: true } : { prompt },
        )
        sent.push(prompt)
        const generationId = String(100 + sent.length * 2)
        const status = sent.length === 2 ? 'failed' : 'completed'
        const message = status === 'failed' ? qualityFailure : null
        if (status === 'completed') {
          currentPreview =
            sent.length === 1
              ? detailPreview
              : detailPreview.replace('<body>', '<body style="background: black; color: white">')
          if (sent.length === 4) currentPreview = currentPreview.replace('查看原图', '浏览作品')
        }
        const base = { errorMessage: null, createTime: app.createTime, updateTime: app.updateTime }
        records.push(
          {
            ...base,
            id: String(Number(generationId) - 1),
            messageRole: 'user',
            content: prompt,
            status: 'completed',
          },
          {
            ...base,
            id: generationId,
            messageRole: 'assistant',
            content: status === 'failed' ? '部分内容' : currentPreview,
            status,
            errorMessage: message,
          },
        )
        await route.fulfill({
          contentType: 'text/event-stream',
          body: ['started', status]
            .map(
              (event) =>
                `event:${event}\ndata:${JSON.stringify({ generationId, status: event === 'started' ? 'running' : status, message: message ?? undefined })}\n\n`,
            )
            .join(''),
        })
      } else if (path === `/api/apps/${app.id}/preview`) {
        await route.fulfill(
          currentPreview
            ? {
                contentType: 'text/html;charset=UTF-8',
                headers: previewHeaders,
                body:
                  currentPreview +
                  (new URL(request.url()).searchParams.get('pick') === 'true' ? picker : ''),
              }
            : { status: 409, json: { code: 50001, data: null, message: '尚无成功版本' } },
        )
      } else {
        await route.abort()
        throw new Error(`意外的 API 请求：${path}`)
      }
    },
  )

  await page.goto(`/apps/${app.id}`)
  const prompt = page.getByLabel('本次需求', { exact: true })
  const live = page.getByLabel('本次生成', { exact: true })
  const frame = page.frameLocator('iframe[title="应用网站预览"]')
  await expect(prompt).toHaveValue(app.initPrompt)
  await page.getByRole('button', { name: '开始生成', exact: true }).click()
  await expect(prompt).toHaveValue('')
  await frame.getByRole('button', { name: '查看照片 0' }).click()
  await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()

  await prompt.fill(change)
  const quality = page.getByRole('checkbox', { name: 'AI 质检与修复' })
  await expect(quality).not.toBeChecked()
  await quality.check()
  if (mode === 'html') {
    const previousReads = historyReads
    rateLimitNext = true
    await page.getByRole('button', { name: '提交需求', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('请在 12 秒后重试')
    await expect(prompt).toHaveValue(change)
    await expect(quality).toBeChecked()
    await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()
    expect(historyReads).toBe(previousReads)
    await page.getByRole('button', { name: '重试生成', exact: true }).click()
  } else {
    await page.getByRole('button', { name: '提交需求', exact: true }).click()
  }
  await expect(live).toContainText(qualityFailure)
  await expect(prompt).toHaveValue(change)
  await expect(frame.getByRole('button', { name: '查看照片 1' })).toBeVisible()
  await prompt.fill('我正在编辑下一条需求')
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(prompt).toBeEnabled()
  await expect(prompt).toHaveValue('我正在编辑下一条需求')

  await page.reload()
  await expect(prompt).toHaveValue(change)
  await expect(quality).not.toBeChecked()
  expect(sent).toHaveLength(2)
  await page.getByRole('button', { name: '重试生成', exact: true }).click()
  await expect(prompt).toHaveValue('')
  await expect(frame.locator('body')).toHaveCSS('background-color', 'rgb(0, 0, 0)')
  await expect(frame.getByRole('heading', { name: '摄影作品集' })).toBeVisible()

  await page.reload()
  await expect(page.getByRole('button', { name: '提交需求', exact: true })).toBeEnabled()
  await expect(prompt).toHaveValue('')
  expect(sent).toHaveLength(3)
  const detail = frame.getByRole('button', { name: '查看原图', exact: true })
  await frame.getByText('打开详情').click()
  await expect(detail).toBeVisible()
  await page.getByRole('button', { name: '选择元素' }).click()
  await detail.click({ timeout: 3000 })
  await expect(page.getByText(`已选元素：${selected}`)).toBeVisible()
  await page.getByRole('button', { name: '退出选择' }).click()
  await expect(page.getByText(`已选元素：${selected}`)).toHaveCount(0)
  await detail.click()
  await expect(detail).toBeHidden()
  await frame.getByText('打开详情').click()
  await page.getByRole('button', { name: '选择元素' }).click()
  await detail.click()
  await expect(page.getByText(`已选元素：${selected}`)).toBeVisible()
  expect(sent).toHaveLength(3)
  await prompt.fill(nextChange)
  await page.getByRole('button', { name: '提交需求', exact: true }).click()
  await expect(prompt).toHaveValue('')
  await frame.getByText('打开详情').click()
  await expect(frame.getByRole('button', { name: '浏览作品', exact: true })).toBeVisible()
  await expect(frame.locator('body')).toHaveCSS('background-color', 'rgb(0, 0, 0)')
  expect(sent).toEqual(expectedPrompts)
}
