import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import ResearchCandidateLibraryCard from '../ResearchCandidateLibraryCard'

const builtinCandidate = {
  id: 1,
  key: 'MA10_RECLAIM_SCALP',
  title: 'MA10 회복 반등 스캘프',
  marketFit: '하락 추세 중 단기 평균선 회복',
  entryRules: ['직전 종가가 MA10 아래'],
  entryDsl: 'close crosses_above ma(10) and close >= open',
  targetProfitPct: '1.5000',
  stopLossPct: '0.8000',
  maxHoldBars: 12,
  sortOrder: 10,
  active: true,
  builtIn: true,
  updatedAt: '2026-06-06T00:00:00Z',
}

let candidateStore = [builtinCandidate]
let lastAddBody: unknown = null

const server = setupServer(
  http.get('/api/strategy-research/candidates', () => HttpResponse.json(candidateStore)),
  http.get('/api/strategy', () =>
    HttpResponse.json([
      { id: '31', userId: 74683223, name: '내 눌림목 전략', nameKo: '내 눌림목 전략', entry: 'close > ma(20)', enabled: true, createdAt: '2026-06-01T00:00:00Z' },
    ]),
  ),
  http.post('/api/strategy-research/candidates/from-strategy', async ({ request }) => {
    lastAddBody = await request.json()
    const created = {
      ...builtinCandidate,
      id: 2,
      key: 'USER_31',
      title: '내 눌림목 전략',
      builtIn: false,
    }
    candidateStore = [builtinCandidate, created]
    return HttpResponse.json(created)
  }),
  http.delete('/api/strategy-research/candidates/:id', () => {
    candidateStore = [builtinCandidate]
    return new HttpResponse(null, { status: 204 })
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  candidateStore = [builtinCandidate]
  lastAddBody = null
})
afterAll(() => server.close())

function renderCard(registeredUserId: number | null = 74683223) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ResearchCandidateLibraryCard registeredUserId={registeredUserId} />
    </QueryClientProvider>,
  )
}

describe('ResearchCandidateLibraryCard', () => {
  it('renders DB-backed candidates with builtin badge and entry DSL', async () => {
    renderCard()
    expect(await screen.findByText('MA10 회복 반등 스캘프')).toBeInTheDocument()
    expect(screen.getByText('close crosses_above ma(10) and close >= open')).toBeInTheDocument()
    expect(screen.getByText('기본')).toBeInTheDocument()
  })

  it('adds my strategy as a non-builtin candidate via the dropdown', async () => {
    const user = userEvent.setup()
    renderCard()
    await screen.findByText('MA10 회복 반등 스캘프')

    await user.selectOptions(screen.getByLabelText('내 전략을 후보로 추가'), '31')
    await user.click(screen.getByRole('button', { name: '후보로 추가' }))

    await waitFor(() => expect(lastAddBody).toEqual({ userId: '74683223', strategyId: 31 }))
    expect(await screen.findByText('내 전략')).toBeInTheDocument()
  })

  it('disables the add flow when no user is registered', async () => {
    renderCard(null)
    await screen.findByText('MA10 회복 반등 스캘프')
    expect(screen.getByRole('button', { name: '후보로 추가' })).toBeDisabled()
    expect(screen.getByText('사용자 등록 후 내 전략을 후보로 추가할 수 있습니다.')).toBeInTheDocument()
  })
})
