/**
 * Instrument API 클라이언트
 * GET /api/instruments/{code}  — 종목 단건 조회
 * GET /api/instruments/{code}/candles  — 캔들 조회
 * GET /api/instruments/{code}/candles?before=<cursor>&limit=<N>  — infinite scroll
 * GET /api/instruments/{code}/quote  — 현재 시세 + stale/source/session 조회
 * GET /api/instruments/{code}/detail  — 종목 상세 메타
 * GET /api/instruments/search?q=...&limit=N  — 종목 검색 자동완성
 */
import { apiClient } from './client'
import type {
  CandleDto,
  ChartCacheMeta,
  Instrument,
  InstrumentDetail,
  QuoteResponse,
  SearchResult,
} from './types'

/**
 * candlesPage 응답 — body + cache 메타 헤더 추출.
 *
 * 사용자 결정 2026-05-05: frontend가 cache 범위 알아 무작위 호출 차단.
 */
export interface CandlesPageResponse {
  candles: CandleDto[]
  cacheMeta: ChartCacheMeta | null
}

function parseCacheMeta(headers: Headers): ChartCacheMeta | null {
  const countRaw = headers.get('X-Cache-Count')
  if (countRaw == null) return null
  const count = Number.parseInt(countRaw, 10)
  if (Number.isNaN(count)) return null
  const firstRaw = headers.get('X-Cache-First')
  const lastRaw = headers.get('X-Cache-Last')
  return {
    firstEpochSec: firstRaw ? Number.parseInt(firstRaw, 10) : null,
    lastEpochSec: lastRaw ? Number.parseInt(lastRaw, 10) : null,
    count,
  }
}

export const instrumentsApi = {
  /** 종목 단건 조회 */
  get: (code: string) => apiClient.get<Instrument>(`/instruments/${code.toUpperCase()}`),

  /**
   * 종목 검색 자동완성 — GET /api/instruments/search?q=...&limit=N.
   * 실패 시 빈 배열 반환 (검색창 UX 유지).
   */
  search: async (q: string, limit = 10): Promise<SearchResult[]> => {
    if (!q || q.length < 1) return []
    try {
      const params = new URLSearchParams({ q, limit: String(limit) })
      return await apiClient.get<SearchResult[]>(`/instruments/search?${params}`)
    } catch {
      console.warn('[instruments] search API 응답 없음')
      return []
    }
  },

  /**
   * 종목 상세 메타 — GET /api/instruments/{code}/detail.
   * 실패 시 에러를 상위로 전파 (useQuery isError 처리).
   */
  detail: (code: string): Promise<InstrumentDetail> =>
    apiClient.get<InstrumentDetail>(`/instruments/${code.toUpperCase()}/detail`),

  /**
   * 현재 시세 조회 — stale/source/session 포함.
   * 실패 시 null 반환 (UI가 STOMP fallback 또는 스켈레톤 표시).
   */
  quote: async (code: string): Promise<QuoteResponse | null> => {
    try {
      return await apiClient.get<QuoteResponse>(`/instruments/${code.toUpperCase()}/quote`)
    } catch {
      console.warn('[instruments] quote API 응답 없음')
      return null
    }
  },

  /** 종목 캔들 조회 */
  candles: (code: string, interval = 'DAY', from?: string, to?: string): Promise<CandleDto[]> => {
    const params = new URLSearchParams({ interval })
    if (from) params.set('from', from)
    if (to) params.set('to', to)
    return apiClient.get<CandleDto[]>(`/instruments/${code.toUpperCase()}/candles?${params}`)
  },

  /**
   * infinite scroll용 캔들 조회.
   * GET /api/instruments/{code}/candles?interval=<I>&before=<cursor>&limit=<N>
   *
   * - before: cursor (D1=YYYY-MM-DD, 분봉=epoch second string). 없으면 최신부터.
   * - limit: 최대 응답 봉 수 (default 1000 — ADR 0050 §C-1, 사용자 결정 2026-05-05).
   * - 응답 길이 < limit 이면 더 이상 데이터 없음 (hasMore=false).
   *
   * 응답 헤더 X-Cache-First/Last/Count 파싱 — frontend가 cache 범위 추적.
   */
  candlesPage: async (
    code: string,
    interval: string,
    before?: string,
    limit = 1000,
  ): Promise<CandlesPageResponse> => {
    const params = new URLSearchParams({ interval, limit: String(limit) })
    if (before) params.set('before', before)
    const { body, headers } = await apiClient.getWithHeaders<CandleDto[]>(
      `/instruments/${code.toUpperCase()}/candles?${params}`,
    )
    return {
      candles: body,
      cacheMeta: parseCacheMeta(headers),
    }
  },
}
