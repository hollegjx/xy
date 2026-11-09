import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/request'
import { getCurrentUser, loginUser, logoutUser } from '@/service/api/user'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { UserVO } from '@/types/api'

vi.mock('@/service/api/user', () => ({
  getCurrentUser: vi.fn(),
  loginUser: vi.fn(),
  logoutUser: vi.fn(),
}))

const currentUser: UserVO = {
  id: '42',
  userAccount: 'xy_demo',
  userName: '星云用户',
  userRole: 'user',
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.resetAllMocks()
})

describe('当前登录用户', () => {
  it('根据后端 Session 恢复用户信息', async () => {
    vi.mocked(getCurrentUser).mockResolvedValue(currentUser)
    const store = useLoginUserStore()
    await store.loadUser()
    expect(store.user).toEqual(currentUser)
    expect(store.status).toBe('ready')
  })

  it('未登录时清除身份并正常显示访客入口', async () => {
    vi.mocked(getCurrentUser).mockRejectedValue(new ApiError('请先登录', 40100))
    const store = useLoginUserStore()
    store.setUser(currentUser)
    await store.loadUser()
    expect(store.user).toBeNull()
    expect(store.status).toBe('ready')
    expect(store.error).toBe('')
  })

  it('读取失败时显示错误，重试成功后恢复身份', async () => {
    vi.mocked(getCurrentUser)
      .mockRejectedValueOnce(new ApiError('无法连接后端服务'))
      .mockResolvedValueOnce(currentUser)
    const store = useLoginUserStore()
    await store.loadUser()
    expect(store.status).toBe('error')
    expect(store.error).toBe('无法连接后端服务')
    expect(store.user).toBeNull()
    await store.loadUser()
    expect(store.user).toEqual(currentUser)
    expect(store.status).toBe('ready')
    expect(store.error).toBe('')
  })

  it.each(['旧身份', '未登录'] as const)('登录成功后忽略较早请求返回的%s', async (outcome) => {
    let resolve!: (value: UserVO) => void
    let reject!: (reason: Error) => void
    vi.mocked(getCurrentUser).mockReturnValue(
      new Promise<UserVO>((onResolve, onReject) => {
        resolve = onResolve
        reject = onReject
      }),
    )
    const store = useLoginUserStore()
    const pending = store.loadUser()
    expect(store.status).toBe('loading')
    store.setUser(currentUser)
    if (outcome === '旧身份') resolve({ ...currentUser, id: '1', userName: '旧用户' })
    else reject(new ApiError('请先登录', 40100))
    await pending
    expect(store.user).toEqual(currentUser)
    expect(store.status).toBe('ready')
    expect(store.error).toBe('')
  })

  it('退出接口成功后才清除当前身份', async () => {
    let finishLogout!: (value: boolean) => void
    vi.mocked(logoutUser).mockReturnValue(
      new Promise<boolean>((resolve) => {
        finishLogout = resolve
      }),
    )
    const store = useLoginUserStore()
    store.setUser(currentUser)
    const pending = store.logout()
    expect(store.user).toEqual(currentUser)
    finishLogout(true)
    await pending
    expect(store.user).toBeNull()
    expect(store.status).toBe('ready')
  })

  it('退出时会话已经失效，也清除本地身份', async () => {
    vi.mocked(logoutUser).mockRejectedValue(new ApiError('请先登录', 40100))
    const store = useLoginUserStore()
    store.setUser(currentUser)
    await store.logout()
    expect(store.user).toBeNull()
    expect(store.status).toBe('ready')
  })

  it('退出失败保留身份，重试成功后再清除', async () => {
    vi.mocked(logoutUser)
      .mockRejectedValueOnce(new ApiError('无法连接后端服务'))
      .mockResolvedValueOnce(true)
    const store = useLoginUserStore()
    store.setUser(currentUser)
    await expect(store.logout()).rejects.toThrow('无法连接后端服务')
    expect(store.user).toEqual(currentUser)
    expect(store.status).toBe('ready')
    await store.logout()
    expect(store.user).toBeNull()
  })

  it('退出响应返回前拦截新登录，退出结束后允许登录另一账号', async () => {
    let finishLogout!: (value: boolean) => void
    vi.mocked(logoutUser).mockReturnValue(
      new Promise((resolve) => {
        finishLogout = resolve
      }),
    )
    const nextUser = { ...currentUser, id: '43', userAccount: 'xy_next' }
    const credentials = { userAccount: nextUser.userAccount, userPassword: 'Demo123456!' }
    vi.mocked(loginUser).mockResolvedValue(nextUser)
    const store = useLoginUserStore()
    store.setUser(currentUser)
    const pending = store.logout()
    expect(store.authAction).toBe('logout')
    expect(await store.login(credentials)).toBe(false)
    expect(loginUser).not.toHaveBeenCalled()
    expect(store.user).toEqual(currentUser)
    finishLogout(true)
    expect(await pending).toBe(true)
    expect(store.authAction).toBeNull()
    expect(await store.login(credentials)).toBe(true)
    expect(store.user).toEqual(nextUser)
  })

  it('登录响应返回前拦截退出，不改变登录结果', async () => {
    let finishLogin!: (value: UserVO) => void
    vi.mocked(loginUser).mockReturnValue(
      new Promise((resolve) => {
        finishLogin = resolve
      }),
    )
    const store = useLoginUserStore()
    store.setUser(currentUser)
    const nextUser = { ...currentUser, id: '43', userAccount: 'xy_next' }
    const pending = store.login({ userAccount: nextUser.userAccount, userPassword: 'Demo123456!' })
    expect(store.authAction).toBe('login')
    expect(await store.logout()).toBe(false)
    expect(logoutUser).not.toHaveBeenCalled()
    finishLogin(nextUser)
    expect(await pending).toBe(true)
    expect(store.authAction).toBeNull()
    expect(store.user).toEqual(nextUser)
  })

  it('登录失败也释放操作状态，允许后续操作', async () => {
    vi.mocked(loginUser).mockRejectedValue(new ApiError('账号或密码错误', 40000))
    vi.mocked(logoutUser).mockResolvedValue(true)
    const store = useLoginUserStore()
    store.setUser(currentUser)
    await expect(store.login({ userAccount: 'xy_next', userPassword: 'wrong' })).rejects.toThrow(
      '账号或密码错误',
    )
    expect(store.authAction).toBeNull()
    expect(store.user).toEqual(currentUser)
    expect(await store.logout()).toBe(true)
  })
})
