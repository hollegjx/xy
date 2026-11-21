import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { cp, mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { chromium } from '../../../../frontend/node_modules/@playwright/test/index.mjs'

test('生成工程的多页导航能构建、刷新和后退，单页旧稿不要求路由文件', async () => {
  const repo = fileURLToPath(new URL('../../../../', import.meta.url))
  const modules = path.join(repo, 'frontend/node_modules')
  const template = JSON.parse(await readFile(path.join(repo, 'backend/src/main/resources/templates/vue-project.json'), 'utf8'))
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'xy-vue-router-')))
  let browser
  try {
    const source = path.join(root, 'source'), builder = path.join(root, 'builder'), out = path.join(root, 'output')
    async function put(name, body) {
      const target = path.join(source, name)
      await mkdir(path.dirname(target), { recursive: true })
      await writeFile(target, body)
    }
    for (const [name, body] of Object.entries(template)) await put(name, body)
    await mkdir(builder)
    await cp(path.join(repo, 'backend/vue-builder/build.mjs'), path.join(builder, 'build.mjs'))
    await symlink(modules, path.join(builder, 'node_modules'), 'dir')
    const build = () => execFileSync(process.execPath, [path.join(builder, 'build.mjs'), source, out], { stdio: 'pipe' })
    await put('src/App.vue', '<template><h1>原来的单页</h1></template>')
    build()
    assert.ok((await readFile(path.join(out, 'app.js'), 'utf8')).includes('原来的单页'))

    await put('src/App.vue', '<template><RouterView /></template>')
    await put('src/views/Home.vue', '<template><h1>首页</h1><RouterLink to="/details">查看详情</RouterLink></template>')
    await put('src/views/Details.vue', '<template><h1>详情</h1></template>')
    await put('src/router/index.js', `import { createRouter, createWebHashHistory } from 'vue-router'
      import Home from '@/views/Home.vue'
      import Details from '@/views/Details.vue'
      export default createRouter({history: createWebHashHistory(), routes: [{path:'/',component:Home},{path:'/details',component:Details}]})`)
    build() // 仅 builder 有依赖；源码里没有 node_modules，和容器的两个目录一致。
    const js = await readFile(path.join(out, 'app.js'))
    const preview = (await readFile(path.join(out, 'index.html'), 'utf8'))
      .replace(/src="(?:\.\/)?app\.js"/, () => 'src="data:text/javascript;base64,' + js.toString('base64') + '"')
    // 下载后用工程自己的 vite.config.js 构建，不能仅验证平台构建脚本。
    await symlink(modules, path.join(source, 'node_modules'), 'dir')
    execFileSync(process.execPath, [path.join(modules, 'vite/bin/vite.js'), 'build'], { cwd: source, stdio: 'pipe' })
    browser = await chromium.launch()
    const page = await browser.newPage()
    const origin = 'https://xy-work.test'
    await page.route('**/*', async route => {
      const url = new URL(route.request().url())
      if (url.origin !== origin) return route.abort()
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<iframe sandbox="allow-scripts" src="/preview"></iframe>' })
      if (url.pathname === '/preview') return route.fulfill({ contentType: 'text/html', body: preview,
        headers: { 'Content-Security-Policy': "sandbox allow-scripts; default-src 'none'; script-src data:; style-src 'unsafe-inline'" } })
      if (!url.pathname.startsWith('/site/')) return route.abort()
      return route.fulfill({ path: path.join(source, 'dist', url.pathname.slice('/site/'.length)) })
    })
    await page.goto(origin + '/site/index.html')
    await page.getByRole('link', { name: '查看详情' }).click()
    assert.ok(page.url().endsWith('#/details'))
    await page.reload()
    await page.getByRole('heading', { name: '详情' }).waitFor()
    await page.goBack()
    await page.getByRole('heading', { name: '首页' }).waitFor()
    await page.goto(origin)
    const frame = page.frameLocator('iframe')
    await frame.getByRole('link', { name: '查看详情' }).click()
    await frame.getByRole('heading', { name: '详情' }).waitFor()
  } finally {
    await browser?.close()
    await rm(root, { recursive: true, force: true })
  }
})
