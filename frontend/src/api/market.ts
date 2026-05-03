/**
 * Market API 클라이언트
 * GET /api/market/index?codes=KOSPI,KOSDAQ  — KOSPI·KOSDAQ 지수 조회
 */
import { apiClient } from './client'
import type { MarketIndex } from './types'

export const marketApi = {
  /**
   * 지수 조회 — GET /api/market/index?codes=KOSPI,KOSDAQ.
   * 실패 시 에러를 상위로 전파 (useQuery isError 처리).
   */
  index: (codes: string[]): Promise<MarketIndex[]> => {
    if (codes.length === 0) return Promise.resolve([])
    const params = new URLSearchParams({ codes: codes.join(',') })
    return apiClient.get<MarketIndex[]>(`/market/index?${params}`)
  },
}
