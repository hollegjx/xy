import request from '@/request'

/** 检查后端与数据库连接 无需登录；成功时返回当前连接的数据库名称，数据库不可用时返回 HTTP 503。 GET /health */
export async function health(options?: import('axios').AxiosRequestConfig) {
  return request<XYApi.HealthVO>(`/api/health`, {
    method: 'GET',
    ...(options || {}),
  })
}
