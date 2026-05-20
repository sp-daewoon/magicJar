/**
 * useAccountPositions — KIS 실시간 보유 종목 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * GET /api/account/positions?userId=&accountType=
 * KIS inquire-balance output1 — 보유 종목별 현재가·평가금·손익 (실시간 KIS 시세).
 *
 * ⚠ 주의: 기존 `usePositions` (../api/hooks/usePositions)는 DB `position` 테이블 조회.
 *   본 hook은 KIS 실시간 응답 — 두 데이터 출처가 다름. (spec §2-3 evidence)
 *
 * - 30s polling fallback — STOMP push (useStompAccountBalance) 가 primary
 * - userId 없으면 쿼리 비활성
 *
 * C-3 polling → push 전환 (2026-05-20): refetchInterval 5s → 30s, staleTime 3s → 25s.
 *   "소켓방식으로 가져와야하는거" (사용자 dogfooding). 5s polling KIS rate limit burst 해소.
 *   STOMP /topic/account.balance.{userId} 가 주문체결·취소·거절 시 즉시 invalidate 트리거.
 *   30s polling 은 STOMP 끊김 시 fallback only.
 *
 * A-3 (2026-05-20): refetchIntervalInBackground=false 명시화 — TanStack Query v5
 *   default이지만 의도 명시 (background tab 시 polling pause).
 */
import { useQuery } from '@tanstack/react-query'
import {
  accountApi,
  type AccountHoldingDto,
  type AccountType,
} from '../account'

interface UseAccountPositionsOptions {
  /** ADR 0089 Phase 2 — userId number */
  userId: number | null | undefined
  accountType?: AccountType
  refetchInterval?: number
  enabled?: boolean
}

export function useAccountPositions({
  userId,
  accountType = 'REAL',
  refetchInterval = 30_000,
  enabled = true,
}: UseAccountPositionsOptions) {
  return useQuery<AccountHoldingDto[], Error>({
    queryKey: ['account', 'positions', userId, accountType],
    queryFn: () => accountApi.getPositions(userId!, accountType),
    enabled: enabled && userId !== null && userId !== undefined,
    staleTime: 25_000,
    refetchInterval,
    refetchIntervalInBackground: false, // A-3: background tab 시 pause (v5 default 명시화)
    refetchOnWindowFocus: true,
  })
}
