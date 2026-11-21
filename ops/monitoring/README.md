# 查看 XY 的模型用量和失败情况

启用后数据从模型调用进入 LangChain4j 监听器，由 Micrometer 计数。Prometheus 定期抓取后端的管理端口；Grafana 只查询 Prometheus，不访问 XY 的 MySQL、模型网关或业务接口。

1. 为 XY 后端启用 Spring Profile `monitoring`（可和已有 profile 一起启用）。默认业务端口不变。先在后端所在主机访问 `http://127.0.0.1:8102/actuator/prometheus`，能看到 `jvm_memory_used_bytes`。如启动命令用 `spring.config.location` **替换**了默认配置目录，须让它同时加载 classpath，或在私有配置中用 `spring.config.import: "classpath:/application-monitoring.yml"` 导入管理端口设置。
2. 在已有 Prometheus 的配置里加入 [xy-backend 抓取任务](prometheus.yml)。这里的 `127.0.0.1` 指后端宿主机；独立容器的回环地址不是宿主机。Targets 中 `xy-backend` 为 UP，且查询 `up{job="xy-backend"}` 返回 1，才表示抓取成功。无需向公网开放 8102。
3. 在已有 Grafana 中添加该 Prometheus 为数据源，测试连接。在 **Dashboards → New → Import** 上传 [xy-ai-dashboard.json](xy-ai-dashboard.json)。在看板的「Prometheus」下拉框选择刚才的数据源。模型调用后，等待一次抓取即可查看累计值；趋势至少需要窗口中有两次采样。

| 看什么 | 看板如何统计 |
| --- | --- |
| 调用次数 | 发给分类、原生或 Vue 模型的实际调用；生成、工具续写、质检和返工可能对应多次调用。趋势可切换模型、用户或应用，滚动窗口从 1 分钟到 1 天。 |
| Token 与排行 | 使用网关实际返回的 input、output、total；没有报告就不填。应用与用户分别按调用次数和总 Token 显示 Top 10。自动分类先于入库，没有应用 ID，因此不占用应用排行榜。 |
| 响应时间 | 从模型请求开始计到 SDK 响应或报错，包含整个流，不是首字延迟。平均值使用耗时之和除以完成次数；P50/P90/P95/P99 从各实例的模型直方图合并计算。 |
| 错误与失败率 | 只用已有成功或错误回调作分母，按模型和异常类型拆分。用户中断后若 SDK 没有终态回调，仅保留发起计数。提示词、回复和网关错误原文不作为指标标签。 |

累计卡片和排行榜展示当前可抓取实例自启动以来的计数；时间范围影响趋势图，进程重启会重置累计值。账单仍以供应方为准。

有 `promtool` 时可用 `python3 ops/monitoring/test_dashboard.py /path/to/promtool` 核对抓取配置，并用固定的两个模型、两个用户和一个第三方任务实际执行看板里的 PromQL。

教程的阿里云 ARMS 是另一套系统监控。接入需要已开通的账号、地域和私有授权信息，以及对后端 JVM 加载官方 Java 探针的运维授权。上述步骤不创建云服务、不上报 ARMS，也不自动安装 Prometheus 或 Grafana。
