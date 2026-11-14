import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import type { AppDeployVO } from '../src/types/api'

test('工作台发布、复制与查看作品、下载源码，刷新恢复发布信息并沿用地址', async ({
  page,
  context,
}) => {
  const app = {
    id: '9007199254740993',
    userId: '42',
    appName: '摄影作品集',
    initPrompt: '生成一个摄影作品集',
    codeGenType: 'multi_file',
    createTime: '2026-11-14T10:00:00',
    updateTime: '2026-11-14T10:00:00',
  }
  const siteUrl = 'https://sites.example.test/0123456789abcdef0123456789abcdef/'
  let deployment: AppDeployVO | null = null
  let publishes = 0
  const archive = Buffer.from(
    'UEsDBBQAAAAIAKNpbl1dOOwXXgAAAIEAAAAKAAAAaW5kZXguaHRtbC2N0Q2AIBBDV0EGwAVO5tBPhTOgKIS7H7YXgZ+mbV5TmGw0XBIKx0/QMBR3qyH49xYZwyKJS0ByiCyFy3iORhkiqWHu+BFt0esGZLJPLCibijWvrob1UE0j6+z/+gBQSwMEFAAAAAgAo2luXXTslxcYAAAAFgAAAAkAAABzdHlsZS5jc3NLyk+pVKhWSM7PyS+yUkjKSUzOtlaoBQBQSwMEFAAAAAgAo2luXToxNfkUAAAAEgAAAAkAAABzY3JpcHQuanNLzs8rzs9J1cvJT9dQj4hU17QGAFBLAQIUAxQAAAAIAKNpbl1dOOwXXgAAAIEAAAAKAAAAAAAAAAAAAACAAQAAAABpbmRleC5odG1sUEsBAhQDFAAAAAgAo2luXXTslxcYAAAAFgAAAAkAAAAAAAAAAAAAAIABhgAAAHN0eWxlLmNzc1BLAQIUAxQAAAAIAKNpbl06MTX5FAAAABIAAAAJAAAAAAAAAAAAAACAAcUAAABzY3JpcHQuanNQSwUGAAAAAAMAAwCmAAAAAAEAAAAA',
    'base64',
  )
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      value: {
        writeText: async (text: string) => sessionStorage.setItem('xy-test-copied-link', text),
      },
    })
  })
  await context.route(siteUrl, (route) =>
    route.fulfill({
      contentType: 'text/html;charset=UTF-8',
      body: '<!doctype html><html><body><h1>已发布的摄影作品集</h1></body></html>',
    }),
  )
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (path === '/api/users/me') {
        await route.fulfill({
          json: {
            code: 0,
            data: {
              id: '42',
              userAccount: 'xy_demo',
              userName: '星云用户',
              userRole: 'user',
            },
            message: 'ok',
          },
        })
      } else if (path === `/api/apps/${app.id}`) {
        await route.fulfill({ json: { code: 0, data: { ...app, deployment }, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}/messages`) {
        await route.fulfill({
          json: {
            code: 0,
            data: {
              records: [
                {
                  id: '101',
                  messageRole: 'assistant',
                  content: '生成完成',
                  status: 'completed',
                  errorMessage: null,
                  createTime: app.createTime,
                  updateTime: app.updateTime,
                },
              ],
              hasMore: false,
              nextCursor: null,
            },
            message: 'ok',
          },
        })
      } else if (path === `/api/apps/${app.id}/preview`) {
        await route.fulfill({
          contentType: 'text/html;charset=UTF-8',
          body: '<!doctype html><html><body>摄影作品集预览</body></html>',
        })
      } else if (path === `/api/apps/${app.id}/deploy`) {
        expect(request.method()).toBe('POST')
        expect(request.postData()).toBeNull()
        publishes += 1
        deployment = {
          deployUrl: siteUrl,
          publishedVersion: '101',
          deployedAt: `2026-11-14T10:0${publishes}:00`,
        }
        await route.fulfill({ json: { code: 0, data: deployment, message: 'ok' } })
      } else if (path === `/api/apps/${app.id}/source`) {
        await route.fulfill({ contentType: 'application/zip', body: archive })
      } else {
        await route.abort()
        throw new Error(`意外的 API 请求：${path}`)
      }
    },
  )

  await page.goto(`/apps/${app.id}`)
  const delivery = page.getByRole('region', { name: '发布与下载', exact: true })
  await expect(delivery).toContainText('尚未发布')
  await delivery.getByRole('button', { name: '发布应用', exact: true }).click()
  const view = delivery.getByRole('link', { name: '查看作品', exact: true })
  await expect(view).toHaveAttribute('href', siteUrl)
  await expect(view).toHaveAttribute('rel', 'noopener noreferrer')
  await delivery.getByRole('button', { name: '复制链接', exact: true }).click()
  await expect(page.getByText('链接已复制', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.getItem('xy-test-copied-link'))).toBe(siteUrl)
  const opened = context.waitForEvent('page')
  await view.click()
  const site = await opened
  await expect(site.getByRole('heading', { name: '已发布的摄影作品集' })).toBeVisible()
  await site.close()

  const received = page.waitForEvent('download')
  await delivery.getByRole('button', { name: '下载源码', exact: true }).click()
  const download = await received
  expect(download.suggestedFilename()).toBe(`xy-app-${app.id}.zip`)
  expect(await readFile((await download.path())!)).toEqual(archive)
  await page.reload()
  await expect(view).toHaveAttribute('href', siteUrl)
  await expect(delivery).toContainText('2026-11-14 10:01:00')
  expect(publishes).toBe(1)
  await delivery.getByRole('button', { name: '重新发布', exact: true }).click()
  await expect(delivery).toContainText('2026-11-14 10:02:00')
  await expect(view).toHaveAttribute('href', siteUrl)
  expect(publishes).toBe(2)
})
