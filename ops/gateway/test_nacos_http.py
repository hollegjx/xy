"""针对一次性本机 Nacos 2.x 的集成测试；不会使用默认 public 命名空间。"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import threading
import time
import unittest
from urllib.parse import urlsplit
from urllib.request import urlopen
import uuid


@unittest.skipUnless(os.getenv("XY_TEST_NACOS_HTTP"), "需要一次性本机 Nacos")
class HttpRegistryTest(unittest.TestCase):
    def test_two_services_keep_their_own_addresses_when_an_instance_moves(self):
        url = os.environ["XY_TEST_NACOS_HTTP"]
        self.assertIn(urlsplit(url).hostname, ("127.0.0.1", "localhost", "::1"))
        command = [sys.executable, str(Path(__file__).with_name("nacos_http.py")), "--nacos", url,
                   "--namespace", "xy-gateway-test-" + uuid.uuid4().hex]

        def cli(*args):
            return subprocess.run(command + list(args), check=True, text=True, stdout=subprocess.PIPE).stdout

        def wait_instances(service, expected):
            until = time.monotonic() + 12
            while True:
                rows = [json.loads(line) for line in cli("list", service).splitlines()]
                actual = {(r["ip"], r["port"], r["enabled"], r["healthy"], r["ephemeral"]) for r in rows}
                if actual == {("127.0.0.1", p, True, True, False) for p in expected}:
                    return rows
                if time.monotonic() >= until:
                    self.fail(f"{service}: {actual}, 预期端口 {expected}")
                time.sleep(.2)

        def change(action, service, port):
            cli(action, service, "--ip", "127.0.0.1", "--port", str(port))

        app, before, after = [self.http_service(label) for label in ("app", "user-old", "user-new")]
        for service, port in (("app", app), ("user", before), ("user", after)):
            change("register", service, port)
            self.addCleanup(change, "remove", service, port)
        change("register", "user", after)
        wait_instances("user", {before, after})
        change("remove", "user", before)
        for service, port, label in (("user", after, b"user-new"), ("app", app, b"app")):
            host, = wait_instances(service, {port})
            with urlopen(f"http://{host['ip']}:{host['port']}/api/probe", timeout=2) as response:
                self.assertEqual(response.read(), label)

    def http_service(self, label):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(label.encode())

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)
        return server.server_port


if __name__ == "__main__":
    unittest.main()
