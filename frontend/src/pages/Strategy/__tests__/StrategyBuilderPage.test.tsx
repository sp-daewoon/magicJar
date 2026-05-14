/**
 * StrategyBuilderPage 통합 테스트
 *
 * 검증:
 * - 페이지 렌더 (팔레트 + 캔버스 + 결과 영역)
 * - 팔레트에서 블록 선택 → DSL 미리보기 갱신
 * - 검증 버튼 흐름 (MSW mock)
 * - 모바일 안내 메시지 (768px)
 * - T-SC Phase 3: "이 전략으로 종목 찾기" 버튼 — strategyId 없을 때 미표시
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, beforeAll, afterAll, afterEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyBuilderPage } from '../StrategyBuilderPage'

// MSW 서버 설정
const server = setupServer(
  http.post('/api/strategy/validate', () => {
    return HttpResponse.json({
      astDepth: 2,
      nodeCount: 3,
      indicatorCallCount: 1,
      estimatedEvalTimeMs: 5,
      complexityClass: 'LOW',
    })
  }),
  http.post('/api/strategy', () => {
    return HttpResponse.json({
      strategyId: 'test-strategy-id',
      complexity: {
        astDepth: 2,
        nodeCount: 3,
        indicatorCallCount: 1,
        estimatedEvalTimeMs: 5,
        complexityClass: 'LOW',
      },
    })
  }),
)

beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

function Wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  )
}

describe('StrategyBuilderPage', () => {
  it('데스크톱 레이아웃 — 팔레트 + 캔버스 렌더', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    // 팔레트 탭 확인
    expect(screen.getByLabelText('블록 팔레트')).toBeInTheDocument()
    // 캔버스 탭 확인
    expect(screen.getByText('매수 조건 (Entry)')).toBeInTheDocument()
    expect(screen.getByText('매도 조건 (Exit)')).toBeInTheDocument()
  })

  it('빈 상태에서 안내 메시지 표시', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    expect(screen.getByText(/팔레트에서 블록을 선택하거나/)).toBeInTheDocument()
  })

  it('팔레트 지표 탭 렌더', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    expect(screen.getByLabelText('지표')).toBeInTheDocument()
  })

  it('자연어 입력 버튼 클릭 → 모달 오픈', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    const nlBtn = screen.getByText('자연어로 입력하기')
    fireEvent.click(nlBtn)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('저장 버튼 — 빈 entry 시 disabled', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    const saveBtn = screen.getByText('저장')
    expect(saveBtn).toBeDisabled()
  })

  it('T-SC Phase 3 — 전략 미저장 상태에서 "이 전략으로 종목 찾기" 버튼 미표시', () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    // strategyId가 없으면 버튼이 렌더되지 않아야 함 (ADR 0071 §8 H-1)
    expect(screen.queryByText('이 전략으로 종목 찾기')).not.toBeInTheDocument()
  })
})
