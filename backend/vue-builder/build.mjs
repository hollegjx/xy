import vue from '@vitejs/plugin-vue'
import { build } from 'vite'
import { fileURLToPath } from 'node:url'

// 构建配置属于平台；不加载模型工程中的配置或执行安装脚本。
await build({
  root: process.argv[2] ?? '/source',
  configFile: false,
  publicDir: false,
  base: './',
  cacheDir: '/tmp/xy-vite-cache',
  plugins: [vue()],
  resolve: { alias: { vue: fileURLToPath(import.meta.resolve('vue/dist/vue.runtime.esm-bundler.js')) } },
  build: {
    outDir: process.argv[3] ?? '/output',
    emptyOutDir: true,
    modulePreload: false,
    cssCodeSplit: false,
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
    rollupOptions: { output: { inlineDynamicImports: true, entryFileNames: 'app.js', assetFileNames: '[name][extname]' } },
  },
})
