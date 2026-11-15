import { expect, test } from '@playwright/test'
import type { UserVO } from '../src/types/api'

test('管理员查询、新增、修改昵称和删除用户，普通用户不能进入用户管理', async ({ page }) => {
  const admin: UserVO = { id: '42', userAccount: 'admin', userName: '管理员', userRole: 'admin' }
  const users: UserVO[] = Array.from({ length: 12 }, (_, index) => ({
    id: String(9007199254740993n + BigInt(index)),
    userAccount: `xy_user_${index + 1}`,
    userName: `星云用户 ${index + 1}`,
    userRole: 'user',
  }))
  users.push(admin)
  let currentUser: UserVO | null = null
  let listRequests = 0
  const newUser = { userAccount: 'xy_new_member', userPassword: 'Demo123456!', userName: '新成员' }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      if (url.pathname === '/api/users/login') {
        currentUser =
          request.postDataJSON().userAccount === 'admin'
            ? admin
            : users.find((user) => user.userAccount === request.postDataJSON().userAccount)!
        await route.fulfill({ json: { code: 0, data: currentUser, message: 'ok' } })
      } else if (url.pathname === '/api/users/me') {
        await route.fulfill({
          status: currentUser ? 200 : 401,
          json: currentUser
            ? { code: 0, data: currentUser, message: 'ok' }
            : { code: 40100, data: null, message: '请先登录' },
        })
      } else if (url.pathname === '/api/users/logout') {
        currentUser = null
        await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
      } else if (/^\/api\/users\/\d+$/.test(url.pathname)) {
        expect(currentUser?.userRole).toBe('admin')
        const targetIndex = users.findIndex((user) => user.id === url.pathname.split('/').pop())
        const target = users[targetIndex]
        expect(target).toBeDefined()
        if (request.method() === 'DELETE') {
          expect(target!.userAccount).toBe(newUser.userAccount)
          expect(request.postData()).toBeNull()
          users.splice(targetIndex, 1)
        } else {
          expect(request.method()).toBe('PATCH')
          const data = request.postDataJSON()
          expect(data).toEqual({ userName: expect.any(String) })
          target!.userName = data.userName
        }
        await route.fulfill({ json: { code: 0, data: true, message: 'ok' } })
      } else if (url.pathname === '/api/users') {
        expect(currentUser?.userRole).toBe('admin')
        if (request.method() === 'POST') {
          expect(request.postDataJSON()).toEqual(newUser)
          const id = '9007199254741006'
          users.unshift({
            id,
            userAccount: newUser.userAccount,
            userName: newUser.userName,
            userRole: 'user',
          })
          await route.fulfill({ json: { code: 0, data: id, message: 'ok' } })
          return
        }
        expect(request.method()).toBe('GET')
        listRequests += 1
        const current = Number(url.searchParams.get('current'))
        expect(url.searchParams.get('pageSize')).toBe('10')
        const matching = users.filter((user) =>
          user.userAccount.includes(url.searchParams.get('userAccount') ?? ''),
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

  await page.goto('/admin/users')
  await expect(page).toHaveURL('/user/login?redirect=/admin/users')
  await page.getByLabel('账号', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill('Demo123456!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/admin/users')
  const panel = page.getByRole('region', { name: '用户管理', exact: true })
  await expect(panel.getByText(users[0]!.id, { exact: true })).toBeVisible()
  await expect(panel.getByText('共 13 个用户')).toBeVisible()
  await panel.getByTitle('下一页').click()
  await expect(panel.getByText('xy_user_11', { exact: true })).toBeVisible()
  const memberRow = panel.getByRole('row').filter({ hasText: 'xy_user_11' })
  await memberRow.getByRole('button', { name: '修改昵称', exact: true }).click()
  const renameDialog = page.getByRole('dialog', { name: '修改昵称', exact: true })
  await expect(renameDialog.getByLabel('昵称', { exact: true })).toHaveValue('星云用户 11')
  await renameDialog.getByLabel('昵称', { exact: true }).fill('更新后的成员')
  await renameDialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(renameDialog).toBeHidden()
  await expect(memberRow.getByText('更新后的成员', { exact: true })).toBeVisible()
  await expect(panel.getByText('xy_user_1', { exact: true })).toHaveCount(0)
  await page.reload()
  await panel.getByTitle('下一页').click()
  await expect(memberRow.getByText('更新后的成员', { exact: true })).toBeVisible()
  await panel.getByLabel('登录账号', { exact: true }).fill('xy_user_12')
  await panel.getByRole('button', { name: '查询', exact: true }).click()
  await expect(panel.getByText('共 1 个用户')).toBeVisible()
  await expect(panel.getByText('xy_user_12', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '新增用户', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增用户', exact: true })
  await dialog.getByLabel('账号', { exact: true }).fill(newUser.userAccount)
  await dialog.getByLabel('初始密码', { exact: true }).fill(newUser.userPassword)
  await dialog.getByLabel('昵称', { exact: true }).fill(newUser.userName)
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(panel.getByLabel('登录账号', { exact: true })).toHaveValue('')
  await expect(panel.getByText(newUser.userAccount, { exact: true })).toBeVisible()
  await expect(panel.getByText('共 14 个用户')).toBeVisible()
  await expect(page.getByLabel('当前账号', { exact: true })).toContainText('管理员')
  await page.reload()
  await expect(panel.getByText(newUser.userAccount, { exact: true })).toBeVisible()
  await expect(panel.getByText('xy_user_1', { exact: true })).toBeVisible()
  const newMemberRow = panel.getByRole('row').filter({ hasText: newUser.userAccount })
  await newMemberRow.getByRole('button', { name: '删除', exact: true }).click()
  await expect(page.getByText('确认删除这个用户？', { exact: true })).toBeVisible()
  await expect(
    page.getByText(`将删除账号「${newUser.userAccount}」`, { exact: false }),
  ).toContainText('全部应用，并下线公开作品')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(newMemberRow).toBeVisible()
  await newMemberRow.getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(newMemberRow).toHaveCount(0)
  await expect(panel.getByText('共 13 个用户')).toBeVisible()
  await page.reload()
  await expect(panel.getByText('共 13 个用户')).toBeVisible()
  await expect(panel.getByText(newUser.userAccount, { exact: true })).toHaveCount(0)
  await panel.getByLabel('登录账号', { exact: true }).fill('admin')
  await panel.getByRole('button', { name: '查询', exact: true }).click()
  await expect(panel.getByText('共 1 个用户')).toBeVisible()
  await expect(panel.getByRole('button', { name: '删除', exact: true })).toHaveCount(0)
  await panel.getByRole('button', { name: '修改昵称', exact: true }).click()
  await renameDialog.getByLabel('昵称', { exact: true }).fill('星云维护员')
  await renameDialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(renameDialog).toBeHidden()
  await expect(panel.getByText('星云维护员', { exact: true })).toBeVisible()
  await expect(panel.getByLabel('登录账号', { exact: true })).toHaveValue('admin')
  await expect(panel.getByText('共 1 个用户')).toBeVisible()
  await expect(page.getByLabel('当前账号', { exact: true })).toContainText('星云维护员')
  await page.reload()
  await expect(page.getByLabel('当前账号', { exact: true })).toContainText('星云维护员')
  await page.getByRole('link', { name: '星云首页', exact: true }).click()
  await page.getByRole('link', { name: '用户管理', exact: true }).click()
  await expect(panel.getByText('共 13 个用户')).toBeVisible()
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page).toHaveURL('/user/login')
  await page.getByLabel('账号', { exact: true }).fill('xy_user_1')
  await page.getByLabel('密码', { exact: true }).fill('Demo123456!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/')
  await expect(page.getByRole('link', { name: '用户管理', exact: true })).toHaveCount(0)
  const requestsBeforeDeniedVisit = listRequests
  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: '无权访问', exact: true })).toBeVisible()
  expect(listRequests).toBe(requestsBeforeDeniedVisit)
})
