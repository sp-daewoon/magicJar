/**
 * useAccountSummary — 계좌 요약 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * GET /api/account/summary?userId=&accountType=
 * Dashboard 헤더용 — 총 평가금액·예수금 D+2·평가손익·수익률·보유 종목 수.
 *
 * - 30s polling fallback (refetchInterval) — STOMP push (useStompAccountBalance) 가 primary
 * - userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 * - mock 합성 0 — 실패 시 isError 노출 → UI "데이터 없음" 한국어
 *
 * C-3 polling → push 전환 (2026-05-20): refetchInterval 5s → 30s, staleTime 3s → 25s.
 *   "소켓방식으로 가져와야하는거" (사용자 dogfooding). 5s polling KIS rate limit burst 해소.
 *   STOMP /topic/account.balance.{userId} 가 주문체결·취소·거절 시 즉시 invalidate 트리거.
 *   30s polling 은 STOMP 끊김 시 fallback only.
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
  /** 자동 갱신 주기 ms — 기본 30s (STOMP push primary, polling fallback) */
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
    staleTime: 25_000, // 25s — 30s refetch 직전까지 fresh (STOMP invalidate 시 즉시 stale)
    refetchInterval,
    refetchIntervalInBackground: false, // A-3: background tab 시 pause (v5 default 명시화)
    refetchOnWindowFocus: true,
  })
}
