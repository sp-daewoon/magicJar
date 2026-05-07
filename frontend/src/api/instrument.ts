/**
 * 종목 마스터 REST API 클라이언트 (T-IM1)
 *
 * dispatch-spec §5-3 REST 시그니처 LOCK 정합:
 * - GET /api/instruments/search?q=&limit=&market=
 * - GET /api/instruments/{code}
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// Response 타입 정의 (dispatch-spec §5-7 예시 페이로드 정합)
// ──────────────────────────────────────────────

/** 종목 검색 결과 단건 DTO */
export interface InstrumentSearchResultDto {
  code: string
  name: string
  market: string
  sector: string | null
  score: number
}

/** GET /api/instruments/search 응답 */
export interface InstrumentSearchResponseDto {
  query: string
  results: InstrumentSearchResultDto[]
  totalMatched: number
  limit: number
}

/** GET /api/instruments/{code} 응답 */
export interface InstrumentDetailDto {
  code: string
  isin: string | null
  name: string
  nameEn: string | null
  market: string
  type: string
  sector: string | null
  theme: string | null
  listedAt: string | null
  delistedAt: string | null
  active: boolean
  aliases: { alias: string; source: string }[]
}

// ──────────────────────────────────────────────
// axios API 함수
// ──────────────────────────────────────────────

export const instrumentApi = {
  /**
   * GET /api/instruments/search — 자동완성·검색
   * @param q 검색어 (1자 이상)
   * @param limit 결과 수 (default 10, max 20)
   * @param market 시장 필터 (optional: KOSPI·KOSDAQ·KONEX)
   */
  search: async (
    q: string,
    limit = 10,
    market?: string,
  ): Promise<InstrumentSearchResultDto[]> => {
    const params: Record<string, string | number> = { q, limit }
    if (market) params['market'] = market
    const res = await apiClient.get<InstrumentSearchResponseDto>('/instruments/search', { params })
    return res.data.results
  },

  /**
   * GET /api/instruments/{code} — 단일 종목 상세
   * @param code 종목코드 6자리
   */
  getByCode: async (code: string): Promise<InstrumentDetailDto> => {
    const res = await apiClient.get<InstrumentDetailDto>(`/instruments/${encodeURIComponent(code)}`)
    return res.data
  },
}
