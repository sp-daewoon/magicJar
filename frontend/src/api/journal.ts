/**
 * Journal API 클라이언트
 * GET /api/journal?code={code}&limit=20&offset=0
 */
import { apiClient } from './client'
import type { TradeJournalEntry } from './types'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

export interface JournalListParams {
  code?: string
  strategyId?: string
  mode?: string
  limit?: number
  offset?: number
}

export const journalApiV2 = {
  list: async (params: JournalListParams = {}): Promise<TradeJournalEntry[]> => {
    if (USE_MOCK) {
      return generateMockJournalEntries(params.code ?? '005930', 5)
    }
    try {
      const q = new URLSearchParams()
      if (params.code) q.set('code', params.code)
      if (params.strategyId) q.set('strategyId', params.strategyId)
      if (params.mode) q.set('mode', params.mode)
      q.set('limit', String(params.limit ?? 20))
      q.set('offset', String(params.offset ?? 0))
      return await apiClient.get<TradeJournalEntry[]>(`/journal?${q}`)
    } catch {
      console.warn('[journal] API unavailable, using mock data')
      return generateMockJournalEntries(params.code ?? '005930', 5)
    }
  },
}

/** Mock 매매일지 데이터 */
export function generateMockJournalEntries(code: string, count = 5): TradeJournalEntry[] {
  const names: Record<string, string> = {
    '005930': '삼성전자',
    '000660': 'SK하이닉스',
    '035420': 'NAVER',
    '005380': '현대차',
  }
  const name = names[code] ?? '종목'

  const entries: TradeJournalEntry[] = []
  const now = Date.now()

  for (let i = 0; i < count; i++) {
    const daysAgo = (i + 1) * 3
    const entryAt = new Date(now - daysAgo * 86_400_000).toISOString()
    const exitAt = new Date(now - (daysAgo - 1) * 86_400_000).toISOString()
    const entryPrice = 70_000 + Math.round(Math.sin(i * 3.7) * 5_000)
    const exitPrice = entryPrice + Math.round(Math.sin(i * 2.1) * 3_000)
    const qty = 10
    const pnl = (exitPrice - entryPrice) * qty
    const pnlRate = ((exitPrice - entryPrice) / entryPrice) * 100

    entries.push({
      id: `mock-id-${i}`,
      tradeId: `mock-trade-${i}`,
      mode: 'BACKTEST',
      code,
      name,
      side: 'BUY',
      entryAt,
      entryPrice,
      entryQty: qty,
      exitAt,
      exitPrice,
      exitQty: qty,
      pnl,
      pnlRate,
      strategyId: 'sma-cross-5-20',
      entrySnapshot: { sma5: entryPrice * 0.98, sma20: entryPrice * 0.96 },
      exitSnapshot: { sma5: exitPrice * 0.99, sma20: exitPrice * 0.97 },
      entryReason: '5일 이동평균이 20일 이동평균을 상향 돌파',
      exitReason: '목표가 도달',
      tags: ['SMA', 'golden-cross'],
      createdAt: entryAt,
      updatedAt: exitAt,
    })
  }

  return entries
}
