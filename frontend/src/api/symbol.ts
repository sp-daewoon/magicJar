/**
 * Symbol REST API 클라이언트 + DTO 정의
 *
 * spec §3 REST 시그니처 LOCK 1:1 정합:
 *   GET  /api/symbols/{code}                      → SymbolDto
 *   GET  /api/symbols/{code}/candles?interval=...  → CandlesResponseDto
 *   POST /api/symbols/{code}/orderbook/subscribe   → OrderbookSubscribeResponseDto
 *   POST /api/symbols/{code}/orderbook/unsubscribe → OrderbookUnsubscribeResponseDto
 *
 * ADR 0019 §2-2 인터벌 확장: 1m·5m·15m·30m·90m·1h·4h·12h·1d...
 * ADR 0014 stale 배지: source 필드 + stale boolean
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// 인터벌 타입 (ADR 0062 채택 2026-05-11 — 확장)
//   KIS 직접 제공 5종: 1m·1d·1wk·1mo·1y
//   합산(precomputed): 5m·15m·30m·90m·1h·4h·12h
// 주의: 1wk·1mo·1y는 backend-core PR(PR-31b)과 동시 활성화.
//       합산 interval은 AggregateBackfillRunner 작동 전 빈 배열 반환 → graceful 표시.
// ──────────────────────────────────────────────

export type CandleInterval = '1m' | '5m' | '15m' | '30m' | '90m' | '1h' | '4h' | '12h' | '1d' | '1wk' | '1mo' | '1y'

// ──────────────────────────────────────────────
// DTO 정의 (spec §3-2 LOCK)
// ──────────────────────────────────────────────

/**
 * GET /api/symbols/{code} 응답
 * source: 'live' | 'polling' | 'closed' — ADR 0014 Fallback tier
 */
export interface SymbolDto {
  code: string
  name?: string
  market?: string
  industry?: string
  currentPrice: string
  previousClose: string
  change: string
  changeRate: string
  high: string
  low: string
  open: string
  volume: number
  marketCap?: string
  per?: string
  pbr?: string
  eps?: string
  bps?: string
  asOf: string // ISO 8601
  source: 'live' | 'live-nxt' | 'polling' | 'closed' | 'candle-1d'
  stale: boolean
}

/** GET /api/symbols/{code}/candles 단일 캔들 */
export interface CandleDto {
  ts: string // ISO 8601
  open: string
  high: string
  low: string
  close: string
  volume: number
  previousClose?: string | null
  change?: string | null
  changeRate?: string | null
}

/** GET /api/symbols/{code}/candles 응답 */
export interface CandlesResponseDto {
  code: string
  interval: string // backend 응답이 확장 interval을 반환할 수 있으므로 string 유지
  candles: CandleDto[]
  hasMore: boolean
  nextBefore?: string
}

/** POST /api/symbols/{code}/orderbook/subscribe 응답 */
export interface OrderbookSubscribeResponseDto {
  subscribed: boolean
  slot: 'live' | 'queued'
}

/** POST /api/symbols/{code}/orderbook/unsubscribe 응답 */
export interface OrderbookUnsubscribeResponseDto {
  unsubscribed: boolean
}

// ──────────────────────────────────────────────
// STOMP 실시간 payload 타입 (spec §4-4 STOMP LOCK)
// ──────────────────────────────────────────────

/** /topic/market.tick.{code} */
export interface TickPayload {
  code: string
  price: string
  /** 전일대비 (신규 — KIS PRDY_VRSS, null 가능: polling source) */
  priceChange: string | null
  /** 등락률 % (신규 — KIS PRDY_CTRT, null 가능: polling source) */
  changeRate: string | null
  /**
   * 부호 코드 (신규 — KIS PRDY_VRSS_SIGN, null 가능: polling source)
   * "1"=상한·"2"=상승·"3"=보합·"4"=하한·"5"=하락
   */
  changeSign: string | null
  volume: number
  ts: string
  source: 'live' | 'live-nxt' | 'polling' | 'closed' | 'candle-1d'
  stale: boolean
}

/** /topic/market.candle.minute.{code} */
export interface CandleMinutePayload {
  code: string
  interval: string
  ts: string
  open: string
  high: string
  low: string
  close: string
  volume: number
}

/**
 * /topic/market.candle.{interval}.{code} — boundary closed 실시간 캔들
 * ADR 0064 §2-C: 합산 interval boundary 마감 시점에 fanout
 * BoundaryAggregator → Kafka market.candle.{interval}.closed.v1 → StompCandleForwarder → fanout
 */
export interface CandleClosedPayload {
  code: string
  interval: string
  ts: string
  open: string
  high: string
  low: string
  close: string
  volume: number
}

/** boundary closed STOMP 구독 대상 인터벌 (ADR 0064 §2-C) */
export const BOUNDARY_INTERVALS = ['5m', '15m', '30m', '90m', '1h', '4h', '12h'] as const
export type BoundaryInterval = (typeof BOUNDARY_INTERVALS)[number]

/** CandleInterval이 boundary closed STOMP 구독 대상인지 확인 */
export function isBoundaryInterval(interval: string): interval is BoundaryInterval {
  return (BOUNDARY_INTERVALS as readonly string[]).includes(interval)
}

/** /topic/market.orderbook.{code} */
export interface OrderbookLevel {
  price: string
  quantity: number
}

export interface OrderbookPayload {
  code: string
  ts: string
  bids: OrderbookLevel[]
  asks: OrderbookLevel[]
}

// ──────────────────────────────────────────────
// 캔들 갱신 API 응답 DTO
// ──────────────────────────────────────────────

/**
 * POST /api/symbols/{code}/candles/refresh 응답
 * market-data sub-agent 발행 endpoint (병렬 개발 중)
 */
export interface CandleRefreshResponseDto {
  code: string
  intervals: string[]
  /** 새로 추가된 캔들 수 (합산) */
  added: number
  /** 갱신 완료 시각 (ISO 8601) */
  refreshedAt: string
}

// ──────────────────────────────────────────────
// axios API 함수
// ──────────────────────────────────────────────

export const symbolApi = {
  /**
   * GET /api/symbols/{code}
   * 종목 메타 + 현재가 (KIS inquire-price + Redis quote:last)
   */
  getSymbol: async (code: string): Promise<SymbolDto> => {
    const res = await apiClient.get<SymbolDto>(`/symbols/${encodeURIComponent(code)}`)
    return res.data
  },

  /**
   * GET /api/symbols/{code}/candles
   * 차트 series — DB candle + Redis current·today 합류
   *
   * @param code - 종목 코드
   * @param interval - 인터벌 (10종: 1m·5m·15m·30m·90m·1h·1d·1wk·1mo·1y)
   * @param before - 페이지네이션 cursor (ISO 8601, 미지정 시 최신)
   * @param limit - 최대 캔들 수 (default 200, max 500)
   */
  getCandles: async (
    code: string,
    interval: CandleInterval,
    before?: string,
    limit = 200,
  ): Promise<CandlesResponseDto> => {
    const params = new URLSearchParams({ interval, limit: String(limit) })
    if (before) params.set('before', before)
    const res = await apiClient.get<CandlesResponseDto>(
      `/symbols/${encodeURIComponent(code)}/candles?${params.toString()}`,
    )
    return res.data
  },

  /**
   * POST /api/symbols/{code}/orderbook/subscribe
   * 호가 slot 사용 통보 (ADR 0048 LRU 10)
   * 페이지 mount 시 호출
   */
  subscribeOrderbook: async (code: string): Promise<OrderbookSubscribeResponseDto> => {
    const res = await apiClient.post<OrderbookSubscribeResponseDto>(
      `/symbols/${encodeURIComponent(code)}/orderbook/subscribe`,
    )
    return res.data
  },

  /**
   * POST /api/symbols/{code}/orderbook/unsubscribe
   * 호가 slot 회수 (ADR 0048 LRU 10)
   * 페이지 unmount 시 호출 + sendBeacon fallback
   */
  unsubscribeOrderbook: async (code: string): Promise<OrderbookUnsubscribeResponseDto> => {
    const res = await apiClient.post<OrderbookUnsubscribeResponseDto>(
      `/symbols/${encodeURIComponent(code)}/orderbook/unsubscribe`,
    )
    return res.data
  },

  /**
   * POST /api/symbols/{code}/candles/refresh?intervals=1d,1wk,1mo,1y
   * KIS에서 최신 캔들을 재수집 후 DB에 적재.
   * intervals 미지정 시 backend default (1d·1wk·1mo·1y).
   */
  refreshCandles: async (
    code: string,
    intervals?: string[],
  ): Promise<CandleRefreshResponseDto> => {
    const params = intervals?.length
      ? new URLSearchParams({ intervals: intervals.join(',') })
      : undefined
    const url = params
      ? `/symbols/${encodeURIComponent(code)}/candles/refresh?${params.toString()}`
      : `/symbols/${encodeURIComponent(code)}/candles/refresh`
    const res = await apiClient.post<CandleRefreshResponseDto>(url)
    return res.data
  },
}
