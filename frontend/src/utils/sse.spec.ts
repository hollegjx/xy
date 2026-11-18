import { describe, expect, it } from 'vitest'
import { readSse } from './sse'

describe('readSse', () => {
  it('忽略独立及事件内的心跳注释，包括被网络分片拆开的注释', async () => {
    const chunks = [
      ':keep',
      'alive\n\nevent:started\ndata:{"generationId":"101"}\n\n:keepalive\r',
      '\n\r\nevent:delta\n:keepalive\ndata:{"text":"页面"}\n\n:',
      'keepalive\n\nevent:completed\ndata:{"status":"completed"}\n\n:keepalive\n\n',
    ]
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(new TextEncoder().encode(chunk))
        controller.close()
      },
    })
    const events = []
    for await (const event of readSse(stream)) events.push(event)
    expect(events).toEqual([
      { event: 'started', data: '{"generationId":"101"}' },
      { event: 'delta', data: '{"text":"页面"}' },
      { event: 'completed', data: '{"status":"completed"}' },
    ])
  })
})
