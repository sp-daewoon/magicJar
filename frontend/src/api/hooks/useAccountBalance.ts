/**
 * useAccountBalance — 계좌 잔고 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-3)
 *
 * GET /api/account/balance?userId=&accountType=
 * KIS inquire-balance output2 11 필드 — 예수금·평가금·평가손익 등.
 *
 * - 5s auto-refresh (C-3 자본 시각화 즉시성 — backend Redis cache TTL 5s 정합)
 * - userId 없으면 쿼리 비활성
 *
 * C-3 (2026-05-20): refetchInterval 30s → 5s, staleTime 25s → 3s.
 *   "돈 관련된건 실시간 정보를 가져와야지" (사용자 dogfooding).
 *   backend 5s cache TTL이라 5s polling은 1번 backend hit + 4번 frontend cache hit —
 *   KIS rate limit 영향 최소.
 */
import { useQuery } from '@tanstack/react-query'
import {
  accountApi,
  type AccountBalanceDto,
  type AccountType,
} from '../account'

interface UseAccountBalanceOptions {
  /** ADR 0089 Phase 2 — userId number */
  userId: number | null | undefined
  accountType?: AccountType
  refetchInterval?: number
  enabled?: boolean
}

export function useAccountBalance({
  userId,
  accountType = 'REAL',
  refetchInterval = 5_000,
  enabled = true,
}: UseAccountBalanceOptions) {
  return useQuery<AccountBalanceDto, Error>({
    queryKey: ['account', 'balance', userId, accountType],
    queryFn: () => accountApi.getBalance(userId!, accountType),
    enabled: enabled && userId !== null && userId !== undefined,
    staleTime: 3_000,
    refetchInterval,
    refetchOnWindowFocus: true,
  })
}
