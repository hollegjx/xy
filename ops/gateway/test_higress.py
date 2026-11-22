"""用真实 Higress / Nacos 检查 HTTP 分流。两个上游只模拟后端协议，不接数据库或模型。"""
from concurrent.futures import ThreadPoolExecutor
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import queue
import subprocess
import threading
import time
import unittest
from pathlib import Path
from uuid import uuid4

from nacos_http import Registry


ROOT = Path(__file__).resolve().parent
NACOS = "nacos/nacos-server@sha256:d70b20546fe59aeb86add0754db1dbc3a3067363e18599329c5163d0d8cd9cd8"  # v2.3.2
HIGRESS = "higress-registry.us-west-1.cr.aliyuncs.com/higress/all-in-one@sha256:3e7de95ac088a0c86a78b9dd236e9cd1e970163f25a6c092ab4589c993073b87"  # 2.2.6


def docker(*args):
    # 首次拉镜像的进度在 stderr；stdout 只包含容器 ID / 端口。
    return subprocess.check_output(["docker", *args], text=True, timeout=240).strip()


def wait_for(check, timeout=120):
    end = time.monotonic() + timeout
    while True:
        try:
            if check():
                return
        except (OSError, http.client.HTTPException, RuntimeError):
            pass
        if time.monotonic() >= end:
            raise AssertionError("等待 " + check.__name__ + " 超时")
        time.sleep(.5)


def request(port, method, path, body=None, cookie=None):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    headers = {"Cookie": cookie} if cookie else {}
    try:
        conn.request(method, path, headers=headers, body=body)
        r = conn.getresponse()
        return r.status, r.getheader("Set-Cookie"), r.read()
    finally:
        conn.close()


@unittest.skipUnless(os.getenv("XY_TEST_HIGRESS") == "1", "需要 Linux Docker 的隔离测试")
class GatewayTest(unittest.TestCase):
    def test_gateway_uses_http_discovery_and_keeps_the_browser_session_and_stream(self):
        # 每轮独立 Linux bridge；只有测试进程从回环端口访问容器。
        network = docker("network", "create", "xy-gateway-test-" + uuid4().hex[:12])
        self.addCleanup(docker, "network", "rm", network)
        host = docker("network", "inspect", "-f", "{{(index .IPAM.Config 0).Gateway}}", network)
        nacos = docker("run", "-d", "--network", network, "--network-alias", "nacos",
                       "-p", "127.0.0.1::8848", "-e", "MODE=standalone",
                       "-e", "JVM_XMS=128m", "-e", "JVM_XMX=384m", NACOS)
        self.addCleanup(docker, "rm", "-f", "-v", nacos)
        registry_port = int(docker("port", nacos, "8848/tcp").rsplit(":", 1)[1])
        def nacos_ready():
            return request(registry_port, "GET", "/nacos/v1/console/health/readiness")[2].strip() in (b"OK", b"UP")
        wait_for(nacos_ready)

        release = threading.Event()
        app = self.upstream("app", host, release)
        user = self.upstream("user", host, release)
        registry = Registry(f"http://127.0.0.1:{registry_port}/nacos")
        def registered():
            for s, p in (("app", app), ("user", user)):
                registry.change("register", s, host, p)
            return True
        wait_for(registered)
        gateway = docker("create", "--network", network, "-p", "127.0.0.1::8080", "-e", "O11Y=off", HIGRESS)
        self.addCleanup(docker, "rm", "-f", "-v", gateway)
        docker("cp", str(ROOT / "higress") + "/.", gateway + ":/data")
        docker("start", gateway)
        port = int(docker("port", gateway, "8080/tcp").rsplit(":", 1)[1])
        def app_route_ready():
            return request(port, "GET", "/api/apps/featured")[2] == b"app"
        def user_route_ready():
            return request(port, "GET", "/api/users/me")[2] == b"user"
        try:
            wait_for(app_route_ready)
            wait_for(user_route_ready)
        except AssertionError:
            print("网关进程:", docker("inspect", "-f", "{{.State.Status}}", gateway))
            for path in ("/api/apps/featured", "/api/users/me"):
                try:
                    print(path, "HTTP", request(port, "GET", path)[0])
                except OSError:
                    print(path, "未建立 HTTP 连接")
            raise

        status, cookie, received = request(port, "POST", "/api/users/login", b'{"login":"example"}')
        self.assertEqual((status, received), (200, b'{"login":"example"}'))
        self.assertEqual(cookie, "SESSION=xy-fixture; Path=/api; HttpOnly")
        for method, path, dest in (("GET", "/api/users", b"user"), ("PATCH", "/api/users/42", b"user"),
                                   ("DELETE", "/api/users/42", b"app"), ("GET", "/api/usersuffix", b"app"),
                                   ("GET", "/api/doc.html", b"app"), ("GET", "/api/v3/api-docs/swagger-config", b"app"),
                                   ("GET", "/api/v3/api-docs", b"app"), ("GET", "/api/v3/api-docs/xy-user", b"user"),
                                   ("GET", "/api/v3/api-docs/xy-user-extra", b"app"),
                                   ("POST", "/api/v3/api-docs/xy-user", b"app"),
                                   ("GET", "/api/apps/mine?current=1", b"app")):
            status, _, response = request(port, method, path, cookie="SESSION=xy-fixture")
            self.assertEqual((status, response), (200, dest + b" " + method.encode() + b" " + path.encode()))
        head = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        try:
            head.request("HEAD", "/api/v3/api-docs/xy-user")
            response = head.getresponse()
            self.assertEqual((response.status, response.getheader("X-XY-Test-Upstream")), (200, "user"))
        finally:
            head.close()

        events = queue.Queue()
        def read_sse():
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=12)
            try:
                conn.request("POST", "/api/apps/42/generations", body=b"{}")
                response = conn.getresponse()
                self.assertEqual(response.status, 200)
                self.assertIn("text/event-stream", response.getheader("Content-Type"))
                events.put(response.readline())
                return response.read()
            finally:
                conn.close()
        with ThreadPoolExecutor(1) as pool:
            future = pool.submit(read_sse)
            try:
                self.assertEqual(events.get(timeout=5), b":keepalive\n")
            finally:
                release.set()
            self.assertIn(b"event:completed", future.result(timeout=12))

        replacement = self.upstream("new-user", host, release)
        registry.change("register", "user", host, replacement)
        registry.change("remove", "user", host, user)
        def switched():
            return all(request(port, "GET", "/api/users/me")[2] == b"new-user" for _ in range(8))
        wait_for(switched)

    def upstream(self, name, host, release):
        class Handler(BaseHTTPRequestHandler):
            def do_HEAD(self):
                self.send_response(200)
                self.send_header("X-XY-Test-Upstream", name)
                self.end_headers()

            def handle_request(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                self.send_response(200)
                if name == "app" and self.path == "/api/apps/42/generations":
                    self.send_header("Content-Type", "text/event-stream")
                    self.end_headers()
                    self.wfile.write(b":keepalive\n\n")
                    self.wfile.flush()
                    if release.wait(10):
                        self.wfile.write(b"event:completed\ndata:{}\n\n")
                    return
                if name == "user" and self.path == "/api/users/login":
                    self.send_header("Set-Cookie", "SESSION=xy-fixture; Path=/api; HttpOnly")
                    result = body
                elif self.headers.get("Cookie") == "SESSION=xy-fixture":
                    result = f"{name} {self.command} {self.path}".encode()
                else:
                    result = name.encode()
                self.end_headers()
                self.wfile.write(result)
            do_GET = do_POST = do_PATCH = do_DELETE = handle_request
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer((host, 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)
        return server.server_port


if __name__ == "__main__":
    unittest.main()
