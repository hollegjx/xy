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

export interface UserQueryRequest {
  userAccount?: string
  current: number
  pageSize: number
}

export interface UserPageVO {
  records: UserVO[]
  total: number
  current: number
  pageSize: number
}

export type CodeGenType = 'html' | 'multi_file'

export interface AppAddRequest {
  initPrompt: string
  codeGenType?: CodeGenType
}

export interface AppUpdateRequest {
  appName: string
}

export interface AppVO {
  id: string
  userId: string
  appName: string
  initPrompt: string
  codeGenType: CodeGenType
  createTime: string
  updateTime: string
  deployment: AppDeployVO | null
}

export interface AppDeployVO {
  deployUrl: string | null
  publishedVersion: string
  deployedAt: string
}

export interface AppQueryRequest {
  appName?: string
  current: number
  pageSize: number
}

export interface AppPageVO {
  records: AppVO[]
  total: number
  current: number
  pageSize: number
}

export type GenerationStatus = 'running' | 'completed' | 'failed' | 'cancelled'

export interface GenerationEventVO {
  generationId: string
  status: GenerationStatus
  text?: string
  message?: string
}

export interface ChatHistoryVO {
  id: string
  messageRole: 'user' | 'assistant'
  content: string
  status: GenerationStatus
  errorMessage: string | null
  createTime: string
  updateTime: string
}

export interface ChatHistoryPageVO {
  records: ChatHistoryVO[]
  hasMore: boolean
  nextCursor: string | null
}

export interface ChatHistoryQueryRequest {
  beforeId?: string
  pageSize?: number
}
