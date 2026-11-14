import request, { ApiError, requestHtml, requestZip } from '@/request'
import type { AppAddRequest, AppVO } from '@/types/api'

export const createApp = (data: AppAddRequest): Promise<string> =>
  request<string>('/api/apps', { method: 'POST', data })

export const getApp = (id: string): Promise<AppVO> =>
  request<AppVO>(`/api/apps/${encodeURIComponent(id)}`)

export const getAppSource = (id: string): Promise<Blob> =>
  requestZip(`/api/apps/${encodeURIComponent(id)}/source`)

export const getAppPreviewUrl = (id: string): string =>
  `/api/apps/${encodeURIComponent(id)}/preview`

export async function hasAppPreview(id: string): Promise<boolean> {
  try {
    await requestHtml(getAppPreviewUrl(id))
    return true
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 409) return false
    throw cause
  }
}
