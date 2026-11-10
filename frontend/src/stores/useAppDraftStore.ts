import { reactive } from 'vue'
import { defineStore } from 'pinia'
import type { AppAddRequest } from '@/types/api'

export const useAppDraftStore = defineStore('appDraft', () => {
  // 创建前可能跨越登录、注册页面；草稿只留在本标签页内存中。
  const draft = reactive<AppAddRequest>({ initPrompt: '', codeGenType: 'multi_file' })

  function clear(): void {
    draft.initPrompt = ''
    draft.codeGenType = 'multi_file'
  }

  return { draft, clear }
})
