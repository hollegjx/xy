import vue from '@vitejs/plugin-vue'
import { build } from 'vite'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

// 构建配置属于平台；不加载模型工程中的配置或执行安装脚本。
const root = process.argv[2] ?? '/source'
await build({
  root,
  configFile: false,
  publicDir: false,
  base: './',
  cacheDir: '/tmp/xy-vite-cache',
  plugins: [vue()],
  resolve: { alias: {
    '@': resolve(root, 'src'),
    vue: fileURLToPath(import.meta.resolve('vue/dist/vue.runtime.esm-bundler.js')),
    'vue-router': fileURLToPath(import.meta.resolve('vue-router/dist/vue-router.mjs')),
  } },
  build: {
    outDir: process.argv[3] ?? '/output',
    emptyOutDir: true,
    modulePreload: false,
    cssCodeSplit: false,
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
    rollupOptions: { output: { inlineDynamicImports: true, entryFileNames: 'app.js', assetFileNames: '[name][extname]' } },
  },
})
