# 用 Compose 运行应用服务和用户服务

从仓库根目录执行。需要 Linux Docker 与支持 `dockerfile_inline`、环境变量 secrets 的 Compose v2.23.1 或更新版本。使用独立项目及新卷；MySQL、Redis、Nacos、两个 Spring 服务只在容器网络内可达。宿主机只开放回环的 Higress 入口。

```sh
export XY_COMPOSE_DB_ROOT_PASSWORD="$(openssl rand -hex 24)"
export XY_COMPOSE_DB_PASSWORD="$(openssl rand -hex 24)"
docker compose -f ops/compose/compose.yaml up -d --build
```

这两个密码只在**首次创建**时生成，请保存在自己的密码管理器；下次操作这组已有卷时，先恢复同样的变量。两个后端只取得普通数据库账号的密码。全新 MySQL 卷只在首次启动时加载 001–005、007；006 是手工指定管理员的脚本，不会执行。此配置不连接现有实例，也不升级已有数据库。

打开 `http://127.0.0.1:8080/api/doc.html`。切到 `xy-user` 注册、登录，切到 `xy-app` 调创建应用并取回详情。账号请求走用户服务，应用服务读同一 Redis 会话并通过 Nacos 发现用户的 Dubbo 实例。退出后原 Cookie 在两边都失效。若端口被占用可另设 `XY_COMPOSE_PORT`。普通停机使用 `docker compose -f ops/compose/compose.yaml down`，保留数据库、会话及作品卷。

HTTP 登记进程将两个服务名解析为容器实际 IP，新端口可以连接后再撤旧登记。这一组 Nacos 专供本 Compose 使用。独立网关规则复用 [Higress 配置](../gateway/README.md)。此编排默认关闭真实模型、Vue 构建和封面截图；当前范围是两项对外提供 HTTP 的后端，尚不包含独立截图执行环境或公网静态站。

隔离测试命令：`XY_TEST_COMPOSE=1 python3 -Bu ops/compose/test_compose.py -v`。测试自己创建随机项目和空卷，使用真实服务、随机端口和密码；结束仅清理自己的项目。
