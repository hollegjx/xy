"""仅供 test_compose 的随机空卷：从给定的成功稿实际发布，再从公开接口解码 JPEG。"""
import base64
import http.client
import json
import time


def verify_published_cover(test, user_id, app_id, cookie):
    def fixture_sql(statement):
        return test.compose("exec", "-T", "mysql", "sh", "-ec",
                            'MYSQL_PWD="$(cat /run/secrets/db-password)" exec mysql -h127.0.0.1 -uxy_service -BN xy -e "$1"',
                            "sh", statement, output=True).strip()

    # 已成功的源码和角色只在本用例的临时库构造，不花模型额度，不预写封面。
    version = int(fixture_sql(f"""
        INSERT INTO chat_history(appId,userId,messageRole,content,status)
        VALUES ({app_id},{user_id},'assistant','cover fixture','completed');
        UPDATE app SET generatedVersion=LAST_INSERT_ID() WHERE id={app_id};
        UPDATE user SET userRole='admin' WHERE id={user_id};
        SELECT LAST_INSERT_ID()"""))
    page = "<html style='height:100%;background:linear-gradient(to right,#2050c0 50%,#e8a040 50%)'></html>"
    folder = f"/var/lib/xy/generated/{user_id}/{app_id}/{version}"
    test.compose("exec", "-T", "app", "sh", "-ec",
                 'mkdir -p "$1" && printf %s "$2" > "$1/index.html"', "sh", folder, page)
    test.compose("exec", "-T", "renderer", "sh", "-ec",
                 'test "$(id -u)" != 0 && test ! -e /var/run/docker.sock && test ! -d /run/secrets'
                 ' && test "$(ls /sys/class/net)" = lo')

    path = f"/apps/{app_id}"
    _, publication = test.expect(path + "/deploy", 200, 0, method="POST", cookie=cookie)
    test.assertEqual(publication["publishedVersion"], str(version))
    test.expect("/admin" + path + "/featured", 200, 0, method="PATCH", cookie=cookie, body={"featured": True})

    deadline = time.monotonic() + 60
    while True:
        conn = http.client.HTTPConnection("127.0.0.1", test.port, timeout=8)
        try:
            conn.request("GET", "/api" + path + f"/covers/{version}")
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
