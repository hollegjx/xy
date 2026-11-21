import request from '@/request'

/** 管理员查询用户 仅管理员可调用；支持账号模糊查询，默认第 1 页、每页 10 个。按创建时间和 ID 倒序，只返回未删除用户的 ID、账号、昵称和角色。未登录返回 HTTP 401，非管理员返回 HTTP 403。 GET /users */
export async function listUsers(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.listUsersParams,
  options?: import('axios').AxiosRequestConfig,
) {
  return request<XYApi.UserPageVO>(`/api/users`, {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 管理员新增用户 仅管理员可调用；填写账号、初始密码及可选昵称，只创建普通用户。昵称留空时使用账号，账号重复时返回 HTTP 409 / 业务码 50001。成功仅返回字符串用户 ID，不改变当前管理员的登录身份。 POST /users */
export async function addUser(
  body: XYApi.UserAddRequest,
  options?: import('axios').AxiosRequestConfig,
) {
  return request<string>(`/api/users`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 管理员删除用户 仅管理员可调用，无需请求体；只允许删除普通用户，管理员账号返回 HTTP 409。先移除目标用户的公开作品，再将账号和所属应用一起逻辑删除；成功返回 true。删除后账号不能登录，原 Session 在后续身份校验时失效；对话和私有源码保留。目标不存在或已删除返回 HTTP 404，公开入口移除失败时保留账号供重试。 DELETE /users/${param0} */
export async function deleteUser(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.deleteUserParams,
  options?: import('axios').AxiosRequestConfig,
) {
  const { id: param0, ...queryParams } = params
  return request<boolean>(`/api/users/${param0}`, {
    method: 'DELETE',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 管理员修改用户昵称 仅管理员可调用，可修改普通用户、其他管理员或本人的昵称。昵称不能为空且最多 128 个字符；账号、密码和角色保持不变。保存成功或昵称未变化时返回 true，目标不存在或已删除时返回 HTTP 404。 PATCH /users/${param0} */
export async function updateUser(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: XYApi.updateUserParams,
  body: XYApi.UserUpdateRequest,
  options?: import('axios').AxiosRequestConfig,
) {
  const { id: param0, ...queryParams } = params
  return request<boolean>(`/api/users/${param0}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  })
}

/** 用户登录 无需登录；校验账号与密码，成功后建立 Session，返回不含密码散列的用户信息。 POST /users/login */
export async function login(
  body: XYApi.UserLoginRequest,
  options?: import('axios').AxiosRequestConfig,
) {
  return request<XYApi.UserVO>(`/api/users/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 用户退出 需要登录；销毁当前 Session，成功返回 true。未登录、重复退出或账号已不可用时返回 HTTP 401。 POST /users/logout */
export async function logout(options?: import('axios').AxiosRequestConfig) {
  return request<boolean>(`/api/users/logout`, {
    method: 'POST',
    ...(options || {}),
  })
}

/** 获取当前用户 需要登录；通过 Session 查询当前有效账号，返回脱敏信息。未登录或账号已不可用时返回 HTTP 401。 GET /users/me */
export async function getCurrentUser(options?: import('axios').AxiosRequestConfig) {
  return request<XYApi.UserVO>(`/api/users/me`, {
    method: 'GET',
    ...(options || {}),
  })
}

/** 注册用户 无需登录；两次密码须一致，账号重复时返回 HTTP 409 / 业务码 50001。仅创建普通用户，返回字符串形式的用户 ID。 POST /users/register */
export async function register(
  body: XYApi.UserRegisterRequest,
  options?: import('axios').AxiosRequestConfig,
) {
  return request<string>(`/api/users/register`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
