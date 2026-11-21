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
