/**
 * Instrument API 클라이언트
 * GET /api/instruments/{code}  — 종목 단건 조회
 * GET /api/instruments/{code}/candles  — 캔들 조회 (미구현 시 mock fallback)
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
