/**
 * useSymbolDisclosures hook 단위 테스트
 *
 * ADR 0075 spec §2-3 단위 테스트 매트릭스:
 *   - refetchInterval 60s (60_000ms) 검증
 *
 * STOMP 0 LOCK 검증: useSymbolDisclosures import 내 STOMP client 없음.
 */
import { beforeEach, describe, it, expect, vi } from 'vitest'

// useQuery mock
vi.mock('@tanstack/react-query', () => ({
  useQuery: vi.fn((options) => {
    return { _capturedOptions: options, data: undefined, isLoading: false, isError: false }
  }),
}))

// disclosureApi mock
vi.mock('../api/disclosureApi', () => ({
  disclosureApi: {
    getDisclosures: vi.fn().mockResolvedValue({
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
import { useSymbolDisclosures } from '../hooks/useSymbolDisclosures'

const mockUseQuery = vi.mocked(useQuery)

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('useSymbolDisclosures', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('refetchInterval이 60_000ms (60s)로 설정된다 (ADR 0075 §6-2 LOCK)', () => {
    useSymbolDisclosures('005930')

    expect(mockUseQuery).toHaveBeenCalledOnce()
    const callArgs = mockUseQuery.mock.calls[0][0] as { refetchInterval?: number }
    expect(callArgs.refetchInterval).toBe(60_000)
  })

  it('code가 빈 문자열이면 enabled=false — 쿼리 비활성화', () => {
    useSymbolDisclosures('')

    expect(mockUseQuery).toHaveBeenCalledOnce()
    const callArgs = mockUseQuery.mock.calls[0][0] as { enabled?: boolean }
    expect(callArgs.enabled).toBe(false)
  })

  it('queryKey에 code가 포함된다', () => {
    useSymbolDisclosures('000660')

    expect(mockUseQuery).toHaveBeenCalledOnce()
    const callArgs = mockUseQuery.mock.calls[0][0] as { queryKey?: unknown[] }
    expect(callArgs.queryKey).toContain('symbolDisclosures')
    expect(callArgs.queryKey).toContain('000660')
  })
})
