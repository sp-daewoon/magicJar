/**
 * 캔들 집계 — 백엔드는 D1을 영속, 주봉/월봉/연봉은 클라이언트에서 합성.
 *
 * 분봉 합성 정책 (분봉 아키 v2):
 *   - M1·M5·M15·M30·H1: 백엔드가 직접 제공 (DB 또는 합성) → 클라이언트 변환 없음.
 *   - W1·MO1·Y1: D1 → 클라이언트 집계 (D1은 영속이므로 단일 진실 원천 유지).
 *
 * OHLCV 합성 규칙:
 *   - open: 첫 캔들 open
 *   - close: 마지막 캔들 close
 *   - high: max(high)
 *   - low: min(low)
 *   - volume: sum(volume)
 *   - time: 그룹 키 (W=ISO 주의 월요일, MO=해당 월 1일, Y=해당 년 1월 1일)
 */
import type { CandleDto } from '@/api/types'

export type ClientInterval =
  | 'DAY'
  | 'WEEK'
  | 'MONTH'
  | 'YEAR'
  | 'M1'
  | 'M5'
  | 'M10'
  | 'M15'
  | 'M30'
  | 'H1'
  | 'TICK'
  | `${number}m`  // 자유 입력 분봉 (예: '12m', '45m')

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
    case 'M10':
    case 'M15':
    case 'M30':
    case 'H1':
      // 분봉 아키 v2: 백엔드가 모든 분봉 단위를 직접 제공 → identity transform
      return { serverInterval: client, transform: (c) => c }
    case 'TICK':
      // 틱은 candle endpoint 호출 안 함 — 별도 STOMP 스트림 (RealtimeTickChart)
      return { serverInterval: 'TICK', transform: () => [] }
    default: {
      // 자유 입력 분봉 (예: '12m', '45m') — Xm 형식 그대로 백엔드에 전달
      const match = (client as string).match(/^(\d+)m$/)
      if (match) {
        return { serverInterval: client, transform: (c) => c }
      }
      // 알 수 없는 interval — DAY fallback
      return { serverInterval: 'DAY', transform: (c) => c }
    }
  }
}
