/**
 * useCandleRefresh — 캔들 갱신 useMutation hook
 *
 * POST /api/symbols/{code}/candles/refresh?intervals=...
 * 성공 시 TanStack Query의 symbolCandles 캐시를 invalidate → 차트 자동 새로고침.
 *
 * 사용처: SymbolChart 헤더 "갱신" 버튼 onClick
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { symbolApi } from '../symbol'

export interface UseCandleRefreshOptions {
  code: string
  /** 갱신할 인터벌 목록 (미지정 시 backend default: 1d·1wk·1mo·1y) */
  intervals?: string[]
  /** 성공 시 콜백 (added 건수 전달) */
  onSuccess?: (added: number) => void
  /** 실패 시 콜백 */
  onError?: (msg: string) => void
}

export function useCandleRefresh({
  code,
  intervals,
  onSuccess,
  onError,
}: UseCandleRefreshOptions) {
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => symbolApi.refreshCandles(code, intervals),
    onSuccess: (data) => {
      // symbolCandles 캐시 invalidate → useSymbolCandles re-fetch → 차트 자동 갱신
      void queryClient.invalidateQueries({ queryKey: ['symbolCandles', code] })
      onSuccess?.(data.added)
    },
    onError: (err) => {
      const msg =
        err instanceof Error ? err.message : '캔들 갱신 중 오류가 발생했습니다.'
      onError?.(msg)
    },
  })

  return {
    refresh: mutation.mutate,
    isPending: mutation.isPending,
    isError: mutation.isError,
  }
}
