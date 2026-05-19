/**
 * useAccountSummary — 계좌 요약 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * GET /api/account/summary?userId=&accountType=
 * Dashboard 헤더용 — 총 평가금액·예수금 D+2·평가손익·수익률·보유 종목 수.
 *
 * - 30s auto-refresh (refetchInterval) — Redis TTL 30s 정합 (spec §4-4)
 * - userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 * - mock 합성 0 — 실패 시 isError 노출 → UI "데이터 없음" 한국어
 */
import { useQuery } from '@tanstack/react-query'
import {
  accountApi,
  type AccountSummaryDto,
  type AccountType,
} from '../account'

interface UseAccountSummaryOptions {
  /** ADR 0089 Phase 2 — userId number (backend Long) */
  userId: number | null | undefined
  accountType?: AccountType
  /** 자동 갱신 주기 ms — 기본 30s (Redis TTL 정합) */
  refetchInterval?: number
  enabled?: boolean
}

export function useAccountSummary({
  userId,
  accountType = 'REAL',
  refetchInterval = 30_000,
  enabled = true,
}: UseAccountSummaryOptions) {
  return useQuery<AccountSummaryDto, Error>({
    queryKey: ['account', 'summary', userId, accountType],
    queryFn: () => accountApi.getSummary(userId!, accountType),
    enabled: enabled && userId !== null && userId !== undefined,
    staleTime: 25_000, // 25s — 30s refetch 직전까지 fresh
    refetchInterval,
    refetchOnWindowFocus: true,
  })
}
