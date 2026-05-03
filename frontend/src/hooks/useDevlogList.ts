import { useQuery } from '@tanstack/react-query'
import { devlogApi } from '@/api/client'
import type { DevlogEntry } from '@/api/types'

/** T22 backend가 머지되지 않은 경우 반환할 mock 데이터 */
const MOCK_ENTRIES: DevlogEntry[] = [
  {
    date: '2026-05-03',
    dayOfWeek: '일',
    mtime: '2026-05-03T20:00:00+09:00',
    sizeBytes: 12800,
    summary: 'T19·T20·T21·T22 — 분봉 캐시 + 종목 검색 + Devlog 메뉴',
  },
  {
    date: '2026-05-02',
    dayOfWeek: '토',
    mtime: '2026-05-02T23:00:00+09:00',
    sizeBytes: 11200,
    summary: '1번 트랙 KIS 주문 어댑터 20 task 완료 + dogfooding 1차',
  },
  {
    date: '2026-05-01',
    dayOfWeek: '금',
    mtime: '2026-05-01T22:00:00+09:00',
    sizeBytes: 9800,
    summary: 'Phase F R2 — 트레이딩뷰 풀세트 + 백테스트 실 연결 + QA hotfix',
  },
]

export interface UseDevlogListResult {
  entries: DevlogEntry[]
  isLoading: boolean
  isError: boolean
  /** true = 백엔드 미연결, mock 데이터 표시 중 */
  isMock: boolean
  refetch: () => void
}

/**
 * 개발일지 목록 조회 Hook (TanStack Query).
 *
 * GET /api/devlog/list 실패 시 graceful fallback:
 *   - mock 데이터를 보여주고 isMock=true 플래그 반환
 *   - "API 미연결 — 백엔드 머지 후 재시도" 메시지는 컴포넌트 측에서 렌더
 */
export function useDevlogList(): UseDevlogListResult {
  const query = useQuery({
    queryKey: ['devlog', 'list'],
    queryFn: () => devlogApi.list(),
    retry: 1,
    staleTime: 30_000,
  })

  const isMock = query.isError || (query.data?.entries == null && !query.isLoading)
  const entries = isMock ? MOCK_ENTRIES : (query.data?.entries ?? [])

  return {
    entries,
    isLoading: query.isLoading,
    isError: query.isError,
    isMock,
    refetch: query.refetch,
  }
}
