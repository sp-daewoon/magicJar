/**
 * useInstrumentDetail — 종목 상세 메타 조회 훅.
 * T20-T7: GET /api/instruments/{code}/detail
 *
 * - Redis 캐시 6시간 TTL (재무 정보는 자주 변경 X) → staleTime: 6h
 * - backend 미구현 시 mock fallback (instrumentsApi.detail 내부에서 처리)
 * - code 없으면 query 비활성화
 */
import { useQuery } from '@tanstack/react-query'
import { instrumentsApi } from '@/api/instruments'
import type { InstrumentDetail } from '@/api/types'

const SIX_HOURS = 6 * 60 * 60 * 1000

export function useInstrumentDetail(code: string | null | undefined) {
  return useQuery<InstrumentDetail>({
    queryKey: ['instrument-detail', code?.toUpperCase()],
    queryFn: () => instrumentsApi.detail(code!),
    enabled: !!code && code.length > 0,
    staleTime: SIX_HOURS,
    gcTime: SIX_HOURS,
    retry: 1,
  })
}
