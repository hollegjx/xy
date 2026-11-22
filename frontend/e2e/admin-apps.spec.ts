import { expect, test } from '@playwright/test'
import type { AppAdminVO, UserVO } from '../src/types/api'

test('管理员管理应用并设置精选，访客从首页浏览公开作品，普通用户不能访问管理页', async ({
  page,
  context,
}) => {
  const admin: UserVO = { id: '42', userAccount: 'admin', userName: '管理员', userRole: 'admin' }
  const member: UserVO = {
    id: '9007199254742001',
    userAccount: 'xy_member',
    userName: '星云成员',
    userRole: 'user',
  }
  const apps: AppAdminVO[] = Array.from({ length: 12 }, (_, index) => ({
    id: String(9007199254740993n + BigInt(index)),
    userId: index === 11 ? admin.id : member.id,
    appName: index === 0 ? '摄影作品集' : `摄影项目 ${index + 1}`,
    codeGenType: index === 0 ? 'html' : 'multi_file',
    featured: false,
    createTime: '2026-11-15T10:00:00',
    updateTime: '2026-11-15T11:00:00',
    deployment:
      index === 0
        ? {
            deployUrl: 'https://sites.example.test/photography/',
            publishedVersion: '101',
            deployedAt: '2026-11-15T11:00:00',
          }
        : null,
  }))
  let currentUser: UserVO | null = null
  let listRequests = 0
  let detailRequests = 0
  let updateRequests = 0
  let deleteRequests = 0
  const featuredChanges: boolean[] = []
  const deletedAppId = apps[0]!.id
  const renamedAppName = '摄影项目 11 · 夜景'
  await context.route('https://sites.example.test/**', (route) =>
    route.fulfill({ contentType: 'text/html; charset=utf-8', body: '<h1>公开摄影作品</h1>' }),
  )
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      if (url.pathname === '/api/users/me') {
        await route.fulfill({
          status: currentUser ? 200 : 401,
          json: currentUser
            ? { code: 0, data: currentUser, message: 'ok' }
            : { code: 40100, data: null, message: '请先登录' },
        })
      } else if (url.pathname === '/api/users/login') {
        currentUser = request.postDataJSON().userAccount === 'admin' ? admin : member
        await route.fulfill({ json: { code: 0, data: currentUser, message: 'ok' } })
      } else if (url.pathname === '/api/users/logout') {
        currentUser = null
        await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
      } else if (url.pathname === `/api/admin/apps/${deletedAppId}/featured`) {
        expect(request.method()).toBe('PATCH')
        expect(currentUser?.userRole).toBe('admin')
        const featured = featuredChanges.length === 0
        expect(request.postDataJSON()).toEqual({ featured })
        featuredChanges.push(featured)
        apps.find((app) => app.id === deletedAppId)!.featured = featured
        await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
      } else if (url.pathname === `/api/admin/apps/${deletedAppId}` && request.method() === 'GET') {
        expect(currentUser?.userRole).toBe('admin')
        detailRequests += 1
        if (detailRequests === 1) {
          await route.fulfill({
            status: 503,
            json: { code: 50300, data: null, message: '暂时无法查询' },
          })
        } else {
          const app = apps.find((entry) => entry.id === deletedAppId)!
          await route.fulfill({
            json: { code: 0, data: { ...app, appName: '摄影作品集 · 最新详情' }, message: 'ok' },
          })
        }
      } else if (url.pathname === `/api/admin/apps/${deletedAppId}`) {
        expect(request.method()).toBe('DELETE')
        expect(currentUser?.userRole).toBe('admin')
        deleteRequests += 1
        apps.splice(
          apps.findIndex((app) => app.id === deletedAppId),
          1,
        )
        await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
      } else if (url.pathname === `/api/admin/apps/${apps[10]!.id}`) {
        expect(request.method()).toBe('PATCH')
        expect(currentUser?.userRole).toBe('admin')
        expect(request.postDataJSON()).toEqual({ appName: renamedAppName })
        updateRequests += 1
        apps[10]!.appName = renamedAppName
        await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
      } else if (url.pathname === '/api/apps') {
        expect(request.method()).toBe('GET')
        expect(currentUser?.userRole).toBe('admin')
        expect(url.searchParams.get('pageSize')).toBe('10')
        listRequests += 1
        const current = Number(url.searchParams.get('current'))
        const name = url.searchParams.get('appName') ?? ''
        const owner = url.searchParams.get('userId')
        if (owner) expect(owner).toBe(member.id)
        const matching = apps.filter(
          (app) => app.appName.includes(name) && (!owner || app.userId === owner),
        )
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
      } else if (url.pathname === '/api/users') {
        await route.fulfill({
          json: {
            code: 0,
            data: { records: [admin, member], total: 2, current: 1, pageSize: 10 },
            message: 'ok',
          },
        })
      } else if (url.pathname === '/api/apps/featured') {
        expect(request.method()).toBe('GET')
        const featured = apps
          .filter((app) => app.featured && app.deployment?.deployUrl)
          .map((app) => ({
            id: app.id,
            appName: app.appName,
            codeGenType: app.codeGenType,
            deployUrl: app.deployment!.deployUrl,
            coverUrl: `/api/apps/${app.id}/covers/${app.deployment!.publishedVersion}`,
          }))
        await route.fulfill({ json: { code: 0, data: featured, message: 'ok' } })
      } else if (url.pathname === `/api/apps/${deletedAppId}/covers/101`) {
        await route.fulfill({
          contentType: 'image/svg+xml',
          body: '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"/>',
        })
      } else if (url.pathname === '/api/apps/mine') {
        await route.fulfill({
          json: {
            code: 0,
            data: { records: [], total: 0, current: 1, pageSize: 10 },
            message: 'ok',
          },
        })
      } else {
        await route.abort()
        throw new Error(`意外的 API 请求：${url.pathname}`)
      }
    },
  )

  await page.goto('/admin/apps')
  await expect(page).toHaveURL('/user/login?redirect=/admin/apps')
  await page.getByLabel('账号', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill('Demo123456!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/admin/apps')
  const panel = page.getByRole('region', { name: '应用管理', exact: true })
  await expect(panel.getByText('共 12 个应用')).toBeVisible()
  await expect(panel.getByText(`ID：${apps[0]!.id}`, { exact: true })).toBeVisible()
  const publishedRow = panel.getByRole('row').filter({ hasText: '摄影作品集' })
  await expect(publishedRow.getByText('单 HTML', { exact: true })).toBeVisible()
  await expect(publishedRow.getByText('已发布', { exact: true })).toBeVisible()
  const unpublishedRow = panel.getByRole('row').filter({ hasText: '摄影项目 2' })
  await expect(unpublishedRow.getByText('未发布', { exact: true })).toBeVisible()
  await expect(unpublishedRow.getByRole('button', { name: '设为精选', exact: true })).toHaveCount(0)
  await expect(unpublishedRow.getByRole('link')).toHaveCount(0)
  await expect(panel.getByRole('link')).toHaveCount(1)
  await publishedRow.getByRole('button', { name: '查看「摄影作品集」详情' }).click()
  const detail = page.getByRole('dialog', { name: '应用详情' })
  await expect(detail.getByRole('alert')).toContainText('暂时无法查询')
  await detail.getByRole('button', { name: '重试' }).click()
  await expect(detail.getByText('摄影作品集 · 最新详情', { exact: true })).toBeVisible()
  await expect(detail.getByText('101', { exact: true })).toBeVisible()
  await expect(detail.getByRole('link', { name: '查看作品' })).toHaveAttribute(
    'href',
    'https://sites.example.test/photography/',
  )
  await page.keyboard.press('Escape')
  await expect(detail).toBeHidden()
  expect(detailRequests).toBe(2)
  const popupPromise = page.waitForEvent('popup')
  await publishedRow.getByRole('link', { name: '查看作品', exact: true }).click()
  const popup = await popupPromise
  await expect(popup.getByRole('heading', { name: '公开摄影作品', exact: true })).toBeVisible()
  await popup.close()

  await panel.getByTitle('下一页').click()
  await expect(panel.getByText('摄影项目 12', { exact: true })).toBeVisible()
  await panel.getByLabel('应用名称', { exact: true }).fill(' 摄影 ')
  await panel.getByLabel('创建者 ID', { exact: true }).fill(member.id)
  await panel.getByRole('button', { name: '查询', exact: true }).click()
  await expect(panel.getByText('共 11 个应用')).toBeVisible()
  await expect(panel.getByText('摄影作品集', { exact: true })).toBeVisible()
  await panel.getByTitle('下一页').click()
  await expect(panel.getByText('摄影项目 11', { exact: true })).toBeVisible()
  await expect(panel.getByText('摄影项目 12', { exact: true })).toHaveCount(0)
  await panel.getByRole('button', { name: '修改名称', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '修改名称', exact: true })
  await expect(dialog.getByText(`应用 ID：${apps[10]!.id}`, { exact: true })).toBeVisible()
  await expect(dialog.getByLabel('应用名称', { exact: true })).toHaveValue('摄影项目 11')
  await dialog.getByLabel('应用名称', { exact: true }).fill(renamedAppName)
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(panel.getByText(renamedAppName, { exact: true })).toBeVisible()
  await expect(panel.getByText('摄影作品集', { exact: true })).toHaveCount(0)
  await expect(panel.getByLabel('应用名称', { exact: true })).toHaveValue(' 摄影 ')
  await expect(panel.getByLabel('创建者 ID', { exact: true })).toHaveValue(member.id)
  expect(updateRequests).toBe(1)
  await panel.getByRole('button', { name: '重置', exact: true }).click()
  await expect(panel.getByLabel('应用名称', { exact: true })).toHaveValue('')
  await expect(panel.getByLabel('创建者 ID', { exact: true })).toHaveValue('')
  await expect(panel.getByText('共 12 个应用')).toBeVisible()
  await panel.getByLabel('应用名称', { exact: true }).fill('没有这个应用')
  await panel.getByRole('button', { name: '查询', exact: true }).click()
  await expect(panel.getByText('没有找到匹配的应用，试试其他筛选条件。')).toBeVisible()
  await panel.getByRole('button', { name: '清除筛选', exact: true }).click()
  await expect(panel.getByText('共 12 个应用')).toBeVisible()
  await page.getByRole('link', { name: '用户管理', exact: true }).click()
  await expect(page.getByRole('heading', { name: '用户管理', exact: true })).toBeVisible()
  await page.getByRole('link', { name: '应用管理', exact: true }).click()
  await expect(panel.getByText('共 12 个应用')).toBeVisible()
  await page.reload()
  await expect(panel.getByText('摄影作品集', { exact: true })).toBeVisible()
  await panel.getByLabel('应用名称', { exact: true }).fill(renamedAppName)
  await panel.getByRole('button', { name: '查询', exact: true }).click()
  await expect(panel.getByText('共 1 个应用')).toBeVisible()
  await expect(panel.getByText(renamedAppName, { exact: true })).toBeVisible()

  await panel.getByRole('button', { name: '重置', exact: true }).click()
  await expect(panel.getByText('共 12 个应用')).toBeVisible()
  await panel.getByLabel('应用名称', { exact: true }).fill('摄影')
  await panel.getByRole('button', { name: '查询', exact: true }).click()
  await publishedRow.getByRole('button', { name: '设为精选', exact: true }).click()
  await expect(publishedRow.getByText('精选', { exact: true })).toBeVisible()
  await expect(publishedRow.getByRole('button', { name: '取消精选', exact: true })).toBeVisible()
  await expect(panel.getByLabel('应用名称', { exact: true })).toHaveValue('摄影')
  await page.reload()
  await expect(publishedRow.getByText('精选', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page).toHaveURL('/user/login')
  await page.getByRole('link', { name: '星云首页', exact: true }).click()
  const featuredPanel = page.getByRole('region', { name: '精选作品', exact: true })
  await expect(
    featuredPanel.getByRole('heading', { name: '摄影作品集', exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('region', { name: '我的应用', exact: true })).toHaveCount(0)
  await expect(featuredPanel.getByRole('link')).toHaveCount(1)
  await expect(featuredPanel.locator('img.work-cover')).toHaveJSProperty('naturalWidth', 960)
  const featuredPopupPromise = page.waitForEvent('popup')
  await featuredPanel.getByRole('link', { name: '查看作品', exact: true }).click()
  const featuredPopup = await featuredPopupPromise
  await expect(
    featuredPopup.getByRole('heading', { name: '公开摄影作品', exact: true }),
  ).toBeVisible()
  await featuredPopup.close()
  await page.goto('/admin/apps')
  await page.getByLabel('账号', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill('Demo123456!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await publishedRow.getByRole('button', { name: '取消精选', exact: true }).click()
  await expect(publishedRow.getByRole('button', { name: '设为精选', exact: true })).toBeVisible()
  await expect(publishedRow.getByText('精选', { exact: true })).toHaveCount(0)
  await expect(publishedRow.getByRole('link', { name: '查看作品', exact: true })).toHaveAttribute(
    'href',
    'https://sites.example.test/photography/',
  )
  expect(featuredChanges).toEqual([true, false])
  await page.getByRole('link', { name: '星云首页', exact: true }).click()
  await expect(featuredPanel.getByText('还没有精选作品，期待灵感在此成形。')).toBeVisible()
  await page.getByRole('link', { name: '应用管理', exact: true }).click()
  await panel.getByLabel('应用名称', { exact: true }).fill('摄影')
  await panel.getByRole('button', { name: '查询', exact: true }).click()
  await publishedRow.getByRole('button', { name: '删除', exact: true }).click()
  await expect(
    page.getByText(`将删除应用「摄影作品集」（ID：${deletedAppId}）`, { exact: false }),
  ).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(deleteRequests).toBe(0)
  await expect(publishedRow).toBeVisible()
  await publishedRow.getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(panel.getByText('共 11 个应用')).toBeVisible()
  await expect(publishedRow).toHaveCount(0)
  await expect(panel.getByLabel('应用名称', { exact: true })).toHaveValue('摄影')
  expect(deleteRequests).toBe(1)
  await page.reload()
  await expect(panel.getByText('共 11 个应用')).toBeVisible()
  await expect(panel.getByText(`ID：${deletedAppId}`, { exact: true })).toHaveCount(0)

  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page).toHaveURL('/user/login')
  await page.getByLabel('账号', { exact: true }).fill(member.userAccount)
  await page.getByLabel('密码', { exact: true }).fill('Demo123456!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/')
  await expect(page.getByRole('link', { name: '应用管理', exact: true })).toHaveCount(0)
  const requestsBeforeDeniedVisit = listRequests
  await page.goto('/admin/apps')
  await expect(page.getByRole('heading', { name: '无权访问', exact: true })).toBeVisible()
  expect(listRequests).toBe(requestsBeforeDeniedVisit)
})
