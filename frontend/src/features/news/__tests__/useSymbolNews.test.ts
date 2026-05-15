/**
 * useSymbolNews hook 단위 테스트
 *
 * ADR 0075 spec §2-3 단위 테스트 매트릭스:
 *   - refetchInterval 60s (60_000ms) 검증
 *
 * STOMP 0 LOCK 검증: useSymbolNews import 내 STOMP client 없음.
 * polling interval은 useQuery options의 refetchInterval 값으로 검증.
 */
import { beforeEach, describe, it, expect, vi } from 'vitest'

// useQuery mock
vi.mock('@tanstack/react-query', () => ({
  useQuery: vi.fn((options) => {
    return { _capturedOptions: options, data: undefined, isLoading: false, isError: false }
  }),
}))

// newsApi mock
vi.mock('../api/newsApi', () => ({
  newsApi: {
    getNews: vi.fn().mockResolvedValue({
      stockCode: '005930',
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    }),
  },
}))

import { useQuery } from '@tanstack/react-query'
import { useSymbolNews } from '../hooks/useSymbolNews'

const mockUseQuery = vi.mocked(useQuery)

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('useSymbolNews', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('refetchInterval이 60_000ms (60s)로 설정된다 (ADR 0075 §6-2 LOCK)', () => {
    useSymbolNews('005930')

    expect(mockUseQuery).toHaveBeenCalledOnce()
    const callArgs = mockUseQuery.mock.calls[0][0] as { refetchInterval?: number }
    expect(callArgs.refetchInterval).toBe(60_000)
  })

  it('code가 빈 문자열이면 enabled=false — 쿼리 비활성화', () => {
    useSymbolNews('')

    expect(mockUseQuery).toHaveBeenCalledOnce()
    const callArgs = mockUseQuery.mock.calls[0][0] as { enabled?: boolean }
    expect(callArgs.enabled).toBe(false)
  })

  it('queryKey에 code가 포함된다', () => {
    useSymbolNews('000660')

    expect(mockUseQuery).toHaveBeenCalledOnce()
    const callArgs = mockUseQuery.mock.calls[0][0] as { queryKey?: unknown[] }
    expect(callArgs.queryKey).toContain('symbolNews')
    expect(callArgs.queryKey).toContain('000660')
  })
})
