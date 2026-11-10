export interface ApiResponse<T> {
  code: number
  data: T
  message: string
}

export interface HealthStatus {
  status: 'UP'
  database: string
}

export interface UserRegisterRequest {
  userAccount: string
  userPassword: string
  checkPassword: string
}

export interface UserLoginRequest {
  userAccount: string
  userPassword: string
}

export interface UserVO {
  id: string
  userAccount: string
  userName: string
  userRole: 'user' | 'admin'
}

export type CodeGenType = 'html' | 'multi_file'

export interface AppAddRequest {
  initPrompt: string
  codeGenType?: CodeGenType
}

export interface AppVO {
  id: string
  userId: string
  appName: string
  initPrompt: string
  codeGenType: CodeGenType
  createTime: string
  updateTime: string
}
