import { expect, test } from '@playwright/test'

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
  await expect(page.getByText('还没有生成结果', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: app.appName, exact: true })).toBeVisible()
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
