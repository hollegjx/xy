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
