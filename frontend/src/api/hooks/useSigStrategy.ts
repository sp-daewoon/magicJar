/**
 * SigStrategy TanStack Query hooks (T-SIG Phase 3 — ADR 0066)
 *
 * ADR 0066 /api/strategies (전략 목록·자동매매 운영 endpoint)
 *
 * hooks:
 *   useSigStrategies(page, size) — useInfiniteQuery
 *   useSigStrategy(id)           — useQuery
 *   useCreateSigStrategy()       — useMutation
 *   useUpdateSigStrategy()       — useMutation
 *   useDeleteSigStrategy()       — useMutation
 *   useToggleSigStrategyStatus() — useMutation
 *
 * ADR 0009 TanStack Query 패턴 정합
 */
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  sigStrategyApi,
  strategyStartApi,
  type CreateSigStrategyRequest,
  type SigStrategyDto,
  type SigStrategyPage,
  type StrategyListQuery,
  type StrategyStatus,
  type UpdateSigStrategyRequest,
} from '../strategy'

const SIG_STRATEGY_LIST_KEY = ['sig-strategies', 'list'] as const
const SIG_STRATEGY_KEY = (id: string) => ['sig-strategies', id] as const

/**
 * 전략 목록 (무한 스크롤 · 검색·정렬)
 * userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 *
 * query.q / query.sort 변경 시 queryKey가 바뀌어 무한 쿼리가 0페이지부터 재시작한다.
 * 인자 없이 호출하면 기존 동작(전체·기본정렬)과 동일 — 하위호환.
 */
export function useSigStrategies(
  userId: number | null | undefined,
  size = 20,
  query?: StrategyListQuery,
) {
  // q는 trim 후 비교 — 공백만 입력했을 때 불필요한 쿼리 재시작 방지
  const q = query?.q?.trim() || undefined
  const sort = query?.sort && query.sort !== 'recent' ? query.sort : undefined
  return useInfiniteQuery<SigStrategyPage, Error>({
    queryKey: [...SIG_STRATEGY_LIST_KEY, userId, q ?? null, sort ?? 'recent'],
    queryFn: ({ pageParam }) =>
      sigStrategyApi.list(userId!, pageParam as number, size, { q, sort }),
    initialPageParam: 0 as number,
    getNextPageParam: (lastPage) =>
      lastPage.last ? undefined : lastPage.page + 1,
    enabled: userId !== null && userId !== undefined,
    staleTime: 30 * 1000,
  })
}

/**
 * 전략 단건 조회
 */
export function useSigStrategy(id: string | undefined) {
  return useQuery<SigStrategyDto>({
    queryKey: SIG_STRATEGY_KEY(id ?? ''),
    queryFn: () => sigStrategyApi.get(id!),
    enabled: !!id,
    staleTime: 30 * 1000,
  })
}

/**
 * 전략 생성 mutation
 */
export function useCreateSigStrategy() {
  const queryClient = useQueryClient()
  return useMutation<SigStrategyDto, Error, CreateSigStrategyRequest>({
    mutationFn: (req) => sigStrategyApi.create(req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}

/**
 * 전략 수정 mutation
 */
export function useUpdateSigStrategy() {
  const queryClient = useQueryClient()
  return useMutation<SigStrategyDto, Error, { id: string; req: UpdateSigStrategyRequest }>({
    mutationFn: ({ id, req }) => sigStrategyApi.update(id, req),
    onSuccess: (data) => {
      queryClient.setQueryData(SIG_STRATEGY_KEY(data.id), data)
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}

/**
 * 전략 삭제 mutation
 */
export function useDeleteSigStrategy() {
  const queryClient = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: (id) => sigStrategyApi.delete(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}

/**
 * 전략 상태 토글 mutation
 */
export function useToggleSigStrategyStatus() {
  const queryClient = useQueryClient()
  return useMutation<SigStrategyDto, Error, { id: string; status: StrategyStatus }>({
    mutationFn: ({ id, status }) => sigStrategyApi.toggleStatus(id, status),
    onSuccess: (data) => {
      queryClient.setQueryData(SIG_STRATEGY_KEY(data.id), data)
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}

/**
 * 전략 보관(archive) mutation — status → ARCHIVED, 일상 목록에서 제외.
 * 보유 포지션·미체결 주문 존재 시 백엔드가 409 + 한국어 사유를 반환한다(호출부에서 토스트).
 * 성공 시 목록 캐시 무효화로 보관함/일상 목록 양쪽이 즉시 재정렬된다.
 */
export function useArchiveSigStrategy(userId: number | null | undefined) {
  const queryClient = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: (id) => {
      if (userId === null || userId === undefined) {
        return Promise.reject(new Error('사용자 정보가 없어 보관할 수 없습니다 — 새로고침 후 다시 시도해 주세요'))
      }
      return sigStrategyApi.archive(id, userId)
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}

/**
 * 전략 보관 해제(unarchive) mutation — status → PAUSED(중지)로 복귀.
 * 자동매매 재시작은 사용자가 별도로 눌러야 한다(꺼내기 = 중지 상태 복귀만).
 */
export function useUnarchiveSigStrategy(userId: number | null | undefined) {
  const queryClient = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: (id) => {
      if (userId === null || userId === undefined) {
        return Promise.reject(new Error('사용자 정보가 없어 꺼낼 수 없습니다 — 새로고침 후 다시 시도해 주세요'))
      }
      return sigStrategyApi.unarchive(id, userId)
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}

/** 일괄 상태 변경 1건 결과 (부분 실패 graceful 보고용) */
export interface BulkStatusResult {
  id: string
  ok: boolean
  error?: string
}

/**
 * 다중 전략 일괄 상태 변경 (신규 백엔드 0 — 기존 status endpoint client-side 루프).
 * 부분 실패해도 전체를 중단하지 않고 각 건의 성공/실패를 모아 반환한다.
 * 한 건이라도 성공하면 목록을 무효화한다.
 */
export function useBulkToggleStatus() {
  const queryClient = useQueryClient()
  return useMutation<BulkStatusResult[], Error, { ids: string[]; status: StrategyStatus }>({
    mutationFn: async ({ ids, status }) => {
      const settled = await Promise.allSettled(
        ids.map((id) => sigStrategyApi.toggleStatus(id, status)),
      )
      return settled.map<BulkStatusResult>((result, idx) => {
        const id = ids[idx]
        if (result.status === 'fulfilled') {
          queryClient.setQueryData(SIG_STRATEGY_KEY(id), result.value)
          return { id, ok: true }
        }
        const reason = result.reason
        const error = reason instanceof Error ? reason.message : '상태 변경 실패'
        return { id, ok: false, error }
      })
    },
    onSuccess: (results) => {
      if (results.some((r) => r.ok)) {
        void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
      }
    },
  })
}

/**
 * 목록 카드용 즐겨찾기 토글 — strategyStartApi favorite endpoint 재사용.
 * 시작 화면 캐시(strategy-start)와 목록 캐시를 함께 무효화한다.
 * (favorite 상태 자체는 GET /api/strategies/start 의 favoriteIds 가 권위)
 */
export function useToggleListFavorite(userId: number | null | undefined) {
  const queryClient = useQueryClient()
  return useMutation<void, Error, { id: number; on: boolean }>({
    mutationFn: ({ id, on }) => {
      if (userId === null || userId === undefined) {
        return Promise.reject(new Error('사용자 정보가 없어 즐겨찾기를 변경할 수 없습니다'))
      }
      return on ? strategyStartApi.addFavorite(id, userId) : strategyStartApi.removeFavorite(id, userId)
    },
    onSuccess: () => {
      if (userId !== null && userId !== undefined) {
        void queryClient.invalidateQueries({ queryKey: ['strategy-start', userId] })
      }
      void queryClient.invalidateQueries({ queryKey: SIG_STRATEGY_LIST_KEY })
    },
  })
}
