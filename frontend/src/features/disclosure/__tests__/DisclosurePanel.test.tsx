/**
 * DisclosurePanel 단위 테스트 — 4 케이스
 *
 * ADR 0075 spec §2-3 단위 테스트 매트릭스:
 *   1. 정정공시 배지 표시 — isCorrection=true → "정정공시" aria-label 배지 렌더링
 *   2. onlyCorrection 필터 — "정정공시만" 버튼 토글
 *   3. 빈 응답 graceful — "최근 7일간 공시가 없습니다" 표시
 *   4. 공시 3건 렌더링 — 보고서명 표시
 *
 * STOMP 0 검증: useSymbolDisclosures mock에 STOMP client 없음.
 * mock 합성 0: 빈 응답 시 "데이터 없음" 한국어 표시.
 */
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { DisclosurePanel } from '../components/DisclosurePanel'
import type { SymbolDisclosureListResponse } from '../api/disclosureApi'

// useSymbolDisclosures hook mock
vi.mock('../hooks/useSymbolDisclosures', () => ({
  useSymbolDisclosures: vi.fn(),
}))

import { useSymbolDisclosures } from '../hooks/useSymbolDisclosures'
const mockUseSymbolDisclosures = vi.mocked(useSymbolDisclosures)

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function renderDisclosurePanel(code = '005930') {
  return render(<DisclosurePanel code={code} />)
}

function makeResponse(
  overrides: Partial<SymbolDisclosureListResponse> = {},
): SymbolDisclosureListResponse {
  return {
    stockCode: '005930',
    items: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    ...overrides,
  }
}

function makeDisclosureItems(count: number, correctionIndices: number[] = []) {
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    rceptNo: `2026051500000${i + 1}`,
    corpCode: '00126380',
    corpName: '삼성전자',
    reportNm: correctionIndices.includes(i) ? `[기재정정]사업보고서 ${i + 1}` : `사업보고서 ${i + 1}`,
    flrNm: '삼성전자',
    rm: correctionIndices.includes(i) ? '정' : null,
    isCorrection: correctionIndices.includes(i),
    url: `https://dart.fss.or.kr/dsaf001/main.do?rcpNo=2026051500000${i + 1}`,
    rceptDt: '20260515',
    ingestedAt: '2026-05-15T09:31:00+09:00',
  }))
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('DisclosurePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('isCorrection=true 공시 항목에 "정정공시" 배지가 표시된다', () => {
    const items = makeDisclosureItems(3, [1]) // index 1 = 정정공시
    mockUseSymbolDisclosures.mockReturnValue({
      data: makeResponse({ items, totalElements: 3, totalPages: 1 }),
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useSymbolDisclosures>)

    renderDisclosurePanel()

    // 정정공시 배지 aria-label 확인
    expect(screen.getByLabelText('정정공시')).toBeInTheDocument()
    // 정정공시 배지 텍스트 확인
    expect(screen.getByText('정정공시')).toBeInTheDocument()
  })

  it('"정정공시만" 버튼이 표시되고 토글된다', () => {
    mockUseSymbolDisclosures.mockReturnValue({
      data: makeResponse(),
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useSymbolDisclosures>)

    renderDisclosurePanel()

    const btn = screen.getByRole('button', { name: '정정공시만' })
    expect(btn).toBeInTheDocument()
    // 초기: aria-pressed=false
    expect(btn).toHaveAttribute('aria-pressed', 'false')

    // 클릭 후 aria-pressed=true
    fireEvent.click(btn)
    expect(btn).toHaveAttribute('aria-pressed', 'true')
  })

  it('빈 응답 시 "최근 7일간 공시가 없습니다." 표시 (mock 합성 0 LOCK)', () => {
    mockUseSymbolDisclosures.mockReturnValue({
      data: makeResponse(),
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useSymbolDisclosures>)

    renderDisclosurePanel()

    expect(screen.getByText('최근 7일간 공시가 없습니다.')).toBeInTheDocument()
  })

  it('공시 3건 응답 시 보고서명 3건 모두 표시', () => {
    const items = makeDisclosureItems(3)
    mockUseSymbolDisclosures.mockReturnValue({
      data: makeResponse({ items, totalElements: 3, totalPages: 1 }),
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useSymbolDisclosures>)

    renderDisclosurePanel()

    for (let i = 1; i <= 3; i++) {
      expect(screen.getByText(`사업보고서 ${i}`)).toBeInTheDocument()
    }
  })
})
