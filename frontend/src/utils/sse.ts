export async function* readSse(
  stream: ReadableStream<Uint8Array>,
): AsyncGenerator<{ event: string; data: string }> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let event = ''
  let data: string[] = []
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      let index: number
      while ((index = buffer.search(/[\r\n]/)) !== -1) {
        // CRLF 可能被拆到两个网络分片，先等下一片再判断行尾。
        if (!done && buffer[index] === '\r' && index === buffer.length - 1) break
        const line = buffer.slice(0, index)
        buffer = buffer.slice(index + (buffer.slice(index, index + 2) === '\r\n' ? 2 : 1))
        if (line === '') {
          if (data.length) yield { event, data: data.join('\n') }
          event = ''
          data = []
        } else {
          const colon = line.indexOf(':')
          const field = colon < 0 ? line : line.slice(0, colon)
          const content = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
          if (field === 'event') event = content
          if (field === 'data') data.push(content)
        }
      }
      if (done) break
    }
  } finally {
    try {
      await reader.cancel()
    } finally {
      reader.releaseLock()
    }
  }
}
