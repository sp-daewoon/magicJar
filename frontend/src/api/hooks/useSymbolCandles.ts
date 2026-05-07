/**
 * useSymbolCandles — 차트 series 무한 스크롤 TanStack Query hook
 *
 * spec §6-3 LOCK:
 * - queryKey: ['symbolCandles', code, interval]
 * - useInfiniteQuery + before cursor 페이지네이션
 * - getNextPageParam: lastPage.hasMore ? lastPage.nextBefore : undefined
 *
 * ADR 0019 §2-2: interval 5종 한정 (1m·5m·30m·1h·1d)
 * ADR 0029 §2-1: 1m = DB + Redis candle:current 합류, 1d = DB + Redis candle:today 합류
 */
import { useInfiniteQuery } from '@tanstack/react-query'
import { symbolApi, type CandleInterval } from '../symbol'

export function useSymbolCandles(code: string, interval: CandleInterval) {
  return useInfiniteQuery({
    queryKey: ['symbolCandles', code, interval] as const,
    queryFn: ({ pageParam }) =>
      symbolApi.getCandles(code, interval, pageParam as string | undefined),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => (lastPage.hasMore ? lastPage.nextBefore : undefined),
    enabled: Boolean(code),
    staleTime: 30_000, // 30초 — 캔들은 상대적으로 안정적
    gcTime: 60_000,    // 1분 캐시 유지
    retry: 1,
  })
}
