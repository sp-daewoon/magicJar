import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  trendlineApi,
  type TrendlineAutoCreateResponse,
  type TrendlineCreateRequest,
  type TrendlineDto,
  type TrendlineUpdateRequest,
} from '../trendlines'
import type { CandleInterval } from '../symbol'

const trendlineQueryKey = (code: string) => ['trendlines', code] as const

export function useTrendlines(code: string) {
  return useQuery<TrendlineDto[]>({
    queryKey: trendlineQueryKey(code),
    queryFn: () => trendlineApi.list(code),
    enabled: Boolean(code),
    staleTime: 30_000,
  })
}

export function useCreateTrendline(code: string) {
  const queryClient = useQueryClient()
  return useMutation<TrendlineDto, unknown, TrendlineCreateRequest>({
    mutationFn: (request) => trendlineApi.create(code, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trendlineQueryKey(code) })
    },
  })
}

export function useUpdateTrendline(code: string) {
  const queryClient = useQueryClient()
  return useMutation<TrendlineDto, unknown, { id: number; request: TrendlineUpdateRequest }>({
    mutationFn: ({ id, request }) => trendlineApi.update(code, id, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trendlineQueryKey(code) })
    },
  })
}

export function useCreateAutoTrendlines(code: string) {
  const queryClient = useQueryClient()
  return useMutation<TrendlineAutoCreateResponse, unknown, CandleInterval>({
    mutationFn: (interval) => trendlineApi.createAuto(code, interval),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trendlineQueryKey(code) })
    },
  })
}

/**
 * 추세선 삭제 — 참조 전략이 있으면 409(ADR 0140 §3-3).
 * `force` 전달 시 참조 전략 자동 PAUSED 후 강제 삭제. 기본(force 없음)은 가드 준수.
 */
export function useDeleteTrendline(code: string) {
  const queryClient = useQueryClient()
  return useMutation<void, unknown, { id: number; force?: boolean }>({
    mutationFn: ({ id, force }) => trendlineApi.delete(code, id, force ?? false),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trendlineQueryKey(code) })
    },
  })
}
