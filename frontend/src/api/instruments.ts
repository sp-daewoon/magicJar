/**
 * Instrument API 클라이언트
 * GET /api/instruments/{code}  — 종목 단건 조회
 * GET /api/instruments/{code}/candles  — 캔들 조회 (미구현 시 mock fallback)
 * GET /api/instruments/{code}/candles?before=<cursor>&limit=<N>  — infinite scroll (T19-T4)
 * GET /api/instruments/{code}/quote  — 현재 시세 + stale/source/session 조회 (R3)
 */
import { apiClient } from './client'
import type { CandleDto, Instrument, QuoteResponse } from './types'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

export const instrumentsApi = {
  /** 종목 단건 조회 */
  get: (code: string) => apiClient.get<Instrument>(`/instruments/${code.toUpperCase()}`),

  /**
   * 현재 시세 조회 — stale/source/session 포함 (R3 GET /api/instruments/{code}/quote).
   * 실패 시 null 반환 (UI가 STOMP fallback 또는 스켈레톤 표시).
   */
  quote: async (code: string): Promise<QuoteResponse | null> => {
    try {
      return await apiClient.get<QuoteResponse>(`/instruments/${code.toUpperCase()}/quote`)
    } catch {
      console.warn('[instruments] quote API unavailable')
      return null
    }
  },

  /** 종목 캔들 조회 — 서버 미구현 시 mock fallback */
  candles: async (code: string, interval = 'DAY', from?: string, to?: string): Promise<CandleDto[]> => {
    if (USE_MOCK) {
      return generateMockCandles(code, 120)
    }
    try {
      const params = new URLSearchParams({ interval })
      if (from) params.set('from', from)
      if (to) params.set('to', to)
      return await apiClient.get<CandleDto[]>(`/instruments/${code.toUpperCase()}/candles?${params}`)
    } catch {
      // 404 또는 미구현(501) → mock fallback
      console.warn('[instruments] candles API unavailable, using mock data')
      return generateMockCandles(code, 120)
    }
  },

  /**
   * infinite scroll용 캔들 조회 (T19-T4 endpoint).
   * GET /api/instruments/{code}/candles?interval=<I>&before=<cursor>&limit=<N>
   *
   * - before: cursor (D1=YYYY-MM-DD, 분봉=epoch second string). 없으면 최신부터.
   * - limit: 최대 응답 봉 수 (기본 200).
   * - 응답 길이 < limit 이면 더 이상 데이터 없음 (hasMore=false).
   *
   * mock 모드에서는 before/limit 기반 슬라이싱으로 흉내냄.
   */
  candlesPage: async (
    code: string,
    interval: string,
    before?: string,
    limit = 200,
  ): Promise<CandleDto[]> => {
    if (USE_MOCK) {
      return generateMockCandlesPage(code, interval, before, limit)
    }
    try {
      const params = new URLSearchParams({ interval, limit: String(limit) })
      if (before) params.set('before', before)
      return await apiClient.get<CandleDto[]>(
        `/instruments/${code.toUpperCase()}/candles?${params}`,
      )
    } catch {
      console.warn('[instruments] candlesPage API unavailable, using mock data')
      return generateMockCandlesPage(code, interval, before, limit)
    }
  },
}

/** Mock 캔들 데이터 생성 — 실제 API 미연결 상태에서 차트 개발·검증용 */
export function generateMockCandles(code: string, count = 120): CandleDto[] {
  // 종목 코드에서 seed 추출 (재현 가능한 랜덤)
  const seed = code.split('').reduce((acc, c) => acc + c.charCodeAt(0), 0)
  const candles: CandleDto[] = []

  const basePrice = 50_000 + (seed % 30_000)
  let price = basePrice

  const today = new Date()
  today.setHours(0, 0, 0, 0)

  for (let i = count - 1; i >= 0; i--) {
    const date = new Date(today)
    date.setDate(date.getDate() - i)

    // 주말 건너뛰기
    const dow = date.getDay()
    if (dow === 0 || dow === 6) continue

    // 반복 가능한 의사 랜덤
    const rand = Math.sin((i + seed) * 9301.0 + 49297) * 0.5 + 0.5
    const rand2 = Math.sin((i + seed) * 233.0 + 1013) * 0.5 + 0.5

    const changeRate = (rand - 0.5) * 0.04 // ±2%
    const high = price * (1 + Math.abs(rand2) * 0.015)
    const low = price * (1 - Math.abs(1 - rand2) * 0.015)
    const open = price
    const close = price * (1 + changeRate)
    price = close

    const yyyy = date.getFullYear()
    const mm = String(date.getMonth() + 1).padStart(2, '0')
    const dd = String(date.getDate()).padStart(2, '0')

    candles.push({
      time: `${yyyy}-${mm}-${dd}`,
      open: Math.round(open),
      high: Math.round(Math.max(open, close, high)),
      low: Math.round(Math.min(open, close, low)),
      close: Math.round(close),
      volume: Math.round(1_000_000 + rand * 5_000_000),
    })
  }

  return candles.slice(-200) // 최대 200개
}

/**
 * infinite scroll mock 페이지 생성.
 * before cursor를 기준으로 이전 데이터 슬라이싱을 흉내냄.
 * - D1: before='YYYY-MM-DD' → 해당 날짜 이전 날짜만
 * - 분봉: before=epoch_second_string → 해당 시각 이전만
 */
function generateMockCandlesPage(
  code: string,
  interval: string,
  before: string | undefined,
  limit: number,
): CandleDto[] {
  const isMinute = interval !== 'DAY' && interval !== 'WEEK' && interval !== 'MONTH' && interval !== 'YEAR'

  if (isMinute) {
    // 분봉 mock: epoch second 기반 (5분 간격 1년치 ~105,000봉)
    const seed = code.split('').reduce((acc, c) => acc + c.charCodeAt(0), 0)
    const INTERVAL_SEC = 5 * 60 // M5 기준
    const now = Math.floor(Date.now() / 1000)
    // 최근 1년치 epoch 범위
    const oldest = now - 365 * 24 * 3600
    const newest = now

    const cursorEpoch = before ? parseInt(before, 10) : newest
    // cursorEpoch 이전에서 limit개 (최신부터 내림차순 → 오름차순으로 반환)
    const endEpoch = cursorEpoch - INTERVAL_SEC
    const startEpoch = Math.max(oldest, endEpoch - limit * INTERVAL_SEC)

    const candles: CandleDto[] = []
    let price = 50_000 + (seed % 30_000)
    for (let t = startEpoch; t <= endEpoch; t += INTERVAL_SEC) {
      const rand = Math.sin((t + seed) * 9301.0 + 49297) * 0.5 + 0.5
      const changeRate = (rand - 0.5) * 0.002
      const open = price
      const close = price * (1 + changeRate)
      price = close
      candles.push({
        time: t,
        open: Math.round(open),
        high: Math.round(Math.max(open, close) * 1.001),
        low: Math.round(Math.min(open, close) * 0.999),
        close: Math.round(close),
        volume: Math.round(100_000 + rand * 1_000_000),
      })
    }
    return candles.slice(-limit)
  } else {
    // 일봉 mock
    const all = generateMockCandles(code, 500)
    if (!before) return all.slice(-limit)
    const idx = all.findIndex((c) => String(c.time) >= before)
    const end = idx === -1 ? all.length : idx
    return all.slice(Math.max(0, end - limit), end)
  }
}
