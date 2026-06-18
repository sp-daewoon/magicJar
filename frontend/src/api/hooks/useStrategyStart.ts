/**
 * 전략 시작 화면 hooks (spec 2026-06-18 §4.2)
 *
 * hooks:
 *   useStrategyStart(userId)  — GET /api/strategies/start (검증 템플릿 + 내 전략 + 즐겨찾기)
 *   useToggleFavorite(userId) — POST/DELETE /api/strategies/{id}/favorite (localStorage 승격)
 *
 * userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합 — mock 합성 0)
 * ADR 0009 TanStack Query 패턴 정합
 * 룰 5: LLM provider import 0건
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { strategyStartApi, type StrategyStartResponse } from '../strategy'

export const STRATEGY_START_KEY = (userId: number) => ['strategy-start', userId] as const

/** 전략 시작 화면 조회 — 세 재료 단일 호출 */
export function useStrategyStart(userId: number | null | undefined) {
  return useQuery<StrategyStartResponse>({
    queryKey: STRATEGY_START_KEY(userId ?? 0),
    queryFn: () => strategyStartApi.getStart(userId!),
    enabled: userId !== null && userId !== undefined,
    staleTime: 60 * 1000,
  })
}

/** 즐겨찾기 토글 — on=true 추가(멱등) / false 해제, 성공 시 시작 화면 무효화 */
export function useToggleFavorite(userId: number | null | undefined) {
  const qc = useQueryClient()
  return useMutation<void, Error, { id: number; on: boolean }>({
    mutationFn: ({ id, on }) => {
      if (userId === null || userId === undefined) {
        return Promise.reject(new Error('사용자 정보가 없어 즐겨찾기를 변경할 수 없습니다'))
      }
      return on ? strategyStartApi.addFavorite(id, userId) : strategyStartApi.removeFavorite(id, userId)
    },
    onSuccess: () => {
      if (userId !== null && userId !== undefined) {
        void qc.invalidateQueries({ queryKey: STRATEGY_START_KEY(userId) })
      }
    },
  })
}
