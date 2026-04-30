/**
 * useSymbolQuote — STOMP 종목 시세 실시간 구독 훅
 * 토픽: /topic/quote/{code}
 * 페이지 unmount 시 자동 unsubscribe
 */
import { useEffect, useState } from 'react'
import { stompService } from '@/ws/stomp'
import type { QuoteMessage } from '@/api/types'

export interface QuoteState {
  price: number | null
  change: number | null
  changeRate: number | null
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

export function useSymbolQuote(code: string): QuoteState {
  const [quote, setQuote] = useState<QuoteState>(INITIAL)

  useEffect(() => {
    if (!code) return

    // STOMP 연결 (이미 연결된 경우 no-op)
    stompService.connect('/ws')

    const topic = `/topic/quote/${code}`
    const unsubscribe = stompService.subscribe(topic, (msg) => {
      try {
        const data: QuoteMessage = JSON.parse(msg.body)
        setQuote({
          price: data.price,
          change: data.change,
          changeRate: data.changeRate,
          volume: data.volume,
          updatedAt: new Date(data.timestamp),
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
