/**
 * useOrders — 주문 목록 조회 훅 (T-O Phase 3 — ADR 0068 §16)
 *
 * GET /api/orders?status=&side=&code=&from=&to=&page=&size=
 *
 * 기능:
 * - 필터 파라미터 변경 시 자동 재조회
 * - 빈 목록 graceful (feedback_no_mock_data 정합)
 */
import { useQuery } from '@tanstack/react-query'
import { orderApi, type OrderListParams, type OrderListPage } from '../order'

export interface UseOrdersOptions extends OrderListParams {
  enabled?: boolean
}

export function useOrders(params: UseOrdersOptions = {}) {
  const { enabled = true, ...queryParams } = params

  return useQuery<OrderListPage, Error>({
    queryKey: ['orders', queryParams],
    queryFn: () => orderApi.list(queryParams),
    enabled,
    staleTime: 30_000,
  })
}
