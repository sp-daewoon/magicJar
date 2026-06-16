/**
 * 전략 성과 히스토리 + 일자별 메모 REST 클라이언트 (T-PERF R3 — ADR 0126)
 *
 * 백엔드(R2) 계약 그대로 소비:
 *   GET  /api/strategy/{id}/performance/history?userId=&from=&to=  → 일자별 성과 행
 *   GET  /api/strategy/{id}/performance/summary?userId=           → 누적 요약 + 신뢰도 + 레짐별 승률
 *   GET  /api/strategy/{id}/note?userId=&from=&to=                → 메모 타임라인
 *   PUT  /api/strategy/{id}/note/{date}?userId= (body)           → 메모 UPSERT
 *
 * 단위 주의:
 *   - 금액(원)·비율(%)은 backend가 BigDecimal → JSON number 로 내려준다.
 *     정밀도 보존 위해 string 으로 올 가능성도 있어 riskUsage.ts 와 동일하게 안전 파싱.
 *   - 응답/필드가 null 이면 "데이터 없음" 으로 표기 (feedback_no_mock_data — mock 합성 금지).
 *
 * 룰 5: LLM provider import 0건 — backend endpoint 호출만.
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// backend wire DTO (금액·비율 number|string 혼용 가능)
// ──────────────────────────────────────────────

type Numish = number | string | null | undefined

export type MarketRegime = 'UPTREND' | 'DOWNTREND' | 'SIDEWAYS' | 'HIGH_VOL'

interface StrategyPerformanceRowWire {
  tradeDate: string
  roundTripCount: number
  winCount: number
  lossCount: number
  winRatePct: Numish
  realizedPnl: Numish
  totalCostBasis: Numish
  returnPct: Numish
  benchmarkIndexCode: string | null
  benchmarkChangePct: Numish
  alphaPct: Numish
  marketRegime: string | null
}

interface StrategyPerformanceHistoryWire {
  strategyId: number
  from: string
  to: string
  rows: StrategyPerformanceRowWire[] | null
}

interface StrategyConfidenceWire {
  label: string
  labelText: string
  sampleRoundTrips: number
  tradingDays: number
  meanReturnPct: Numish
  stdDevReturnPct: Numish
  sharpe: Numish
}

interface RegimeWinRateWire {
  regime: string
  tradingDays: number
  roundTripCount: number
  winCount: number
  lossCount: number
  winRatePct: Numish
}

interface StrategyPerformanceSummaryWire {
  strategyId: number
  hasData: boolean
  totalRoundTrips: number
  tradingDays: number
  totalWins: number
  totalLosses: number
  winRatePct: Numish
  totalRealizedPnl: Numish
  avgDailyReturnPct: Numish
  avgAlphaPct: Numish
  confidence: StrategyConfidenceWire
  regimeBreakdown: RegimeWinRateWire[] | null
}

interface StrategyNoteWire {
  id: number
  strategyId: number
  tradeDate: string
  noteText: string | null
  tags: string[] | null
  createdAt: string
  updatedAt: string
}

interface StrategyNoteTimelineWire {
  strategyId: number
  from: string
  to: string
  notes: StrategyNoteWire[] | null
}

// ──────────────────────────────────────────────
// 클라이언트 DTO (금액·비율 number 파싱)
// ──────────────────────────────────────────────

export interface StrategyPerformanceRow {
  tradeDate: string
  roundTripCount: number
  winCount: number
  lossCount: number
  winRatePct: number | null
  realizedPnl: number | null
  totalCostBasis: number | null
  returnPct: number | null
  benchmarkIndexCode: string | null
  benchmarkChangePct: number | null
  alphaPct: number | null
  marketRegime: MarketRegime | null
}

export interface StrategyPerformanceHistory {
  strategyId: number
  from: string
  to: string
  rows: StrategyPerformanceRow[]
}

export interface StrategyConfidence {
  label: string
  labelText: string
  sampleRoundTrips: number
  tradingDays: number
  meanReturnPct: number | null
  stdDevReturnPct: number | null
  sharpe: number | null
}

export interface RegimeWinRate {
  regime: MarketRegime | string
  tradingDays: number
  roundTripCount: number
  winCount: number
  lossCount: number
  winRatePct: number | null
}

export interface StrategyPerformanceSummary {
  strategyId: number
  hasData: boolean
  totalRoundTrips: number
  tradingDays: number
  totalWins: number
  totalLosses: number
  winRatePct: number | null
  totalRealizedPnl: number | null
  avgDailyReturnPct: number | null
  avgAlphaPct: number | null
  confidence: StrategyConfidence
  regimeBreakdown: RegimeWinRate[]
}

export interface StrategyNote {
  id: number
  strategyId: number
  tradeDate: string
  noteText: string | null
  tags: string[]
  createdAt: string
  updatedAt: string
}

export interface StrategyNoteTimeline {
  strategyId: number
  from: string
  to: string
  notes: StrategyNote[]
}

export interface StrategyNoteUpsertRequest {
  noteText?: string | null
  tags?: string[]
}

// ──────────────────────────────────────────────
// 파싱 유틸 — number|string|null → number|null (mock 합성 X)
// ──────────────────────────────────────────────

function num(v: Numish): number | null {
  if (v === null || v === undefined || v === '') return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}

const REGIME_SET: ReadonlySet<string> = new Set(['UPTREND', 'DOWNTREND', 'SIDEWAYS', 'HIGH_VOL'])

function asRegime(v: string | null): MarketRegime | null {
  if (v && REGIME_SET.has(v)) return v as MarketRegime
  return null
}

function normalizeRow(w: StrategyPerformanceRowWire): StrategyPerformanceRow {
  return {
    tradeDate: w.tradeDate,
    roundTripCount: w.roundTripCount ?? 0,
    winCount: w.winCount ?? 0,
    lossCount: w.lossCount ?? 0,
    winRatePct: num(w.winRatePct),
    realizedPnl: num(w.realizedPnl),
    totalCostBasis: num(w.totalCostBasis),
    returnPct: num(w.returnPct),
    benchmarkIndexCode: w.benchmarkIndexCode ?? null,
    benchmarkChangePct: num(w.benchmarkChangePct),
    alphaPct: num(w.alphaPct),
    marketRegime: asRegime(w.marketRegime),
  }
}

function normalizeConfidence(w: StrategyConfidenceWire): StrategyConfidence {
  return {
    label: w.label,
    labelText: w.labelText,
    sampleRoundTrips: w.sampleRoundTrips ?? 0,
    tradingDays: w.tradingDays ?? 0,
    meanReturnPct: num(w.meanReturnPct),
    stdDevReturnPct: num(w.stdDevReturnPct),
    sharpe: num(w.sharpe),
  }
}

function normalizeRegimeRow(w: RegimeWinRateWire): RegimeWinRate {
  return {
    regime: w.regime,
    tradingDays: w.tradingDays ?? 0,
    roundTripCount: w.roundTripCount ?? 0,
    winCount: w.winCount ?? 0,
    lossCount: w.lossCount ?? 0,
    winRatePct: num(w.winRatePct),
  }
}

function normalizeNote(w: StrategyNoteWire): StrategyNote {
  return {
    id: w.id,
    strategyId: w.strategyId,
    tradeDate: w.tradeDate,
    noteText: w.noteText ?? null,
    tags: Array.isArray(w.tags) ? w.tags : [],
    createdAt: w.createdAt,
    updatedAt: w.updatedAt,
  }
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const strategyPerformanceApi = {
  getHistory: async (
    strategyId: number,
    userId: number,
    from: string,
    to: string,
  ): Promise<StrategyPerformanceHistory> => {
    const params = new URLSearchParams({ userId: String(userId), from, to })
    const res = await apiClient.get<StrategyPerformanceHistoryWire>(
      `/strategy/${strategyId}/performance/history?${params.toString()}`,
    )
    const data = res.data
    return {
      strategyId: data?.strategyId ?? strategyId,
      from: data?.from ?? from,
      to: data?.to ?? to,
      rows: Array.isArray(data?.rows) ? data.rows.map(normalizeRow) : [],
    }
  },

  getSummary: async (strategyId: number, userId: number): Promise<StrategyPerformanceSummary> => {
    const params = new URLSearchParams({ userId: String(userId) })
    const res = await apiClient.get<StrategyPerformanceSummaryWire>(
      `/strategy/${strategyId}/performance/summary?${params.toString()}`,
    )
    const data = res.data
    return {
      strategyId: data?.strategyId ?? strategyId,
      hasData: Boolean(data?.hasData),
      totalRoundTrips: data?.totalRoundTrips ?? 0,
      tradingDays: data?.tradingDays ?? 0,
      totalWins: data?.totalWins ?? 0,
      totalLosses: data?.totalLosses ?? 0,
      winRatePct: num(data?.winRatePct),
      totalRealizedPnl: num(data?.totalRealizedPnl),
      avgDailyReturnPct: num(data?.avgDailyReturnPct),
      avgAlphaPct: num(data?.avgAlphaPct),
      confidence: data?.confidence
        ? normalizeConfidence(data.confidence)
        : {
            label: 'LOW',
            labelText: '신뢰도 데이터 없음',
            sampleRoundTrips: 0,
            tradingDays: 0,
            meanReturnPct: null,
            stdDevReturnPct: null,
            sharpe: null,
          },
      regimeBreakdown: Array.isArray(data?.regimeBreakdown)
        ? data.regimeBreakdown.map(normalizeRegimeRow)
        : [],
    }
  },

  getNotes: async (
    strategyId: number,
    userId: number,
    from: string,
    to: string,
  ): Promise<StrategyNoteTimeline> => {
    const params = new URLSearchParams({ userId: String(userId), from, to })
    const res = await apiClient.get<StrategyNoteTimelineWire>(
      `/strategy/${strategyId}/note?${params.toString()}`,
    )
    const data = res.data
    return {
      strategyId: data?.strategyId ?? strategyId,
      from: data?.from ?? from,
      to: data?.to ?? to,
      notes: Array.isArray(data?.notes) ? data.notes.map(normalizeNote) : [],
    }
  },

  upsertNote: async (
    strategyId: number,
    userId: number,
    date: string,
    body: StrategyNoteUpsertRequest,
  ): Promise<StrategyNote> => {
    const params = new URLSearchParams({ userId: String(userId) })
    const res = await apiClient.put<StrategyNoteWire>(
      `/strategy/${strategyId}/note/${date}?${params.toString()}`,
      body,
    )
    return normalizeNote(res.data)
  },
}
