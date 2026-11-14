import { expect, test } from '@playwright/test'
import type { AppVO } from '../src/types/api'

test('我的应用查询、分页、改名与确认删除后重新读取列表', async ({ page }) => {
  const user = { id: '42', userAccount: 'xy_demo', userName: '星云用户', userRole: 'user' }
  let apps: AppVO[] = Array.from({ length: 12 }, (_, index) => ({
    id: String(9007199254740993n + BigInt(index)),
    userId: user.id,
    appName: index === 0 ? '摄影作品集' : `个人项目 ${index + 1}`,
    initPrompt: '用简洁的网格展示照片，包含关于我和联系方式。',
    codeGenType: 'multi_file',
    createTime: '2026-11-15T10:00:00',
    updateTime: '2026-11-15T10:00:00',
    deployment: null,
  }))
  const app = apps[0]!
  let deletes = 0
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      if (url.pathname === '/api/users/me') {
        await route.fulfill({ json: { code: 0, data: user, message: 'ok' } })
      } else if (url.pathname === '/api/apps/mine') {
        const current = Number(url.searchParams.get('current'))
        const name = url.searchParams.get('appName') ?? ''
        expect(url.searchParams.get('pageSize')).toBe('10')
        const matching = apps.filter((item) => item.appName.includes(name))
        await route.fulfill({
          json: {
            code: 0,
            data: {
              records: matching.slice((current - 1) * 10, current * 10),
              total: matching.length,
              current,
              pageSize: 10,
            },
            message: 'ok',
          },
        })
      } else if (url.pathname === `/api/apps/${app.id}`) {
        if (request.method() === 'PATCH') {
          expect(request.postDataJSON()).toEqual({ appName: '我的摄影网站' })
          app.appName = '我的摄影网站'
          await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
        } else if (request.method() === 'DELETE') {
          expect(request.postData()).toBeNull()
          deletes += 1
          apps = apps.filter((item) => item.id !== app.id)
          await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
        } else {
          expect(request.method()).toBe('GET')
          await route.fulfill({ json: { code: 0, data: app, message: 'ok' } })
        }
      } else if (url.pathname === `/api/apps/${app.id}/messages`) {
        await route.fulfill({
          json: {
            code: 0,
            data: { records: [], hasMore: false, nextCursor: null },
            message: 'ok',
          },
        })
      } else if (url.pathname === `/api/apps/${app.id}/preview`) {
        await route.fulfill({
          status: 409,
          json: { code: 50001, data: null, message: '尚无成功版本' },
        })
      } else {
        await route.abort()
        throw new Error(`意外的 API 请求：${url.pathname}`)
      }
    },
  )

  await page.goto('/')
  const panel = page.getByRole('region', { name: '我的应用', exact: true })
  await expect(panel.getByRole('heading', { level: 3 })).toHaveCount(10)
  await panel.getByTitle('下一页').click()
  await expect(panel.getByRole('link', { name: '个人项目 11', exact: true })).toBeVisible()
  await panel.getByLabel('应用名称', { exact: true }).fill('摄影')
  await panel.getByRole('button', { name: '查询', exact: true }).click()
  await expect(panel.getByRole('heading', { level: 3 })).toHaveCount(1)
  await panel.getByRole('link', { name: '摄影作品集', exact: true }).click()
  await expect(page).toHaveURL(`/apps/${app.id}`)

  await page.getByRole('button', { name: '修改名称', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '修改名称', exact: true })
  await dialog.getByLabel('应用名称', { exact: true }).fill('我的摄影网站')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('我的摄影网站')
  await page.reload()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('我的摄影网站')
  await page.getByRole('link', { name: '返回首页', exact: true }).click()
  await panel.getByRole('link', { name: '我的摄影网站', exact: true }).click()

  await page.getByRole('button', { name: '删除应用', exact: true }).click()
  await expect(page.getByText('确认删除这个应用？', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(deletes).toBe(0)
  await page.getByRole('button', { name: '删除应用', exact: true }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page).toHaveURL('/')
  await expect(panel.getByText('共 11 个应用', { exact: true })).toBeVisible()
  await expect(panel.getByRole('link', { name: '我的摄影网站', exact: true })).toHaveCount(0)
  await page.reload()
  await expect(panel.getByText('共 11 个应用', { exact: true })).toBeVisible()
  await expect(panel.getByRole('link', { name: '我的摄影网站', exact: true })).toHaveCount(0)
  expect(deletes).toBe(1)
})
