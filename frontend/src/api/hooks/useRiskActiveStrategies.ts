/**
 * useRiskActiveStrategies — 거래 빈도(D) 콤보박스 후보 전략 hook
 *
 * GET /api/risk/usage/active-strategies?userId=
 *   - 오늘(KST) 시그널/거래가 1건 이상 있은 전략만 후보로 내려준다.
 *   - ACTIVE(enabled) 여부와 무관 — PAUSE 한 전략도 오늘 거래했으면 포함된다.
 *     (기존엔 useStrategies + filter(enabled) 로 ACTIVE 전략만 보여 오늘 거래 후
 *      PAUSE 하면 콤보에서 사라지는 문제가 있었음 → 이 hook 으로 교체)
 *   - 빈 배열이면 "오늘 활동한 전략 없음" — mock 합성 금지.
 *
 * 거래 빈도 사용량은 장중 자주 바뀌므로 후보 목록도 10초 polling 으로
 * 동기화한다(오늘 새로 거래한 전략이 늦지 않게 등장).
 *
 * ADR 0009 TanStack Query 패턴 정합
 * 룰 5: LLM provider import 0건
 */
import { useQuery } from '@tanstack/react-query'
import { riskUsageApi, type ActiveStrategy } from '../riskUsage'

/** 후보 목록 polling — 거래 빈도 게이지(useRiskUsage)와 동일 주기 */
export const RISK_ACTIVE_STRATEGIES_POLL_MS = 10 * 1000

const RISK_ACTIVE_STRATEGIES_KEY = (userId: number | string) =>
  ['risk-active-strategies', userId] as const

export function useRiskActiveStrategies(userId: number | null | undefined) {
  return useQuery<ActiveStrategy[]>({
    queryKey: RISK_ACTIVE_STRATEGIES_KEY(userId ?? ''),
    queryFn: () => riskUsageApi.getActiveStrategies(userId!),
    enabled: userId !== null && userId !== undefined,
    refetchInterval: RISK_ACTIVE_STRATEGIES_POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 0,
  })
}
