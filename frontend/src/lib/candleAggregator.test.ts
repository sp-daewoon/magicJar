/**
 * candleAggregator.ts 단위 테스트
 * T19-T5: M5 → M15/M30/H1 합성 정확성 검증
 */
import { describe, it, expect } from 'vitest'
import {
  aggregateToWeek,
  aggregateToMonth,
  aggregateToYear,
  aggregateToM15,
  aggregateToM30,
  aggregateToH1,
  resolveInterval,
} from './candleAggregator'
import type { CandleDto } from '@/api/types'

// 유틸: 날짜 문자열 기반 일봉 생성
function mkDayCandles(dates: string[], prices: number[]): CandleDto[] {
  return dates.map((date, i) => ({
    time: date,
    open: prices[i],
    high: prices[i] + 100,
    low: prices[i] - 100,
    close: prices[i] + 50,
    volume: 1_000 * (i + 1),
  }))
}

// 유틸: epoch second 기반 분봉 생성
// baseEpoch: 기준 epoch (초), intervalSec: 봉 간격(초), count: 봉 개수
function mkMinuteCandles(baseEpoch: number, intervalSec: number, count: number): CandleDto[] {
  const candles: CandleDto[] = []
  for (let i = 0; i < count; i++) {
    const t = baseEpoch + i * intervalSec
    const base = 50_000 + i * 100
    candles.push({
      time: t,
      open: base,
      high: base + 200,
      low: base - 200,
      close: base + 50,
      volume: 100 + i * 10,
    })
  }
  return candles
}

// KST 09:00 = UTC 00:00 ≒ 2026-05-03T00:00:00Z epoch
// 2026-05-03 00:00:00 UTC = 1746230400
const BASE_EPOCH = 1746230400 // 2026-05-03 00:00:00 UTC

describe('aggregateToM15 (M5 → M15 합성)', () => {
  it('5분봉 3개 → 15분봉 1개, OHLCV 정확', () => {
    // 3개 M5봉: 0분, 5분, 10분 (모두 같은 15분 그룹)
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 3)
    const result = aggregateToM15(candles)
    expect(result).toHaveLength(1)
    const bar = result[0]
    // time: 그룹 첫 epoch (15분 경계로 floor)
    expect(typeof bar.time).toBe('number')
    // open: 첫 봉 open
    expect(bar.open).toBe(candles[0].open)
    // close: 마지막 봉 close
    expect(bar.close).toBe(candles[2].close)
    // high: max(high)
    expect(bar.high).toBe(Math.max(candles[0].high, candles[1].high, candles[2].high))
    // low: min(low)
    expect(bar.low).toBe(Math.min(candles[0].low, candles[1].low, candles[2].low))
    // volume: 합계
    expect(bar.volume).toBe(candles[0].volume + candles[1].volume + candles[2].volume)
  })

  it('5분봉 6개 → 15분봉 2개 (각 3개씩 그룹)', () => {
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 6)
    const result = aggregateToM15(candles)
    expect(result).toHaveLength(2)
  })

  it('빈 배열 → 빈 배열', () => {
    expect(aggregateToM15([])).toHaveLength(0)
  })

  it('M15 결과 시간순 정렬 보장', () => {
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 9)
    const result = aggregateToM15(candles)
    expect(result).toHaveLength(3)
    for (let i = 1; i < result.length; i++) {
      expect(Number(result[i].time)).toBeGreaterThan(Number(result[i - 1].time))
    }
  })
})

describe('aggregateToM30 (M5 → M30 합성)', () => {
  it('5분봉 6개 → 30분봉 1개, OHLCV 정확', () => {
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 6)
    const result = aggregateToM30(candles)
    expect(result).toHaveLength(1)
    const bar = result[0]
    expect(bar.open).toBe(candles[0].open)
    expect(bar.close).toBe(candles[5].close)
    expect(bar.high).toBe(Math.max(...candles.map((c) => c.high)))
    expect(bar.low).toBe(Math.min(...candles.map((c) => c.low)))
    expect(bar.volume).toBe(candles.reduce((s, c) => s + c.volume, 0))
  })

  it('5분봉 12개 → 30분봉 2개', () => {
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 12)
    expect(aggregateToM30(candles)).toHaveLength(2)
  })
})

describe('aggregateToH1 (M5 → H1 합성)', () => {
  it('5분봉 12개 → 60분봉 1개, volume 합계 일치', () => {
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 12)
    const result = aggregateToH1(candles)
    expect(result).toHaveLength(1)
    const totalVol = candles.reduce((s, c) => s + c.volume, 0)
    expect(result[0].volume).toBe(totalVol)
  })

  it('5분봉 24개 → 60분봉 2개', () => {
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 24)
    expect(aggregateToH1(candles)).toHaveLength(2)
  })

  it('H1 결과 time은 epoch second(number) 타입', () => {
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 12)
    const result = aggregateToH1(candles)
    expect(typeof result[0].time).toBe('number')
  })
})

describe('resolveInterval — 분봉 합성 경로', () => {
  it('M15 → serverInterval=M5, transform=aggregateToM15', () => {
    const { serverInterval, transform } = resolveInterval('M15')
    expect(serverInterval).toBe('M5')
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 3)
    expect(transform(candles)).toHaveLength(1)
  })

  it('M30 → serverInterval=M5, transform=aggregateToM30', () => {
    const { serverInterval, transform } = resolveInterval('M30')
    expect(serverInterval).toBe('M5')
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 6)
    expect(transform(candles)).toHaveLength(1)
  })

  it('H1 → serverInterval=M5, transform=aggregateToH1', () => {
    const { serverInterval, transform } = resolveInterval('H1')
    expect(serverInterval).toBe('M5')
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 12)
    expect(transform(candles)).toHaveLength(1)
  })

  it('M5 → serverInterval=M5, transform=identity', () => {
    const { serverInterval, transform } = resolveInterval('M5')
    expect(serverInterval).toBe('M5')
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 5)
    expect(transform(candles)).toHaveLength(5)
  })

  it('DAY → serverInterval=DAY, transform=identity', () => {
    const { serverInterval, transform } = resolveInterval('DAY')
    expect(serverInterval).toBe('DAY')
    const candles = mkDayCandles(['2026-01-01', '2026-01-02'], [50000, 51000])
    expect(transform(candles)).toHaveLength(2)
  })
})

// 기존 일봉 합성 (회귀)
describe('aggregateToWeek/Month/Year (회귀)', () => {
  it('같은 주 3개 일봉 → 주봉 1개', () => {
    // 2026-05-04(월), 05(화), 06(수) → 같은 ISO 주
    const candles = mkDayCandles(['2026-05-04', '2026-05-05', '2026-05-06'], [100, 110, 120])
    const result = aggregateToWeek(candles)
    expect(result).toHaveLength(1)
  })

  it('같은 월 2개 일봉 → 월봉 1개', () => {
    const candles = mkDayCandles(['2026-05-01', '2026-05-31'], [100, 200])
    const result = aggregateToMonth(candles)
    expect(result).toHaveLength(1)
  })

  it('같은 연도 2개 일봉 → 연봉 1개', () => {
    const candles = mkDayCandles(['2026-01-01', '2026-12-31'], [100, 200])
    const result = aggregateToYear(candles)
    expect(result).toHaveLength(1)
  })
})
