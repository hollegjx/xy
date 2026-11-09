import { ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, errorMessage } from '@/request'
import { getCurrentUser } from '@/service/api/user'
import type { UserVO } from '@/types/api'

export const useLoginUserStore = defineStore('loginUser', () => {
  const user = ref<UserVO | null>(null)
  const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const error = ref('')
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

  return { user, status, error, setUser, loadUser }
})
