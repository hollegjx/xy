import { reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AppAddRequest } from '@/types/api'

export const useAppDraftStore = defineStore('appDraft', () => {
  // 创建前可能跨越登录、注册页面；草稿只留在本标签页内存中。
  const draft = reactive<AppAddRequest>({ initPrompt: '', codeGenType: 'multi_file' })
  const pendingAppId = ref<string | null>(null)

  function clear(): void {
    draft.initPrompt = ''
    draft.codeGenType = 'multi_file'
  }

  function prepareFirstGeneration(appId: string): void {
    // 只把本次创建带入工作台；刷新、回退或重新进入都不能重复触发生成。
    pendingAppId.value = appId
  }

  function consumeFirstGeneration(appId: string): boolean {
    const shouldGenerate = pendingAppId.value === appId
    pendingAppId.value = null
    return shouldGenerate
  }

  return { draft, pendingAppId, clear, prepareFirstGeneration, consumeFirstGeneration }
})
