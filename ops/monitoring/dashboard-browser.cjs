const assert = require('node:assert/strict')
const { randomBytes } = require('node:crypto')
const { chromium, expect, request } = require('../../frontend/node_modules/@playwright/test')
const dashboard = require('./xy-ai-dashboard.json')

async function queryPrometheus(query) {
  const url = new URL('http://127.0.0.1:' + process.env.XY_PROMETHEUS_PORT + '/api/v1/query')
  url.searchParams.set('query', query)
  const response = await fetch(url, { signal: AbortSignal.timeout(5000) })
  assert.equal(response.status, 200, 'Prometheus 即时查询')
  return (await response.json()).data.result
}

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
    // 先留出可靠的窗口起点，再产生用量；否则首个样本可能已经包含本次生成。
    const up = 'up{job="xy-backend"}'
    await expect.poll(async () => (await queryPrometheus(`(${up} == 1) and (${up} offset 1m == 1)`)).length,
      { timeout: 150000, intervals: [2000, 5000] }).toBe(1)
    const events = await xy.post('/api/apps/' + id + '/generations', { data: { prompt }, timeout: 75000 })
    assert.equal(events.status(), 200)
    const stream = await events.text()
    assert.match(stream, /^event:completed\s*$/m)
    assert.doesNotMatch(stream, /^event:failed\s*$/m)
    await xyRequest('/apps', { initPrompt: prompt, codeGenType: 'auto' }, 503, 50300)
    const tokenTitle = '输入、输出与总 Token'
    const query = dashboard.panels.find((panel) => panel.title === tokenTitle).targets[0].expr
      .replaceAll('${group:raw}', 'app_id').replaceAll('${window:raw}', '1m')
    await expect.poll(async () => Object.fromEntries((await queryPrometheus(query))
      .filter((sample) => sample.metric.app_id === String(id))
      .map((sample) => [sample.metric.type, Number(sample.value[1])])),
      { timeout: 45000, intervals: [1000, 2000] }).toEqual({ input: 9, output: 3, total: 12 })
    console.log('Prometheus 看板查询：当前应用输入 9、输出 3、总量 12')

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

    // 直接读取访客可见的每条曲线图例，不依赖 Grafana 内部网络响应的编码。
    await page.goto('/d/xy-ai-observability/xy?var-group=app_id&var-window=1m&from=now-5m&to=now')
    const tokenPanel = page.getByRole('region', { name: tokenTitle, exact: true })
    await tokenPanel.scrollIntoViewIfNeeded()
    try {
      for (const kind of ['input', 'output', 'total']) {
        const label = new RegExp(`(?=.*app_id="${id}")(?=.*type="${kind}")`)
        await expect(tokenPanel.getByText(label).first()).toBeVisible({ timeout: 45000 })
      }
    } catch (error) {
      console.error('Token 面板当前显示：', (await tokenPanel.innerText()).slice(0, 500))
      throw error
    }
    console.log('Grafana 按应用显示本次生成的输入、输出与总 Token')
  } finally {
    await guest.close()
    await browser.close()
    await xy.dispose()
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1 })
