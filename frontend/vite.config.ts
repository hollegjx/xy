import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    // E2E 只使用拦截数据；测试退出或拦截遗漏时也不能访问真实后端。
    proxy:
      mode === 'test'
        ? undefined
        : {
            '/api': {
              target: mode === 'gateway' ? 'http://127.0.0.1:8080' : 'http://127.0.0.1:8101',
              changeOrigin: true,
            },
          },
  },
}))
