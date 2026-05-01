/**
 * 기술적 지표 계산 — 클라이언트에서 즉시 계산해 차트에 overlay.
 * 백엔드 domain/strategy/indicator(SMA/RSI 등)와 알고리즘 일치(첫 N-1봉은 NaN).
 *
 * 사용처: TradingChart의 lineSeries overlay.
 */
import type { CandleDto } from '@/api/types'

export type IndicatorId =
  | 'SMA-5'
  | 'SMA-10'
  | 'SMA-20'
  | 'SMA-60'
  | 'SMA-120'
  | 'EMA-12'
  | 'EMA-26'
  | 'BB-20-UPPER'
  | 'BB-20-MID'
  | 'BB-20-LOWER'

export interface IndicatorPoint {
  time: string | number
  value: number
}

/** Simple Moving Average — close 기준 N봉 평균. 첫 N-1 봉은 결과 제외 (NaN 회피). */
export function sma(candles: CandleDto[], period: number): IndicatorPoint[] {
  if (period <= 0 || candles.length < period) return []
  const out: IndicatorPoint[] = []
  let sum = 0
  for (let i = 0; i < candles.length; i++) {
    sum += candles[i].close
    if (i >= period) sum -= candles[i - period].close
    if (i >= period - 1) {
      out.push({ time: candles[i].time, value: sum / period })
    }
  }
  return out
}

/** Exponential Moving Average — k = 2/(N+1). 초기값은 단순 평균. */
export function ema(candles: CandleDto[], period: number): IndicatorPoint[] {
  if (period <= 0 || candles.length < period) return []
  const k = 2 / (period + 1)
  const out: IndicatorPoint[] = []
  let prevEma = 0
  let initSum = 0
  for (let i = 0; i < candles.length; i++) {
    if (i < period - 1) {
      initSum += candles[i].close
      continue
    }
    if (i === period - 1) {
      initSum += candles[i].close
      prevEma = initSum / period
      out.push({ time: candles[i].time, value: prevEma })
      continue
    }
    prevEma = candles[i].close * k + prevEma * (1 - k)
    out.push({ time: candles[i].time, value: prevEma })
  }
  return out
}

/**
 * Bollinger Bands — middle = SMA(20), upper/lower = middle ± 2σ.
 * 표준편차는 모집단 분산(population stddev) 기준.
 */
export function bollingerBands(
  candles: CandleDto[],
  period = 20,
  k = 2,
): {
  upper: IndicatorPoint[]
  middle: IndicatorPoint[]
  lower: IndicatorPoint[]
} {
  if (candles.length < period) return { upper: [], middle: [], lower: [] }
  const middle = sma(candles, period)
  const upper: IndicatorPoint[] = []
  const lower: IndicatorPoint[] = []

  for (let i = period - 1; i < candles.length; i++) {
    const slice = candles.slice(i - period + 1, i + 1)
    const mean = middle[i - period + 1].value
    const variance =
      slice.reduce((acc, c) => acc + (c.close - mean) ** 2, 0) / period
    const std = Math.sqrt(variance)
    upper.push({ time: candles[i].time, value: mean + k * std })
    lower.push({ time: candles[i].time, value: mean - k * std })
  }
  return { upper, middle, lower }
}

/** Wilder's RSI(14). 첫 N-1 봉은 결과 제외. */
export function rsi(candles: CandleDto[], period = 14): IndicatorPoint[] {
  if (candles.length <= period) return []
  const out: IndicatorPoint[] = []
  let avgGain = 0
  let avgLoss = 0

  // 초기 평균
  for (let i = 1; i <= period; i++) {
    const diff = candles[i].close - candles[i - 1].close
    if (diff > 0) avgGain += diff
    else avgLoss -= diff
  }
  avgGain /= period
  avgLoss /= period

  const firstRs = avgLoss === 0 ? Infinity : avgGain / avgLoss
  out.push({ time: candles[period].time, value: 100 - 100 / (1 + firstRs) })

  for (let i = period + 1; i < candles.length; i++) {
    const diff = candles[i].close - candles[i - 1].close
    const gain = diff > 0 ? diff : 0
    const loss = diff < 0 ? -diff : 0
    avgGain = (avgGain * (period - 1) + gain) / period
    avgLoss = (avgLoss * (period - 1) + loss) / period
    const rs = avgLoss === 0 ? Infinity : avgGain / avgLoss
    out.push({ time: candles[i].time, value: 100 - 100 / (1 + rs) })
  }
  return out
}

export interface IndicatorConfig {
  id: IndicatorId | 'RSI-14'
  label: string
  color: string
  /** 'main' = 가격 차트 overlay, 'rsi' = 별도 패널 (0~100) */
  pane: 'main' | 'rsi'
}

export const INDICATOR_CATALOG: IndicatorConfig[] = [
  { id: 'SMA-5', label: 'MA 5', color: '#f59e0b', pane: 'main' },
  { id: 'SMA-10', label: 'MA 10', color: '#10b981', pane: 'main' },
  { id: 'SMA-20', label: 'MA 20', color: '#3b82f6', pane: 'main' },
  { id: 'SMA-60', label: 'MA 60', color: '#8b5cf6', pane: 'main' },
  { id: 'SMA-120', label: 'MA 120', color: '#6b7280', pane: 'main' },
  { id: 'EMA-12', label: 'EMA 12', color: '#ec4899', pane: 'main' },
  { id: 'EMA-26', label: 'EMA 26', color: '#06b6d4', pane: 'main' },
  { id: 'BB-20-UPPER', label: 'BB 상', color: '#94a3b8', pane: 'main' },
  { id: 'BB-20-MID', label: 'BB 중', color: '#94a3b8', pane: 'main' },
  { id: 'BB-20-LOWER', label: 'BB 하', color: '#94a3b8', pane: 'main' },
  { id: 'RSI-14', label: 'RSI 14', color: '#dc2626', pane: 'rsi' },
]

/** 활성 indicator 한 묶음 → 시리즈 데이터로 변환 */
export function computeIndicators(
  candles: CandleDto[],
  active: Set<string>,
): Array<{ id: string; label: string; color: string; pane: 'main' | 'rsi'; data: IndicatorPoint[] }> {
  const out: Array<{ id: string; label: string; color: string; pane: 'main' | 'rsi'; data: IndicatorPoint[] }> = []

  // SMA
  for (const period of [5, 10, 20, 60, 120]) {
    const id = `SMA-${period}` as IndicatorId
    if (!active.has(id)) continue
    const cfg = INDICATOR_CATALOG.find((c) => c.id === id)!
    out.push({ id, label: cfg.label, color: cfg.color, pane: cfg.pane, data: sma(candles, period) })
  }

  // EMA
  for (const period of [12, 26]) {
    const id = `EMA-${period}` as IndicatorId
    if (!active.has(id)) continue
    const cfg = INDICATOR_CATALOG.find((c) => c.id === id)!
    out.push({ id, label: cfg.label, color: cfg.color, pane: cfg.pane, data: ema(candles, period) })
  }

  // Bollinger — 3개 시리즈 묶음 활성화
  if (active.has('BB-20-UPPER') || active.has('BB-20-MID') || active.has('BB-20-LOWER')) {
    const bb = bollingerBands(candles, 20, 2)
    if (active.has('BB-20-UPPER')) {
      const cfg = INDICATOR_CATALOG.find((c) => c.id === 'BB-20-UPPER')!
      out.push({ id: cfg.id, label: cfg.label, color: cfg.color, pane: cfg.pane, data: bb.upper })
    }
    if (active.has('BB-20-MID')) {
      const cfg = INDICATOR_CATALOG.find((c) => c.id === 'BB-20-MID')!
      out.push({ id: cfg.id, label: cfg.label, color: cfg.color, pane: cfg.pane, data: bb.middle })
    }
    if (active.has('BB-20-LOWER')) {
      const cfg = INDICATOR_CATALOG.find((c) => c.id === 'BB-20-LOWER')!
      out.push({ id: cfg.id, label: cfg.label, color: cfg.color, pane: cfg.pane, data: bb.lower })
    }
  }

  // RSI
  if (active.has('RSI-14')) {
    const cfg = INDICATOR_CATALOG.find((c) => c.id === 'RSI-14')!
    out.push({ id: cfg.id, label: cfg.label, color: cfg.color, pane: cfg.pane, data: rsi(candles, 14) })
  }

  return out
}
