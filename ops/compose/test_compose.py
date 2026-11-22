"""在独立 Linux Docker 上从空卷启动整个 Compose。只清理本用例自己的随机项目。"""
import http.client
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import unittest


@unittest.skipUnless(os.getenv("XY_TEST_COMPOSE") == "1", "需要隔离的 Linux Docker Compose")
class ComposeTest(unittest.TestCase):
    def compose(self, *args, output=False):
        files = ["-f", str(self.file)]
        if self.covers:
            files += ["-f", str(self.file.with_name("covers.yaml"))]
        return subprocess.run(["docker", "compose", "-p", self.project, *files, *args],
                              env=self.env, check=True, text=True, timeout=1100,
                              stdout=subprocess.PIPE if output else None).stdout

    def request(self, method, path, body=None, cookie=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=8)
        headers = {"Content-Type": "application/json"}
        if cookie:
            headers["Cookie"] = cookie
        try:
            conn.request(method, "/api" + path, None if body is None else json.dumps(body), headers)
            response = conn.getresponse()
            return response.status, response.getheader("Set-Cookie"), json.loads(response.read())
        finally:
            conn.close()

    def gateway_port(self):
        return int(self.compose("port", "gateway", "8080", output=True).strip().rsplit(":", 1)[1])

    def expect(self, path, status, code, *, method="GET", body=None, cookie=None, wait=0):
        end = time.monotonic() + wait
        while True:
            try:
                actual, session, result = self.request(method, path, body, cookie)
                last = (actual, result.get("code"))
                if last == (status, code):
                    return session, result.get("data")
            except (OSError, ValueError, http.client.HTTPException) as error:
                last = type(error).__name__
            if time.monotonic() >= end:
                self.fail(f"{method} {path}: 预期 {(status, code)}，实际 {last}")
            time.sleep(2)

    def test_login_and_private_app_use_real_services_through_one_gateway(self):
        self.project = "xy-smoke-" + secrets.token_hex(6)
        self.file = Path(__file__).with_name("compose.yaml")
        self.covers = os.getenv("XY_TEST_COMPOSE_COVERS") == "1"
        self.env = dict(os.environ, XY_COMPOSE_DB_PASSWORD=secrets.token_hex(24),
                        XY_COMPOSE_DB_ROOT_PASSWORD=secrets.token_hex(24), XY_COMPOSE_PORT="0")
        if self.covers:
            self.env["XY_COMPOSE_PUBLIC_URL"] = "https://works.invalid"
        self.addCleanup(self.compose, "down", "--volumes", "--remove-orphans")
        try:
            self.compose("config", "--quiet")
            self.compose("up", "-d", "--build")
            self.port = self.gateway_port()
            _, health = self.expect("/health", 200, 0, wait=240)
            self.assertEqual((health["status"], health["database"]), ("UP", "xy"))
            self.expect("/users/me", 401, 40100, wait=120)
            password = secrets.token_urlsafe(18)
            account = {"userAccount": "composedemo", "userPassword": password}
            _, user_id = self.expect("/users/register", 200, 0, method="POST",
                                     body={**account, "checkPassword": password})
            session, _ = self.expect("/users/login", 200, 0, method="POST", body=account)
            self.assertTrue(session and session.startswith("SESSION="), "登录应返回会话")
            cookie = session.split(";", 1)[0]
            self.expect("/apps/mine", 200, 0, cookie=cookie, wait=120)
            for path in ("/users", "/apps"):
                self.expect(path, 403, 40300, cookie=cookie)
            _, app_id = self.expect("/apps", 200, 0, method="POST", cookie=cookie,
                                    body={"initPrompt": "制作一个读书清单", "codeGenType": "html"})
            path = "/apps/" + app_id
            _, app = self.expect(path, 200, 0, cookie=cookie)
            self.assertEqual((app["userId"], app["codeGenType"]), (user_id, "html"))
            if self.covers:
                from cover_acceptance import verify_published_cover
                verify_published_cover(self, int(user_id), int(app_id), cookie)
            # 如果应用仍在本机查身份，这里会错误地继续返回 200。
            self.compose("stop", "user")
            self.expect(path, 503, 50300, cookie=cookie, wait=90)
            self.expect("/health", 200, 0)
            self.compose("start", "user")
            self.expect(path, 200, 0, cookie=cookie, wait=180)
            self.expect("/users/me", 200, 0, cookie=cookie, wait=120)
            # 用应用进程的实际用户写作品卷；标记只在本次隔离项目内，不调用模型。
            marker = "/var/lib/xy/compose-volume-check"
            self.compose("exec", "-T", "app", "sh", "-ec", 'echo "$1" > "$2"', "sh", app_id, marker)
            self.compose("down")
            self.compose("up", "-d", "--no-build")
            self.port = self.gateway_port()
            _, saved = self.expect(path, 200, 0, cookie=cookie, wait=240)
            self.assertEqual((saved["id"], saved["userId"]), (app_id, user_id))
            self.assertEqual(self.compose("exec", "-T", "app", "cat", marker, output=True).strip(), app_id)
            self.expect("/users/me", 200, 0, cookie=cookie, wait=120)
            self.expect("/users/logout", 200, 0, method="POST", cookie=cookie)
            for private in ("/users/me", path):
                self.expect(private, 401, 40100, cookie=cookie)
        except Exception:
            self.compose("ps", "--all")
            # 此测试只使用随机密码的空库，仍不输出应用原始日志、配置或 Cookie。
            self.compose("logs", "--tail=8", "http-registry")
            raise


if __name__ == "__main__":
    unittest.main()
