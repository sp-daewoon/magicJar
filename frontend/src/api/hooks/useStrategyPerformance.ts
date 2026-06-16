/**
 * 전략 성과 히스토리 + 메모 TanStack Query hooks (T-PERF R3 — ADR 0126)
 *
 *  - useStrategyPerformanceHistory(strategyId, userId, from, to) — 일자별 성과 행
 *  - useStrategyPerformanceSummary(strategyId, userId)           — 누적 요약 + 신뢰도 + 레짐별 승률
 *  - useStrategyNotes(strategyId, userId, from, to)              — 메모 타임라인
 *  - useUpsertStrategyNote()                                     — 메모 UPSERT (성공 시 history/summary/note invalidate)
 *
 * userId 없으면 비활성 (feedback_no_mock_data 정합 — mock 합성 0).
 * 성과/메모는 일배치(00:30)·사용자 입력으로만 바뀌므로 polling 없이 staleTime 보수적.
 *
 * ADR 0009 TanStack Query 패턴 정합. 룰 5: LLM provider import 0건.
 */
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  strategyPerformanceApi,
  type StrategyPerformanceHistory,
  type StrategyPerformanceSummary,
  type StrategyNoteTimeline,
  type StrategyNote,
  type StrategyNoteUpsertRequest,
} from '../strategyPerformance'

const PERF_KEY = ['strategy-performance'] as const

const historyKey = (strategyId: number, userId: number | string, from: string, to: string) =>
  [...PERF_KEY, 'history', strategyId, userId, from, to] as const

const summaryKey = (strategyId: number, userId: number | string) =>
  [...PERF_KEY, 'summary', strategyId, userId] as const

const notesKey = (strategyId: number, userId: number | string, from: string, to: string) =>
  [...PERF_KEY, 'notes', strategyId, userId, from, to] as const

/** 전략 1개 관련 모든 성과/메모 쿼리 prefix (invalidate 용) */
const strategyPrefix = (strategyId: number) => [...PERF_KEY, strategyId] as const

/** 5분 — 성과·메모는 일배치·사용자 입력으로만 변함 */
const PERF_STALE_MS = 5 * 60 * 1000

export function useStrategyPerformanceHistory(
  strategyId: number,
  userId: number | null | undefined,
  from: string,
  to: string,
) {
  return useQuery<StrategyPerformanceHistory>({
    queryKey: historyKey(strategyId, userId ?? '', from, to),
    queryFn: () => strategyPerformanceApi.getHistory(strategyId, userId!, from, to),
    enabled: userId !== null && userId !== undefined,
    staleTime: PERF_STALE_MS,
  })
}

export function useStrategyPerformanceSummary(
  strategyId: number,
  userId: number | null | undefined,
) {
  return useQuery<StrategyPerformanceSummary>({
    queryKey: summaryKey(strategyId, userId ?? ''),
    queryFn: () => strategyPerformanceApi.getSummary(strategyId, userId!),
    enabled: userId !== null && userId !== undefined,
    staleTime: PERF_STALE_MS,
  })
}

export function useStrategyNotes(
  strategyId: number,
  userId: number | null | undefined,
  from: string,
  to: string,
) {
  return useQuery<StrategyNoteTimeline>({
    queryKey: notesKey(strategyId, userId ?? '', from, to),
    queryFn: () => strategyPerformanceApi.getNotes(strategyId, userId!, from, to),
    enabled: userId !== null && userId !== undefined,
    staleTime: PERF_STALE_MS,
  })
}

interface UpsertNoteVars {
  strategyId: number
  userId: number
  date: string
  body: StrategyNoteUpsertRequest
}

/**
 * 메모 UPSERT — 성공 시 해당 전략의 history/summary/note 쿼리를 모두 무효화.
 * (메모 자체뿐 아니라, 메모는 성과 일자행과 짝이므로 보수적으로 전체 재조회)
 */
export function useUpsertStrategyNote() {
  const queryClient = useQueryClient()
  return useMutation<StrategyNote, Error, UpsertNoteVars>({
    mutationFn: ({ strategyId, userId, date, body }) =>
      strategyPerformanceApi.upsertNote(strategyId, userId, date, body),
    onSuccess: (_data, vars) => {
      void queryClient.invalidateQueries({ queryKey: strategyPrefix(vars.strategyId) })
    },
  })
}
