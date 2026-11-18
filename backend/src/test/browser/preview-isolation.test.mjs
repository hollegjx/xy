import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { chromium } from '../../../../frontend/node_modules/@playwright/test/index.mjs'

// 先运行 PreviewControllerTest 生成实际响应；本测试拦截全部请求，不启动或访问项目服务。
const fixtures = {
  html: JSON.parse(await readFile(new URL('../../../target/preview-browser-fixture.json', import.meta.url), 'utf8')),
  multi_file: JSON.parse(await readFile(new URL('../../../target/preview-multi-file-browser-fixture.json', import.meta.url), 'utf8')),
  vue_project: JSON.parse(await readFile(new URL('../../../target/preview-vue-browser-fixture.json', import.meta.url), 'utf8')),
}
const origin = 'https://xy-preview.test'
const previewPath = '/api/apps/55/preview'

for (const [mode, fixture] of Object.entries(fixtures)) {
  for (const embedded of [false, true]) {
    test(`${mode}：${embedded ? 'iframe 预览隔离父页面与凭证' : '直接打开预览也强制执行沙箱'}`, async () => {
      const browser = await chromium.launch()
      try {
        const context = await browser.newContext()
        await context.addCookies([{ name: 'session_test', value: 'test-secret', url: origin, httpOnly: false }])
        const unexpected = []
        await context.route('**/*', async route => {
          const url = new URL(route.request().url())
          if (url.origin === origin && url.pathname === previewPath) {
            assert.match((await route.request().allHeaders()).cookie ?? '', /session_test=test-secret/)
            await route.fulfill({ status: 200, ...fixture })
          } else if (url.origin === origin && url.pathname === '/') {
            await route.fulfill({ contentType: 'text/html', body: `<!DOCTYPE html><html><body>
              <div id="platform-secret">平台私有内容</div>
              <script>localStorage.setItem('platform-secret', 'test-secret')</script>
              <iframe src="${previewPath}"></iframe></body></html>` })
          } else {
            unexpected.push(route.request().url())
            await route.fulfill({ json: { secret: 'test-secret' } })
          }
        })
        const page = await context.newPage()
        await page.goto(origin + (embedded ? '/' : previewPath))
        const frame = embedded ? page.frames().find(item => item.url() === origin + previewPath) : page.mainFrame()
        assert.ok(frame)
        await frame.waitForFunction(() => document.body.dataset.ready === 'true')
        const checks = await frame.evaluate(() => JSON.parse(document.body.dataset.checks))
        for (const name of ['cookie', 'storage', 'api', 'external', 'image', 'script', 'style']) assert.equal(checks[name], 'blocked')
        if (embedded) assert.equal(checks.parent, 'blocked')
        assert.equal(await frame.evaluate(() => document.baseURI), origin + previewPath)
        assert.equal(await frame.locator('body').evaluate(element => getComputedStyle(element).color), 'rgb(29, 29, 31)')
        await frame.locator('#counter').click()
        assert.equal(await frame.locator('#counter').textContent(), '1')
        if (mode === 'multi_file') assert.equal(await frame.evaluate(() => window.closingScriptTag), '</script>')
        assert.deepEqual(unexpected, [])
      } finally {
        await browser.close()
      }
    })
  }
}
