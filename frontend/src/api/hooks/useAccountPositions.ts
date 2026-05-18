/**
 * useAccountPositions — KIS 실시간 보유 종목 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * GET /api/account/positions?userId=&accountType=
 * KIS inquire-balance output1 — 보유 종목별 현재가·평가금·손익 (실시간 KIS 시세).
 *
 * ⚠ 주의: 기존 `usePositions` (../api/hooks/usePositions)는 DB `position` 테이블 조회.
 *   본 hook은 KIS 실시간 응답 — 두 데이터 출처가 다름. (spec §2-3 evidence)
 *
 * - 30s auto-refresh (Redis TTL 30s 정합)
 * - userId 없으면 쿼리 비활성
 */
import { useQuery } from '@tanstack/react-query'
import {
  accountApi,
  type AccountHoldingDto,
  type AccountType,
} from '../account'

interface UseAccountPositionsOptions {
  userId: string | null | undefined
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
    enabled: enabled && !!userId,
    staleTime: 25_000,
    refetchInterval,
    refetchOnWindowFocus: true,
  })
}
