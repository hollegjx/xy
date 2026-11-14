import request, { ApiError, requestHtml, requestZip } from '@/request'
import type {
  AppAddRequest,
  AppDeployVO,
  AppPageVO,
  AppQueryRequest,
  AppUpdateRequest,
  AppVO,
} from '@/types/api'

export const createApp = (data: AppAddRequest): Promise<string> =>
  request<string>('/api/apps', { method: 'POST', data })

export const getApp = (id: string): Promise<AppVO> =>
  request<AppVO>(`/api/apps/${encodeURIComponent(id)}`)

export const updateApp = (id: string, data: AppUpdateRequest): Promise<boolean> =>
  request<boolean>(`/api/apps/${encodeURIComponent(id)}`, { method: 'PATCH', data })

export const deleteApp = (id: string): Promise<boolean> =>
  request<boolean>(`/api/apps/${encodeURIComponent(id)}`, { method: 'DELETE' })

export const listMyApps = (params: AppQueryRequest): Promise<AppPageVO> =>
  request<AppPageVO>('/api/apps/mine', { params })

export const getAppSource = (id: string): Promise<Blob> =>
  requestZip(`/api/apps/${encodeURIComponent(id)}/source`)

export const deployApp = (id: string): Promise<AppDeployVO> =>
  request<AppDeployVO>(`/api/apps/${encodeURIComponent(id)}/deploy`, { method: 'POST' })

export const getAppPreviewUrl = (id: string): string =>
  `/api/apps/${encodeURIComponent(id)}/preview`

export async function hasAppPreview(id: string): Promise<boolean> {
  try {
    await requestHtml(getAppPreviewUrl(id))
    return true
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 50001) return false
    throw cause
  }
}
