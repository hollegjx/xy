import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useAppDraftStore } from '@/stores/useAppDraftStore'

beforeEach(() => setActivePinia(createPinia()))

describe('创建后的首次生成', () => {
  it('只把本次选择交给新应用一次，成功创建后重置草稿，刷新不恢复', () => {
    const store = useAppDraftStore()
    store.draft.qualityCheck = true
    store.prepareFirstGeneration('55')
    expect(store.draft.qualityCheck).toBe(false)
    expect(store.consumeFirstGeneration('55')).toEqual({ appId: '55', qualityCheck: true })
    expect(store.consumeFirstGeneration('55')).toBeNull()
    store.prepareFirstGeneration('56')
    setActivePinia(createPinia())
    expect(useAppDraftStore().consumeFirstGeneration('56')).toBeNull()
  })

  it('进入其他应用时丢弃旧标记，不误发生成', () => {
    const store = useAppDraftStore()
    store.prepareFirstGeneration('55')
    expect(store.consumeFirstGeneration('56')).toBeNull()
    expect(store.consumeFirstGeneration('55')).toBeNull()
  })
})
