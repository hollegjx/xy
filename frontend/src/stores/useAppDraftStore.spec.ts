import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useAppDraftStore } from '@/stores/useAppDraftStore'

beforeEach(() => setActivePinia(createPinia()))

describe('创建后的首次生成', () => {
  it('创建标记只能消费一次，刷新后不恢复', () => {
    const store = useAppDraftStore()
    store.prepareFirstGeneration('55')
    expect(store.consumeFirstGeneration('55')).toBe(true)
    expect(store.consumeFirstGeneration('55')).toBe(false)
    store.prepareFirstGeneration('56')
    setActivePinia(createPinia())
    expect(useAppDraftStore().consumeFirstGeneration('56')).toBe(false)
  })

  it('进入其他应用时丢弃旧标记，不误发生成', () => {
    const store = useAppDraftStore()
    store.prepareFirstGeneration('55')
    expect(store.consumeFirstGeneration('56')).toBe(false)
    expect(store.consumeFirstGeneration('55')).toBe(false)
  })
})
