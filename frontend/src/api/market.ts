/**
 * Market API 클라이언트
 * GET /api/market/index?codes=KOSPI,KOSDAQ  — KOSPI·KOSDAQ 지수 조회 (T21-T3)
 */
import { apiClient } from './client'
import type { MarketIndex } from './types'

export const marketApi = {
  /**
   * 지수 조회 — GET /api/market/index?codes=KOSPI,KOSDAQ.
   * T21-T3 신규 endpoint. backend 미머지 시 mock fallback.
   */
  index: async (codes: string[]): Promise<MarketIndex[]> => {
    if (codes.length === 0) return []
    try {
      const params = new URLSearchParams({ codes: codes.join(',') })
      return await apiClient.get<MarketIndex[]>(`/market/index?${params}`)
    } catch {
      console.warn('[market] index API unavailable, using mock')
      return generateMockMarketIndex(codes)
    }
  },
}

/** Mock 지수 데이터 — backend T21-T3 미구현 시 사용 */
export function generateMockMarketIndex(codes: string[]): MarketIndex[] {
  const MOCK_DATA: Record<string, MarketIndex> = {
    KOSPI: {
      code: 'KOSPI',
      name: 'KOSPI',
      currentValue: 2_680.45,
      prevClose: 2_651.80,
      changeAmount: 28.65,
      changePercent: 1.08,
      volume: 8_420_000_000_000,
      session: null,
    },
    KOSDAQ: {
      code: 'KOSDAQ',
      name: '코스닥',
      currentValue: 856.32,
      prevClose: 848.10,
      changeAmount: 8.22,
      changePercent: 0.97,
      volume: 4_210_000_000_000,
      session: null,
    },
  }

  return codes.map((code) => MOCK_DATA[code] ?? {
    code,
    name: code,
    currentValue: 0,
    prevClose: 0,
    changeAmount: 0,
    changePercent: 0,
    volume: null,
    session: null,
  })
}
