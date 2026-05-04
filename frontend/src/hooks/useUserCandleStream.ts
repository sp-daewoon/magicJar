/**
 * useUserCandleStream — 사용자 차트 candle 갱신 STOMP 구독 (ADR 0050 R3-5a).
 *
 * 동작:
 *   1. SymbolDetail 진입 시 `/topic/market.candle.user.{userId}.{code}` 구독
 *   2. consumer가 ZADD 직후 api가 STOMP fanout한 UserCandleUpdatedEvent 수신
 *   3. 메시지 수신 시 useCandles의 queryKey invalidate → 자동 refetch
 *      (현재는 1차 — 페이지 새로고침 없이 차트 갱신 보장)
 *
 * R3-5b 이후 정교화 옵션:
 *   - setQueryData로 마지막 페이지에 새 봉만 patch (refetch 없이)
 *   - interval 별 별도 토픽 분리
 */
import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { stompService } from '@/ws/stomp'
import { DEFAULT_USER_ID } from '@/hooks/useAuth'

export function useUserCandleStream(code: string | undefined): void {
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!code) return

    stompService.connect('/ws')
    const topic = `/topic/market.candle.user.${DEFAULT_USER_ID}.${code}`
    const unsub = stompService.subscribe(topic, () => {
      // 새 candle 도착 → useCandles의 모든 interval cache invalidate
      queryClient.invalidateQueries({ queryKey: ['candles-infinite', code] })
    })

    return () => {
      unsub()
    }
  }, [code, queryClient])
}
