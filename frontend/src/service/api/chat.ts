import request from '@/request'
import type { ChatHistoryPageVO } from '@/types/api'

export const getAppMessages = (appId: string): Promise<ChatHistoryPageVO> =>
  request<ChatHistoryPageVO>(`/api/apps/${encodeURIComponent(appId)}/messages`)
