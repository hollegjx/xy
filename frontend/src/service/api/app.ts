import * as api from '@/service/generated/app'
import { ApiError, requestHtml, requestWithHeaders, requestZip } from '@/request'
import type {
  AppAddRequest,
  AppAdminPageVO,
  AppAdminQueryRequest,
  AppAdminVO,
  AppDeployVO,
  AppFeaturedRequest,
  AppFeaturedSlice,
  AppFeaturedVO,
  AppPageVO,
  AppQueryRequest,
  AppUpdateRequest,
  AppVO,
} from '@/types/api'

export const createApp = (data: AppAddRequest): Promise<string> =>
  api.createApp(data, data.codeGenType === 'auto' ? { timeout: 60000 } : undefined)
export const getApp = (id: string): Promise<AppVO> => api.getAppById({ id: encodeURIComponent(id) })
export const getAppForAdmin = (id: string): Promise<AppAdminVO> =>
  api.getAppForAdmin({ id: encodeURIComponent(id) })
export const updateApp = (id: string, data: AppUpdateRequest): Promise<boolean> =>
  api.updateApp({ id: encodeURIComponent(id) }, data)
export const updateAppForAdmin = (id: string, data: AppUpdateRequest): Promise<boolean> =>
  api.updateAppForAdmin({ id: encodeURIComponent(id) }, data)
export const updateAppFeatured = (id: string, data: AppFeaturedRequest): Promise<boolean> =>
  api.updateFeaturedForAdmin({ id: encodeURIComponent(id) }, data)
export const deleteApp = (id: string): Promise<boolean> =>
  api.deleteApp({ id: encodeURIComponent(id) })
export const deleteAppForAdmin = (id: string): Promise<boolean> =>
  api.deleteAppForAdmin({ id: encodeURIComponent(id) })
export const listMyApps = (params: AppQueryRequest): Promise<AppPageVO> => api.listMyApps(params)
export const listAppsForAdmin = (params: AppAdminQueryRequest): Promise<AppAdminPageVO> =>
  api.listAppsForAdmin(params)

export async function listFeaturedApps(current: number): Promise<AppFeaturedSlice> {
  const { data, headers } = await requestWithHeaders<AppFeaturedVO[]>('/api/apps/featured', {
    params: { current },
  })
  return {
    records: data,
    hasNext: typeof headers.link === 'string' && headers.link.includes('rel="next"'),
  }
}

export const getAppSource = (id: string): Promise<Blob> =>
  requestZip(`/api/apps/${encodeURIComponent(id)}/source`)

export const deployApp = (id: string): Promise<AppDeployVO> =>
  api.deployApp({ id: encodeURIComponent(id) }, { timeout: 30000 })

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
