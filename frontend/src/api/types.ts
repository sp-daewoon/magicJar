/**
 * API 도메인 타입 정의
 * Kotlin 서버 도메인 모델과 1:1 대응
 */

export type Market = 'KOSPI' | 'KOSDAQ' | 'KONEX'

export interface Instrument {
  code: string
  name: string
  market: Market
}

export type Mode = 'BACKTEST' | 'PAPER' | 'REAL'
export type Side = 'BUY' | 'SELL'

export interface TradeJournalEntry {
  id: string
  tradeId: string
  mode: Mode
  code: string
  name: string
  side: Side
  entryAt: string        // ISO-8601
  entryPrice: number
  entryQty: number
  exitAt?: string
  exitPrice?: number
  exitQty?: number
  pnl?: number
  pnlRate?: number
  strategyId: string
  entrySnapshot: Record<string, number>
  exitSnapshot: Record<string, number>
  entryReason: string
  exitReason?: string
  aiExplanation?: string
  aiReview?: string
  tags: string[]
  userNote?: string
  createdAt: string
  updatedAt: string
}

/** LWC용 캔들 데이터 (서버 Candle 모델 → 클라이언트 변환) */
export interface CandleDto {
  time: string    // 'YYYY-MM-DD' or unix timestamp
  open: number
  high: number
  low: number
  close: number
  volume: number
}

/* ---------- 백테스트 ---------- */

export type BacktestRunStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface BacktestTriggerRequest {
  strategyId: string
  symbol: string
  from: string  // 'YYYY-MM-DD'
  to: string    // 'YYYY-MM-DD'
}

export interface BacktestTriggerResponse {
  runId: string
  status: BacktestRunStatus
  message: string
}

/** 백테스트 결과 요약 — summaryJson 파싱 결과 (전략별 스키마 상이) */
export interface BacktestSummary {
  netReturn?: number    // 0.123 = 12.3%
  winRate?: number      // 0.55 = 55%
  mdd?: number          // -0.082 = -8.2%
  trades?: number
  [key: string]: unknown
}

export interface BacktestStatusResponse {
  runId: string
  strategyId: string
  symbol: string
  from: string
  to: string
  status: BacktestRunStatus
  startedAt: string | null
  finishedAt: string | null
  /** 백엔드는 string으로 직렬화. 클라이언트가 JSON.parse → BacktestSummary */
  summaryJson: string | null
  errorMessage: string | null
}

/**
 * WebSocket STOMP 실시간 시세 메시지 — 백엔드 TickEvent와 1:1 호환.
 * 토픽: /topic/market.tick.{code}
 */
export interface TickMessage {
  code: string
  at: string         // ISO-8601 (백엔드 Instant 직렬화)
  price: number      // BigDecimal → number (KRW 정수)
  volume: number     // 누적 또는 체결 단위 (백엔드 source에 따라 의미 상이)
  source?: string    // KIS_WS, MOCK 등
}

/**
 * @deprecated 사용 중지. TickMessage를 사용하라. (토픽 mismatch hotfix 2026-05-01)
 * change/changeRate 필드는 백엔드 미발행 — 클라이언트가 전일 종가 대비 계산.
 */
export interface QuoteMessage {
  code: string
  price: number
  change: number
  changeRate: number
  volume: number
  timestamp: number
}
