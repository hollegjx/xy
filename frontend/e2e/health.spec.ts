import { expect, test } from '@playwright/test'

test('连接失败后可以重试并显示接口返回的数据库名称', async ({ page }) => {
  let attempts = 0
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      if (new URL(route.request().url()).pathname !== '/api/health') {
        await route.abort()
        return
      }
      attempts += 1
      await route.fulfill({
        status: attempts === 1 ? 503 : 200,
        json:
          attempts === 1
            ? { code: 50000, data: null, message: '数据库连接失败，请检查本地配置' }
            : { code: 0, data: { status: 'UP', database: 'xy_e2e' }, message: 'ok' },
      })
    },
  )

  await page.goto('/')
  await expect(page.getByRole('heading', { name: '连接检查' })).toBeVisible()
  await expect(page.getByText('数据库连接失败，请检查本地配置', { exact: true })).toBeVisible()
  await expect(page.getByText('后端与数据库连接正常', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: /^重\s*试$/ }).click()
  await expect(page.getByText('后端与数据库连接正常', { exact: true })).toBeVisible()
  await expect(page.getByText('xy_e2e', { exact: true })).toBeVisible()
  await expect(page.getByText('连接检查失败', { exact: true })).toHaveCount(0)
})
