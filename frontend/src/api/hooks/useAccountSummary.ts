/**
 * useAccountSummary — 계좌 요약 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * GET /api/account/summary?userId=&accountType=
 * Dashboard 헤더용 — 총 평가금액·예수금 D+2·평가손익·수익률·보유 종목 수.
 *
 * - 5s auto-refresh (refetchInterval) — backend Redis TTL 5s 정합 (C-3 즉시성)
 * - userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 * - mock 합성 0 — 실패 시 isError 노출 → UI "데이터 없음" 한국어
 *
 * C-3 (2026-05-20): refetchInterval 30s → 5s, staleTime 25s → 3s.
 *   "돈 관련된건 실시간 정보를 가져와야지" (사용자 dogfooding).
 *
 * A-3 (2026-05-20): refetchIntervalInBackground=false 명시화 — TanStack Query v5
 *   default이지만 의도 명시 (background tab 시 polling pause → 불필요 KIS 호출 차단).
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
  /** 자동 갱신 주기 ms — 기본 5s (자본 시각화 즉시성) */
  refetchInterval?: number
  enabled?: boolean
}

export function useAccountSummary({
  userId,
  accountType = 'REAL',
  refetchInterval = 5_000,
  enabled = true,
}: UseAccountSummaryOptions) {
  return useQuery<AccountSummaryDto, Error>({
    queryKey: ['account', 'summary', userId, accountType],
    queryFn: () => accountApi.getSummary(userId!, accountType),
    enabled: enabled && userId !== null && userId !== undefined,
    staleTime: 3_000, // 3s — 5s refetch 직전까지 fresh
    refetchInterval,
    refetchIntervalInBackground: false, // A-3: background tab 시 pause (v5 default 명시화)
    refetchOnWindowFocus: true,
  })
}
