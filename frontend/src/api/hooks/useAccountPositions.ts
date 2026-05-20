/**
 * useAccountPositions — KIS 실시간 보유 종목 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * GET /api/account/positions?userId=&accountType=
 * KIS inquire-balance output1 — 보유 종목별 현재가·평가금·손익 (실시간 KIS 시세).
 *
 * ⚠ 주의: 기존 `usePositions` (../api/hooks/usePositions)는 DB `position` 테이블 조회.
 *   본 hook은 KIS 실시간 응답 — 두 데이터 출처가 다름. (spec §2-3 evidence)
 *
 * - 5s auto-refresh (C-3 자본 시각화 즉시성 — backend Redis cache TTL 5s 정합)
 * - userId 없으면 쿼리 비활성
 *
 * C-3 (2026-05-20): refetchInterval 30s → 5s, staleTime 25s → 3s.
 *   "돈 관련된건 실시간 정보를 가져와야지" (사용자 dogfooding).
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
  refetchInterval = 5_000,
  enabled = true,
}: UseAccountPositionsOptions) {
  return useQuery<AccountHoldingDto[], Error>({
    queryKey: ['account', 'positions', userId, accountType],
    queryFn: () => accountApi.getPositions(userId!, accountType),
    enabled: enabled && userId !== null && userId !== undefined,
    staleTime: 3_000,
    refetchInterval,
    refetchIntervalInBackground: false, // A-3: background tab 시 pause (v5 default 명시화)
    refetchOnWindowFocus: true,
  })
}
