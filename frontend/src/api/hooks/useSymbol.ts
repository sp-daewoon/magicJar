/**
 * useSymbol — 종목 메타·현재가 TanStack Query hook
 *
 * spec §6-3 LOCK:
 * - queryKey: ['symbol', code]
 * - refetchInterval: 5000ms — STOMP 미작동 시 polling fallback
 * - staleTime: 1000ms
 *
 * ADR 0014: stale 배지 — data.stale 확인 후 UI 표시
 */
import { useQuery } from '@tanstack/react-query'
import { symbolApi } from '../symbol'

export function useSymbol(code: string) {
  return useQuery({
    queryKey: ['symbol', code] as const,
    queryFn: () => symbolApi.getSymbol(code),
    refetchInterval: 5000, // STOMP 미연결 시 5s polling fallback
    staleTime: 1000,
    enabled: Boolean(code),
    retry: 2,
    retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 10000),
  })
}
