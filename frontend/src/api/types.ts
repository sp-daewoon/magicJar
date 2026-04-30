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

/** WebSocket STOMP 실시간 시세 메시지 */
export interface QuoteMessage {
  code: string
  price: number
  change: number        // 전일 대비 변동
  changeRate: number   // 등락률 (%)
  volume: number
  timestamp: number    // unix ms
}
