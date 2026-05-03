/**
 * useMarketIndex — KOSPI·KOSDAQ 지수 조회 훅.
 * T21-T4: GET /api/market/index?codes=KOSPI,KOSDAQ
 *
 * - 1분 자동 refetch (refetchInterval: 60_000)
 * - backend 미구현 시 mock fallback (marketApi.index 내부에서 처리)
 */
import { useQuery } from '@tanstack/react-query'
import { marketApi } from '@/api/market'
import type { MarketIndex } from '@/api/types'

export function useMarketIndex(codes: string[]) {
  return useQuery<MarketIndex[]>({
    queryKey: ['market-index', codes.join(',')],
    queryFn: () => marketApi.index(codes),
    enabled: codes.length > 0,
    staleTime: 60_000,
    refetchInterval: 60_000,
    retry: 2,
  })
}
