# XY 开发决策

## 2026-11-06：后端骨架

- 骨架拆为「后端健康检查」和「前端代理与健康状态页」两步，每步交付一个可验证结果。用户模块继续按 Spec 顺序实现。
- 独立搭建工程，不复制旧版通用模板。沿用 Controller 编排、Service 查询、DTO / VO 返回及异常统一转换的职责划分，让数据库细节与接口协议分开；暂不增加没有业务用途的接口和抽象层。
- 使用 Java 21 编译目标、Spring Boot **3.5.16**、MyBatis-Flex Boot 3 starter **1.11.8**、Knife4j UI **4.5.0**、springdoc **2.8.17**。MySQL 驱动、JDBC 连接池及其余 Spring 组件由固定的 Spring Boot BOM 管理。
- springdoc 2.8.17 的官方 POM 以 Spring Boot 3.5.13 为父项目，与所选 3.5 系列一致。测试发现 Knife4j 4.5.0 starter 的后端增强调用与该版本的 `getGroupConfigs()` 返回类型不兼容，因此仅保留 Knife4j 静态界面，使用 springdoc 提供标准文档和配置接口。接口注解使用 OpenAPI 3，不使用旧模板的 Swagger 2 注解。
- 本机已有 JDK 17、24，没有 JDK 21；本步使用现有 JDK 24 构建 Java 21 目标，不安装或替换 JDK。[Spring Boot 3.5 官方要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)列出的 Java 兼容范围为 17–25；JDK 21 上的实际运行仍需单独验收。
- 本地配置直接使用 `application.yml` 并忽略提交，跟踪 `application.example.yml` 供新环境复制。这样保持 IDEA 直接运行，同时满足项目要求的凭证不进入 Git。
- `GET /health` 实际执行只读查询 `SELECT DATABASE()`，不通过配置里的库名假装连接成功；失败返回 HTTP 503 / 业务码 50000。测试使用查询替身，真实数据库连接由用户验收。

## 2026-11-07：前端骨架

- 前端独立初始化 Vue **3.5.45**、Vite **7.3.7**、TypeScript **5.9.3**、Ant Design Vue **4.2.6**、Pinia **3.0.4**、Vue Router **4.6.4**、Axios **1.21.0**。依赖兼容要求已根据 npm 官方注册表核对；具体版本见 `frontend/package.json`，传递依赖由 lockfile 固定。
- 只创建系统连接检查页，页面状态留在本页。Pinia 只初始化插件，用户模块需要跨页面登录状态时再建立 store；本步不添加占位业务页面。
- 页面通过模块 API 和统一请求客户端访问 `/api/health`。客户端同时处理 HTTP 错误和业务错误，页面在重新检查前清除旧结果，避免连接失败后仍展示成功状态。
- 开发代理保留 `/api` 前缀，普通开发使用 5173，页面测试使用 5175，均禁止端口占用时自动递增。代理不会替用户启动后端。
- 页面测试固定 Playwright **1.56.1**，与本机已有 Chromium **1194** 对应；所有 `/api` 请求由测试拦截，测试数据只写在测试文件中。真实前后端联调和窄屏外观由用户验收。
- [Vite 环境与运行文档](https://vite.dev/guide/)；[开发代理文档](https://vite.dev/config/server-options.html#server-proxy)；[Playwright 1.56.1 浏览器版本清单](https://github.com/microsoft/playwright/blob/v1.56.1/packages/playwright-core/browsers.json)。

## 2026-11-07：用户模块第 1 步（注册）

- 代码保留按业务模块组织，模块内遵循 `fullstack-conventions` 的分层目录：`controller`、`service` / `service/impl`、`mapper`、`model/entity`、`model/dto` 和 `config`。Controller 依赖 `UserService` 接口，业务实现在 `UserServiceImpl`；实体与请求模型分别放置。后续新增业务模块沿用此结构，VO 在有相应响应模型时加入。
- 参考行为是此前已读取大纲中的用户注册能力；本步未取得教程该章正文。请求字段、表结构、散列方案和错误响应属于 XY 的独立设计。
- 新增 `POST /users/register`。请求只接收账号、密码和确认密码；边界使用 Bean Validation 校验必填及账号最多 128 个 Unicode 码点，Service 校验两次密码一致并固定普通用户角色。初始昵称使用账号，成功仅返回字符串形式的用户 ID。
- `002_create_user.sql` 只创建用户表，按 `001` → `002` 顺序由用户执行；ID 使用 MySQL 自增，创建和更新时间由数据库维护，MyBatis-Flex 显式关闭驼峰转下划线以匹配 SQL 字段。账号使用唯一索引，沿用数据库的 `utf8mb4_0900_ai_ci` 排序规则；逻辑删除记录也保留账号占用。
- 注册直接插入，由唯一索引统一判定重复，避免先查再写之间的竞态；将重复键转换为 HTTP 409 / 业务码 40000。未成功写入不返回成功。单次插入无需额外事务。
- 引入 Spring Boot **3.5.16** BOM 管理的 `spring-security-crypto` **6.5.11** 和 validation starter（Hibernate Validator **8.0.3.Final**）。只用密码编码组件，认证仍按 Spec 采用服务端 Session。
- 密码使用 PBKDF2-HMAC-SHA256、16 字节随机盐、600,000 次迭代；散列前缀标明参数版本，后续登录复用同一个 `PasswordEncoder`。账号长度对齐存储上限；不额外限制账号字符类型或密码长度，不裁剪密码内容。
- 原有统一响应扩展到注册参数错误和业务错误；不将密码、校验失败的原值或数据库内部细节返回客户端。Mapper 替身测试实际 Controller、校验、Service 与密码编码流程；真实 MySQL 表结构、唯一约束和写入由用户联调验收。
- 核对来源：[Spring Security 密码存储文档](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)、[PBKDF2 官方实现](https://github.com/spring-projects/spring-security/blob/6.5.0/crypto/src/main/java/org/springframework/security/crypto/password/Pbkdf2PasswordEncoder.java)、[MyBatis-Flex 1.11.8 Table 注解](https://github.com/mybatis-flex/mybatis-flex/blob/v1.11.8/mybatis-flex-annotation/src/main/java/com/mybatisflex/annotation/Table.java)。本步通过官方源码与本地固定版本的公开 API 核对调用方式，依赖版本以现有 Boot BOM 为准。

## 2026-11-07：用户模块第 2 步（登录）

- 新增 `POST /users/login`，沿用模块内分层：请求放 `model/dto/UserLoginRequest`，脱敏响应放 `model/vo/UserVO`，会话键放 `constant/UserConstant`；业务实现在 `service/impl/UserServiceImpl`，Controller 通过 Service 接口调用。
- 账号和密码在请求边界校验必填，密码复用注册时的 `PasswordEncoder.matches` 校验；账号不存在与密码错误统一返回 HTTP 400 / 业务码 40000「账号或密码错误」。账号查询通过 MyBatis-Flex 参数化条件完成，沿用实体的逻辑删除过滤。
- 成功后建立 Session 并更换 Session ID，仅写入 `loginUserId`；密码散列和角色不存入会话。用户响应只包含字符串 ID、账号、昵称及数据库中的角色。失败路径不会创建会话或写入新的登录身份。
- 参考行为仍基于此前读取的大纲；接口字段、会话内容和错误语义为 XY 设计。技术行为核对参考 [MyBatis-Flex 逻辑删除文档](https://mybatis-flex.com/zh/core/logic-delete.html)和 [Servlet HttpServletRequest API](https://tomcat.apache.org/tomcat-10.1-doc/servletapi/jakarta/servlet/http/HttpServletRequest.html)。测试以 Mapper 替身验证登录业务和会话状态，真实数据库查询与浏览器 Cookie 按 README 验收。

## 2026-11-07：注册与登录审查修复

- 注册账号使用现有 Hibernate Validator 的 `@CodePointLength(max = 128)`，与账号及初始昵称的 `VARCHAR(128)` 上限对齐。按 Unicode 码点计数，避免把一个补充平面字符按两个 UTF-16 单元计算；OpenAPI 同步标注 `maxLength: 128`。超长请求在进入 Service / Mapper 前返回 HTTP 400 / 业务码 40000，SQL 无需修改。
- 全局异常处理继承 Spring 的 `ResponseEntityExceptionHandler`，使用框架对应的 HTTP 状态码及协议响应头，统一包装为 `{ code, data, message }`。保留已有字段校验与 JSON 解析错误提示；405 / 415 保留原状态，未预期应用异常仍返回 500。
- 回归测试覆盖 ASCII 与补充平面字符的 128 / 129 边界、超长输入不访问 Mapper、405 的 `Allow` 响应头、415 的 `Accept` 响应头，以及真正的应用异常继续返回 500。

## 第三方文件与依赖来源

- Maven Wrapper **3.3.4** 使用 Apache 发布的 `only-script` 发行包；保留原始脚本的 Apache-2.0 声明。Maven 发行版本固定 **3.9.11**。
- [Maven Wrapper 官方文档](https://maven.apache.org/wrapper/)；[Apache-2.0 许可](https://www.apache.org/licenses/LICENSE-2.0)。
- [MyBatis-Flex Boot 3 starter POM](https://repo.maven.apache.org/maven2/com/mybatis-flex/mybatis-flex-spring-boot3-starter/1.11.8/mybatis-flex-spring-boot3-starter-1.11.8.pom)。
- [Knife4j UI POM](https://repo.maven.apache.org/maven2/com/github/xiaoymin/knife4j-openapi3-ui/4.5.0/knife4j-openapi3-ui-4.5.0.pom)；[springdoc 2.8.17 POM](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi/2.8.17/springdoc-openapi-2.8.17.pom)。
