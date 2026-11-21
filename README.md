# 星云 · XY

零代码应用搭建平台。

**星云（Xing Yun）**，拼音首字母为 **XY**。通过零代码方式，让创意成为应用。

## 当前阶段

首期采用方案 B，功能范围见 [docs/spec.md](docs/spec.md)。当前包含健康检查、接口文档、账号注册与登录、当前账号恢复和退出登录，以及首页创建应用和应用详情工作台。支持单 HTML 与原生多文件流式生成、停止生成、产物保存、消息游标查询、生成状态恢复与成功版本隔离预览；工作台已接入创建后自动生成、预览和加载更早消息。后端从成功轮次的用户消息、AI 回复与成功源码恢复修改上下文；工作台支持多轮需求提交，成功后清空输入，失败或停止后保留需求供手动重试。

工作台支持下载最近成功版本的源码 ZIP，并显示下载中与失败重试状态；仅应用创建者可访问，单 HTML 下载 `index.html`，多文件下载原始 HTML、CSS 和 JS。

工作台支持发布最近成功版本，发布后可查看作品、复制分享链接，并显示最近发布时间；刷新页面会恢复发布信息。后端通过 `POST /api/apps/{id}/deploy` 保存网页快照并返回稳定地址，实际访问依赖独立静态站点的配置与启动。

## 项目结构

```text
xy/
├── backend/        Java 后端、Maven Wrapper 和 SQL
├── frontend/       Vue 前端、请求客户端和页面测试
├── docs/           需求、设计与开发文档
├── .editorconfig   基础编辑格式
├── .gitattributes  Git 文本与换行规则
└── .gitignore      本地文件与构建产物忽略规则
```

后端 Java 包位于 `backend/src/main/java/com/xy`：

```text
com.xy/
├── common/、exception/  全局响应、共享常量与异常
├── config/              AI 模型、密码编码与发布配置
├── manager/             生成文件存取、发布快照与隔离构建
├── health/              controller、service/impl、model/vo
├── user/                用户业务
├── app/                 应用业务，包含 PreviewController 与 PreviewService
├── chat/                消息与历史
└── generation/          controller、model、service/impl
```

业务模块按需使用 `controller`、`service`、`service/impl`、`mapper` 和 `model/{entity,dto,vo,enums}`；`service` 只放接口。生成模块的会话与持久化协作类位于 `generation/service/impl`。测试采用对应包路径；长期目录约定见 [AGENTS.md](AGENTS.md)。

## Git 约定

- 默认分支：`main`。
- 功能分支：`feature/<功能名>`；修复分支：`fix/<问题名>`。
- 提交信息使用 `feat:`、`fix:`、`docs:`、`chore:` 等前缀。
- 依赖目录、编译产物、日志和本地密钥不提交。

## 后端环境

- 项目源码目标为 Java 21。推荐 JDK 21；当前已安装的 JDK 24 也在 Spring Boot 3.5.16 的兼容范围内，可用于本步验证，Maven 编译目标仍为 21。
- MySQL 8.0 或以上，使用现有实例和账号；本机检测到 MySQL 8.4。
- 精选缓存、生成限流、登录 Session 和成功轮次记忆共用 `spring.data.redis.host / port / database` 指定的 Redis。示例为 `127.0.0.1:6379`、库 0。同一环境的后端使用同一逻辑库，不同环境分开；凭证只填在服务端配置中。单机升级见 [Redis 部署和就绪检查](docs/redis-deployment.md)。
- Maven Wrapper 固定 Maven 3.9.11，无需全局安装 Maven；首次构建需要下载发行包与项目依赖。
- 后端依赖版本、来源和选择原因见 [docs/NOTES.md](docs/NOTES.md)。

## 本地配置与启动

1. 在 IDEA 打开 `backend/pom.xml`，选择已有且受支持的 JDK，等待 Maven 导入完成。
2. 在已有 MySQL 连接中依次执行 [backend/sql/001_create_database.sql](backend/sql/001_create_database.sql)、[backend/sql/002_create_user.sql](backend/sql/002_create_user.sql)、[backend/sql/003_create_app.sql](backend/sql/003_create_app.sql)、[backend/sql/004_add_generation.sql](backend/sql/004_add_generation.sql)、[backend/sql/005_add_app_deployment.sql](backend/sql/005_add_app_deployment.sql) 和 [backend/sql/007_add_app_featured.sql](backend/sql/007_add_app_featured.sql)，创建数据库和业务表，并为应用增加生成、发布及精选字段。不删除已有数据；已执行过的文件无需重复执行，`004`、`005`、`007` 中的 ALTER 只能执行一次。如已有同名表或字段，先核对结构。`006_set_admin.sql` 仅用于按需指定已有管理员账号。
3. 将 `backend/src/main/resources/application.example.yml` 复制为同目录的 `application.yml`，填写实际数据库地址、库名、账号、密码。本工作区若已存在该文件，直接编辑，勿覆盖已有配置。密码建议使用 YAML 单引号字符串，密码本身含单引号时写成两个单引号。
4. 在 IDEA 运行 `com.xy.MainApplication`，打开 `http://localhost:8101/api/doc.html`，在「健康检查」中调用 `GET /health`。

`application.yml` 是唯一的本地配置文件，已被 Git 忽略；仓库只保存不含真实凭证的 `application.example.yml`。不要强制添加本地配置到 Git。切换配置后需要重新运行后端。

使用生成接口时，在该文件的 `xy.ai` 下填写 `base-url`、`api-key`、`model-name`，将 `enabled` 设为 `true`。默认超时为 180 秒、最大完成 token 数为 16384，可在同一配置中修改。配置示例默认关闭模型接入，账号与应用基础接口仍可使用。

首页的「自动选择」只负责判断应用适合哪种生成模式。可将 `xy.ai.routing-model-name` 设为同一网关提供的轻量模型；不填或留空就沿用 `model-name`。选择哪种分类模型不影响实际写代码和质检使用的模型。

Vue 工程可单独配置同一网关下支持流式工具调用的 `xy.ai.vue-model-name`，以及可选的 `xy.ai.vue-max-completion-tokens`。未配置时，Vue 和原生模式继续用原生成模型及其输出上限。可选质检使用该工程的生成模型。检查发现问题时最多自动修复一次；检查通过后，工作流只为已启用构建的 Vue 工程运行独立构建节点。成功后才交付新版本，源码模式直接交付。

原生 HTML / 多文件生成默认边写边显示。若启用 `xy.ai.native-output-guardrail: true`，SDK 对空或过短回复最多追加一次请求，只在校验通过后显示正文；工作台的 AI 质检开关与它独立。

首页「精选作品」每页默认 6 张。前 10 页缓存 5 分钟，键形如 `xy:featured:pages::<公开站点地址>:<版本>:1:6`。更换公开地址并重启后端后，首页使用新地址；清空地址则直接返回空列表，无需清理 Redis。已配置站点但 Redis 暂不可用时直接查询 MySQL。可在 `backend/` 执行 `sh mvnw -Dtest=FeaturedRedisCacheTest test` 验证共享缓存与站点切换；测试自带临时 Redis，不使用开发库。

生成额度按账号计算：最近 60 秒最多发起 5 轮，在不同应用或不同后端发起也合并计数。超限时工作台显示还要等几秒；Redis 不可用时，新生成返回 503。可在 `backend/` 执行 `sh mvnw -Dtest=GenerationStartLimiterTest,ConcurrentGenerationTest test`，用临时 Redis 和模型替身复验。

生成文件位于 `xy.generation.output-directory`，默认是相对于后端启动工作目录的 `./generated`；下级按用户 ID、应用 ID、生成 ID 划分。单 HTML 保存 `index.html`，多文件保存 `index.html`、`style.css` 和 `script.js`。目录被 Git 忽略。浏览器通过 `GET /api/apps/{appId}/preview` 在沙箱中运行最近成功版本，需要应用创建者的登录会话；多文件预览将 CSS/JS 编码为 data URL，原始文件继续分别保存。预览允许页面样式与脚本交互，禁止读取平台凭证和联网加载资源；当前多文件不支持模块导入或额外资源目录。工作台嵌入时使用前端代理后的同源 `/api` 地址。

当前后端按单实例运行。服务重启后，首次有效生成、停止请求或消息查询会将遗留的运行中生成记为失败，保留已有成功版本；查询不会自动重发生成请求。消息接口返回已保存的内容，运行中的增量输出通过生成接口的 SSE 获取；主动停止后保留部分输出，状态为 `cancelled`。

后续生成按原来的说话顺序恢复本应用的用户需求与 AI 回复，再以磁盘中的成功源码为基准处理本次修改。重启后继续修改需要保留数据库记录、生成文件及相同的输出目录；源码读取失败时会明确报告失败并保留旧版本。

成功结果示例：

```json
{"code":0,"data":{"status":"UP","database":"xy"},"message":"ok"}
```

也可在浏览器打开 `http://localhost:8101/api/health`。数据库不可连接或未选择库时，接口返回 HTTP 503 和可读错误；数据库原始异常记录在服务端，不返回给页面。OpenAPI 文档地址为 `http://localhost:8101/api/v3/api-docs`。

### 独立静态站点配置

Vue 工程的构建配置见下节。发布时直接使用最近成功版本已构建的页面，不重新构建；仅有源码的版本可下载，无法发布。

**登录与对话记忆**：启动后端前配置可访问的 Redis（`spring.data.redis`）；多个实例使用相同逻辑库和 `spring.session.redis.namespace`（示例：`xy:session`）。MySQL 仍保存聊天历史；Redis 的成功版本记忆保留一小时。后端仍按数据库当前账号校验角色。

**前端接口客户端**：通常直接使用仓库中的生成结果。修改 JSON 接口契约后在 `frontend/` 执行 `npm run api:generate`，由 MockMvc 导出本地 OpenAPI 并重新生成请求代码。无需启动后端或连接业务数据库。

**工作流图片（可选）**：`xy.artwork.kroki-url` 填自有 Kroki 根地址，使用 Mermaid 生成架构 PNG；如需百炼图像模型，将 `xy.artwork.logo-provider` 设为 `dashscope` 并在服务端填写可用模型名与密钥。默认不调用外部绘图，下载仍能离线使用。配置项见 `backend/src/main/resources/application.example.yml`。

**封面持久化**：要在不同后端之间共享封面，将 `xy.cover.storage` 设为 `cos`，填写已有私有桶的 region、bucket 和服务端凭证，并启用下方截图。不开通新桶也能沿用本地存储；切换后仍可读取旧本地封面。

发布记录保存后即返回作品链接，封面使用本次发布的快照在虚拟线程生成；完成前精选卡片可先显示文字。

**精选作品封面（可选）**：在运行后端所连接的 Docker Engine 上准备截图镜像；从仓库根目录运行：

```sh
docker build -t xy-cover:1 backend/cover-renderer
```

在后端本地 `application.yml` 的 `xy` 下设置 `cover.enabled: true`，重启后端，重新发布需要封面的作品。后端复用 `xy.vue-build.docker-executable`，无需启用 Vue 构建。截图容器断网，只从标准输入读取这次发布的页面。精选首页读取当前发布版本的 JPEG；旧作品没有截图或截图失败时照常提供作品链接。无需执行 SQL 或更改静态站点配置。

镜像准备好以后，可以先在 `backend/` 运行下面的验收。它在临时目录发布两个测试版本，读取精选接口返回的 JPEG，核对尺寸及新旧页面颜色，不连接真实数据库或模型。有 Docker 命令白名单的环境，用 `XY_TEST_DOCKER_CLI` 指向后端实际使用的包装器；不要绕开白名单执行测试。

```sh
XY_TEST_DOCKER_COVER=true sh mvnw -Dtest=AppDeployControllerTest#rendersRepublishedFeaturedWorkInDocker test
```

在本地 `application.yml` 的 `xy.deployment` 中配置 `directory`（默认 `./published`）、`public-url`（静态站点来源，不含路径）和 `platform-origin`（浏览器访问平台的来源，默认 `http://localhost:5173`）。`public-url` 留空时发布返回 503，其他业务正常使用。

静态服务器需要与后端共享发布目录，且此目录不能与私有 `generated` 目录重叠。已有 Nginx 可参考 [backend/nginx.example.conf](backend/nginx.example.conf)：将示例 `root` 改为发布目录的绝对路径，并保留文件白名单和响应头配置；先验证并启动实际站点，再填写 `public-url`。本机示例使用 `http://127.0.0.1:8102`，平台通过 `localhost` 打开。仅换端口不能隔离 Cookie；线上作品使用与平台隔离的域名，不共享平台会话 Cookie，不代理平台 `/api`。

发布文件为 `published/{deployKey}/index.html`。`deployKey` 由服务端首次发布时生成并保存；重新发布只更新此快照，分享地址保持不变。多文件模式复用已有合并逻辑，将 CSS/JS 编码进 HTML，因此发布快照只有一个文件，源码下载仍保留原始三文件。页面保持现有沙箱限制：允许本地样式与脚本交互，不支持联网资源、表单提交或模块导入。静态服务器仅公开快照，不提供目录浏览、对话或源码下载接口。

本机地址只能用于本机验收。公网分享需要配置真实静态站点与 HTTPS 地址，并另外验证匿名访问；当前示例配置不代表已部署站点。

### Vue 工程构建与预览

`vue_project` 模式通过工具生成工程源码，下载接口返回完整源码 ZIP。`xy.vue-build.enabled` 默认 false；此时完成仅表示源码就绪。

macOS 先安装并启动 Docker Desktop；Linux 使用已有 Docker Engine。在项目根目录准备固定构建镜像：

```sh
docker build -t xy-vue-builder:local -f backend/vue-builder/Dockerfile backend
```

镜像构建只安装固定模板中的依赖；`backend/.dockerignore` 仅允许模板和构建工具进入上下文。升级到支持 Vue Router 4 的生成模板时，需重新执行上面的构建命令更新已有镜像。单页不需要路由文件；多页作品可以用哈希地址直接打开详情，下载源码也带相同依赖。在本地 `application.yml` 已有的 `xy` 节点下追加：

```yaml
vue-build:
  enabled: true
  docker-executable: docker
  image: xy-vue-builder:local
  timeout-seconds: 120
```

IDEA 找不到 Docker 时，`docker-executable` 填 `command -v docker` 输出的绝对路径。重新运行后端后，Vue 生成会展示构建阶段，构建成功后可通过已有预览接口或工作台浏览；原来的源码版本需要重新生成才能获得预览产物。`xy.ai.timeout-seconds` 是整个 SSE 任务的等待时间，包含模型生成与构建。

每次构建只挂载当前版本的源码快照和产物目录，容器断网、根文件系统只读，使用平台固定配置。失败、超时或停止均不替换旧成功版本。源码 ZIP 不包含构建缓存和预览产物。

## 用户注册验收

在 IDEA 执行 `backend/sql/002_create_user.sql`，重新加载 Maven 并重启后端。在 Knife4j 的「用户」中调用 `POST /users/register`（完整路径为 `/api/users/register`）：

```json
{
  "userAccount": "xy_demo",
  "userPassword": "Demo123456!",
  "checkPassword": "Demo123456!"
}
```

成功返回 `{"code":0,"data":"1","message":"ok"}`，其中 `data` 是实际生成的用户 ID，字符串形式避免前端整数精度丢失。注册后需要单独调用登录接口。

| # | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | 用未注册的账号提交上面的请求 | HTTP 200、`code: 0`、`data` 为用户 ID；库中新增普通用户，昵称初始为账号，密码为散列 |
| 2 | 再提交相同账号 | HTTP 409、`code: 50001`，提示「账号已存在」，不会新增第二条记录 |
| 3 | 分别将账号、密码、确认密码设为空字符串或删去对应字段，其余字段填写正确 | HTTP 400、`code: 40000`，提示对应字段不能为空 |
| 4 | 填入不同的两次密码 | HTTP 400、`code: 40000`，提示「两次输入的密码不一致」 |
| 5 | 换新账号 `xy_role_test`，额外传入 `"userRole": "admin"` | 注册成功，库中角色仍为 `user`，响应只有 ID、不含密码散列 |
| 6 | 用 128 字符的新账号注册，再改为 129 字符账号提交 | 128 字符可注册；129 字符返回 HTTP 400 / `code: 40000`，提示「账号长度不能超过 128 个字符」 |

账号长度按 Unicode 码点计数，与 `VARCHAR(128)` 的字符上限一致；ASCII 和补充平面字符（例如 emoji）的 128 / 129 边界均有自动测试覆盖。

在 IDEA 用只读查询核对角色与散列标识，无需显示密码散列原文：

```sql
SELECT id, userAccount, userName, userRole,
       userPassword LIKE '{pbkdf2-sha256-600000}%' AS passwordHashed
FROM xy.`user`
WHERE userAccount IN ('xy_demo', 'xy_role_test');
```

预期角色均为 `user`，`passwordHashed` 为 `1`。重复验收时正常注册用新的账号。

## 用户登录验收

准备一个已通过注册接口创建的账号，在 IDEA 重启后端。本步沿用用户表，无需执行新的 SQL。在 Knife4j 的「用户」中调用 `POST /users/login`（完整路径为 `/api/users/login`）：

```json
{
  "userAccount": "xy_demo",
  "userPassword": "Demo123456!"
}
```

成功响应示例（ID、昵称和角色以实际账号为准）：

```json
{
  "code": 0,
  "data": {
    "id": "1",
    "userAccount": "xy_demo",
    "userName": "xy_demo",
    "userRole": "user"
  },
  "message": "ok"
}
```

| # | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | 输入已注册账号及正确密码 | HTTP 200、`code: 0`，返回 ID、账号、昵称和数据库中的角色，不含密码散列 |
| 2 | 将密码改为错误值 | HTTP 400、`code: 40000`，提示「账号或密码错误」 |
| 3 | 输入不存在的账号 | 与密码错误返回相同的状态码和提示 |
| 4 | 分别将账号、密码设为空字符串或删去对应字段 | HTTP 400、`code: 40000`，提示对应字段不能为空 |
| 5 | 用 GET 请求 `/api/users/login` | HTTP 405 / `code: 40000`，提示「请求方法不支持」，`Allow` 响应头包含 `POST` |
| 6 | 用 POST 请求登录接口，设置 `Content-Type: text/plain` 并提交请求体 | HTTP 415 / `code: 40000`，提示「请求内容类型不支持」，`Accept` 响应头保留支持的内容类型 |

在浏览器开发者工具的 Network 中选中成功的登录请求，响应头应有 `Set-Cookie: SESSION=...`。再次成功登录时会更换 Session ID。服务端 Session 只保存用户 ID，密码和角色不写入会话；后续请求由浏览器携带 Cookie。登录和退出经过 Spring Session Data Redis。多个后端共用同一 Redis 库及 Session 命名空间时可复用 Cookie；旧版内存会话不会迁移，升级后需重新登录。

## 当前用户验收

在 IDEA 重启后端，本步无需执行新的 SQL。在同一个浏览器窗口的 Knife4j 中先调用 `POST /users/login`，再调用 `GET /users/me`（完整路径为 `/api/users/me`），由浏览器自动携带登录 Cookie。

成功响应的字段与登录响应相同：`id`、`userAccount`、`userName`、`userRole`。每次根据 Session 中的用户 ID 查询数据库，返回最新用户信息；不接收客户端提供的用户 ID 作为登录身份。

| # | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | 在新的无痕窗口直接访问 `http://localhost:8101/api/users/me` | HTTP 401 / `code: 40100`，提示「请先登录」 |
| 2 | 在同一窗口、相同主机地址下登录，再查询当前用户 | HTTP 200 / `code: 0`，返回已登录账号的信息，不含密码散列 |
| 3 | 登录后访问 `/api/users/me?userId=0` | 仍返回当前登录账号的信息，查询参数不改变身份 |
| 4 | 清除该站点的 `SESSION` Cookie 后重新请求 | 再次返回 HTTP 401 / `code: 40100` |

无会话的请求不会创建新 Session。会话对应账号不存在或被逻辑删除时，查询返回 401 并清除会话内的登录身份；此路径由 Mapper 替身测试覆盖，常规验收无需修改或删除数据库里的账号。

## 用户退出验收

在 IDEA 重启后端，本步无需执行新的 SQL。在同一浏览器窗口、相同主机地址的 Knife4j 中先登录，再调用 `POST /users/logout`（完整路径为 `/api/users/logout`），无需请求体。

成功返回 `{"code":0,"data":true,"message":"ok"}`，服务端销毁当前 Session；浏览器即使仍保留旧的 `SESSION` Cookie，也无法再凭它获取当前用户信息。

| # | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | 登录 → `GET /users/me` → `POST /users/logout` → 再次 `GET /users/me` | 退出前可查询；退出返回 `code: 0`、`data: true`；退出后查询返回 HTTP 401 / `code: 40100` |
| 2 | 退出后再次退出，或在未登录窗口直接调用退出 | HTTP 401 / `code: 40100`，提示「请先登录」 |
| 3 | 退出后重新登录，再查询当前用户 | 登录成功，当前用户查询恢复 HTTP 200 / `code: 0` |
| 4 | 普通窗口与无痕窗口分别登录同一账号，只在普通窗口退出，再到无痕窗口查询当前用户 | 普通窗口退出成功，无痕窗口仍能获取当前用户信息；两个普通标签页共享 Cookie，不用于此项验证 |

退出复用当前用户校验；账号已不可用时返回 401 并清除登录身份。自动测试覆盖会话销毁、失效会话被拒绝、重新登录及会话隔离；真实数据库与浏览器 Cookie 行为按本节验收。

## 前端启动与联调

前端要求 Node.js 22.13 及以上的 22 系列，或 24 及以上；本机已有 Node.js 25.3.0。直接依赖固定版本，传递依赖通过 `frontend/package-lock.json` 锁定。

在 `frontend/` 目录执行：

```sh
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173`，在首页填写需求并创建应用。未登录时跳转登录，返回首页后保留需求与生成模式，再点击「创建应用」进入 `/apps/{id}` 工作台，自动使用初始需求生成网站。生成成功后展示可交互的隔离预览；失败或停止保留已有成功预览，可手动重试。草稿仅留在当前标签页的内存中，刷新会清空尚未提交的草稿；工作台刷新或重新进入只恢复记录与预览，不自动重新生成。

顶部「系统状态」进入 `http://127.0.0.1:5173/system/status`，该页面自动检查后端和数据库。开发服务器把 `/api` 原样代理到 `http://127.0.0.1:8101`，保留后端的 `/api` 路径；端口被占用时直接报错，不自动切换端口。

- 正常连接时显示服务状态、实际数据库名称和本次检查时间。
- 连接失败时显示错误，点击「重试」可重新检查；旧的成功结果不会继续显示。
- 页面右上角「接口文档」通过同一代理打开 Knife4j。

| 验收 | 操作 | 预期结果 |
| --- | --- | --- |
| 代理与页面 | 保持后端运行，打开 `http://127.0.0.1:5173/api/health`，再打开「系统状态」 | 接口返回 `code: 0`；页面显示连接正常及实际库名 |
| 错误与恢复 | 手动停止后端，在页面点击「重新检查」；重新启动后端后点击「重试」 | 先显示连接失败且不保留成功结果，再恢复正常 |
| 文档与窄屏 | 点击「接口文档」；将连接检查页窗口缩窄到约 390px | 文档可调试健康接口；页面按钮可操作，无横向溢出 |

## 注册页面验收

按上面的启动方式运行后端与前端；用户表已建立时无需重复执行 SQL。打开 `http://127.0.0.1:5173/user/register`，或从首页顶部点击「注册账号」。页面通过前端代理调用已有注册接口。

| # | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | 输入一个未注册账号和一致的两次密码，点击「注册账号」 | 提交中按钮显示「正在注册…」且表单禁用；成功后显示「注册成功」，可返回首页 |
| 2 | 重新进入注册页，使用已注册账号提交 | 显示「注册失败」及后端提示「账号已存在」，保留输入，可改账号重试 |
| 3 | 空表单提交，或输入不一致的两次密码后提交 | 字段下方显示必填或密码不一致提示，不提交注册请求 |
| 4 | 输入 129 个字符的账号并提交，再改为 128 个字符的新账号提交 | 前者提示长度不能超过 128；后者可注册；emoji 等字符按 Unicode 码点计数 |
| 5 | 暂停后端后提交有效表单，再恢复后端并重试 | 显示连接失败提示且保留输入，按钮恢复可用；服务恢复后可正常提交 |

本步成功后不自动登录；可使用新账号在 Knife4j 调用已有登录接口。密码不会保存到浏览器持久存储，成功后清除本页密码字段；窗口缩窄至约 390px 时检查表单与顶部导航可用且无横向溢出。注册、登录、退出的页面流程测试在用户模块最后一步统一补齐。

## 一次性检查

在 `backend/` 目录执行：

```sh
sh mvnw verify
```

该命令运行接口测试并打包。健康检查使用数据库查询替身，用户模块使用 Mapper 替身与真实密码编码器；测试覆盖必填项、账号长度边界、密码一致、重复账号、普通用户注册、登录密码校验、脱敏响应及 Session 行为。当前用户测试覆盖登录后读取最新信息、未登录拒绝访问、无效身份清理及不同会话的身份隔离。退出测试覆盖销毁当前会话、退出后查询与重复退出被拒绝、重新登录恢复访问，以及不影响同一账号的另一会话。协议错误回归测试验证 405 / 415 及相关响应头，未预期业务异常仍返回 500。测试不连接实际 MySQL，建表、读写及浏览器 Cookie 需按上面的步骤验收。

前端在 `frontend/` 目录执行：

```sh
npm run type-check
npm run lint
npm test
npm run build
npm run format:check
npm run test:e2e
```

前端单元测试验证请求客户端对业务码和错误的处理。页面流程测试只临时使用 5175 端口，拦截所有 `/api` 请求，不访问实际后端；完成后关闭测试进程，不录屏或截图。Playwright 1.56.1 使用本机已有的 Chromium 1194；其他开发环境若缺少测试浏览器，需自行准备对应浏览器后运行页面测试。

预览沙箱的浏览器检查在 `backend/` 下执行，先运行 Maven 测试生成真实接口响应，并确保前端依赖与测试浏览器已就绪：

```sh
sh mvnw -Dtest=PreviewControllerTest test
node --test src/test/browser/preview-isolation.test.mjs
```

该检查复用前端已有的 Playwright，拦截全部网络请求，验证直接打开和 iframe 嵌入时的交互及凭证隔离，不启动项目服务。

后端健康检查的完整验收用例：

| 验收 | 操作 | 预期结果 |
| --- | --- | --- |
| 正常连接 | 配置实际账号后，在 Knife4j 调用 `GET /health` | `code: 0`，`status: UP`，`database` 为实际连接的库名 |
| 连接失败 | 临时将本地配置的数据库端口改为一个未监听端口，重启后调用接口 | HTTP 503，提示「数据库连接失败，请检查本地配置」，不返回伪成功 |
| 恢复连接 | 恢复正确端口，重新运行后端并调用接口 | 恢复正常结果 |
