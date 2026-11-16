import { expect, test } from '@playwright/test'

test('注册后登录并刷新恢复账号，退出期间禁止重新登录', async ({ page }) => {
  const credentials = { userAccount: 'xy_e2e', userPassword: 'Demo123456!' }
  const user = {
    id: '42',
    userAccount: credentials.userAccount,
    userName: '星云用户',
    userRole: 'user',
  }
  let registered = false
  let signedIn = false
  let finishLogout!: () => void
  const logoutResponse = new Promise<void>((resolve) => {
    finishLogout = resolve
  })
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (path === '/api/users/register') {
        expect(request.method()).toBe('POST')
        expect(request.postDataJSON()).toEqual({
          ...credentials,
          checkPassword: credentials.userPassword,
        })
        registered = true
        await route.fulfill({ json: { code: 0, data: user.id, message: 'ok' } })
      } else if (path === '/api/users/login') {
        expect(request.method()).toBe('POST')
        expect(registered).toBe(true)
        expect(request.postDataJSON()).toEqual(credentials)
        signedIn = true
        await route.fulfill({ json: { code: 0, data: user, message: 'ok' } })
      } else if (path === '/api/users/me') {
        await route.fulfill({
          status: signedIn ? 200 : 401,
          json: signedIn
            ? { code: 0, data: user, message: 'ok' }
            : { code: 40100, data: null, message: '请先登录' },
        })
      } else if (path === '/api/users/logout') {
        expect(request.method()).toBe('POST')
        expect(signedIn).toBe(true)
        signedIn = false
        // 服务端已退出，但响应仍在途中。
        await logoutResponse
        await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
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
      } else if (path === '/api/health') {
        await route.fulfill({
          json: { code: 0, data: { status: 'UP', database: 'xy_e2e' }, message: 'ok' },
        })
      } else {
        await route.abort()
      }
    },
  )

  await page.goto('/user/register')
  await page.getByLabel('账号', { exact: true }).fill(credentials.userAccount)
  await page.getByLabel('密码', { exact: true }).fill(credentials.userPassword)
  await page.getByLabel('确认密码', { exact: true }).fill(credentials.userPassword)
  await page.getByRole('button', { name: '注册账号', exact: true }).click()
  await expect(page.getByText('注册成功', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '去登录', exact: true }).click()
  await page.getByLabel('账号', { exact: true }).fill(credentials.userAccount)
  await page.getByLabel('密码', { exact: true }).fill(credentials.userPassword)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/')
  const account = page.getByLabel('当前账号', { exact: true })
  await expect(account).toContainText(user.userName)
  await expect(account).toContainText('普通用户')
  await expect(page.getByRole('link', { name: '登录', exact: true })).toHaveCount(0)
  await page.reload()
  await expect(account).toContainText(user.userName)

  await page.goto('/user/login')
  await expect(account).toContainText(user.userName)
  await page.getByLabel('账号', { exact: true }).fill(credentials.userAccount)
  await page.getByLabel('密码', { exact: true }).fill(credentials.userPassword)
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page.getByText('退出中', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeDisabled()
  await expect(page.getByLabel('账号', { exact: true })).toBeDisabled()
  finishLogout()
  await expect(page).toHaveURL('/user/login')
  await expect(page.getByRole('heading', { name: '登录星云', exact: true })).toBeVisible()
  await expect(account).toHaveCount(0)
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled()
  await page.reload()
  await expect(page.getByLabel('正在读取登录状态', { exact: true })).toHaveCount(0)
  await expect(account).toHaveCount(0)
  await page.getByRole('link', { name: '星云首页', exact: true }).click()
  await expect(page.getByRole('link', { name: '登录', exact: true })).toBeVisible()
  await page.getByRole('link', { name: '登录', exact: true }).click()
  await page.getByLabel('账号', { exact: true }).fill(credentials.userAccount)
  await page.getByLabel('密码', { exact: true }).fill(credentials.userPassword)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/')
  await expect(account).toContainText(user.userName)
})
