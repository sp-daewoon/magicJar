/**
 * RiskEvent TanStack Query hooks (T-RISK Phase 3 — ADR 0067)
 *
 * hooks:
 *   useRiskEvents(params)  — useInfiniteQuery (무한 스크롤)
 *   useRiskEvent(id)       — useQuery (단건)
 *
 * ADR 0009 TanStack Query 패턴 정합 (useSignal 패턴 준용)
 * 룰 5: LLM provider import 0건
 */
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { riskApi, type RiskEventDto, type RiskEventListParams, type RiskEventPage } from '../risk'

const RISK_EVENT_LIST_KEY = (params: RiskEventListParams) =>
  ['risk-events', 'list', params] as const

const RISK_EVENT_KEY = (id: string) => ['risk-events', id] as const

/**
 * 리스크 이벤트 목록 (무한 스크롤)
 * page=0 → page=1 → ... 자동 누적
 */
export function useRiskEvents(params: Omit<RiskEventListParams, 'page'> = {}, size = 20) {
  return useInfiniteQuery<RiskEventPage, Error>({
    queryKey: RISK_EVENT_LIST_KEY({ ...params, size }),
    queryFn: ({ pageParam }) =>
      riskApi.listEvents({ ...params, size, page: pageParam as number }),
    initialPageParam: 0 as number,
    getNextPageParam: (lastPage) =>
      lastPage.last ? undefined : lastPage.page + 1,
    staleTime: 15 * 1000,
  })
}

/**
 * 리스크 이벤트 단건 조회
 */
export function useRiskEvent(id: string | undefined) {
  return useQuery<RiskEventDto>({
    queryKey: RISK_EVENT_KEY(id ?? ''),
    queryFn: () => riskApi.getEvent(id!),
    enabled: !!id,
    staleTime: 60 * 1000,
  })
}
