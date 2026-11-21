import { cancel } from '@/service/generated/app'
import { ApiError, requestStream } from '@/request'
import type { GenerationEventVO, GenerationRequest } from '@/types/api'
import { readSse } from '@/utils/sse'

export async function generateApp(
  appId: string,
  prompt: string,
  signal: AbortSignal,
  onEvent: (event: GenerationEventVO) => void,
  qualityCheck?: boolean,
): Promise<void> {
  const data: GenerationRequest = { prompt, qualityCheck }
  try {
    const stream = await requestStream(
      `/api/apps/${encodeURIComponent(appId)}/generations`,
      data,
      signal,
    )
    for await (const { event, data } of readSse(stream)) {
      if (!['started', 'delta', 'completed', 'failed', 'cancelled'].includes(event)) continue
      let payload: GenerationEventVO
      try {
        payload = JSON.parse(data)
        const expectedStatus = event === 'started' || event === 'delta' ? 'running' : event
        if (
          !payload ||
          typeof payload.generationId !== 'string' ||
          payload.status !== expectedStatus ||
          (event === 'delta' && typeof payload.text !== 'string') ||
          (payload.message !== undefined && typeof payload.message !== 'string')
        )
          throw new Error()
      } catch {
        throw new ApiError('生成事件格式不正确，请刷新状态确认结果')
      }
      onEvent(payload)
      if (payload.status !== 'running') return
    }
    throw new ApiError('连接已结束，但未收到最终结果，请刷新状态确认')
  } catch (cause) {
    if (signal.aborted || cause instanceof ApiError) throw cause
    throw new ApiError('生成连接中断，请刷新状态确认结果')
  }
}

export const cancelGeneration = (appId: string, generationId: string): Promise<boolean> =>
  cancel({ appId: encodeURIComponent(appId), generationId: encodeURIComponent(generationId) })
