import { useQuery } from '@tanstack/react-query'
import { devlogApi } from '@/api/client'
import type { DevlogEntry } from '@/api/types'

export interface UseDevlogListResult {
  entries: DevlogEntry[]
  isLoading: boolean
  isError: boolean
  /** true = 백엔드 미연결 (데이터 없음 안내 표시용) */
  isApiUnavailable: boolean
  refetch: () => void
}

/**
 * 개발일지 목록 조회 Hook (TanStack Query).
 *
 * GET /api/devlog/list 실패 시 빈 배열 반환 — "API 미연결" 안내는 컴포넌트에서 렌더.
 * mock 데이터 합성 없음 (사용자 정책: mock 데이터 사용 금지).
 */
export function useDevlogList(): UseDevlogListResult {
  const query = useQuery({
    queryKey: ['devlog', 'list'],
    queryFn: () => devlogApi.list(),
    retry: 1,
    staleTime: 30_000,
  })

  const isApiUnavailable = query.isError || (!query.isLoading && query.data?.entries == null)
  const entries = query.data?.entries ?? []

  return {
    entries,
    isLoading: query.isLoading,
    isError: query.isError,
    isApiUnavailable,
    refetch: query.refetch,
  }
}
