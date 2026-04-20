import { http } from '@/utils/http'
import type { Result } from '@/api/system'

export interface MusicAssistantPayload {
  memoryId: string
  message: string
}

export const sendMusicAssistantMessage = (data: MusicAssistantPayload) => {
  return http<Result>('post', '/ai/chat/message', {
    data,
    timeout: 60000,
  })
}

export const clearMusicAssistantMemory = (memoryId: string) => {
  return http<Result>('delete', `/ai/chat/memory/${memoryId}`)
}
