# XY 的 Higress 网关

浏览器请求走 HTTP，应用查询用户身份或生成截图走 Dubbo Triple。两者使用同一个 Nacos 时，给网关单独登记两个 HTTP 服务：

| Nacos 服务名（组 `xy-http`） | HTTP 端口（默认） | 用途 |
| --- | --- | --- |
| `xy-app-http` | 8101 | 应用与生成、公开精选及封面、删除账号 |
| `xy-user-http` | 8124 | 注册、登录、退出、当前账号，以及管理员新增、查询、修改账号 |

**登记 HTTP 地址。** 两个后端需已运行，且从网关能连到准备登记的 IP 和 HTTP 端口。服务只绑定回环时，只有同一网络命名空间的网关能使用回环地址；不要把网关容器自己的 `127.0.0.1` 当成宿主机。截图没有面向浏览器的接口，无须登记。

在仓库根目录执行，替换下列地址。全局参数（如 `--namespace`）写在子命令之前。默认使用内置的 `public` 命名空间；使用其他命名空间时填写已有空间的 ID。鉴权开启时从私有环境传入 `XY_NACOS_ACCESS_TOKEN`，不将令牌写进命令、配置样例或 Git。

```sh
python3 ops/gateway/nacos_http.py --nacos http://<注册中心>:8848/nacos register app --ip <应用服务IP> --port 8101
python3 ops/gateway/nacos_http.py --nacos http://<注册中心>:8848/nacos register user --ip <用户服务IP> --port 8124
python3 ops/gateway/nacos_http.py --nacos http://<注册中心>:8848/nacos list user
```

这是持久实例，登记命令退出后地址仍然有效。换端口时先登记新地址，确认新实例健康、HTTP 可用后再撤下旧地址：`remove user --ip <旧IP> --port <旧端口>`。只删除这个地址，不删除整个服务或修改 Dubbo 注册。Higress 的服务来源应订阅同一命名空间的 `xy-http` 组。多服务分流需要两个进程使用同一个 Redis 会话。

**通过 Higress 访问。** [higress/](higress/) 是 standalone 文件模式配置，对应网关的数据目录 `/data`。将其中的 `mcpbridges/` 和 `ingresses/` 放入独立的 XY 网关数据目录。样例使用内网服务名 `nacos:8848`、内置命名空间 `public`。如使用其他命名空间，同步替换各条路由目标中的命名空间。启用 Nacos 鉴权的环境须在私有部署配置中给网关提供发现服务的凭证；登记命令的令牌不会传给 Higress。

| 浏览器请求（路径不改写） | 网关送达 |
| --- | --- |
| `/api/users` 及其子路径的 GET、HEAD、POST、PATCH | 用户服务；例如登录响应的 `Set-Cookie` 原样返回 |
| `DELETE /api/users/{id}` | 应用服务；在同一次操作中下线作品、删除应用和用户 |
| `GET` 或 `HEAD /api/v3/api-docs/xy-user` | 用户服务的 OpenAPI 文档；其他文档、页面资源仍由应用服务提供 |
| 其余 `/api` 请求，包括 `POST /api/apps/{id}/generations` | 应用服务；生成过程按 SSE 分片返回，路由超时为 360 秒，调大应用的生成时限时需同步调整 |

前端始终向同一来源请求 `/api`，浏览器的会话 Cookie 可随请求到达两个服务。服务端仍校验身份、角色和归属。这份样例不需要修改域名或防火墙，也不改变已有单体部署的入口。

**从同一入口查接口文档。** 当网关已接管账号请求，在**应用服务**现有的 Spring profiles 中加上 `gateway-docs`。例如启用 Nacos 的平台可用 `--spring.profiles.active=nacos,gateway-docs --xy.user.http-enabled=false`；后一个开关才负责关闭平台重复的账号接口。用户服务使用自己的默认配置即可。

通过网关打开 `http://localhost:8080/api/doc.html`（或在前端的网关联调模式下点「接口文档」）。在 Knife4j 切换 `xy-app` 可查应用生成和删号，切换 `xy-user` 可查注册、登录等七个账号操作。文档来自两个运行中的服务，调试请求仍走同一个网关。截图只提供内部 RPC，所以没有 HTTP 文档。直连单体开发时不用启用这个 profile，原来的 `:8101/api/doc.html` 保持不变。

在**隔离的 Linux Docker 测试机**上运行 `XY_TEST_HIGRESS=1 python3 -Bu ops/gateway/test_higress.py -v`。测试创建专用 Docker 网络、真实 Nacos 和 Higress，使用宿主回环的随机端口，退出时清理。两个临时 HTTP 上游代替业务服务，用于单独验证路由、Cookie、流式转发以及 Nacos 地址变更；不连接模型或业务数据库。
