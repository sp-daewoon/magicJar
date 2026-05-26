/**
 * useIndexCandles — market_index_snapshot 기반 지수 캔들 series.
 *
 * Trend API의 MA 계산 데이터가 부족해도, 장중 실제 snapshot 이 있으면 차트는 별도로 표시한다.
 */
import { useQuery } from '@tanstack/react-query'
import { fetchIndexCandles } from '../market'

interface UseIndexCandlesOptions {
  code: string
  interval?: '1m' | '1d'
  limit?: number
  enabled?: boolean
}

export function indexCandlesQueryKey(
  code: string,
  interval: '1m' | '1d' = '1m',
  limit = 240,
) {
  return ['indexCandles', code, interval, limit] as const
}

const POLLING_INTERVAL_MS = 30_000

export function useIndexCandles({
  code,
  interval = '1m',
  limit = 240,
  enabled = true,
}: UseIndexCandlesOptions) {
  const query = useQuery({
    queryKey: indexCandlesQueryKey(code, interval, limit),
    queryFn: () => fetchIndexCandles(code, { interval, limit }),
    enabled: enabled && code.length > 0,
    refetchInterval: POLLING_INTERVAL_MS,
    refetchOnWindowFocus: false,
    staleTime: 5_000,
  })

  return {
    candles: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  }
}
