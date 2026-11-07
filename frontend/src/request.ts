import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import type { ApiResponse } from '@/types/api'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: number | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export const httpClient = axios.create({ timeout: 10000, withCredentials: true })

function isApiResponse(value: unknown): value is ApiResponse<unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    typeof value.code === 'number' &&
    'message' in value &&
    typeof value.message === 'string' &&
    'data' in value
  )
}

export default async function request<T>(url: string, config: AxiosRequestConfig = {}): Promise<T> {
  try {
    const { data } = await httpClient.request<ApiResponse<T>>({ ...config, url })
    if (!isApiResponse(data)) throw new ApiError('接口返回格式不正确，请稍后重试')
    if (data.code !== 0) throw new ApiError(data.message || '请求失败，请重试', data.code)
    return data.data
  } catch (error) {
    if (!axios.isAxiosError<unknown>(error)) throw error
    const body = error.response?.data
    if (isApiResponse(body)) throw new ApiError(body.message || '请求失败，请重试', body.code)
    if (error.code === 'ECONNABORTED') throw new ApiError('请求超时，请稍后重试')
    throw new ApiError(
      error.response ? '服务请求失败，请稍后重试' : '无法连接后端服务，请确认服务已启动',
    )
  }
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}
