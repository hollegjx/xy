"""为 Higress 在 Nacos 2.x 登记两个 XY HTTP 服务；不操作 Dubbo 服务。"""
import argparse
import http.client
import ipaddress
import json
import os
import sys
from urllib.parse import urlencode, urlsplit


GROUP = "xy-http"
SERVICES = {"app": ("xy-app-http", 8101), "user": ("xy-user-http", 8124)}


class Registry:
    def __init__(self, url, namespace="public", token=None):
        endpoint = urlsplit(url)
        if endpoint.scheme not in ("http", "https") or not endpoint.hostname or (
                endpoint.username is not None or endpoint.query or endpoint.fragment):
            raise ValueError("Nacos 地址应为 http(s)://主机:端口/nacos，令牌单独设置")
        if token and not all(33 <= ord(c) <= 126 for c in token):
            raise ValueError("Nacos 令牌含空白或无效字符")
        self.endpoint, self.namespace, self.token = endpoint, namespace, token

    def request(self, method, path, params):
        endpoint = self.endpoint
        conn_type = http.client.HTTPSConnection if endpoint.scheme == "https" else http.client.HTTPConnection
        conn = conn_type(endpoint.hostname, endpoint.port, timeout=8)
        url = endpoint.path.rstrip("/") + "/v2/ns/instance" + path
        form = urlencode(params)
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        try:
            if method in ("GET", "DELETE"):
                conn.request(method, url + "?" + form, headers=headers)
            else:
                conn.request(method, url, body=form, headers=headers)
            res = conn.getresponse()
            if res.status != 200:
                raise RuntimeError(f"Nacos 返回 HTTP {res.status}")
            result = json.load(res)
        finally:
            conn.close()
        if result.get("code") != 0:
            raise RuntimeError(f"Nacos 未接受请求（业务码 {result.get('code')}）")
        return result["data"]

    def key(self, service):
        return {"namespaceId": self.namespace, "groupName": GROUP, "serviceName": SERVICES[service][0]}

    def change(self, action, service, ip, port):
        ip = str(ipaddress.ip_address(ip))
        if not 1 <= port <= 65535:
            raise ValueError("端口应在 1–65535 之间")
        params = dict(self.key(service), ip=ip, port=port, clusterName="DEFAULT", ephemeral="false")
        method = {"register": "POST", "remove": "DELETE"}[action]
        if self.request(method, "", params) != "ok":
            raise RuntimeError("Nacos 未确认实例变更")

    def instances(self, service):
        data = self.request("GET", "/list", dict(self.key(service), healthyOnly="false"))
        return data["hosts"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nacos", default="http://127.0.0.1:8848/nacos")
    parser.add_argument("--namespace", default="public")
    actions = parser.add_subparsers(dest="action", required=True)
    for action in ("register", "list", "remove"):
        cmd = actions.add_parser(action)
        cmd.add_argument("service", choices=SERVICES)
        if action != "list":
            cmd.add_argument("--ip", required=True, help="从网关能访问的服务 IP")
            cmd.add_argument("--port", type=int, help="实际 HTTP 端口（默认 app 8101 / user 8124）")
    args = parser.parse_args()
    registry = Registry(args.nacos, args.namespace, os.getenv("XY_NACOS_ACCESS_TOKEN"))
    if args.action == "list":
        for host in registry.instances(args.service):
            print(json.dumps({k: host[k] for k in ("ip", "port", "healthy", "enabled", "ephemeral")}))
    else:
        port = SERVICES[args.service][1] if args.port is None else args.port
        registry.change(args.action, args.service, args.ip, port)
        print(f"{args.action}: {GROUP}/{SERVICES[args.service][0]} {args.ip} {port}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, http.client.HTTPException, ValueError, KeyError, RuntimeError) as error:
        sys.exit(str(error))
