# 用 Compose 运行应用服务和用户服务

从仓库根目录执行。需要 Linux Docker 与支持 `dockerfile_inline`、环境变量 secrets 的 Compose v2.23.1 或更新版本。使用独立项目及新卷；MySQL、Redis、Nacos、两个 Spring 服务只在容器网络内可达。宿主机只开放回环的 Higress 入口。

```sh
export XY_COMPOSE_DB_ROOT_PASSWORD="$(openssl rand -hex 24)"
export XY_COMPOSE_DB_PASSWORD="$(openssl rand -hex 24)"
docker compose -f ops/compose/compose.yaml up -d --build
```

这两个密码只在**首次创建**时生成，请保存在自己的密码管理器；下次操作这组已有卷时，先恢复同样的变量。两个后端只取得普通数据库账号的密码。全新 MySQL 卷只在首次启动时加载 001–005、007；006 是手工指定管理员的脚本，不会执行。此配置不连接现有实例，也不升级已有数据库。

打开 `http://127.0.0.1:8080/api/doc.html`。切到 `xy-user` 注册、登录，切到 `xy-app` 调创建应用并取回详情。账号请求走用户服务，应用服务读同一 Redis 会话并通过 Nacos 发现用户的 Dubbo 实例。退出后原 Cookie 在两边都失效。若端口被占用可另设 `XY_COMPOSE_PORT`。普通停机使用 `docker compose -f ops/compose/compose.yaml down`，保留数据库、会话及作品卷。

HTTP 登记进程将两个服务名解析为容器实际 IP，新端口可以连接后再撤旧登记。这一组 Nacos 专供本 Compose 使用。独立网关规则复用 [Higress 配置](../gateway/README.md)。默认关闭真实模型、Vue 构建和封面截图。

已有独立作品站时，将 `XY_COMPOSE_PUBLIC_URL` 设为它的来源，基础编排就能发布；站点须能读取应用卷中的 `published/`，且不与平台使用相同主机名。若还需要发布时自动生成封面，再叠加截图服务：

```sh
docker compose -f ops/compose/compose.yaml -f ops/compose/covers.yaml up -d --build
```

应用通过 Nacos 找到截图服务，由 Dubbo 传入本次发布的快照。截图服务通过只在两个容器间共享的 Unix 套接字将 HTML 送入 Chromium，取回 960×540 JPEG。渲染器没有网络、数据库密码、宿主 Docker 套接字或作品目录，每页使用新的 Chromium 进程。停机使用相同的两份配置执行 `down`。

要在本机直接打开发布结果，可使用随仓库提供的 Nginx（作品站另需 Linux Docker Engine 26.0+、Compose 2.26.0+，以支持只挂载命名卷的一个子目录）：

```sh
docker compose -f ops/compose/compose.yaml -f ops/compose/sites.yaml up -d --build
```

平台仍用 `http://127.0.0.1:8080`，发布一份已有成功稿的作品后，直接打开接口返回的 `http://127.0.0.2:8182/{发布标识}/`。两个服务分别绑定不同的回环地址。用 `XY_COMPOSE_SITE_PORT` 可同时修改作品端口和返回的网址。Nginx 只读挂载原作品卷的 `published/`，不接入平台容器网络；无需移动之前的快照。作品更新沿用原链接，删除应用后该链接返回 404。要一并截图，命令中再加 `-f ops/compose/covers.yaml`；停机时使用相同的配置组合。这个本机入口不接管公网作品域名。

隔离测试命令：`XY_TEST_COMPOSE=1 XY_TEST_COMPOSE_SITE=1 python3 -Bu ops/compose/test_compose.py -v`。测试在随机空卷准备成功稿，从分享网址匿名读取页面，验证重新发布、普通停机重启及删除。CI 还设置 `XY_TEST_SITE_BROWSER=google-chrome`，用全新的访客浏览器打开同一网址，确认作品脚本显示新版本。加上 `XY_TEST_COMPOSE_COVERS=1` 还会设为精选、解码真实封面，并核对尺寸及左右两块颜色。测试不访问模型或公网作品站。
