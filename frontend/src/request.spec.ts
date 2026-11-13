import { AxiosError } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import request, { httpClient, requestStream } from '@/request'
import { generateApp } from '@/service/api/generation'
import type { GenerationEventVO } from '@/types/api'

const originalAdapter = httpClient.defaults.adapter
afterEach(() => {
  httpClient.defaults.adapter = originalAdapter
  vi.unstubAllGlobals()
})

describe('生成请求', () => {
  function mockStream(content: string): ReturnType<typeof vi.fn> {
    const bytes = new TextEncoder().encode(content)
    const fetchMock = vi.fn(
      async () =>
        new Response(
          new ReadableStream({
            start(controller) {
              for (const byte of bytes) controller.enqueue(new Uint8Array([byte]))
              controller.close()
            },
          }),
          { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it('携带凭证提交需求，正确拼接跨分片的中文与 CRLF 事件', async () => {
    const fetchMock = mockStream(
      ':heartbeat\r\nevent:started\r\ndata:{"generationId":"9007199254740993","status":"running"}\r\n\r\n' +
        'event:delta\r\ndata:{"generationId":"9007199254740993","status":"running",\r\ndata:"text":"星云🌟"}\r\n\r\n' +
        'event:completed\ndata:{"generationId":"9007199254740993","status":"completed"}\n\n',
    )
    const events: GenerationEventVO[] = []
    await generateApp('55', '摄影网站', new AbortController().signal, (event) => events.push(event))
    expect(events.map((event) => event.status)).toEqual(['running', 'running', 'completed'])
    expect(events[1]?.text).toBe('星云🌟')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/apps/55/generations',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        body: '{"prompt":"摄影网站"}',
      }),
    )
  })

  it('未收到完整终态时报告中断，不自动重发生成', async () => {
    const fetchMock = mockStream(
      'event:started\ndata:{"generationId":"101","status":"running"}\n\n' +
        'event:completed\ndata:{"generationId":"101","status":"completed"}',
    )
    await expect(generateApp('55', '网站', new AbortController().signal, () => {})).rejects.toThrow(
      '未收到最终结果',
    )
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it.each(['failed', 'cancelled'] as const)('HTTP 200 中的 %s 终态保持原状态', async (status) => {
    mockStream(
      `event:${status}\ndata:${JSON.stringify({ generationId: '101', status, message: '生成已结束' })}\n\n`,
    )
    const onEvent = vi.fn()
    await generateApp('55', '网站', new AbortController().signal, onEvent)
    expect(onEvent).toHaveBeenCalledWith({ generationId: '101', status, message: '生成已结束' })
  })

  it('保留生成开始前的登录失效错误', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        Response.json({ code: 40100, data: null, message: '请先登录' }, { status: 401 }),
      ),
    )
    await expect(
      requestStream('/api/apps/55/generations', {}, new AbortController().signal),
    ).rejects.toMatchObject({ code: 40100, message: '请先登录' })
  })
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
