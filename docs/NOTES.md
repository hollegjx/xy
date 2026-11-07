# XY 开发决策

## 2026-11-06：后端骨架

- 骨架拆为「后端健康检查」和「前端代理与健康状态页」两步，每步交付一个可验证结果。用户模块继续按 Spec 顺序实现。
- 独立搭建工程，不复制旧版通用模板。沿用 Controller 编排、Service 查询、DTO / VO 返回及异常统一转换的职责划分，让数据库细节与接口协议分开；暂不增加没有业务用途的接口和抽象层。
- 使用 Java 21 编译目标、Spring Boot **3.5.16**、MyBatis-Flex Boot 3 starter **1.11.8**、Knife4j UI **4.5.0**、springdoc **2.8.17**。MySQL 驱动、JDBC 连接池及其余 Spring 组件由固定的 Spring Boot BOM 管理。
- springdoc 2.8.17 的官方 POM 以 Spring Boot 3.5.13 为父项目，与所选 3.5 系列一致。测试发现 Knife4j 4.5.0 starter 的后端增强调用与该版本的 `getGroupConfigs()` 返回类型不兼容，因此仅保留 Knife4j 静态界面，使用 springdoc 提供标准文档和配置接口。接口注解使用 OpenAPI 3，不使用旧模板的 Swagger 2 注解。
- 本机已有 JDK 17、24，没有 JDK 21；本步使用现有 JDK 24 构建 Java 21 目标，不安装或替换 JDK。[Spring Boot 3.5 官方要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)列出的 Java 兼容范围为 17–25；JDK 21 上的实际运行仍需单独验收。
- 本地配置直接使用 `application.yml` 并忽略提交，跟踪 `application.example.yml` 供新环境复制。这样保持 IDEA 直接运行，同时满足项目要求的凭证不进入 Git。
- `GET /health` 实际执行只读查询 `SELECT DATABASE()`，不通过配置里的库名假装连接成功；失败返回 HTTP 503 / 业务码 50000。测试使用查询替身，真实数据库连接由用户验收。

## 第三方文件与依赖来源

- Maven Wrapper **3.3.4** 使用 Apache 发布的 `only-script` 发行包；保留原始脚本的 Apache-2.0 声明。Maven 发行版本固定 **3.9.11**。
- [Maven Wrapper 官方文档](https://maven.apache.org/wrapper/)；[Apache-2.0 许可](https://www.apache.org/licenses/LICENSE-2.0)。
- [MyBatis-Flex Boot 3 starter POM](https://repo.maven.apache.org/maven2/com/mybatis-flex/mybatis-flex-spring-boot3-starter/1.11.8/mybatis-flex-spring-boot3-starter-1.11.8.pom)。
- [Knife4j UI POM](https://repo.maven.apache.org/maven2/com/github/xiaoymin/knife4j-openapi3-ui/4.5.0/knife4j-openapi3-ui-4.5.0.pom)；[springdoc 2.8.17 POM](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi/2.8.17/springdoc-openapi-2.8.17.pom)。
