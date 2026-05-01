/**
 * useSymbolQuote — STOMP 실시간 시세 구독 훅.
 * 토픽: /topic/market.tick.{code} (백엔드 TickStompFanoutListener 정합)
 * 페이지 unmount 시 자동 unsubscribe.
 *
 * 변경 이력:
 *   2026-05-01: 토픽 /topic/quote/{code} → /topic/market.tick.{code} (P1 hotfix).
 *               메시지 스키마 QuoteMessage → TickMessage (백엔드 TickEvent 정합).
 */
import { useEffect, useRef, useState } from 'react'
import { stompService } from '@/ws/stomp'
import type { TickMessage } from '@/api/types'

export interface QuoteState {
  price: number | null
  change: number | null      // 클라이언트 계산: price - prevClose
  changeRate: number | null  // (price - prevClose) / prevClose * 100
  volume: number | null
  updatedAt: Date | null
}

const INITIAL: QuoteState = {
  price: null,
  change: null,
  changeRate: null,
  volume: null,
  updatedAt: null,
}

/**
 * @param code KRX 종목 코드
 * @param prevClose 전일 종가 (있으면 change/changeRate 계산, 없으면 null)
 */
export function useSymbolQuote(code: string, prevClose?: number | null): QuoteState {
  const [quote, setQuote] = useState<QuoteState>(INITIAL)
  const prevCloseRef = useRef<number | null | undefined>(prevClose)
  prevCloseRef.current = prevClose

  useEffect(() => {
    if (!code) return

    stompService.connect('/ws')

    const topic = `/topic/market.tick.${code}`
    const unsubscribe = stompService.subscribe(topic, (msg) => {
      try {
        const data: TickMessage = JSON.parse(msg.body)
        const ref = prevCloseRef.current
        const change = ref != null && ref > 0 ? data.price - ref : null
        const changeRate = ref != null && ref > 0 ? ((data.price - ref) / ref) * 100 : null
        setQuote({
          price: data.price,
          change,
          changeRate,
          volume: data.volume,
          updatedAt: new Date(data.at),
        })
      } catch {
        console.warn('[useSymbolQuote] parse error', msg.body)
      }
    })

    return () => {
      unsubscribe()
    }
  }, [code])

  return quote
}
