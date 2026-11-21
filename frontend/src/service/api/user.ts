import * as api from '@/service/generated/user'
import type {
  UserAddRequest,
  UserLoginRequest,
  UserPageVO,
  UserQueryRequest,
  UserRegisterRequest,
  UserUpdateRequest,
  UserVO,
} from '@/types/api'

export const addUser = (data: UserAddRequest): Promise<string> => api.addUser(data)
export const listUsers = (params: UserQueryRequest): Promise<UserPageVO> => api.listUsers(params)
export const updateUser = (id: string, data: UserUpdateRequest): Promise<boolean> =>
  api.updateUser({ id: encodeURIComponent(id) }, data)
export const deleteUser = (id: string): Promise<boolean> =>
  api.deleteUser({ id: encodeURIComponent(id) })
export const registerUser = (data: UserRegisterRequest): Promise<string> => api.register(data)
export const loginUser = (data: UserLoginRequest): Promise<UserVO> => api.login(data)
export const getCurrentUser = (): Promise<UserVO> => api.getCurrentUser()
export const logoutUser = (): Promise<boolean> => api.logout()
