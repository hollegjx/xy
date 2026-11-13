import axios from 'axios'
import type { AxiosRequestConfig, AxiosResponse } from 'axios'
import type { ApiResponse } from '@/types/api'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: number | null = null,
    readonly status: number | null = null,
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

async function sendRequest<T>(
  url: string,
  config: AxiosRequestConfig = {},
): Promise<AxiosResponse<T>> {
  try {
    return await httpClient.request<T>({ ...config, url })
  } catch (error) {
    if (!axios.isAxiosError<unknown>(error)) throw error
    const body = error.response?.data
    if (isApiResponse(body))
      throw new ApiError(body.message || '请求失败，请重试', body.code, error.response?.status)
    if (error.code === 'ECONNABORTED') throw new ApiError('请求超时，请稍后重试')
    throw new ApiError(
      error.response ? '服务请求失败，请稍后重试' : '无法连接后端服务，请确认服务已启动',
      null,
      error.response?.status,
    )
  }
}

export default async function request<T>(url: string, config: AxiosRequestConfig = {}): Promise<T> {
  const { data } = await sendRequest<ApiResponse<T>>(url, config)
  if (!isApiResponse(data)) throw new ApiError('接口返回格式不正确，请稍后重试')
  if (data.code !== 0) throw new ApiError(data.message || '请求失败，请重试', data.code)
  return data.data
}

export async function requestHtml(url: string): Promise<string> {
  const { data, headers } = await sendRequest<string | ApiResponse<null>>(url, {
    headers: { Accept: 'text/html, application/json' },
  })
  if (isApiResponse(data)) throw new ApiError(data.message || '请求失败，请重试', data.code)
  if (typeof data !== 'string' || !String(headers['content-type']).includes('text/html')) {
    throw new ApiError('预览返回格式不正确，请稍后重试')
  }
  return data
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

export async function requestStream(
  url: string,
  data: unknown,
  signal: AbortSignal,
): Promise<ReadableStream<Uint8Array>> {
  let response: Response
  try {
    response = await fetch(url, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(data),
      signal,
    })
  } catch (cause) {
    if (signal.aborted) throw cause
    throw new ApiError('无法连接后端服务，请确认服务已启动')
  }
  if (!response.ok || !response.headers.get('Content-Type')?.includes('text/event-stream')) {
    const body: unknown = await response.json().catch(() => null)
    if (isApiResponse(body)) throw new ApiError(body.message || '请求失败，请重试', body.code)
    throw new ApiError('未能建立生成连接，请稍后重试')
  }
  if (!response.body) throw new ApiError('生成响应为空，请稍后重试')
  return response.body
}
