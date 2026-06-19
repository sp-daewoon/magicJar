/**
 * useScaleInPlan — 분할 매수 계획 조회 hook (ADR 0128 R3)
 *
 * GET /api/strategies/{id}/scalein-plan?currentPrice=
 *   - 회차별 목표 수량·진입 조건 + 예상 손절가 + floor 알람선을 반환한다.
 *   - 분할이 진행되며 평단이 바뀌면(보유 변동) 갱신되어야 하므로 8초 폴링.
 *     useStrategyPosition(8초)와 같은 주기로 보유 변동에 따른 손절가·목표 수량 동기화.
 *   - background tab 폴링 pause (과도 호출 회피).
 *   - 분할 매수 미설정 전략은 서버가 400 → enabled 게이트(scaleIn 있을 때만)로 호출 차단.
 *     currentPrice를 명시하면 라이브 현재가로 미진입 미리보기 목표 수량을 즉시 계산.
 * ADR 0009 TanStack Query 패턴 정합. 룰 5: LLM provider import 0건.
 */
import { useQuery } from '@tanstack/react-query'
import { strategyPositionApi, type ScaleInPlanDto } from '../strategyPosition'

/** currentPrice는 라운딩해 쿼리 키 변동(=재요청 폭주)을 줄인다 */
const SCALE_IN_PLAN_KEY = (id: string, currentPrice?: number | null) =>
  ['scalein-plan', id, currentPrice ?? null] as const

/** 8초 폴링 — useStrategyPosition과 동일 주기로 보유(평단·회차) 변동 동기화 */
export const SCALE_IN_PLAN_POLL_MS = 8 * 1000

/**
 * 분할 매수 계획 조회. strategyId 없거나 분할 미설정(enabled=false)이면 비활성.
 *
 * @param strategyId 전략 ID
 * @param enabled    분할 매수 설정 여부 게이트 — false면 400 호출 회피
 * @param currentPrice 미진입 미리보기 시 목표 수량 산정 기준가(라이브 현재가). 생략 시 서버 폴백
 */
export function useScaleInPlan(
  strategyId: string | undefined,
  enabled = true,
  currentPrice?: number | null,
) {
  return useQuery<ScaleInPlanDto>({
    queryKey: SCALE_IN_PLAN_KEY(strategyId ?? '', currentPrice),
    queryFn: () => strategyPositionApi.getScaleInPlan(strategyId!, currentPrice),
    enabled: !!strategyId && enabled,
    staleTime: 0,
    refetchInterval: SCALE_IN_PLAN_POLL_MS,
    refetchIntervalInBackground: false,
  })
}
