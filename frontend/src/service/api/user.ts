import request from '@/request'
import type { UserRegisterRequest } from '@/types/api'

export const registerUser = (data: UserRegisterRequest): Promise<string> =>
  request<string>('/api/users/register', { method: 'POST', data })
