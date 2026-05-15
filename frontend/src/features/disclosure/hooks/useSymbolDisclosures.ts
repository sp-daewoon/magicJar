/**
 * useSymbolDisclosures — 종목별 공시 TanStack Query hook
 *
 * ADR 0075 §6-2 polling interval LOCK:
 *   refetchInterval: 60_000 (60초)
 *
 * STOMP 0 LOCK (§6 사용자 결정 6-1): STOMP client import 0.
 * 종목 상세 페이지 unmount 시 polling 자동 stop (queryClient cleanup).
 *
 * 기본 조회 기간: 최근 7일 (from = T-7일 YYYY-MM-DD)
 */
import { useQuery } from '@tanstack/react-query'
import { disclosureApi, type SymbolDisclosureParams } from '../api/disclosureApi'

/** 7일 전 YYYY-MM-DD 문자열 계산 */
function sevenDaysAgoDate(): string {
  const d = new Date()
  d.setDate(d.getDate() - 7)
  return d.toISOString().slice(0, 10)
}

export function useSymbolDisclosures(code: string, params: SymbolDisclosureParams = {}) {
  return useQuery({
    queryKey: ['symbolDisclosures', code, params] as const,
    queryFn: () =>
      disclosureApi.getDisclosures(code, {
        from: params.from ?? sevenDaysAgoDate(),
        ...params,
      }),
    // ADR 0075 §6-2 LOCK: 60s polling
    refetchInterval: 60_000,
    staleTime: 30_000,
    enabled: Boolean(code),
    retry: 1,
    retryDelay: 3000,
  })
}
