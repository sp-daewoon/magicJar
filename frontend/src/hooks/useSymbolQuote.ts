/**
 * useSymbolQuote — 종목 시세 훅 (REST + STOMP 통합).
 *
 * 동작 흐름:
 *  1. 마운트 시 GET /api/instruments/{code}/quote 초기 로드 (stale·source·asOf·session 포함).
 *  2. STOMP /topic/market.tick.{code} 수신 시 price·volume·asOf·stale 실시간 업데이트.
 *  3. STOMP 연결 끊김 / 응답 없음 → REST에서 받은 stale/source 그대로 유지.
 *
 * 변경 이력:
 *   2026-05-01: 토픽 /topic/quote/{code} → /topic/market.tick.{code} (P1 hotfix).
 *               메시지 스키마 QuoteMessage → TickMessage (백엔드 TickEvent 정합).
 *   2026-05-03: T2-R4 — QuoteResponse REST 초기 로드 + stale·source·asOf·session 상태 추가.
 */
import { useEffect, useRef, useState } from 'react'
import { stompService } from '@/ws/stomp'
import { instrumentsApi } from '@/api/instruments'
import type { TickMessage, QuoteSource, MarketSession } from '@/api/types'

export interface QuoteState {
  price: number | null
  change: number | null      // 클라이언트 계산: price - prevClose
  changeRate: number | null  // (price - prevClose) / prevClose * 100
  volume: number | null
  updatedAt: Date | null
  // T2-R4 추가: 데이터 품질 필드
  stale: boolean             // true = 실시간 아님 (CACHED 또는 LAST_DAY_CLOSE)
  source: QuoteSource | null // LIVE | CACHED | LAST_DAY_CLOSE
  asOf: Date | null          // 데이터 기준 시각 (stale=true 시 의미 있음)
  session: MarketSession | null // 시장 세션 상태
  restLoaded: boolean        // REST 초기 로드 완료 여부
}

const INITIAL: QuoteState = {
  price: null,
  change: null,
  changeRate: null,
  volume: null,
  updatedAt: null,
  stale: false,
  source: null,
  asOf: null,
  session: null,
  restLoaded: false,
}

/**
 * @param code KRX 종목 코드
 * @param prevClose 전일 종가 (있으면 change/changeRate 계산, 없으면 null)
 */
export function useSymbolQuote(code: string, prevClose?: number | null): QuoteState {
  const [quote, setQuote] = useState<QuoteState>(INITIAL)
  const prevCloseRef = useRef<number | null | undefined>(prevClose)
  prevCloseRef.current = prevClose

  // REST 초기 로드: GET /api/instruments/{code}/quote
  useEffect(() => {
    if (!code) return

    let cancelled = false
    instrumentsApi.quote(code).then((resp) => {
      if (cancelled || !resp) return
      const ref = prevCloseRef.current
      const change = ref != null && ref > 0 ? resp.price - ref : null
      const changeRate = ref != null && ref > 0 ? ((resp.price - ref) / ref) * 100 : null

      setQuote((prev) => ({
        ...prev,
        price: resp.price,
        change,
        changeRate,
        volume: resp.volume,
        updatedAt: new Date(resp.asOf),
        stale: resp.stale,
        source: resp.source,
        asOf: new Date(resp.asOf),
        session: resp.session,
        restLoaded: true,
      }))
    })

    return () => { cancelled = true }
  }, [code])

  // STOMP 실시간 구독: /topic/market.tick.{code}
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

        setQuote((prev) => ({
          ...prev,
          price: data.price,
          change,
          changeRate,
          volume: data.volume,
          updatedAt: new Date(data.at),
          // STOMP 메시지에 stale/asOf 있으면 반영, 없으면 LIVE로 처리
          stale: data.stale ?? false,
          source: data.stale ? (prev.source ?? 'LIVE') : 'LIVE',
          asOf: data.asOf ? new Date(data.asOf) : new Date(data.at),
        }))
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
