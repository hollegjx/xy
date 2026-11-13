import request from '@/request'
import type { ChatHistoryPageVO, ChatHistoryQueryRequest } from '@/types/api'

export const getAppMessages = (
  appId: string,
  params: ChatHistoryQueryRequest = {},
): Promise<ChatHistoryPageVO> =>
  request<ChatHistoryPageVO>(`/api/apps/${encodeURIComponent(appId)}/messages`, { params })
