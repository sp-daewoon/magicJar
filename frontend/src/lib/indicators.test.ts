/**
 * indicators.ts 단위 테스트.
 * 백엔드 domain/strategy/indicator(SMA/RSI)와 결과 일치 검증.
 */
import { describe, it, expect } from 'vitest'
import { sma, ema, bollingerBands, rsi, computeIndicators } from './indicators'
import type { CandleDto } from '@/api/types'

function mkCandles(closes: number[]): CandleDto[] {
  return closes.map((close, i) => ({
    time: `2026-01-${String(i + 1).padStart(2, '0')}`,
    open: close,
    high: close + 100,
    low: close - 100,
    close,
    volume: 1000,
  }))
}

describe('sma', () => {
  it('period=3 → [(1+2+3)/3, (2+3+4)/3, ...]', () => {
    const result = sma(mkCandles([1, 2, 3, 4, 5]), 3)
    expect(result).toHaveLength(3)
    expect(result[0].value).toBeCloseTo(2.0)
    expect(result[1].value).toBeCloseTo(3.0)
    expect(result[2].value).toBeCloseTo(4.0)
  })

  it('candles < period → empty', () => {
    expect(sma(mkCandles([1, 2]), 5)).toHaveLength(0)
  })
})

describe('ema', () => {
  it('첫 값은 단순평균과 일치', () => {
    const r = ema(mkCandles([10, 20, 30, 40]), 3)
    expect(r[0].value).toBeCloseTo(20.0) // (10+20+30)/3
    // k = 2/(3+1) = 0.5; next = 40*0.5 + 20*0.5 = 30
    expect(r[1].value).toBeCloseTo(30.0)
  })
})

describe('bollingerBands', () => {
  it('upper > middle > lower, 표준편차 0이면 모두 동일', () => {
    const closes = Array(20).fill(100)
    const bb = bollingerBands(mkCandles(closes), 20, 2)
    expect(bb.upper).toHaveLength(1)
    expect(bb.middle[0].value).toBeCloseTo(100)
    expect(bb.upper[0].value).toBeCloseTo(100) // σ=0
    expect(bb.lower[0].value).toBeCloseTo(100)
  })

  it('변동 있는 데이터에서 upper > middle > lower', () => {
    const closes = Array.from({ length: 20 }, (_, i) => 100 + i)
    const bb = bollingerBands(mkCandles(closes), 20, 2)
    expect(bb.upper[0].value).toBeGreaterThan(bb.middle[0].value)
    expect(bb.lower[0].value).toBeLessThan(bb.middle[0].value)
  })
})

describe('rsi', () => {
  it('상승 일변도면 RSI ≈ 100', () => {
    const closes = Array.from({ length: 20 }, (_, i) => 100 + i * 2)
    const r = rsi(mkCandles(closes), 14)
    expect(r[r.length - 1].value).toBeCloseTo(100, 0)
  })

  it('하락 일변도면 RSI ≈ 0', () => {
    const closes = Array.from({ length: 20 }, (_, i) => 200 - i * 2)
    const r = rsi(mkCandles(closes), 14)
    expect(r[r.length - 1].value).toBeLessThan(10)
  })
})

describe('computeIndicators', () => {
  it('빈 active set → 빈 배열', () => {
    const result = computeIndicators(mkCandles([1, 2, 3, 4, 5]), new Set())
    expect(result).toHaveLength(0)
  })

  it('SMA-5 + RSI-14 활성 → 두 시리즈, pane 분리', () => {
    const closes = Array.from({ length: 30 }, (_, i) => 100 + i)
    const result = computeIndicators(mkCandles(closes), new Set(['SMA-5', 'RSI-14']))
    expect(result).toHaveLength(2)
    const main = result.find((r) => r.id === 'SMA-5')!
    const rsi = result.find((r) => r.id === 'RSI-14')!
    expect(main.pane).toBe('main')
    expect(rsi.pane).toBe('rsi')
  })

  it('BB 토글 시 upper/middle/lower 3개 시리즈 동시 생성', () => {
    const closes = Array.from({ length: 25 }, (_, i) => 100 + i)
    const result = computeIndicators(
      mkCandles(closes),
      new Set(['BB-20-UPPER', 'BB-20-MID', 'BB-20-LOWER']),
    )
    expect(result).toHaveLength(3)
  })
})
