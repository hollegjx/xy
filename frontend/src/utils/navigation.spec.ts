import { describe, expect, it } from 'vitest'
import { safeRedirect } from '@/utils/navigation'

describe('登录回跳', () => {
  it('保留站内路径、查询参数和锚点', () => {
    expect(safeRedirect('/?from=login#status')).toBe('/?from=login#status')
  })

  it('缺失或多个回跳地址时返回首页', () => {
    expect(safeRedirect(undefined)).toBe('/')
    expect(safeRedirect(['/', '/apps'])).toBe('/')
  })

  it('拒绝外站及含反斜杠的地址', () => {
    expect(safeRedirect('https://outside.example/')).toBe('/')
    expect(safeRedirect('//outside.example/')).toBe('/')
    expect(safeRedirect('/\\outside.example/')).toBe('/')
    expect(safeRedirect('/\n/outside.example/')).toBe('/')
  })

  it('不回到登录或注册页造成循环', () => {
    expect(safeRedirect('/user/login?redirect=/')).toBe('/')
    expect(safeRedirect('/USER/REGISTER/')).toBe('/')
    expect(safeRedirect('/example/../user/login')).toBe('/')
  })
})
