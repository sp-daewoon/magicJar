/**
 * Instrument API 클라이언트
 * GET /api/instruments/{code}  — 종목 단건 조회
 * GET /api/instruments/{code}/candles  — 캔들 조회 (미구현 시 mock fallback)
 * GET /api/instruments/{code}/candles?before=<cursor>&limit=<N>  — infinite scroll (T19-T4)
 * GET /api/instruments/{code}/quote  — 현재 시세 + stale/source/session 조회 (R3)
 * GET /api/instruments/{code}/detail  — 종목 상세 메타 (T20-T5)
 * GET /api/instruments/search?q=...&limit=N  — 종목 검색 자동완성
 * GET /api/market/index?codes=KOSPI,KOSDAQ  — 지수 조회 (T21-T3)
 */
import { apiClient } from './client'
import type { CandleDto, Instrument, InstrumentDetail, QuoteResponse, SearchResult } from './types'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

export const instrumentsApi = {
  /** 종목 단건 조회 */
  get: (code: string) => apiClient.get<Instrument>(`/instruments/${code.toUpperCase()}`),

  /**
   * 종목 검색 자동완성 — GET /api/instruments/search?q=...&limit=N.
   * T3 R1에서 이미 구현된 endpoint.
   */
  search: async (q: string, limit = 10): Promise<SearchResult[]> => {
    if (!q || q.length < 1) return []
    try {
      const params = new URLSearchParams({ q, limit: String(limit) })
      return await apiClient.get<SearchResult[]>(`/instruments/search?${params}`)
    } catch {
      console.warn('[instruments] search API unavailable, using mock')
      return generateMockSearchResults(q)
    }
  },

  /**
   * 종목 상세 메타 — GET /api/instruments/{code}/detail (T20-T5 신규).
   * 실패 시 mock fallback 반환 (backend 미머지 상태에서도 UI 검증 가능).
   */
  detail: async (code: string): Promise<InstrumentDetail> => {
    try {
      return await apiClient.get<InstrumentDetail>(`/instruments/${code.toUpperCase()}/detail`)
    } catch {
      console.warn('[instruments] detail API unavailable, using mock')
      return generateMockInstrumentDetail(code)
    }
  },

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

/** Mock 종목 검색 결과 — search API 미구현 시 사용 */
export function generateMockSearchResults(q: string): SearchResult[] {
  const ALL_STOCKS = [
    { code: '005930', name: '삼성전자', market: 'KOSPI' },
    { code: '000660', name: 'SK하이닉스', market: 'KOSPI' },
    { code: '035720', name: '카카오', market: 'KOSPI' },
    { code: '005380', name: '현대차', market: 'KOSPI' },
    { code: '051910', name: 'LG화학', market: 'KOSPI' },
    { code: '035420', name: 'NAVER', market: 'KOSPI' },
    { code: '096770', name: 'SK이노베이션', market: 'KOSPI' },
    { code: '006400', name: '삼성SDI', market: 'KOSPI' },
    { code: '373220', name: 'LG에너지솔루션', market: 'KOSPI' },
    { code: '207940', name: '삼성바이오로직스', market: 'KOSPI' },
    { code: '263750', name: '펄어비스', market: 'KOSDAQ' },
    { code: '293490', name: '카카오게임즈', market: 'KOSDAQ' },
    { code: '112040', name: '위메이드', market: 'KOSDAQ' },
    { code: '095340', name: 'ISC', market: 'KOSDAQ' },
    { code: '039200', name: '오스코텍', market: 'KOSDAQ' },
  ] as SearchResult[]

  const lower = q.toLowerCase()
  return ALL_STOCKS.filter(
    (s) =>
      s.code.includes(q) ||
      s.name.toLowerCase().includes(lower)
  ).slice(0, 10)
}

/** Mock 종목 상세 메타 — detail API 미구현 시 사용 */
export function generateMockInstrumentDetail(code: string): InstrumentDetail {
  const MOCK_DETAILS: Record<string, Partial<InstrumentDetail>> = {
    '005930': {
      name: '삼성전자',
      market: 'KOSPI',
      sector: '전기·전자',
      industry: '반도체',
      representativeName: '이재용',
      marketCap: 450_000_000_000_000,
      sharesOutstanding: 7_780_000_000,
      foreignRatio: 0.537,
      institutionRatio: 0.221,
      revenue: 79_400_000_000_000,
      operatingIncome: 6_600_000_000_000,
      netIncome: 5_400_000_000_000,
      per: 22.4,
      pbr: 1.3,
      eps: 3_166,
      bps: 53_428,
      dividendYield: 0.024,
      majorShareholderRatio: 0.234,
    },
    '000660': {
      name: 'SK하이닉스',
      market: 'KOSPI',
      sector: '전기·전자',
      industry: '반도체',
      representativeName: '곽노정',
      marketCap: 112_000_000_000_000,
      sharesOutstanding: 728_000_000,
      foreignRatio: 0.498,
      institutionRatio: 0.189,
      revenue: 34_000_000_000_000,
      operatingIncome: 6_900_000_000_000,
      netIncome: 5_200_000_000_000,
      per: 18.6,
      pbr: 1.7,
      eps: 7_146,
      bps: 88_960,
      dividendYield: 0.011,
      majorShareholderRatio: 0.205,
    },
  }

  const base: InstrumentDetail = {
    code,
    name: MOCK_DETAILS[code]?.name ?? code,
    market: MOCK_DETAILS[code]?.market ?? 'KOSPI',
    sector: MOCK_DETAILS[code]?.sector ?? null,
    industry: MOCK_DETAILS[code]?.industry ?? null,
    representativeName: MOCK_DETAILS[code]?.representativeName ?? null,
    marketCap: MOCK_DETAILS[code]?.marketCap ?? null,
    sharesOutstanding: MOCK_DETAILS[code]?.sharesOutstanding ?? null,
    foreignRatio: MOCK_DETAILS[code]?.foreignRatio ?? null,
    institutionRatio: MOCK_DETAILS[code]?.institutionRatio ?? null,
    revenue: MOCK_DETAILS[code]?.revenue ?? null,
    operatingIncome: MOCK_DETAILS[code]?.operatingIncome ?? null,
    netIncome: MOCK_DETAILS[code]?.netIncome ?? null,
    per: MOCK_DETAILS[code]?.per ?? null,
    pbr: MOCK_DETAILS[code]?.pbr ?? null,
    eps: MOCK_DETAILS[code]?.eps ?? null,
    bps: MOCK_DETAILS[code]?.bps ?? null,
    dividendYield: MOCK_DETAILS[code]?.dividendYield ?? null,
    majorShareholderRatio: MOCK_DETAILS[code]?.majorShareholderRatio ?? null,
  }

  return base
}
