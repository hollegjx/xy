import { AxiosError } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import request, { httpClient, requestHtml, requestStream } from '@/request'
import { getAppSource, hasAppPreview } from '@/service/api/app'
import { getAppMessages } from '@/service/api/chat'
import { generateApp } from '@/service/api/generation'
import type { GenerationEventVO } from '@/types/api'

const originalAdapter = httpClient.defaults.adapter
afterEach(() => {
  httpClient.defaults.adapter = originalAdapter
  vi.unstubAllGlobals()
})

describe('源码下载', () => {
  it('携带凭证请求字符串应用 ID，并完整保留下载字节', async () => {
    const bytes = new Uint8Array([80, 75, 3, 4, 0, 255]).buffer
    httpClient.defaults.adapter = async (config) => {
      expect(config.url).toBe('/api/apps/9007199254740993/source')
      expect(config.withCredentials).toBe(true)
      expect(config.responseType).toBe('arraybuffer')
      return {
        data: bytes,
        status: 200,
        statusText: 'OK',
        headers: { 'content-type': 'application/zip' },
        config,
      }
    }
    const source = await getAppSource('9007199254740993')
    expect(source.type).toBe('application/zip')
    expect(await source.arrayBuffer()).toEqual(bytes)
  })

  it.each([
    [401, 40100, '请先登录'],
    [403, 40300, '无权访问该应用'],
    [409, 50001, '应用还没有成功生成的版本'],
  ])('下载 HTTP %s 时解码 JSON 并保留业务错误', async (status, code, message) => {
    httpClient.defaults.adapter = async (config) => {
      throw new AxiosError('download error', AxiosError.ERR_BAD_RESPONSE, config, undefined, {
        data: new TextEncoder().encode(JSON.stringify({ code, data: null, message })).buffer,
        status: Number(status),
        statusText: 'Error',
        headers: { 'content-type': 'application/json' },
        config,
      })
    }
    await expect(getAppSource('55')).rejects.toMatchObject({ status, code, message })
  })

  it('拒绝将 JSON 业务错误或 HTML 页面保存为 ZIP', async () => {
    for (const contentType of ['application/json', 'text/html']) {
      const body =
        contentType === 'application/json'
          ? JSON.stringify({ code: 50001, data: null, message: '源码暂时不可用' })
          : '<html>代理错误页面</html>'
      httpClient.defaults.adapter = async (config) => ({
        data: new TextEncoder().encode(body).buffer,
        status: 200,
        statusText: 'OK',
        headers: { 'content-type': contentType },
        config,
      })
      await expect(getAppSource('55')).rejects.toThrow(
        contentType === 'application/json' ? '源码暂时不可用' : '源码返回格式不正确',
      )
    }
  })
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
  it('历史查询原样传递字符串游标，不丢失长整数精度', async () => {
    httpClient.defaults.adapter = async (config) => {
      expect(httpClient.getUri(config)).toBe('/api/apps/55/messages?beforeId=9007199254740993')
      return {
        data: { code: 0, data: { records: [], hasMore: false, nextCursor: null }, message: 'ok' },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    await expect(getAppMessages('55', { beforeId: '9007199254740993' })).resolves.toEqual({
      records: [],
      hasMore: false,
      nextCursor: null,
    })
  })

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
      status: 503,
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

  it('读取 HTML 预览，并拒绝把其他响应当成预览', async () => {
    let contentType = 'text/html;charset=UTF-8'
    httpClient.defaults.adapter = async (config) => ({
      data: '<!doctype html><html><body>星云</body></html>',
      status: 200,
      statusText: 'OK',
      headers: { 'content-type': contentType },
      config,
    })
    await expect(requestHtml('/api/apps/55/preview')).resolves.toContain('星云')
    contentType = 'text/plain'
    await expect(hasAppPreview('55')).rejects.toThrow('预览返回格式不正确')
  })

  it.each([409, 500])('预览 HTTP %s 区分暂无成功版本和读取失败', async (status) => {
    httpClient.defaults.adapter = async (config) => {
      throw new AxiosError('preview error', AxiosError.ERR_BAD_RESPONSE, config, undefined, {
        data: { code: 50001, data: null, message: '预览不可用' },
        status,
        statusText: 'Error',
        headers: {},
        config,
      })
    }
    if (status === 409) await expect(hasAppPreview('55')).resolves.toBe(false)
    else await expect(hasAppPreview('55')).rejects.toMatchObject({ status: 500, code: 50001 })
  })
})
