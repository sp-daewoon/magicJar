/**
 * useStrategyPosition — 전략별 포지션 조회 hook (ADR 0110 · 분할매수 R3-a)
 *
 * GET /api/strategies/{id}/position
 *   - 실시간 평가손익(currentPrice·unrealizedPnl·unrealizedPnlPct, PR #1035) 주기 갱신을 위해
 *     8초 폴링(refetchInterval) — RiskUsageCards 10초 패턴 정합. 현재가는 장중 자주 변함.
 *   - background tab 에서는 polling pause (refetchIntervalInBackground=false) — 과도구독 회피.
 * ADR 0009 TanStack Query 패턴 정합.
 */
import { useQuery } from '@tanstack/react-query'
import { strategyPositionApi, type StrategyPositionDto } from '../strategyPosition'

const STRATEGY_POSITION_KEY = (id: string) => ['strategy-position', id] as const

/** 8초 폴링 — 평가손익(현재가)이 주기 갱신되게. 전략 카드 다수 시 부담 최소화한 합리적 간격 */
export const STRATEGY_POSITION_POLL_MS = 8 * 1000

/**
 * 전략별 포지션 조회. strategyId 없으면 비활성.
 */
export function useStrategyPosition(strategyId: string | undefined, enabled = true) {
  return useQuery<StrategyPositionDto[]>({
    queryKey: STRATEGY_POSITION_KEY(strategyId ?? ''),
    queryFn: () => strategyPositionApi.get(strategyId!),
    enabled: !!strategyId && enabled,
    // 폴링 주기(8초)로 평가손익 갱신 — staleTime 0 으로 매 주기 실제 재조회
    staleTime: 0,
    refetchInterval: STRATEGY_POSITION_POLL_MS,
    refetchIntervalInBackground: false,
  })
}
