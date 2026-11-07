import request from '@/request'
import type { UserLoginRequest, UserRegisterRequest, UserVO } from '@/types/api'

export const registerUser = (data: UserRegisterRequest): Promise<string> =>
  request<string>('/api/users/register', { method: 'POST', data })

export const loginUser = (data: UserLoginRequest): Promise<UserVO> =>
  request<UserVO>('/api/users/login', { method: 'POST', data })
