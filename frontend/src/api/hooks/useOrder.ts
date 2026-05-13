/**
 * useOrder — 주문 단건 조회 훅 (T-O Phase 3 — ADR 0068 §16)
 *
 * GET /api/orders/{id}
 * 반환: request + executions N건 + audit_events 시계열
 */
import { useQuery } from '@tanstack/react-query'
import { orderApi, type OrderDetailDto } from '../order'

export function useOrder(id: number | null | undefined) {
  return useQuery<OrderDetailDto, Error>({
    queryKey: ['orders', id],
    queryFn: () => orderApi.get(id!),
    enabled: id != null && id > 0,
    staleTime: 30_000,
  })
}
