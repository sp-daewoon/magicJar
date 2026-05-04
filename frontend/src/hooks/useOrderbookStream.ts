/**
 * useOrderbookStream — 단일 종목 호가창 STOMP 실시간 구독 훅.
 *
 * 동작:
 *   1. 마운트 시 STOMP `/topic/market.orderbook.{code}` 구독.
 *   2. 메시지 수신 시 latest 상태 업데이트 (1초 KIS WS frame 주기).
 *   3. unmount 또는 code 변경 시 cleanup.
 *
 * topic↔code mismatch guard 포함 (E hotfix PR #139 정합).
 *
 * Refs: plan T2-R2-A2 §T16, ADR 0049 §2-A.
 */
import { useEffect, useState } from 'react'
import { stompService } from '@/ws/stomp'
import type { OrderbookMessage } from '@/api/types'

export function useOrderbookStream(code: string | null | undefined): OrderbookMessage | null {
  const [latest, setLatest] = useState<OrderbookMessage | null>(null)

  useEffect(() => {
    if (!code) return

    setLatest(null)
    stompService.connect('/ws')

    const topic = `/topic/market.orderbook.${code}`
    const unsubscribe = stompService.subscribe(topic, (msg) => {
      try {
        const data: OrderbookMessage = JSON.parse(msg.body)
        if (data.code !== code) {
          if (import.meta.env.DEV) {
            console.warn('[useOrderbookStream] topic/code mismatch', { topic, expected: code, got: data.code })
          }
          return
        }
        setLatest(data)
      } catch {
        console.warn('[useOrderbookStream] parse error', msg.body)
      }
    })

    return () => unsubscribe()
  }, [code])

  return latest
}
