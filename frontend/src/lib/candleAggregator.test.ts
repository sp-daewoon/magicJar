/**
 * candleAggregator.ts 단위 테스트
 *
 * 분봉 아키 v2 정책:
 *   - M5→M15/M30/H1 클라이언트 합성 폐기 (백엔드가 직접 제공)
 *   - aggregateToM15/M30/H1/aggregateMinutes 함수 삭제
 *   - 이 파일에서 해당 분봉 합성 테스트도 제거
 *
 * 유지 테스트:
 *   - D1→W/MO/Y 집계 정확성
 *   - resolveInterval 경로 (분봉은 identity, W/MO/Y는 집계)
 */
import { describe, it, expect } from 'vitest'
import {
  aggregateToWeek,
  aggregateToMonth,
  aggregateToYear,
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
const BASE_EPOCH = 1746230400

// 일봉 집계 (회귀)
describe('aggregateToWeek/Month/Year', () => {
  it('같은 주 3개 일봉 → 주봉 1개', () => {
    // 2026-05-04(월), 05(화), 06(수) → 같은 ISO 주
    const candles = mkDayCandles(['2026-05-04', '2026-05-05', '2026-05-06'], [100, 110, 120])
    const result = aggregateToWeek(candles)
    expect(result).toHaveLength(1)
  })

  it('같은 주 OHLCV 정확성', () => {
    const candles = mkDayCandles(['2026-05-04', '2026-05-05', '2026-05-06'], [100, 110, 120])
    const result = aggregateToWeek(candles)
    const bar = result[0]
    // open: 첫 캔들 open
    expect(bar.open).toBe(100)
    // close: 마지막 캔들 close
    expect(bar.close).toBe(170) // 120 + 50
    // high: max(high) = max(200, 210, 220) = 220
    expect(bar.high).toBe(220)
    // low: min(low) = min(0, 10, 20) = 0
    expect(bar.low).toBe(0)
    // volume: 합계 = 1000 + 2000 + 3000 = 6000
    expect(bar.volume).toBe(6000)
  })

  it('다른 주 2개 일봉 → 주봉 2개', () => {
    // 2026-04-27(월) 와 2026-05-04(월) → 다른 주
    const candles = mkDayCandles(['2026-04-27', '2026-05-04'], [100, 200])
    const result = aggregateToWeek(candles)
    expect(result).toHaveLength(2)
  })

  it('같은 월 2개 일봉 → 월봉 1개', () => {
    const candles = mkDayCandles(['2026-05-01', '2026-05-31'], [100, 200])
    const result = aggregateToMonth(candles)
    expect(result).toHaveLength(1)
  })

  it('다른 월 2개 일봉 → 월봉 2개', () => {
    const candles = mkDayCandles(['2026-04-30', '2026-05-01'], [100, 200])
    const result = aggregateToMonth(candles)
    expect(result).toHaveLength(2)
  })

  it('같은 연도 2개 일봉 → 연봉 1개', () => {
    const candles = mkDayCandles(['2026-01-01', '2026-12-31'], [100, 200])
    const result = aggregateToYear(candles)
    expect(result).toHaveLength(1)
  })

  it('다른 연도 2개 일봉 → 연봉 2개', () => {
    const candles = mkDayCandles(['2025-12-31', '2026-01-01'], [100, 200])
    const result = aggregateToYear(candles)
    expect(result).toHaveLength(2)
  })

  it('빈 배열 → 빈 배열', () => {
    expect(aggregateToWeek([])).toHaveLength(0)
    expect(aggregateToMonth([])).toHaveLength(0)
    expect(aggregateToYear([])).toHaveLength(0)
  })
})

describe('resolveInterval — 분봉 v2 경로 (백엔드 직접 제공)', () => {
  it('DAY → serverInterval=DAY, transform=identity', () => {
    const { serverInterval, transform } = resolveInterval('DAY')
    expect(serverInterval).toBe('DAY')
    const candles = mkDayCandles(['2026-01-01', '2026-01-02'], [50000, 51000])
    expect(transform(candles)).toHaveLength(2)
  })

  it('WEEK → serverInterval=DAY, transform=aggregateToWeek', () => {
    const { serverInterval, transform } = resolveInterval('WEEK')
    expect(serverInterval).toBe('DAY')
    const candles = mkDayCandles(['2026-05-04', '2026-05-05'], [100, 110])
    expect(transform(candles)).toHaveLength(1) // 같은 주 → 주봉 1개
  })

  it('MONTH → serverInterval=DAY, transform=aggregateToMonth', () => {
    const { serverInterval, transform } = resolveInterval('MONTH')
    expect(serverInterval).toBe('DAY')
    const candles = mkDayCandles(['2026-05-01', '2026-05-15'], [100, 150])
    expect(transform(candles)).toHaveLength(1)
  })

  it('YEAR → serverInterval=DAY, transform=aggregateToYear', () => {
    const { serverInterval, transform } = resolveInterval('YEAR')
    expect(serverInterval).toBe('DAY')
    const candles = mkDayCandles(['2026-01-01', '2026-06-30'], [100, 200])
    expect(transform(candles)).toHaveLength(1)
  })

  it('M5 → serverInterval=M5, transform=identity (백엔드 직접)', () => {
    const { serverInterval, transform } = resolveInterval('M5')
    expect(serverInterval).toBe('M5')
    const candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 5)
    expect(transform(candles)).toHaveLength(5)
  })

  it('M15 → serverInterval=M15, transform=identity (백엔드 직접)', () => {
    const { serverInterval, transform } = resolveInterval('M15')
    expect(serverInterval).toBe('M15')
    const candles = mkMinuteCandles(BASE_EPOCH, 15 * 60, 3)
    expect(transform(candles)).toHaveLength(3)
  })

  it('M30 → serverInterval=M30, transform=identity (백엔드 직접)', () => {
    const { serverInterval, transform } = resolveInterval('M30')
    expect(serverInterval).toBe('M30')
    const candles = mkMinuteCandles(BASE_EPOCH, 30 * 60, 2)
    expect(transform(candles)).toHaveLength(2)
  })

  it('H1 → serverInterval=H1, transform=identity (백엔드 직접)', () => {
    const { serverInterval, transform } = resolveInterval('H1')
    expect(serverInterval).toBe('H1')
    const candles = mkMinuteCandles(BASE_EPOCH, 60 * 60, 4)
    expect(transform(candles)).toHaveLength(4)
  })

  it('M1 → serverInterval=M1, transform=identity', () => {
    const { serverInterval, transform } = resolveInterval('M1')
    expect(serverInterval).toBe('M1')
    const candles = mkMinuteCandles(BASE_EPOCH, 60, 10)
    expect(transform(candles)).toHaveLength(10)
  })

  it('M10 → serverInterval=M10, transform=identity (백엔드 직접)', () => {
    const { serverInterval, transform } = resolveInterval('M10')
    expect(serverInterval).toBe('M10')
    const candles = mkMinuteCandles(BASE_EPOCH, 10 * 60, 6)
    expect(transform(candles)).toHaveLength(6)
  })

  it('TICK → serverInterval=TICK, transform=[]', () => {
    const { serverInterval, transform } = resolveInterval('TICK')
    expect(serverInterval).toBe('TICK')
    const candles = mkMinuteCandles(BASE_EPOCH, 1, 5)
    expect(transform(candles)).toHaveLength(0)
  })

  it('자유 입력 "45m" → serverInterval="45m", transform=identity', () => {
    const { serverInterval, transform } = resolveInterval('45m' as Parameters<typeof resolveInterval>[0])
    expect(serverInterval).toBe('45m')
    const candles = mkMinuteCandles(BASE_EPOCH, 45 * 60, 4)
    expect(transform(candles)).toHaveLength(4)
  })

  it('자유 입력 "12m" → serverInterval="12m", transform=identity', () => {
    const { serverInterval, transform } = resolveInterval('12m' as Parameters<typeof resolveInterval>[0])
    expect(serverInterval).toBe('12m')
    const candles = mkMinuteCandles(BASE_EPOCH, 12 * 60, 3)
    expect(transform(candles)).toHaveLength(3)
  })
})
