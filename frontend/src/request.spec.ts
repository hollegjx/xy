import { AxiosError } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import request, { httpClient } from '@/request'

const originalAdapter = httpClient.defaults.adapter
afterEach(() => {
  httpClient.defaults.adapter = originalAdapter
})

describe('请求客户端', () => {
  it('返回成功响应中的业务数据', async () => {
    httpClient.defaults.adapter = async (config) => ({
      data: { code: 0, data: { database: 'xy_contract' }, message: 'ok' },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    })
    await expect(request('/api/health')).resolves.toEqual({ database: 'xy_contract' })
  })

  it('保留 HTTP 503 中的业务错误说明', async () => {
    httpClient.defaults.adapter = async (config) => {
      throw new AxiosError('unavailable', AxiosError.ERR_BAD_RESPONSE, config, undefined, {
        data: { code: 50000, data: null, message: '数据库连接失败，请检查本地配置' },
        status: 503,
        statusText: 'Service Unavailable',
        headers: {},
        config,
      })
    }
    await expect(request('/api/health')).rejects.toMatchObject({
      code: 50000,
      message: '数据库连接失败，请检查本地配置',
    })
  })

  it('将 HTTP 200 中的业务失败作为错误处理', async () => {
    httpClient.defaults.adapter = async (config) => ({
      data: { code: 50000, data: null, message: '检查失败' },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    })
    await expect(request('/api/health')).rejects.toMatchObject({ code: 50000, message: '检查失败' })
  })

  it('网络不可达时给出可读说明', async () => {
    httpClient.defaults.adapter = async () => {
      throw new AxiosError('Network Error', AxiosError.ERR_NETWORK)
    }
    await expect(request('/api/health')).rejects.toThrow('无法连接后端服务，请确认服务已启动')
  })
})
