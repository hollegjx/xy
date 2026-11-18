import type { CodeGenType } from '@/types/api'

export const generationModes = {
  multi_file: { label: '多文件', hint: '页面、样式和交互分开组织，默认使用此模式。' },
  html: { label: '单 HTML', hint: '页面、样式和交互放在同一个文件中。' },
  vue_project: { label: 'Vue 工程', hint: '生成可下载的 Vue 项目，构建成功后可在线预览。' },
} satisfies Record<CodeGenType, { label: string; hint: string }>
