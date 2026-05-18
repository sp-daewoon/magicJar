/**
 * useAccountBalance — 계좌 잔고 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-3)
 *
 * GET /api/account/balance?userId=&accountType=
 * KIS inquire-balance output2 11 필드 — 예수금·평가금·평가손익 등.
 *
 * - 30s auto-refresh (Redis TTL 30s 정합)
 * - userId 없으면 쿼리 비활성
 */
import { useQuery } from '@tanstack/react-query'
import {
  accountApi,
  type AccountBalanceDto,
  type AccountType,
} from '../account'

interface UseAccountBalanceOptions {
  userId: string | null | undefined
  accountType?: AccountType
  refetchInterval?: number
  enabled?: boolean
}

export function useAccountBalance({
  userId,
  accountType = 'REAL',
  refetchInterval = 30_000,
  enabled = true,
}: UseAccountBalanceOptions) {
  return useQuery<AccountBalanceDto, Error>({
    queryKey: ['account', 'balance', userId, accountType],
    queryFn: () => accountApi.getBalance(userId!, accountType),
    enabled: enabled && !!userId,
    staleTime: 25_000,
    refetchInterval,
    refetchOnWindowFocus: true,
  })
}
