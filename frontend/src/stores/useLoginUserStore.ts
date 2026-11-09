import { ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, errorMessage } from '@/request'
import { getCurrentUser, loginUser, logoutUser } from '@/service/api/user'
import type { UserLoginRequest, UserVO } from '@/types/api'

export const useLoginUserStore = defineStore('loginUser', () => {
  const user = ref<UserVO | null>(null)
  const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const error = ref('')
  const authAction = ref<'login' | 'logout' | null>(null)
  let identityRevision = 0

  function setUser(value: UserVO | null): void {
    identityRevision += 1
    user.value = value
    status.value = 'ready'
    error.value = ''
  }

  async function loadUser(): Promise<void> {
    if (status.value === 'loading') return
    status.value = 'loading'
    error.value = ''
    const revision = identityRevision
    try {
      const currentUser = await getCurrentUser()
      // 刷新时的读取可能晚于登录完成，不能覆盖已经改变的身份。
      if (revision === identityRevision) setUser(currentUser)
    } catch (cause) {
      if (revision !== identityRevision) return
      if (cause instanceof ApiError && cause.code === 40100) {
        setUser(null)
      } else {
        user.value = null
        status.value = 'error'
        error.value = errorMessage(cause)
      }
    }
  }

  // 登录和退出都会改变服务端 Session，必须等待前一个操作结束。
  async function login(data: UserLoginRequest): Promise<boolean> {
    if (authAction.value !== null) return false
    authAction.value = 'login'
    try {
      setUser(await loginUser(data))
      return true
    } finally {
      authAction.value = null
    }
  }

  async function logout(): Promise<boolean> {
    if (authAction.value !== null) return false
    authAction.value = 'logout'
    try {
      try {
        await logoutUser()
      } catch (cause) {
        // 会话已失效时，本地同样应回到未登录状态；其他失败保留身份供重试。
        if (!(cause instanceof ApiError && cause.code === 40100)) throw cause
      }
      setUser(null)
      return true
    } finally {
      authAction.value = null
    }
  }

  return { user, status, error, authAction, setUser, loadUser, login, logout }
})
