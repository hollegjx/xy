# 星云 · XY

零代码应用搭建平台。

**星云（Xing Yun）**，拼音首字母为 **XY**。通过零代码方式，让创意成为应用。

## 当前阶段

首期采用方案 B，功能范围见 [docs/spec.md](docs/spec.md)。当前包含后端健康检查、接口文档、前端连接检查页和用户注册接口；用户模块按接口逐步增加。

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

成功返回 `{"code":0,"data":"1","message":"ok"}`，其中 `data` 是实际生成的用户 ID，字符串形式避免前端整数精度丢失。注册后需要单独登录，登录接口在下一步实现。

| # | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | 用未注册的账号提交上面的请求 | HTTP 200、`code: 0`、`data` 为用户 ID；库中新增普通用户，昵称初始为账号，密码为散列 |
| 2 | 再提交相同账号 | HTTP 409、`code: 40000`，提示「账号已存在」，不会新增第二条记录 |
| 3 | 分别将账号、密码、确认密码设为空字符串或删去对应字段，其余字段填写正确 | HTTP 400、`code: 40000`，提示对应字段不能为空 |
| 4 | 填入不同的两次密码 | HTTP 400、`code: 40000`，提示「两次输入的密码不一致」 |
| 5 | 换新账号 `xy_role_test`，额外传入 `"userRole": "admin"` | 注册成功，库中角色仍为 `user`，响应只有 ID、不含密码散列 |

在 IDEA 用只读查询核对角色与散列标识，无需显示密码散列原文：

```sql
SELECT id, userAccount, userName, userRole,
       userPassword LIKE '{pbkdf2-sha256-600000}%' AS passwordHashed
FROM xy.`user`
WHERE userAccount IN ('xy_demo', 'xy_role_test');
```

预期角色均为 `user`，`passwordHashed` 为 `1`。重复验收时正常注册用新的账号。

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

该命令运行接口测试并打包。健康检查使用数据库查询替身，注册使用 Mapper 替身和真实密码编码器；测试覆盖必填项、密码一致、重复账号、普通用户角色及脱敏响应。测试不连接实际 MySQL，建表与实际写入需按上面的步骤验收。

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
