# 给 XY 单机部署准备 Redis

Spring Session 用 Redis 保存登录会话，Redisson 用它计算生成额度。升级到这版前先确认后端能访问 Redis；仅把镜像构建成功，不能证明登录可用。后端已配置共享 Redis 时直接沿用，无需新容器。

以下命令适用于 XY 后端使用 Linux host 网络、同机进程可信且现有 Redis 端口空闲的环境。容器只监听回环地址，不发布 Docker 端口；数据单独保存在命名卷，和 MySQL、作品文件分开。镜像摘要固定为本次核对的 Redis 7.4.12 Alpine。

```sh
docker run -d --name xy-redis --restart unless-stopped --network host \
  --mount type=volume,src=xy-redis-data,dst=/data \
  redis@sha256:7f7d30b7fdd09467bac58198a1f166dd69789d348d4fe859cccb20e0cf287fbe \
  redis-server --bind 127.0.0.1 --port 6379 --protected-mode yes --appendonly yes
```

运行 `docker exec xy-redis redis-cli -h 127.0.0.1 ping` 应返回 `PONG`。确认 `ss -ltn 'sport = :6379'` 仅显示 `127.0.0.1:6379`，再切换后端。默认连接 `127.0.0.1:6379`、库 0；若使用外部或已有带认证实例，按其运维要求填写后端私有 `spring.data.redis` 配置。

旧版的登录态只在进程内，首次升级后需要重新登录。回退应用不需要删除 Redis 容器或数据卷。
