import request from '@/request'
import type {
  UserLoginRequest,
  UserPageVO,
  UserQueryRequest,
  UserRegisterRequest,
  UserVO,
} from '@/types/api'

export const listUsers = (params: UserQueryRequest): Promise<UserPageVO> =>
  request<UserPageVO>('/api/users', { params })

export const registerUser = (data: UserRegisterRequest): Promise<string> =>
  request<string>('/api/users/register', { method: 'POST', data })

export const loginUser = (data: UserLoginRequest): Promise<UserVO> =>
  request<UserVO>('/api/users/login', { method: 'POST', data })

export const getCurrentUser = (): Promise<UserVO> => request<UserVO>('/api/users/me')

export const logoutUser = (): Promise<boolean> =>
  request<boolean>('/api/users/logout', { method: 'POST' })
