const { readFileSync } = require('node:fs')
const { chromium } = require('playwright-core')

async function render() {
  const html = readFileSync(0, 'utf8')
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH })
  try {
    const context = await browser.newContext({
      viewport: { width: 960, height: 540 }, serviceWorkers: 'block', reducedMotion: 'reduce',
    })
    const page = await context.newPage()
    const url = 'https://cover.invalid/'
    await context.route('**/*', route => route.request().url() === url && route.request().isNavigationRequest()
      ? route.fulfill({ contentType: 'text/html; charset=utf-8', body: html, headers: {
          'Content-Security-Policy': "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline' data:; style-src 'unsafe-inline' data:; img-src data: blob:; font-src data:; media-src data: blob:; base-uri 'none'; form-action 'none'",
        } })
      : route.abort())
    await page.goto(url, { waitUntil: 'load', timeout: 6000 })
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    process.stdout.write(await page.screenshot({ type: 'jpeg', quality: 80, animations: 'disabled', timeout: 4000 }))
  } finally {
    await browser.close()
  }
}

render().catch(() => { process.exitCode = 1 })
