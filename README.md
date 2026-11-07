# 星云 · XY

零代码应用搭建平台。

**星云（Xing Yun）**，拼音首字母为 **XY**。通过零代码方式，让创意成为应用。

## 当前阶段

首期采用方案 B，功能范围见 [docs/spec.md](docs/spec.md)。当前包含后端健康检查、接口文档、前端连接检查页和用户注册、登录、当前用户、退出接口；用户页面按后续步骤增加。

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

## Git 约定

- 默认分支：`main`。
- 功能分支：`feature/<功能名>`；修复分支：`fix/<问题名>`。
- 提交信息使用 `feat:`、`fix:`、`docs:`、`chore:` 等前缀。
- 依赖目录、编译产物、日志和本地密钥不提交。

## 后端环境

- 项目源码目标为 Java 21。推荐 JDK 21；当前已安装的 JDK 24 也在 Spring Boot 3.5.16 的兼容范围内，可用于本步验证，Maven 编译目标仍为 21。
- MySQL 8.0 或以上，使用现有实例和账号；本机检测到 MySQL 8.4。
- Maven Wrapper 固定 Maven 3.9.11，无需全局安装 Maven；首次构建需要下载发行包与项目依赖。
- 后端依赖版本、来源和选择原因见 [docs/NOTES.md](docs/NOTES.md)。

## 本地配置与启动

1. 在 IDEA 打开 `backend/pom.xml`，选择已有且受支持的 JDK，等待 Maven 导入完成。
2. 在已有 MySQL 连接中依次执行 [backend/sql/001_create_database.sql](backend/sql/001_create_database.sql) 和 [backend/sql/002_create_user.sql](backend/sql/002_create_user.sql)，分别创建数据库和用户表。它们不删除或覆盖已有数据；已执行过的文件无需重复执行。如已有同名用户表，先核对结构。
3. 将 `backend/src/main/resources/application.example.yml` 复制为同目录的 `application.yml`，填写实际数据库地址、库名、账号、密码。本工作区若已存在该文件，直接编辑，勿覆盖已有配置。密码建议使用 YAML 单引号字符串，密码本身含单引号时写成两个单引号。
4. 在 IDEA 运行 `com.xy.MainApplication`，打开 `http://localhost:8101/api/doc.html`，在「健康检查」中调用 `GET /health`。

`application.yml` 是唯一的本地配置文件，已被 Git 忽略；仓库只保存不含真实凭证的 `application.example.yml`。不要强制添加本地配置到 Git。切换配置后需要重新运行后端。

成功结果示例：

```json
{"code":0,"data":{"status":"UP","database":"xy"},"message":"ok"}
```

也可在浏览器打开 `http://localhost:8101/api/health`。数据库不可连接或未选择库时，接口返回 HTTP 503 和可读错误；数据库原始异常记录在服务端，不返回给页面。OpenAPI 文档地址为 `http://localhost:8101/api/v3/api-docs`。

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
| 2 | 再提交相同账号 | HTTP 409、`code: 40000`，提示「账号已存在」，不会新增第二条记录 |
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

在浏览器开发者工具的 Network 中选中成功的登录请求，响应头应有 `Set-Cookie: JSESSIONID=...`。再次成功登录时会更换 Session ID。服务端 Session 只保存用户 ID，密码和角色不写入会话；后续请求由浏览器携带 Cookie。实际浏览器 Cookie 行为由此步骤验收；自动测试验证的是会话属性、登录失败不创建会话以及成功时更新会话 ID。

## 当前用户验收

在 IDEA 重启后端，本步无需执行新的 SQL。在同一个浏览器窗口的 Knife4j 中先调用 `POST /users/login`，再调用 `GET /users/me`（完整路径为 `/api/users/me`），由浏览器自动携带登录 Cookie。

成功响应的字段与登录响应相同：`id`、`userAccount`、`userName`、`userRole`。每次根据 Session 中的用户 ID 查询数据库，返回最新用户信息；不接收客户端提供的用户 ID 作为登录身份。

| # | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | 在新的无痕窗口直接访问 `http://localhost:8101/api/users/me` | HTTP 401 / `code: 40100`，提示「请先登录」 |
| 2 | 在同一窗口、相同主机地址下登录，再查询当前用户 | HTTP 200 / `code: 0`，返回已登录账号的信息，不含密码散列 |
| 3 | 登录后访问 `/api/users/me?userId=0` | 仍返回当前登录账号的信息，查询参数不改变身份 |
| 4 | 清除该站点的 `JSESSIONID` Cookie 后重新请求 | 再次返回 HTTP 401 / `code: 40100` |

无会话的请求不会创建新 Session。会话对应账号不存在或被逻辑删除时，查询返回 401 并清除会话内的登录身份；此路径由 Mapper 替身测试覆盖，常规验收无需修改或删除数据库里的账号。

## 用户退出验收

在 IDEA 重启后端，本步无需执行新的 SQL。在同一浏览器窗口、相同主机地址的 Knife4j 中先登录，再调用 `POST /users/logout`（完整路径为 `/api/users/logout`），无需请求体。

成功返回 `{"code":0,"data":true,"message":"ok"}`，服务端销毁当前 Session；浏览器即使仍保留旧的 `JSESSIONID` Cookie，也无法再凭它获取当前用户信息。

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

打开 `http://127.0.0.1:5173`，页面自动检查后端和数据库。开发服务器把 `/api` 原样代理到 `http://127.0.0.1:8101`，保留后端的 `/api` 路径；端口被占用时直接报错，不自动切换端口。

- 正常连接时显示服务状态、实际数据库名称和本次检查时间。
- 连接失败时显示错误，点击「重试」可重新检查；旧的成功结果不会继续显示。
- 页面右上角「接口文档」通过同一代理打开 Knife4j。

| 验收 | 操作 | 预期结果 |
| --- | --- | --- |
| 代理与页面 | 保持后端运行，打开 `http://127.0.0.1:5173/api/health`，再打开首页 | 接口返回 `code: 0`；页面显示连接正常及实际库名 |
| 错误与恢复 | 手动停止后端，在页面点击「重新检查」；重新启动后端后点击「重试」 | 先显示连接失败且不保留成功结果，再恢复正常 |
| 文档与窄屏 | 点击「接口文档」；将首页窗口缩窄到约 390px | 文档可调试健康接口；页面按钮可操作，无横向溢出 |

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

后端健康检查的完整验收用例：

| 验收 | 操作 | 预期结果 |
| --- | --- | --- |
| 正常连接 | 配置实际账号后，在 Knife4j 调用 `GET /health` | `code: 0`，`status: UP`，`database` 为实际连接的库名 |
| 连接失败 | 临时将本地配置的数据库端口改为一个未监听端口，重启后调用接口 | HTTP 503，提示「数据库连接失败，请检查本地配置」，不返回伪成功 |
| 恢复连接 | 恢复正确端口，重新运行后端并调用接口 | 恢复正常结果 |
