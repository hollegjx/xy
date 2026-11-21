import request from '@/request'

/** 管理员删除应用 仅管理员可删除任一有效应用；先下线公开作品，再逻辑删除应用，成功返回 true；保留私有对话和生成文件。未登录返回 HTTP 401，普通用户返回 HTTP 403，不存在或已删除返回 HTTP 404，非正数 ID 返回 HTTP 400；公开入口移除失败时保留应用并返回错误。 DELETE /admin/apps/${param0} */
export async function deleteAppForAdmin(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.deleteAppForAdminParams,
  options?: import('axios').AxiosRequestConfig,
) {
  const { id: param0, ...queryParams } = params
  return request<boolean>(`/api/admin/apps/${param0}`, {
    method: 'DELETE',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 管理员修改应用名称 仅管理员可修改任一有效应用的名称；名称不能为空且最多 128 个字符，成功或名称未变化时返回 true。只修改名称，不改变创建者、初始需求、生成模式或发布信息，也不开放私有工作台权限。未登录返回 HTTP 401，普通用户返回 HTTP 403，不存在或已删除返回 HTTP 404。 PATCH /admin/apps/${param0} */
export async function updateAppForAdmin(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.updateAppForAdminParams,
  body: XYApi.AppUpdateRequest,
  options?: import('axios').AxiosRequestConfig,
) {
  const { id: param0, ...queryParams } = params
  return request<boolean>(`/api/admin/apps/${param0}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  })
}

/** 管理员设置或取消精选 仅管理员可调用；featured 为 true 时应用须已发布，为 false 时取消精选。成功或状态未变化时返回 true，不改变生成或发布内容。未登录返回 HTTP 401，普通用户返回 HTTP 403，不存在或已删除返回 HTTP 404，参数错误返回 HTTP 400，未发布应用设为精选返回 HTTP 409。 PATCH /admin/apps/${param0}/featured */
export async function updateFeaturedForAdmin(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.updateFeaturedForAdminParams,
  body: XYApi.AppFeaturedRequest,
  options?: import('axios').AxiosRequestConfig,
) {
  const { id: param0, ...queryParams } = params
  return request<boolean>(`/api/admin/apps/${param0}/featured`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  })
}

/** 管理员查询应用 仅管理员可调用，支持名称模糊查询和创建者 ID 精确筛选；省略条件时查询全平台未删除应用，默认第 1 页、每页 10 个，按创建时间和 ID 倒序排列。仅返回应用摘要及已有发布信息，不包含初始需求、对话或源码；不开放他人工作台访问权限。未登录返回 HTTP 401，普通用户返回 HTTP 403，非法分页或创建者 ID 返回 HTTP 400。 GET /apps */
export async function listAppsForAdmin(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.listAppsForAdminParams,
  options?: import('axios').AxiosRequestConfig,
) {
  return request<XYApi.AppAdminPageVO>(`/api/apps`, {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 创建应用 需要登录；创建者取当前账号，名称取初始需求前 12 个字符，省略模式默认多文件；auto 根据需求选定实际模式，失败不创建应用。返回字符串 ID；生成由后续接口发起。 POST /apps */
export async function createApp(
  body: XYApi.AppAddRequest,
  options?: import('axios').AxiosRequestConfig,
) {
  return request<string>(`/api/apps`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 获取应用详情 需要登录且为应用创建者；返回名称、初始需求、生成模式、时间和发布信息。其他账号（包括管理员）返回 HTTP 403，应用不存在或已删除时返回 HTTP 404。 GET /apps/${param0} */
export async function getAppById(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.getAppByIdParams,
  options?: import('axios').AxiosRequestConfig,
) {
  const { id: param0, ...queryParams } = params
  return request<XYApi.AppVO>(`/api/apps/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 删除应用 仅创建者可删除，管理员不能删除他人的应用；无需请求体。先移除已发布页面，再逻辑删除应用，成功返回 true。删除后应用不再出现在列表中，详情、对话、预览和源码不可访问；不存在或已删除返回 404。 DELETE /apps/${param0} */
export async function deleteApp(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.deleteAppParams,
  options?: import('axios').AxiosRequestConfig,
) {
  const { id: param0, ...queryParams } = params
  return request<boolean>(`/api/apps/${param0}`, {
    method: 'DELETE',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 修改应用名称 仅创建者可修改，管理员不能修改他人的应用。名称不能为空且最多 128 个字符；只修改名称，不改变初始需求、生成模式或发布信息。保存成功或名称未变化时返回 true。 PATCH /apps/${param0} */
export async function updateApp(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.updateAppParams,
  body: XYApi.AppUpdateRequest,
  options?: import('axios').AxiosRequestConfig,
) {
  const { id: param0, ...queryParams } = params
  return request<boolean>(`/api/apps/${param0}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  })
}

/** 发布应用 仅创建者可发布最近成功版本，无需请求体。返回独立静态站点地址，重新发布沿用原地址；新生成不会自动更新已发布页面。Vue 发布已构建页面；无成功版本或该 Vue 版本尚无构建产物返回 409，原生网站文件缺失返回 404，未配置静态站点返回 503。 POST /apps/${param0}/deploy */
export async function deployApp(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.deployAppParams,
  options?: import('axios').AxiosRequestConfig,
) {
  const { id: param0, ...queryParams } = params
  return request<XYApi.AppDeployVO>(`/api/apps/${param0}/deploy`, {
    method: 'POST',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 停止应用生成 仅创建者可调用，无需请求体。generationId 取 started 事件或消息查询中的运行中 AI 消息 ID。成功保存 cancelled 后返回 true，并向原事件流发送 cancelled。已结束、重复取消或不匹配的生成返回 409；取消保留部分输出和之前的成功版本。 POST /apps/${param0}/generations/${param1}/cancel */
export async function cancel(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.cancelParams,
  options?: import('axios').AxiosRequestConfig,
) {
  const { appId: param0, generationId: param1, ...queryParams } = params
  return request<boolean>(`/api/apps/${param0}/generations/${param1}/cancel`, {
    method: 'POST',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 查询应用消息与生成状态 仅创建者可查询，包括管理员在内的其他账号返回 403。默认最近 10 条，每页最多 20 条；用 nextCursor 作为 beforeId 向前翻页，本页按旧到新排列。重启后首次查询或生成会将遗留 running 标记为 failed；运行中的增量输出仍通过 SSE 获取。 GET /apps/${param0}/messages */
export async function listMessages(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.listMessagesParams,
  options?: import('axios').AxiosRequestConfig,
) {
  const { appId: param0, ...queryParams } = params
  return request<XYApi.ChatHistoryPageVO>(`/api/apps/${param0}/messages`, {
    method: 'GET',
    params: {
      ...queryParams,
    },
    ...(options || {}),
  })
}

/** 查询我的应用 需要登录；仅查询当前账号创建且未删除的应用，管理员也不例外。支持名称模糊查询，默认第 1 页、每页 10 个，最多 20 个；按创建时间和 ID 倒序排列。返回应用列表、总数和分页参数；已有发布信息随列表返回。 GET /apps/mine */
export async function listMyApps(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.listMyAppsParams,
  options?: import('axios').AxiosRequestConfig,
) {
  return request<XYApi.AppPageVO>(`/api/apps/mine`, {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}
