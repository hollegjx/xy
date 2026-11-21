import { expect, test } from '@playwright/test'

test('访客可翻页，下一页读取失败时重试该页，末页撤下后刷新回到首页', async ({ page }) => {
  let secondPageReads = 0
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const url = new URL(route.request().url())
      if (url.pathname === '/api/users/me') {
        return route.fulfill({
          status: 401,
          json: { code: 40100, data: null, message: '请先登录' },
        })
      }
      if (url.pathname !== '/api/apps/featured') return route.abort()
      const first = url.searchParams.get('current') === '1'
      if (!first && ++secondPageReads === 1) {
        return route.fulfill({ status: 503, json: { code: 50300, data: null, message: '请重试' } })
      }
      const more = first && secondPageReads < 3
      const data =
        first || secondPageReads < 3
          ? [
              {
                id: first ? '1' : '7',
                appName: first ? '首页作品' : '末页作品',
                codeGenType: 'html',
                deployUrl: '/site/',
              },
            ]
          : []
      await route.fulfill({
        headers: more ? { Link: '<?current=2&pageSize=6>; rel="next"' } : {},
        json: { code: 0, data, message: 'ok' },
      })
    },
  )
  await page.goto('/')
  const list = page.getByRole('region', { name: '精选作品' })
  await expect(list.getByText('首页作品')).toBeVisible()
  await list.getByRole('button', { name: '下一页' }).click()
  await list.getByRole('button', { name: '重试' }).click()
  await expect(list.getByText('末页作品')).toBeVisible()
  await expect(list.getByRole('button', { name: '下一页' })).toBeDisabled()
  await list.getByRole('button', { name: '刷新' }).click()
  await expect(list.getByText('首页作品')).toBeVisible()
  await expect(list.getByRole('navigation')).toHaveCount(0)
})
