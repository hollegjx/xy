import request from '@/request'
import type { AppAddRequest, AppVO } from '@/types/api'

export const createApp = (data: AppAddRequest): Promise<string> =>
  request<string>('/api/apps', { method: 'POST', data })

export const getApp = (id: string): Promise<AppVO> =>
  request<AppVO>(`/api/apps/${encodeURIComponent(id)}`)
