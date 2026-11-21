declare namespace XYApi {
  type ApiResponse = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: unknown
    /** 结果说明 */
    message?: string
  }

  type ApiResponseAppAdminPageVO = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: AppAdminPageVO
    /** 结果说明 */
    message?: string
  }

  type ApiResponseAppDeployVO = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: AppDeployVO
    /** 结果说明 */
    message?: string
  }

  type ApiResponseAppPageVO = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: AppPageVO
    /** 结果说明 */
    message?: string
  }

  type ApiResponseAppVO = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: AppVO
    /** 结果说明 */
    message?: string
  }

  type ApiResponseBoolean = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: boolean
    /** 结果说明 */
    message?: string
  }

  type ApiResponseChatHistoryPageVO = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: ChatHistoryPageVO
    /** 结果说明 */
    message?: string
  }

  type ApiResponseHealthVO = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: HealthVO
    /** 结果说明 */
    message?: string
  }

  type ApiResponseListAppFeaturedVO = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: AppFeaturedVO[]
    /** 结果说明 */
    message?: string
  }

  type ApiResponseString = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: string
    /** 结果说明 */
    message?: string
  }

  type ApiResponseUserPageVO = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: UserPageVO
    /** 结果说明 */
    message?: string
  }

  type ApiResponseUserVO = {
    /** 业务状态码，0 表示成功 */
    code?: number
    /** 响应数据 */
    data?: UserVO
    /** 结果说明 */
    message?: string
  }

  type AppAddRequest = {
    /** 初始网站需求，最多 16383 个字符；名称取前 12 个字符。内容检查未通过时返回 400，不创建应用 */
    initPrompt: string
    /** auto 按初始需求自动选择；也可直接指定生成模式；省略时默认多文件 */
    codeGenType?: 'auto' | 'html' | 'multi_file' | 'vue_project'
  }

  type AppAdminPageVO = {
    /** 本页应用摘要，按创建时间和 ID 倒序排列 */
    records: AppAdminVO[]
    /** 符合查询条件的应用总数 */
    total: number
    /** 当前页码 */
    current: number
    /** 每页条数 */
    pageSize: number
  }

  type AppAdminVO = {
    /** 服务端生成的应用 ID */
    id: string
    /** 创建者 ID */
    userId: string
    /** 应用名称 */
    appName: string
    /** 生成模式：html 单 HTML / multi_file 多文件 / vue_project Vue 工程源码 */
    codeGenType: 'html' | 'multi_file' | 'vue_project'
    /** 是否精选，仅管理员可设置 */
    featured: boolean
    /** 创建时间 */
    createTime: string
    /** 更新时间 */
    updateTime: string
    /** 已有发布信息；未发布时为 null，未配置静态站点时 deployUrl 为 null */
    deployment: AppDeployVO | null
  }

  type AppDeployVO = {
    /** 独立静态站点的分享地址；重新发布沿用此地址 */
    deployUrl: string | null
    /** 已发布的成功生成版本 ID */
    publishedVersion: string
    /** 本次发布时间 */
    deployedAt: string
  }

  type AppFeaturedRequest = {
    /** true 设为精选，false 取消精选 */
    featured: boolean
  }

  type AppFeaturedVO = {
    /** 服务端生成的应用 ID */
    id: string
    /** 应用名称 */
    appName: string
    /** 生成模式：html 单 HTML / multi_file 多文件 */
    codeGenType: 'html' | 'multi_file' | 'vue_project'
    /** 独立站点的公开作品地址 */
    deployUrl: string
    /** 当前发布版本的封面地址；没有截图时为 null */
    coverUrl: string | null
  }

  type AppPageVO = {
    /** 本页应用，按创建时间和 ID 倒序排列 */
    records: AppVO[]
    /** 符合查询条件的应用总数 */
    total: number
    /** 当前页码 */
    current: number
    /** 每页条数 */
    pageSize: number
  }

  type AppUpdateRequest = {
    /** 应用名称，最多 128 个字符 */
    appName: string
  }

  type AppVO = {
    /** 服务端生成的应用 ID */
    id: string
    /** 创建者 ID */
    userId: string
    /** 应用名称 */
    appName: string
    /** 初始需求 */
    initPrompt: string
    /** 生成模式：html 单 HTML / multi_file 多文件 / vue_project Vue 工程源码 */
    codeGenType: 'html' | 'multi_file' | 'vue_project'
    /** 创建时间 */
    createTime: string
    /** 更新时间 */
    updateTime: string
    /** 最近发布信息；尚未发布时为 null */
    deployment: AppDeployVO | null
  }

  type cancelParams = {
    /** 应用 ID，必须大于 0 */
    appId: string
    /** 本次生成 ID，必须大于 0 */
    generationId: string
  }

  type ChatHistoryPageVO = {
    /** 本页消息，按 ID 从旧到新排列 */
    records: ChatHistoryVO[]
    /** 是否还有更早的消息 */
    hasMore: boolean
    /** 下一页 beforeId；没有更早消息时为 null */
    nextCursor: string | null
  }

  type ChatHistoryVO = {
    /** 服务端生成的消息 ID */
    id: string
    /** 消息角色：user / assistant */
    messageRole: 'user' | 'assistant'
    /** 已保存的需求或模型输出；运行中的增量内容由 SSE 提供 */
    content: string
    /** 状态：running / completed / failed / cancelled */
    status: 'running' | 'completed' | 'failed' | 'cancelled'
    /** 可读失败原因，无错误时为 null */
    errorMessage: string | null
    /** 创建时间 */
    createTime: string
    /** 更新时间 */
    updateTime: string
  }

  type deleteAppForAdminParams = {
    /** 应用 ID，必须大于 0 */
    id: string
  }

  type deleteAppParams = {
    /** 应用 ID，必须大于 0 */
    id: string
  }

  type deleteUserParams = {
    /** 待删除的普通用户 ID，必须大于 0 */
    id: string
  }

  type deployAppParams = {
    /** 应用 ID，必须大于 0 */
    id: string
  }

  type GenerationEventVO = {
    /** 生成 ID，与 AI 消息 ID 相同 */
    generationId: string
    /** 运行状态：running / completed / failed / cancelled */
    status: 'running' | 'completed' | 'failed' | 'cancelled'
    /** delta 事件的增量源码 */
    text?: string
    /** failed 或 cancelled 事件的可读说明 */
    message?: string
  }

  type GenerationRequest = {
    /** 本次网站需求，最多 16383 个 Unicode 码点；内容检查未通过时返回 400，不建立生成流 */
    prompt: string
    /** 生成后进行 AI 质检，必要时修复一次；支持全部三种生成模式，默认关闭 */
    qualityCheck?: boolean
  }

  type getAppByIdParams = {
    /** 应用 ID，必须大于 0 */
    id: string
  }

  type HealthVO = {
    /** 连接成功时为 UP */
    status: 'UP'
    /** 数据库实际返回的当前库名 */
    database: string
  }

  type listAppsForAdminParams = {
    /** 应用名称，模糊匹配；留空不筛选 */
    appName?: string
    /** 创建者 ID，必须大于 0；省略时查询所有创建者 */
    userId?: string
    /** 页码，从 1 开始 */
    current?: number
    /** 每页条数，默认 10 */
    pageSize?: number
  }

  type listMessagesParams = {
    /** 应用 ID，必须大于 0 */
    appId: string
    /** 向前查询的消息 ID，不包含该消息；首次查询不填 */
    beforeId?: string
    /** 每页条数，默认 10，最多 20 */
    pageSize?: number
  }

  type listMyAppsParams = {
    /** 应用名称，模糊匹配；留空查询全部 */
    appName?: string
    /** 页码，从 1 开始 */
    current?: number
    /** 每页条数，默认 10，最多 20 */
    pageSize?: number
  }

  type listUsersParams = {
    /** 登录账号，模糊匹配；留空查询全部 */
    userAccount?: string
    /** 页码，从 1 开始 */
    current?: number
    /** 每页条数，默认 10 */
    pageSize?: number
  }

  type updateAppForAdminParams = {
    /** 应用 ID，必须大于 0 */
    id: string
  }

  type updateAppParams = {
    /** 应用 ID，必须大于 0 */
    id: string
  }

  type updateFeaturedForAdminParams = {
    /** 应用 ID，必须大于 0 */
    id: string
  }

  type updateUserParams = {
    /** 用户 ID，必须大于 0 */
    id: string
  }

  type UserAddRequest = {
    /** 登录账号，最多 128 个字符 */
    userAccount: string
    /** 新用户的初始密码 */
    userPassword: string
    /** 昵称，最多 128 个字符；省略或空白时使用账号 */
    userName?: string
  }

  type UserLoginRequest = {
    /** 登录账号 */
    userAccount: string
    /** 密码 */
    userPassword: string
  }

  type UserPageVO = {
    /** 本页用户，按创建时间和 ID 倒序排列 */
    records: UserVO[]
    /** 符合查询条件的用户总数 */
    total: number
    /** 当前页码 */
    current: number
    /** 每页条数 */
    pageSize: number
  }

  type UserRegisterRequest = {
    /** 登录账号，最多 128 个字符 */
    userAccount: string
    /** 密码 */
    userPassword: string
    /** 再次输入密码 */
    checkPassword: string
  }

  type UserUpdateRequest = {
    /** 昵称，最多 128 个字符 */
    userName: string
  }

  type UserVO = {
    /** 服务端生成的用户 ID */
    id: string
    /** 登录账号 */
    userAccount: string
    /** 昵称 */
    userName: string
    /** 角色：user 普通用户 / admin 管理员 */
    userRole: 'user' | 'admin'
  }
}
