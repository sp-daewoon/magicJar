/**
 * 캔들 집계 — 백엔드는 D1만 시드되어 있으므로 클라이언트에서 W1/MO1/Y1을 합성.
 * 분봉 합성:
 *   - W/MO/Y: D1 → W/MO/Y (기존)
 *   - M15: M5 → M15 (15분 그룹)
 *   - M30: M5 → M30 (30분 그룹) — 또는 캐시 hit 시 서버 M30 직접 사용
 *   - H1:  M5 → H1  (60분 그룹)
 *
 * OHLCV 합성 규칙:
 *   - open: 첫 캔들 open
 *   - close: 마지막 캔들 close
 *   - high: max(high)
 *   - low: min(low)
 *   - volume: sum(volume)
 *   - time:
 *     - D1 기반: 그룹 키 (W=ISO 주의 월요일, MO=해당 월 1일, Y=해당 년 1월 1일)
 *     - 분봉 기반: 그룹의 첫 캔들 time (epoch second) — LWC UTCTimestamp
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

// ---- 분봉 합성 (epoch second 기반) ----

/**
 * 분봉 epoch second 기반 집계.
 * epoch second를 intervalMinutes 간격으로 버림(floor) → 그룹 키 = floor된 epoch second.
 * LWC UTCTimestamp(number) 그대로 time 필드에 반환.
 */
function aggregateMinutes(candles: CandleDto[], intervalMinutes: number): CandleDto[] {
  if (candles.length === 0) return []

  const intervalSec = intervalMinutes * 60
  const groups = new Map<number, CandleDto[]>()

  for (const c of candles) {
    const epochSec = typeof c.time === 'number' ? c.time : parseInt(String(c.time), 10)
    if (isNaN(epochSec)) continue
    const key = Math.floor(epochSec / intervalSec) * intervalSec
    const arr = groups.get(key) ?? []
    arr.push(c)
    groups.set(key, arr)
  }

  const out: CandleDto[] = []
  for (const [key, group] of [...groups.entries()].sort(([a], [b]) => a - b)) {
    if (group.length === 0) continue
    out.push({
      time: key, // epoch second (LWC UTCTimestamp)
      open: group[0].open,
      close: group[group.length - 1].close,
      high: group.reduce((m, c) => Math.max(m, c.high), group[0].high),
      low: group.reduce((m, c) => Math.min(m, c.low), group[0].low),
      volume: group.reduce((s, c) => s + c.volume, 0),
    })
  }
  return out
}

/**
 * M5 → M15 합성 (5분봉 3개 → 15분봉 1개).
 * 캔들 정책: M15는 합성 only (DB 저장 X).
 */
export function aggregateToM15(candles: CandleDto[]): CandleDto[] {
  return aggregateMinutes(candles, 15)
}

/**
 * M5 → M30 합성 (5분봉 6개 → 30분봉 1개).
 * 캔들 정책: M30은 선택 — 캐시 miss 시 합성, 캐시 hit 시 서버 M30 직접 사용.
 */
export function aggregateToM30(candles: CandleDto[]): CandleDto[] {
  return aggregateMinutes(candles, 30)
}

/**
 * M5 → H1 합성 (5분봉 12개 → 60분봉 1개).
 * 캔들 정책: H1은 합성 only (DB 저장 X).
 */
export function aggregateToH1(candles: CandleDto[]): CandleDto[] {
  return aggregateMinutes(candles, 60)
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
      // M1·M5는 서버에서 직접 가져옴 (raw)
      return { serverInterval: client, transform: (c) => c }
    case 'M15':
      // M15: M5 취득 후 클라 합성 (캔들 정책: 저장 X)
      return { serverInterval: 'M5', transform: aggregateToM15 }
    case 'M30':
      // M30: M5 취득 후 클라 합성 (또는 서버 M30 캐시 — 여기서는 M5 합성 우선)
      return { serverInterval: 'M5', transform: aggregateToM30 }
    case 'H1':
      // H1: M5 취득 후 클라 합성 (캔들 정책: 저장 X)
      return { serverInterval: 'M5', transform: aggregateToH1 }
    case 'TICK':
      // 틱은 candle endpoint 호출 안 함 — 별도 STOMP 스트림 (RealtimeTickChart)
      return { serverInterval: 'TICK', transform: () => [] }
  }
}
