import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/request'
import { getCurrentUser } from '@/service/api/user'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import type { UserVO } from '@/types/api'

vi.mock('@/service/api/user', () => ({ getCurrentUser: vi.fn() }))

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
})
