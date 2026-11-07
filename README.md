# 星云 · XY

零代码应用搭建平台。

**星云（Xing Yun）**，拼音首字母为 **XY**。通过零代码方式，让创意成为应用。

## 当前阶段

首期采用方案 B，功能范围见 [docs/spec.md](docs/spec.md)。当前开发后端骨架：数据库健康检查、统一响应和接口文档；前端工程将在下一步建立。

## 项目结构

```text
xy/
├── backend/        Java 后端、Maven Wrapper 和 SQL
├── frontend/       前端工程占位目录
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
2. 在已有 MySQL 连接中执行 [backend/sql/001_create_database.sql](backend/sql/001_create_database.sql)。它只在 `xy` 不存在时创建数据库，不修改已有表或数据；用户表随用户模块再加入。
3. 将 `backend/src/main/resources/application.example.yml` 复制为同目录的 `application.yml`，填写实际数据库地址、库名、账号、密码。本工作区若已存在该文件，直接编辑，勿覆盖已有配置。密码建议使用 YAML 单引号字符串，密码本身含单引号时写成两个单引号。
4. 在 IDEA 运行 `com.xy.MainApplication`，打开 `http://localhost:8101/api/doc.html`，在「健康检查」中调用 `GET /health`。

`application.yml` 是唯一的本地配置文件，已被 Git 忽略；仓库只保存不含真实凭证的 `application.example.yml`。不要强制添加本地配置到 Git。切换配置后需要重新运行后端。

成功结果示例：

```json
{"code":0,"data":{"status":"UP","database":"xy"},"message":"ok"}
```

也可在浏览器打开 `http://localhost:8101/api/health`。数据库不可连接或未选择库时，接口返回 HTTP 503 和可读错误；数据库原始异常记录在服务端，不返回给页面。OpenAPI 文档地址为 `http://localhost:8101/api/v3/api-docs`。

## 一次性检查

在 `backend/` 目录执行：

```sh
sh mvnw verify
```

该命令运行接口测试并打包。测试使用可控的数据库查询替身，不连接实际 MySQL；真实连接需按上面的启动步骤验收。

| 验收 | 操作 | 预期结果 |
| --- | --- | --- |
| 正常连接 | 配置实际账号后，在 Knife4j 调用 `GET /health` | `code: 0`，`status: UP`，`database` 为实际连接的库名 |
| 连接失败 | 临时将本地配置的数据库端口改为一个未监听端口，重启后调用接口 | HTTP 503，提示「数据库连接失败，请检查本地配置」，不返回伪成功 |
| 恢复连接 | 恢复正确端口，重新运行后端并调用接口 | 恢复正常结果 |
