"""在 test_compose 的随机空卷中准备成功稿；访客使用真实发布网址和封面接口。"""
import base64
from dataclasses import dataclass
import http.client
import json
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.parse import urlsplit


@dataclass
class Publication:
    url: str
    version: int
    html: str


def fixture_sql(test, statement):
    return test.compose("exec", "-T", "mysql", "sh", "-ec",
                        'MYSQL_PWD="$(cat /run/secrets/db-password)" exec mysql -h127.0.0.1 -uxy_service -BN xy -e "$1"',
                        "sh", statement, output=True).strip()


def publish_fixture(test, user_id, app_id, cookie, title):
    version = int(fixture_sql(test, f"""
        INSERT INTO chat_history(appId,userId,messageRole,content,status)
        VALUES ({app_id},{user_id},'assistant','publication fixture','completed');
        UPDATE app SET generatedVersion=LAST_INSERT_ID() WHERE id={app_id};
        SELECT LAST_INSERT_ID()"""))
    page = ("<!doctype html><html style='height:100%;background:linear-gradient(to right,#2050c0 50%,#e8a040 50%)'>"
            f"<head><title>{title}</title></head><body><h1>{title}</h1><output id='xy-state'>loading</output>"
            f"<script>document.getElementById('xy-state').textContent = 'rendered:' + {version};</script></body></html>")
    folder = f"/var/lib/xy/generated/{user_id}/{app_id}/{version}"
    test.compose("exec", "-T", "app", "sh", "-ec",
                 'mkdir -p "$1" && printf %s "$2" > "$1/index.html"', "sh", folder, page)
    _, data = test.expect(f"/apps/{app_id}/deploy", 200, 0, method="POST", cookie=cookie)
    test.assertEqual(data["publishedVersion"], str(version))
    return Publication(data["deployUrl"], version, page)


def read_site(test, url, suffix=None, host=None):
    # 使用接口真正返回的主机与端口，不拼回网关，也不把测试会话发送给作品。
    parsed = urlsplit(url)
    test.assertEqual((parsed.scheme, parsed.hostname, parsed.port), ("http", "127.0.0.2", test.site_port))
    test.assertRegex(parsed.path, r"^/[a-f0-9]{32}/$")
    conn = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=8)
    try:
        conn.request("GET", parsed.path if suffix is None else suffix, headers={"Host": host} if host else {})
        response = conn.getresponse()
        return response.status, response.headers, response.read()
    finally:
        conn.close()


def verify_site(test, publication):
    status, headers, html = read_site(test, publication.url)
    test.assertEqual((status, html), (200, publication.html.encode()))
    test.assertEqual(headers.get_content_type(), "text/html")
    test.assertEqual(headers.get("Cache-Control"), "no-store")
    directives = {parts[0]: parts[1:] for token in headers["Content-Security-Policy"].split(";")
                  if (parts := token.split())}
    test.assertEqual(directives["sandbox"], ["allow-scripts"])
    test.assertEqual(directives["connect-src"], ["'none'"])
    test.assertIsNone(headers.get("Set-Cookie"))
    test.assertEqual(read_site(test, publication.url, suffix="/api/health")[0], 404)
    test.assertEqual(read_site(test, publication.url, host="127.0.0.1")[0], 404)


def verify_site_in_browser(test, publication, chrome):
    with tempfile.TemporaryDirectory(prefix="xy-guest-") as profile:
        result = subprocess.run([chrome, "--headless", "--disable-gpu", "--no-first-run", "--no-proxy-server",
                                 "--disable-background-networking", "--user-data-dir=" + profile,
                                 "--dump-dom", publication.url], capture_output=True, text=True, timeout=35)
    test.assertEqual(result.returncode, 0, "访客浏览器未能打开分享链接")
    # 原始 HTML 中只有 loading；此处的版本号由作品内的脚本写进可见 DOM。
    test.assertIn(f'<output id="xy-state">rendered:{publication.version}</output>', result.stdout)


def verify_manual_site_example(test, publication):
    backend = Path(__file__).resolve().parents[2] / "backend"
    example = (backend / "nginx.example.conf").read_text().replace(
        "/ABSOLUTE/PATH/TO/xy/backend/published", "/srv/works").replace(
        "/ABSOLUTE/PATH/TO/xy/backend/nginx-published.inc", "/etc/nginx/xy-published.inc")
    # 在一次性容器内使用示例原本的 127.0.0.1:8102；不占宿主机端口。
    test.compose("run", "--rm", "--no-deps", "-T", "--entrypoint", "sh", "site", "-ec", r"""
        printf '%s\n' "$1" > /tmp/xy-example.conf
        sed 's|/etc/nginx/conf.d/\*.conf|/tmp/xy-example.conf|' /etc/nginx/nginx.conf > /tmp/xy-nginx.conf
        nginx -c /tmp/xy-nginx.conf
        url="http://127.0.0.1:8102$2"
        test "$(curl -sS -o /dev/null -w '%{http_code}' "$url")" = 200
        test "$(curl -sS -H 'Host: localhost:8102' -o /dev/null -w '%{http_code}' "$url")" = 404
        """, "sh", example, urlsplit(publication.url).path)


def verify_published_cover(test, user_id, app_id, cookie, publication):
    fixture_sql(test, f"UPDATE user SET userRole='admin' WHERE id={user_id}")
    test.compose("exec", "-T", "renderer", "sh", "-ec",
                 'test "$(id -u)" != 0 && test ! -e /var/run/docker.sock && test ! -d /run/secrets'
                 ' && test "$(ls /sys/class/net)" = lo')

    path = f"/apps/{app_id}"
    test.expect("/admin" + path + "/featured", 200, 0, method="PATCH", cookie=cookie, body={"featured": True})

    deadline = time.monotonic() + 60
    while True:
        conn = http.client.HTTPConnection("127.0.0.1", test.port, timeout=8)
        try:
            conn.request("GET", "/api" + path + f"/covers/{publication.version}")
            response = conn.getresponse()
            image = response.read()
            if response.status == 200:
                test.assertEqual(response.getheader("Content-Type"), "image/jpeg")
                break
            test.assertEqual(response.status, 404)
        finally:
            conn.close()
        if time.monotonic() >= deadline:
            test.fail("作品已发布，仍未收到当前版本的公开封面")
        time.sleep(1)

    # 解码从网关匿名下载的字节，检查页面左右两块颜色，不以扩展名或非空文件判成功。
    inspect = """
    (async () => {
      const {chromium} = require('playwright-core')
      const browser = await chromium.launch({executablePath: process.env.CHROMIUM_PATH})
      try {
        const page = await browser.newPage()
        console.log(JSON.stringify(await page.evaluate(async data => {
          const img = new Image(); img.src = 'data:image/jpeg;base64,' + data; await img.decode()
          const canvas = document.createElement('canvas')
          canvas.width = img.width; canvas.height = img.height
          const ctx = canvas.getContext('2d'); ctx.drawImage(img, 0, 0)
          const pixel = x => [...ctx.getImageData(x, 270, 1, 1).data].slice(0, 3)
          return [img.width, img.height, ...pixel(240), ...pixel(720)]
        }, process.argv[1])))
      } finally { await browser.close() }
    })().catch(() => {process.exitCode = 1})
    """
    pixels = json.loads(test.compose("exec", "-T", "renderer", "node", "-e", inspect,
                                     base64.b64encode(image).decode("ascii"), output=True))
    test.assertEqual(pixels[:2], [960, 540])
    test.assertEqual(len(pixels), 8)
    for actual, expected in zip(pixels[2:], [32, 80, 192, 232, 160, 64]):
        test.assertAlmostEqual(actual, expected, delta=5)
