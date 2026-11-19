import request, { ApiError, requestHtml, requestZip } from '@/request'
import type {
  AppAddRequest,
  AppAdminPageVO,
  AppAdminQueryRequest,
  AppDeployVO,
  AppFeaturedRequest,
  AppFeaturedVO,
  AppPageVO,
  AppQueryRequest,
  AppUpdateRequest,
  AppVO,
} from '@/types/api'

export const createApp = (data: AppAddRequest): Promise<string> =>
  request<string>('/api/apps', {
    method: 'POST',
    data,
    ...(data.codeGenType === 'auto' && { timeout: 60000 }),
  })

export const getApp = (id: string): Promise<AppVO> =>
  request<AppVO>(`/api/apps/${encodeURIComponent(id)}`)

export const updateApp = (id: string, data: AppUpdateRequest): Promise<boolean> =>
  request<boolean>(`/api/apps/${encodeURIComponent(id)}`, { method: 'PATCH', data })

export const updateAppForAdmin = (id: string, data: AppUpdateRequest): Promise<boolean> =>
  request<boolean>(`/api/admin/apps/${encodeURIComponent(id)}`, { method: 'PATCH', data })

export const updateAppFeatured = (id: string, data: AppFeaturedRequest): Promise<boolean> =>
  request<boolean>(`/api/admin/apps/${encodeURIComponent(id)}/featured`, { method: 'PATCH', data })

export const deleteApp = (id: string): Promise<boolean> =>
  request<boolean>(`/api/apps/${encodeURIComponent(id)}`, { method: 'DELETE' })

export const deleteAppForAdmin = (id: string): Promise<boolean> =>
  request<boolean>(`/api/admin/apps/${encodeURIComponent(id)}`, { method: 'DELETE' })

export const listMyApps = (params: AppQueryRequest): Promise<AppPageVO> =>
  request<AppPageVO>('/api/apps/mine', { params })

export const listAppsForAdmin = (params: AppAdminQueryRequest): Promise<AppAdminPageVO> =>
  request<AppAdminPageVO>('/api/apps', { params })

export const listFeaturedApps = (): Promise<AppFeaturedVO[]> =>
  request<AppFeaturedVO[]>('/api/apps/featured')

export const getAppSource = (id: string): Promise<Blob> =>
  requestZip(`/api/apps/${encodeURIComponent(id)}/source`)

export const deployApp = (id: string): Promise<AppDeployVO> =>
  request<AppDeployVO>(`/api/apps/${encodeURIComponent(id)}/deploy`, { method: 'POST' })

export const getAppPreviewUrl = (id: string, pick = false): string =>
  `/api/apps/${encodeURIComponent(id)}/preview${pick ? '?pick=true' : ''}`

export async function getPreviewUnavailableReason(id: string): Promise<string | null> {
  try {
    await requestHtml(getAppPreviewUrl(id))
    return null
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 50001) return cause.message
    throw cause
  }
}
