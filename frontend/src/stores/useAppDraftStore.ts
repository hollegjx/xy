import { reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AppAddRequest } from '@/types/api'

interface FirstGeneration {
  appId: string
  qualityCheck: boolean
}

export const useAppDraftStore = defineStore('appDraft', () => {
  // 创建前可能跨越登录、注册页面；草稿只留在本标签页内存中。
  const draft = reactive<AppAddRequest & { qualityCheck: boolean }>({
    initPrompt: '',
    codeGenType: 'multi_file',
    qualityCheck: false,
  })
  const pending = ref<FirstGeneration | null>(null)

  function prepareFirstGeneration(appId: string): void {
    pending.value = { appId, qualityCheck: draft.qualityCheck }
    draft.initPrompt = ''
    draft.codeGenType = 'multi_file'
    draft.qualityCheck = false
  }

  function consumeFirstGeneration(appId: string): FirstGeneration | null {
    const first = pending.value
    pending.value = null
    return first?.appId === appId ? first : null
  }

  return { draft, pending, prepareFirstGeneration, consumeFirstGeneration }
})
