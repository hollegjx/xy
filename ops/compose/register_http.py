"""仅同步本 Compose 专用 Nacos 中的 HTTP 实例，不改 Dubbo 的服务登记。"""
import http.client
import socket
import time

from nacos_http import Registry, SERVICES


def sync(registry, service):
    port = SERVICES[service][1]
    addresses = {row[4][0] for row in socket.getaddrinfo(service, port, socket.AF_INET, socket.SOCK_STREAM)}
    # 新容器还没开始监听时保留原登记。Compose 默认每服务一个实例。
    for ip in addresses:
        with socket.create_connection((ip, port), timeout=2):
            pass
    wanted = {(ip, port) for ip in addresses}
    try:
        instances = {(row["ip"], row["port"]) for row in registry.instances(service)}
    except RuntimeError:
        # 空注册中心可能还没有这个持久服务；先登记，下一轮读取成功后才撤旧地址。
        instances = set()
    for ip, number in wanted - instances:
        registry.change("register", service, ip, number)
    for ip, number in instances - wanted:
        registry.change("remove", service, ip, number)


if __name__ == "__main__":
    registry = Registry("http://nacos:8848/nacos")
    while True:
        for service in SERVICES:
            try:
                sync(registry, service)
            except (OSError, http.client.HTTPException, RuntimeError) as error:
                print(f"{service} HTTP 登记等待重试: {error}", flush=True)
        time.sleep(5)
