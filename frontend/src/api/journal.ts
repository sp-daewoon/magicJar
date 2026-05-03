/**
 * Journal API 클라이언트
 * GET /api/journal?code={code}&limit=20&offset=0
 */
import { apiClient } from './client'
import type { TradeJournalEntry } from './types'

export interface JournalListParams {
  code?: string
  strategyId?: string
  mode?: string
  limit?: number
  offset?: number
}

export const journalApiV2 = {
  list: async (params: JournalListParams = {}): Promise<TradeJournalEntry[]> => {
    try {
      const q = new URLSearchParams()
      if (params.code) q.set('code', params.code)
      if (params.strategyId) q.set('strategyId', params.strategyId)
      if (params.mode) q.set('mode', params.mode)
      q.set('limit', String(params.limit ?? 20))
      q.set('offset', String(params.offset ?? 0))
      return await apiClient.get<TradeJournalEntry[]>(`/journal?${q}`)
    } catch {
      console.warn('[journal] API 응답 없음 — 빈 목록 반환')
      return []
    }
  },
}
