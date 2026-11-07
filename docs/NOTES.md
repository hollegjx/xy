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

## 第三方文件与依赖来源

- Maven Wrapper **3.3.4** 使用 Apache 发布的 `only-script` 发行包；保留原始脚本的 Apache-2.0 声明。Maven 发行版本固定 **3.9.11**。
- [Maven Wrapper 官方文档](https://maven.apache.org/wrapper/)；[Apache-2.0 许可](https://www.apache.org/licenses/LICENSE-2.0)。
- [MyBatis-Flex Boot 3 starter POM](https://repo.maven.apache.org/maven2/com/mybatis-flex/mybatis-flex-spring-boot3-starter/1.11.8/mybatis-flex-spring-boot3-starter-1.11.8.pom)。
- [Knife4j UI POM](https://repo.maven.apache.org/maven2/com/github/xiaoymin/knife4j-openapi3-ui/4.5.0/knife4j-openapi3-ui-4.5.0.pom)；[springdoc 2.8.17 POM](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi/2.8.17/springdoc-openapi-2.8.17.pom)。
