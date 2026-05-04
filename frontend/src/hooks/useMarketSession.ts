/**
 * useMarketSession — KRX 장 상태 스냅샷 polling 훅.
 *
 * GET /api/market/session — 1분 자동 refetch.
 * Dashboard 헤더 / SymbolDetail 등 어디서든 일관된 세션 배지 노출용.
 *
 * 백엔드는 1분 in-memory cache라 polling 부담 적음 (ApiMarketSessionResolverImpl).
 *
 * plan T2-R2-A3 §T22.
 */
import { useQuery } from '@tanstack/react-query'
import { marketApi } from '@/api/market'
import type { MarketSession } from '@/api/types'

export function useMarketSession() {
  return useQuery<MarketSession>({
    queryKey: ['market-session'],
    queryFn: () => marketApi.session(),
    staleTime: 60_000,
    refetchInterval: 60_000,
    retry: 1,
  })
}
