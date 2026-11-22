const assert = require('node:assert/strict')
const { randomBytes } = require('node:crypto')
const { chromium, expect, request } = require('../../frontend/node_modules/@playwright/test')

async function run() {
  const grafana = 'http://127.0.0.1:' + process.env.XY_GRAFANA_PORT
  const xy = await request.newContext({ baseURL: 'http://127.0.0.1:' + process.env.XY_COMPOSE_PORT })
  const browser = await chromium.launch({ channel: 'chrome' })
  const guest = await browser.newContext({ baseURL: grafana, viewport: { width: 1440, height: 1000 } })
  try {
    const xyRequest = async (path, data, status = 200, code = 0) => {
      const result = await xy.post('/api' + path, { data })
      assert.equal(result.status(), status, path)
      const json = await result.json()
      assert.equal(json.code, code, path)
      return json.data
    }
    const readyStatus = async (path) => {
      try { return (await xy.get(path, { timeout: 5000 })).status() } catch { return 0 }
    }
    await expect.poll(() => readyStatus('/api/users/me'), { timeout: 240000 }).toBe(401)
    const password = randomBytes(18).toString('hex')
    const account = { userAccount: 'monitor-demo', userPassword: password }
    await xyRequest('/users/register', { ...account, checkPassword: password })
    await xyRequest('/users/login', account)
    const prompt = '制作一个简洁的读书会介绍页'
    await expect.poll(() => readyStatus('/api/apps/mine'), { timeout: 120000 }).toBe(200)
    const id = await xyRequest('/apps', { initPrompt: prompt, codeGenType: 'html' })
    const events = await xy.post('/api/apps/' + id + '/generations', { data: { prompt }, timeout: 75000 })
    assert.equal(events.status(), 200)
    const stream = await events.text()
    assert.match(stream, /^event:completed\s*$/m)
    assert.doesNotMatch(stream, /^event:failed\s*$/m)
    await xyRequest('/apps', { initPrompt: prompt, codeGenType: 'auto' }, 503, 50300)

    // 另一个 Cookie 容器模拟运维人员，实际读取 Grafana，看板数据不经测试代码写入。
    await expect.poll(async () => {
      try { return (await guest.request.get('/api/health')).status() } catch { return 0 }
    }, { timeout: 90000 }).toBe(200)
    const login = await guest.request.post('/login', {
      data: { user: 'admin', password: process.env.XY_MONITORING_ADMIN_PASSWORD },
    })
    assert.equal(login.status(), 200, 'Grafana 登录')
    const page = await guest.newPage()
    await page.goto('/d/xy-ai-observability/xy')
    const panels = { '抓取状态': '1', '累计模型调用': '2', '累计失败调用': '1', '累计已报告 Token': '12' }
    for (const [title, value] of Object.entries(panels)) {
      const content = page.getByRole('region', { name: title, exact: true }).getByTestId('data-testid panel content')
      await expect(content.getByText(value, { exact: true })).toBeVisible({ timeout: 90000 })
    }
    console.log('Grafana 实际看板：抓取正常、模型调用 2、失败 1、Token 12')

    // 打开按应用分组的看板，读取 Grafana 真正交给图表的数据；滚动窗口需要一轮历史。
    const tokenPanel = page.getByRole('region', { name: '输入、输出与总 Token', exact: true })
    await Promise.all([
      page.waitForResponse(async (response) => {
        if (!response.ok() || !response.request().postData()?.includes('xy_ai_tokens_total')) return false
        const body = await response.json().catch(() => null)
        const types = new Set()
        for (const result of Object.values(body?.results || {})) {
          for (const frame of result.frames || []) {
            frame.schema.fields.forEach((field, index) => {
              if (field.labels?.app_id === String(id) && frame.data.values[index]?.some(Number.isFinite)) {
                types.add(field.labels.type)
              }
            })
          }
        }
        return ['input', 'output', 'total'].every((type) => types.has(type))
      }, { timeout: 150000 }),
      page.goto('/d/xy-ai-observability/xy?var-group=app_id&var-window=1m')
        .then(() => tokenPanel.scrollIntoViewIfNeeded()),
    ])
    await expect(tokenPanel.getByText(/\binput\b/).first()).toBeVisible()
    console.log('Grafana 按应用显示本次生成的输入、输出与总 Token')
  } finally {
    await guest.close()
    await browser.close()
    await xy.dispose()
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1 })
