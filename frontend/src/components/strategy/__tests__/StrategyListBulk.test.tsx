/**
 * StrategyList 다중 선택 + 일괄 시작/중지 테스트 (list-ux-uplift)
 *
 * 검증:
 *  - "여러 개 선택" → 카드 체크박스 노출 → 선택 → "일괄 중지" 호출 (client-side 루프)
 *  - 부분 실패 graceful 토스트
 */
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SigStrategyDto } from '../../../api/strategy'

// StrategyList 무한 스크롤 sentinel은 IntersectionObserver를 쓴다 — jsdom 국소 polyfill.
// (전역 setup에 두면 useInViewport graceful fallback이 깨지므로 이 파일에서만 정의)
if (typeof IntersectionObserver === 'undefined') {
  // @ts-expect-error 테스트 환경 polyfill (최소 구현)
  global.IntersectionObserver = class IntersectionObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() {
      return []
    }
  }
}

const showToastMock = vi.fn()
const bulkMutateMock = vi.fn()

vi.mock('../../ui/ToastProvider', () => ({
  useToast: () => ({ showToast: showToastMock }),
}))

// StrategyCard는 별도 테스트 — 여기선 가벼운 stub으로 selectMode/selected/onToggleSelect만 검증
vi.mock('../StrategyCard', () => ({
  StrategyCard: ({
    strategy,
    selectMode,
    selected,
    onToggleSelect,
  }: {
    strategy: SigStrategyDto
    selectMode?: boolean
    selected?: boolean
    onToggleSelect?: () => void
  }) => (
    <div data-testid={`card-${strategy.id}`}>
      <span>{strategy.name}</span>
      {selectMode && (
        <input
          type="checkbox"
          aria-label={`${strategy.name} 선택`}
          checked={selected}
          onChange={() => onToggleSelect?.()}
        />
      )}
    </div>
  ),
}))

const strategies: SigStrategyDto[] = [
  { id: '1', userId: 1, name: 'A', codes: ['005930'], intervals: ['1d'], status: 'PAUSED', createdAt: '' },
  { id: '2', userId: 1, name: 'B', codes: ['000660'], intervals: ['1d'], status: 'PAUSED', createdAt: '' },
]

vi.mock('../../../api/hooks/useSigStrategy', () => ({
  useSigStrategies: () => ({
    data: { pages: [{ content: strategies, page: 0, size: 20, last: true, totalElements: 2, totalPages: 1 }] },
    isLoading: false,
    isError: false,
    fetchNextPage: vi.fn(),
    hasNextPage: false,
    isFetchingNextPage: false,
  }),
  useBulkToggleStatus: () => ({ mutate: bulkMutateMock, isPending: false }),
}))

import { MemoryRouter } from 'react-router-dom'
import { StrategyList } from '../StrategyList'

function renderList() {
  render(
    <MemoryRouter>
      <StrategyList userId={1} filter="ALL" />
    </MemoryRouter>,
  )
}

describe('StrategyList 일괄 액션', () => {
  beforeEach(() => {
    showToastMock.mockClear()
    bulkMutateMock.mockClear()
  })

  it('선택 모드 진입 → 카드 선택 → 일괄 중지 호출', async () => {
    const user = userEvent.setup()
    renderList()

    // 기본은 선택 모드 아님 → 체크박스 없음
    expect(screen.queryByLabelText('A 선택')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '여러 개 선택' }))
    await user.click(screen.getByLabelText('A 선택'))

    await waitFor(() => expect(screen.getByText('1개 선택')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: '일괄 중지' }))

    expect(bulkMutateMock).toHaveBeenCalledTimes(1)
    expect(bulkMutateMock.mock.calls[0][0]).toEqual({ ids: ['1'], status: 'PAUSED' })
  })

  it('전체 선택 후 일괄 시작은 모든 id로 호출', async () => {
    const user = userEvent.setup()
    renderList()

    await user.click(screen.getByRole('button', { name: '여러 개 선택' }))
    await user.click(screen.getByRole('button', { name: '전체 선택' }))
    await waitFor(() => expect(screen.getByText('2개 선택')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: '일괄 시작' }))

    expect(bulkMutateMock.mock.calls[0][0]).toEqual({ ids: ['1', '2'], status: 'ACTIVE' })
  })

  it('부분 실패 시 graceful 토스트', async () => {
    bulkMutateMock.mockImplementation((_vars, opts) => {
      opts.onSuccess([
        { id: '1', ok: true },
        { id: '2', ok: false, error: 'boom' },
      ])
    })
    const user = userEvent.setup()
    renderList()

    await user.click(screen.getByRole('button', { name: '여러 개 선택' }))
    await user.click(screen.getByRole('button', { name: '전체 선택' }))
    await user.click(screen.getByRole('button', { name: '일괄 중지' }))

    expect(showToastMock).toHaveBeenCalledWith('1개 중지, 1개 실패(부분 적용).', 'error')
  })
})
