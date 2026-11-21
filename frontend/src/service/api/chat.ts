import { listMessages } from '@/service/generated/app'
import type { ChatHistoryPageVO, ChatHistoryQueryRequest } from '@/types/api'

export const getAppMessages = (
  appId: string,
  params: ChatHistoryQueryRequest = {},
): Promise<ChatHistoryPageVO> => listMessages({ appId: encodeURIComponent(appId), ...params })
