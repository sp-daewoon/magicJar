/**
 * 캔들 집계 — 백엔드는 D1만 시드되어 있으므로 클라이언트에서 W1/MO1/Y1을 합성.
 * 분봉(M1/M5/M15/M30/H1)은 백엔드 Interval에 정의되어 있어 별도 API 호출.
 *
 * OHLCV 합성 규칙:
 *   - open: 첫 캔들 open
 *   - close: 마지막 캔들 close
 *   - high: max(high)
 *   - low: min(low)
 *   - volume: sum(volume)
 *   - time: 그룹 키 (W1=ISO 주의 월요일, MO1=해당 월 1일, Y1=해당 년 1월 1일)
 */
import type { CandleDto } from '@/api/types'

export type ClientInterval =
  | 'DAY'
  | 'WEEK'
  | 'MONTH'
  | 'YEAR'
  | 'M1'
  | 'M5'
  | 'M15'
  | 'M30'
  | 'H1'
  | 'TICK'

/** YYYY-MM-DD → Date(UTC). 'YYYY-MM-DD' 또는 epoch second 지원. */
function parseTime(time: string | number): Date {
  if (typeof time === 'number') return new Date(time * 1000)
  return new Date(`${time}T00:00:00Z`)
}

/** Date → 'YYYY-MM-DD' (UTC) */
function formatDate(d: Date): string {
  const y = d.getUTCFullYear()
  const m = String(d.getUTCMonth() + 1).padStart(2, '0')
  const day = String(d.getUTCDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 해당 날짜가 속한 ISO 주의 월요일 (UTC) */
function startOfIsoWeek(d: Date): Date {
  const date = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()))
  const dow = date.getUTCDay() || 7 // 일=0 → 7
  if (dow !== 1) date.setUTCDate(date.getUTCDate() - (dow - 1))
  return date
}

function startOfMonth(d: Date): Date {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1))
}

function startOfYear(d: Date): Date {
  return new Date(Date.UTC(d.getUTCFullYear(), 0, 1))
}

function aggregate(candles: CandleDto[], keyFn: (d: Date) => Date): CandleDto[] {
  const groups = new Map<string, CandleDto[]>()
  for (const c of candles) {
    const key = formatDate(keyFn(parseTime(c.time)))
    const arr = groups.get(key) ?? []
    arr.push(c)
    groups.set(key, arr)
  }

  const out: CandleDto[] = []
  for (const [key, group] of [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    if (group.length === 0) continue
    out.push({
      time: key,
      open: group[0].open,
      close: group[group.length - 1].close,
      high: group.reduce((m, c) => Math.max(m, c.high), group[0].high),
      low: group.reduce((m, c) => Math.min(m, c.low), group[0].low),
      volume: group.reduce((s, c) => s + c.volume, 0),
    })
  }
  return out
}

export function aggregateToWeek(candles: CandleDto[]): CandleDto[] {
  return aggregate(candles, startOfIsoWeek)
}

export function aggregateToMonth(candles: CandleDto[]): CandleDto[] {
  return aggregate(candles, startOfMonth)
}

export function aggregateToYear(candles: CandleDto[]): CandleDto[] {
  return aggregate(candles, startOfYear)
}

/** 클라이언트 interval → 백엔드 호출 interval + 후처리 변환기 */
export function resolveInterval(client: ClientInterval): {
  serverInterval: string
  transform: (candles: CandleDto[]) => CandleDto[]
} {
  switch (client) {
    case 'DAY':
      return { serverInterval: 'DAY', transform: (c) => c }
    case 'WEEK':
      return { serverInterval: 'DAY', transform: aggregateToWeek }
    case 'MONTH':
      return { serverInterval: 'DAY', transform: aggregateToMonth }
    case 'YEAR':
      return { serverInterval: 'DAY', transform: aggregateToYear }
    case 'M1':
    case 'M5':
    case 'M15':
    case 'M30':
    case 'H1':
      return { serverInterval: client, transform: (c) => c }
    case 'TICK':
      // 틱은 candle endpoint 호출 안 함 — 별도 STOMP 스트림 (RealtimeTickChart)
      return { serverInterval: 'TICK', transform: () => [] }
  }
}
