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

/**
 * LWC용 캔들 데이터 (서버 CandleResponse → 클라이언트 변환).
 * - 일봉(D1): time = 'YYYY-MM-DD' 문자열 (LWC BusinessDay)
 * - 분봉(M1·M5·M15·M30·H1): time = epoch second number (LWC UTCTimestamp)
 *   Backend Jackson이 String/Long 자동 분기 직렬화. 분봉을 string으로 받으면
 *   LWC가 BusinessDay로 파싱 시도해 'Invalid date string' 에러 발생.
 */
export interface CandleDto {
  time: string | number
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
 * AI 뉴스 감성 분석 STOMP 메시지 — 백엔드 NewsAnalyzedEvent 정합.
 * 토픽: /topic/news (전역) — 클라이언트가 instruments 배열을 보고 종목별 필터.
 */
export type SentimentLabel = 'POSITIVE' | 'NEUTRAL' | 'NEGATIVE'

export interface NewsAnalyzedMessage {
  newsId: string             // UUID
  source: string             // NAVER, OPEN_DART 등
  publishedAt: string        // ISO-8601
  title: string
  sentiment: SentimentLabel
  sentimentScore: number     // 0.0 ~ 1.0 (LLM 신뢰도)
  summary: string | null
  instruments: string[]      // 관련 종목 코드 (예: ['005930'])
  analyzedAt: string         // ISO-8601
}

/**
 * 시장 세션 상태값 — 백엔드 MarketSessionState sealed class의 5 sub-class 이름.
 */
export type MarketSessionStateValue =
  | 'Open'
  | 'PreMarket'
  | 'PostMarket'
  | 'Closed'
  | 'Holiday'

/**
 * 시장 세션 정보 — 백엔드 `MarketSession` data class와 1:1 대응.
 *
 * 백엔드 응답 예시:
 *   { state: "Closed", lastBusinessDate: "2026-04-30", nextOpenAt: "2026-05-04T09:00+09:00" }
 *
 * GET /api/instruments/{code}/quote 응답에 포함.
 */
export interface MarketSession {
  state: MarketSessionStateValue
  lastBusinessDate?: string | null      // ISO-8601 yyyy-MM-dd
  nextOpenAt?: string | null            // ISO-8601 OffsetDateTime
}

/**
 * 시세 데이터 소스 — 백엔드 QuoteSource enum과 1:1 대응.
 * LIVE: KIS WebSocket 실시간
 * CACHED: Redis 캐시 (마지막 실시간 값)
 * LAST_DAY_CLOSE: 전일 종가 (장외/캐시 없음)
 */
export type QuoteSource = 'LIVE' | 'CACHED' | 'LAST_DAY_CLOSE'

/**
 * GET /api/instruments/{code}/quote 응답 — 백엔드 QuoteResponse와 1:1 대응.
 * R3 머지 산출물: stale·asOf·source·session 필드 추가.
 */
export interface QuoteResponse {
  code: string
  price: number
  volume: number
  source: QuoteSource
  stale: boolean
  asOf: string        // ISO-8601 (백엔드 Instant 직렬화)
  session: MarketSession
}

/**
 * WebSocket STOMP 실시간 시세 메시지 — 백엔드 TickEvent와 1:1 호환.
 * 토픽: /topic/market.tick.{code}
 * R3 머지: stale·asOf 필드 추가 (STOMP fanout에서 수신 가능).
 */
export interface TickMessage {
  code: string
  at: string         // ISO-8601 (백엔드 Instant 직렬화)
  price: number      // BigDecimal → number (KRW 정수)
  volume: number     // 누적 또는 체결 단위 (백엔드 source에 따라 의미 상이)
  source?: string    // KIS_WS, MOCK 등
  stale?: boolean    // true = 실시간 아님 (캐시 또는 전일 종가)
  asOf?: string      // 데이터 기준 시각 ISO-8601 (stale=true 시 의미 있음)
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

/* ---------- 개발일지(Devlog) ---------- */

/**
 * GET /api/devlog/list 응답 항목 — 백엔드 DevlogEntry와 1:1 대응.
 * date: 'YYYY-MM-DD' 형식
 * dayOfWeek: '월'~'일' (한국어 요일)
 * mtime: ISO-8601 (마지막 수정 시각)
 * sizeBytes: 파일 크기 (bytes)
 * summary: HTML 첫 <title> 또는 <h1> 텍스트
 */
export interface DevlogEntry {
  date: string       // 'YYYY-MM-DD'
  dayOfWeek: string  // '월' ~ '일'
  mtime: string      // ISO-8601
  sizeBytes: number
  summary: string
}

export interface DevlogListResponse {
  entries: DevlogEntry[]
}
