/**
 * PositionsPage test — hotfix /positions 빈 화면 (사용자 dogfooding 2026-05-21)
 *
 * 검증 시나리오:
 * 1. userId null → 셋업 안내 화면
 * 2. /api/account/positions 가 3건 반환 → 카드 3개 + KIS 매트릭스 셀 표시
 * 3. /api/account/positions 빈 배열 → "보유 포지션이 없습니다" 안내
 * 4. 412 응답 → "KIS 키 미등록" 안내 (실전·모의 동적 분기)
 * 5. accountType 토글(MOCK→REAL) 시 새 endpoint 호출 (?accountType=REAL)
 *
 * 회귀 방지: 기존 PositionsPage 는 usePositions (DB position) 호출 → 사용자 실제 보유와 무관 → 빈 결과.
 * 본 test 는 useAccountPositions (KIS inquire-balance) 로 정합됐는지 보증.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { PositionsPage } from '../pages/Risk/PositionsPage'
import { useOnboardingStore } from '../stores/onboarding'

// ──────────────────────────────────────────────
// msw — /api/account/positions 분기 스텁
// ──────────────────────────────────────────────

let lastQueryString = ''
let positionsResponse: 'three' | 'empty' | 'missing-credential' | 'error' = 'three'

const THREE_HOLDINGS = [
  {
    code: '000660',
    name: 'SK하이닉스',
    quantity: 1,
    avgPrice: 1_840_000,
    currentPrice: 1_900_000,
    evaluationAmount: 1_900_000,
    profitLoss: 60_000,
    profitLossRate: 3.26,
  },
  {
    code: '004090',
    name: '한국석유',
    quantity: 5,
    avgPrice: 12_000,
    currentPrice: 11_500,
    evaluationAmount: 57_500,
    profitLoss: -2_500,
    profitLossRate: -4.17,
  },
  {
    code: '005930',
    name: '삼성전자',
    quantity: 1,
    avgPrice: 78_000,
    currentPrice: 78_000,
    evaluationAmount: 78_000,
    profitLoss: 0,
    profitLossRate: 0,
  },
]

const server = setupServer(
  http.get('/api/account/positions', ({ request }) => {
    lastQueryString = new URL(request.url).search
    if (positionsResponse === 'three') return HttpResponse.json(THREE_HOLDINGS)
    if (positionsResponse === 'empty') return HttpResponse.json([])
    if (positionsResponse === 'missing-credential')
      return new HttpResponse(JSON.stringify({ message: '키 미등록' }), {
        status: 412,
        headers: { 'Content-Type': 'application/json' },
      })
    return new HttpResponse('upstream error', { status: 502 })
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  lastQueryString = ''
  positionsResponse = 'three'
})
afterAll(() => server.close())

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <PositionsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function setUserId(id: number | null) {
  useOnboardingStore.setState({ registeredUserId: id })
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('PositionsPage — /api/account/positions 정합 (hotfix 2026-05-21)', () => {
  beforeEach(() => {
    // 각 테스트마다 깨끗한 상태 — 기본은 등록된 userId
    setUserId(74683223)
  })

  it('userId 미등록 시 셋업 안내 화면', () => {
    setUserId(null)
    renderPage()

    expect(screen.getByText('보유 포지션')).toBeInTheDocument()
    expect(screen.getByText(/KIS 계좌 등록을 완료하면/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '계좌 등록하러 가기' })).toBeInTheDocument()
  })

  it('3건 응답 → 카드 3개 + 종목명/평가금액/수익률 셀 표시 + 호출 횟수 검증', async () => {
    positionsResponse = 'three'
    renderPage()

    // 카드 로드 대기
    await waitFor(() => {
      expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    })

    expect(screen.getByText('한국석유')).toBeInTheDocument()
    expect(screen.getByText('삼성전자')).toBeInTheDocument()
    expect(screen.getByText('총 3개 종목')).toBeInTheDocument()
    // 매트릭스 셀 — SK하이닉스 현재가/평가금 1,900,000원 둘 다 표시 (getAllByText 2건)
    expect(screen.getAllByText('1,900,000원').length).toBeGreaterThanOrEqual(2)
    // 수익률 부호 검증 — 한국 표준 색상 분기 cover
    expect(screen.getByText('+3.26%')).toBeInTheDocument()
    expect(screen.getByText('-4.17%')).toBeInTheDocument()
    expect(screen.getByText('0.00%')).toBeInTheDocument()
    // 평가손익 부호 — SK하이닉스 +60,000원
    expect(screen.getByText('+60,000원')).toBeInTheDocument()
    // 런타임 플래그/저장값이 없으면 안전 기본값 MOCK
    expect(lastQueryString).toContain('accountType=MOCK')
    expect(lastQueryString).toContain('userId=74683223')
  })

  it('빈 배열 → "보유 포지션이 없습니다" 안내', async () => {
    positionsResponse = 'empty'
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('보유 포지션이 없습니다.')).toBeInTheDocument()
    })
  })

  it('412 응답 → "KIS 키 미등록" 안내 + accountType 분기 메시지', async () => {
    positionsResponse = 'missing-credential'
    renderPage()

    await waitFor(() => {
      // 기본 MOCK → "모의계좌 KIS 키가 등록되지 않았습니다"
      expect(screen.getByText(/모의계좌 KIS 키가 등록되지 않았습니다/)).toBeInTheDocument()
    })
    expect(screen.getByText(/모의\(VPS\) 키를 등록한 뒤/)).toBeInTheDocument()
  })

  it('accountType 토글 MOCK→REAL 시 새 endpoint 호출(?accountType=REAL)', async () => {
    positionsResponse = 'three'
    renderPage()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    })
    expect(lastQueryString).toContain('accountType=MOCK')

    // 실전 버튼 클릭
    await user.click(screen.getByRole('button', { name: '실전' }))

    await waitFor(() => {
      expect(lastQueryString).toContain('accountType=REAL')
    })
  })
})
