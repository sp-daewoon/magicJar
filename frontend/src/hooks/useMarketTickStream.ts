/**
 * useMarketTickStream — 여러 종목의 시세를 STOMP로 동시 구독하고 marketTick store에 dispatch.
 * 대시보드 등 멀티 종목 실시간 페이지에서 사용.
 *
 * codes 배열이 변할 때마다 추가/제거 자동 반영. 빈 배열 → 구독 해제.
 *
 * 변경 이력:
 *   2026-05-03: T2-R4 — stale·asOf 필드 store dispatch 추가.
 */
import { useEffect } from 'react'
import { stompService } from '@/ws/stomp'
import { useMarketTickStore } from '@/stores/marketTick'
import type { TickMessage, QuoteSource } from '@/api/types'

export function useMarketTickStream(codes: string[]): void {
  const addTick = useMarketTickStore((s) => s.addTick)

  useEffect(() => {
    if (codes.length === 0) return

    stompService.connect('/ws')

    const unsubscribers = codes.map((code) => {
      const topic = `/topic/market.tick.${code}`
      return stompService.subscribe(topic, (msg) => {
        try {
          const data: TickMessage = JSON.parse(msg.body)
          addTick({
            code: data.code,
            price: data.price,
            volume: data.volume,
            timestamp: new Date(data.at).getTime(),
            // T2-R4: stale/source/asOf 전달
            stale: data.stale,
            source: data.stale ? undefined : ('LIVE' as QuoteSource),
            asOf: data.asOf ? new Date(data.asOf).getTime() : new Date(data.at).getTime(),
          })
        } catch {
          console.warn('[useMarketTickStream] parse error', msg.body)
        }
      })
    })

    return () => {
      unsubscribers.forEach((u) => u())
    }
  }, [codes.join(','), addTick])
}
