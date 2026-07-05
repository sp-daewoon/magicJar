/**
 * StrategyCard 핸드오프 단위 테스트
 *
 * 검증 (백테스트 핸드오프 — agent/frontend/backtest-handoff-and-refresh):
 * - PAUSED + activationReady=false(백테스트 사유) "백테스트 필요" 클릭 → navigate('/strategy/backtest?strategyId=N')
 * - RiskPlan(손절·익절) 사유는 백테스트로 보내지 않고 toast 유지
 * - 종목/인터벌 미지정 사유는 toast 유지 (버튼 disabled가 아니어도 핸드오프 X)
 */
import type { ComponentProps } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { SigStrategyDto } from '../../../api/strategy'

const navigateMock = vi.fn()
const showToastMock = vi.fn()
const toggleMutateMock = vi.fn()
const favoriteMutateMock = vi.fn()
const archiveMutateMock = vi.fn()
const unarchiveMutateMock = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('../../ui/ToastProvider', () => ({
  useToast: () => ({ showToast: showToastMock }),
}))

vi.mock('../../../api/hooks/useSigStrategy', () => ({
  useToggleSigStrategyStatus: () => ({ mutate: toggleMutateMock, isPending: false, isError: false }),
  useDeleteSigStrategy: () => ({ mutate: vi.fn(), isPending: false, isError: false }),
  useToggleListFavorite: () => ({ mutate: favoriteMutateMock, isPending: false, isError: false }),
  useArchiveSigStrategy: () => ({ mutate: archiveMutateMock, isPending: false, isError: false }),
  useUnarchiveSigStrategy: () => ({ mutate: unarchiveMutateMock, isPending: false, isError: false }),
}))

vi.mock('../../../stores/onboarding', () => ({
  useOnboardingStore: () => 1,
}))

vi.mock('../../../features/ai/components/AiExplainEntry', () => ({
  AiExplainEntry: () => null,
}))

vi.mock('../StrategyPositionPanel', () => ({
  StrategyPositionPanel: () => null,
}))

import { MemoryRouter } from 'react-router-dom'
import { StrategyCard } from '../StrategyCard'

const baseStrategy: SigStrategyDto = {
  id: '48',
  userId: 1,
  name: 'xxx',
  codes: ['005930'],
  codeNames: { '005930': '삼성전자' },
  intervals: ['1d'],
  stopLossPct: 3,
  takeProfitPct: 5,
  status: 'PAUSED',
  activationReady: false,
  activationBlockedReason: '성공한 백테스트가 있어야 자동매매를 시작할 수 있습니다.',
  recentSignalCount: 0,
  createdAt: '2026-06-06T00:00:00Z',
}

function renderCard(strategy: SigStrategyDto, props: Partial<ComponentProps<typeof StrategyCard>> = {}) {
  render(
    <MemoryRouter>
      <StrategyCard strategy={strategy} {...props} />
    </MemoryRouter>,
  )
}

describe('StrategyCard 백테스트 핸드오프', () => {
  beforeEach(() => {
    navigateMock.mockClear()
    showToastMock.mockClear()
    toggleMutateMock.mockClear()
    favoriteMutateMock.mockClear()
  })

  it('백테스트 사유 "백테스트 필요" 클릭 시 백테스트 화면으로 전략 prefill 네비게이션', async () => {
    const user = userEvent.setup()
    renderCard(baseStrategy)

    await user.click(screen.getByRole('button', { name: '백테스트 필요' }))

    expect(navigateMock).toHaveBeenCalledWith('/strategy/backtest?strategyId=48')
    expect(showToastMock).not.toHaveBeenCalled()
    expect(toggleMutateMock).not.toHaveBeenCalled()
  })

  it('RiskPlan(손절·익절) 사유는 네비게이션 대신 toast 유지', async () => {
    const user = userEvent.setup()
    renderCard({
      ...baseStrategy,
      stopLossPct: null,
      takeProfitPct: null,
      activationBlockedReason: '자동매매를 시작하려면 손절 비율을 설정해 주세요.',
    })

    await user.click(screen.getByRole('button', { name: 'RiskPlan 필요' }))

    expect(navigateMock).not.toHaveBeenCalled()
    expect(showToastMock).toHaveBeenCalledWith(
      '자동매매를 시작하려면 손절 비율을 설정해 주세요.',
      'error',
    )
    expect(toggleMutateMock).not.toHaveBeenCalled()
  })

  it('종목 미지정 전략의 "종목 지정 필요" 버튼은 비활성화되어 핸드오프하지 않는다', () => {
    renderCard({
      ...baseStrategy,
      codes: [],
      activationReady: true,
      activationBlockedReason: null,
    })

    const btn = screen.getByRole('button', { name: '종목 지정 필요' })
    expect(btn).toBeDisabled()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('페이퍼 전략은 모의 배지만 표시하고 안내 박스와 백테스트 점검 박스를 숨긴다', () => {
    renderCard({
      ...baseStrategy,
      origin: 'PAPER_DISCOVERY',
      status: 'ACTIVE',
      activationReady: false,
      activationBlockedReason: '성공한 백테스트가 있어야 자동매매를 시작할 수 있습니다.',
    })

    expect(screen.getByLabelText('모의 페이퍼, 실거래 아님')).toBeInTheDocument()
    expect(screen.queryByText('모의 페이퍼 전략')).not.toBeInTheDocument()
    expect(screen.queryByText(/단타 발굴 setup 을 가상 자본으로 검증/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '페이퍼 대시보드에서 보기' })).not.toBeInTheDocument()
    expect(screen.getByText('모의 감시 중')).toBeInTheDocument()
    expect(screen.queryByText('자동매매 점검 필요')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '백테스트 필요' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '모의 감시 중지' })).toBeInTheDocument()
  })

  it('페이퍼 배지는 모의 트레이닝 화면으로 이동하는 링크다', () => {
    renderCard({
      ...baseStrategy,
      origin: 'PAPER_DISCOVERY',
      status: 'ACTIVE',
      activationReady: true,
      activationBlockedReason: null,
    })

    expect(screen.getByRole('link', { name: '모의 트레이닝으로 이동' })).toHaveAttribute(
      'href',
      '/strategy/paper',
    )
  })

  it('종목이 4개를 넘으면 +N 버튼으로 나머지 종목을 펼친다', async () => {
    const user = userEvent.setup()
    renderCard({
      ...baseStrategy,
      codes: ['005930', '000660', '035720', '005380', '066570', '068270'],
      codeNames: {
        '005930': '삼성전자',
        '000660': 'SK하이닉스',
        '035720': '카카오',
        '005380': '현대차',
        '066570': 'LG전자',
        '068270': '셀트리온',
      },
    })

    expect(screen.getByText('삼성전자(005930)')).toBeInTheDocument()
    expect(screen.queryByText('LG전자(066570)')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '나머지 종목 2개 펼치기' }))

    expect(screen.getByText('LG전자(066570)')).toBeInTheDocument()
    expect(screen.getByText('셀트리온(068270)')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '종목 목록 접기' })).toBeInTheDocument()
  })
})

describe('StrategyCard 정보 밀도·빠른 액션 (list-ux-uplift)', () => {
  beforeEach(() => {
    navigateMock.mockClear()
    showToastMock.mockClear()
    favoriteMutateMock.mockClear()
  })

  it('분할매수 + floor 알람 배지를 표시한다', () => {
    renderCard({
      ...baseStrategy,
      scaleIn: { totalSlices: 3, stopLossFloorPct: 15, floorAutoLiquidate: false },
    })
    expect(screen.getByText('분할 3회')).toBeInTheDocument()
    expect(screen.getByText('floor 알람 -15%')).toBeInTheDocument()
  })

  it('floor 자동청산 전략은 자동청산 배지를 표시한다', () => {
    renderCard({
      ...baseStrategy,
      scaleIn: { totalSlices: 2, stopLossFloorPct: 20, floorAutoLiquidate: true },
    })
    expect(screen.getByText('floor 자동청산 -20%')).toBeInTheDocument()
  })

  it('단일 진입 + floor 미설정이면 배지를 표시하지 않는다', () => {
    renderCard(baseStrategy)
    expect(screen.queryByText(/분할 \d+회/)).not.toBeInTheDocument()
    expect(screen.queryByText(/floor/)).not.toBeInTheDocument()
  })

  it('즐겨찾기 버튼 클릭 시 토글 mutation 호출 (현재 미즐겨찾기 → on=true)', async () => {
    const user = userEvent.setup()
    renderCard(baseStrategy, { isFavorite: false })
    await user.click(screen.getByRole('button', { name: '즐겨찾기 추가' }))
    expect(favoriteMutateMock).toHaveBeenCalledTimes(1)
    expect(favoriteMutateMock.mock.calls[0][0]).toEqual({ id: 48, on: true })
  })

  it('즐겨찾기 상태면 해제 버튼이 노출된다', () => {
    renderCard(baseStrategy, { isFavorite: true })
    expect(screen.getByRole('button', { name: '즐겨찾기 해제' })).toBeInTheDocument()
  })

  it('복제 버튼 클릭 시 빌더로 duplicate 라우팅', async () => {
    const user = userEvent.setup()
    renderCard(baseStrategy)
    await user.click(screen.getByRole('button', { name: '복제' }))
    expect(navigateMock).toHaveBeenCalledWith('/strategy/builder?strategyId=48&duplicate=1')
  })

  it('선택 모드에서 체크박스가 노출되고 onToggleSelect 가 호출된다', async () => {
    const onToggleSelect = vi.fn()
    const user = userEvent.setup()
    renderCard(baseStrategy, { selectMode: true, selected: false, onToggleSelect })
    const checkbox = screen.getByRole('checkbox', { name: 'xxx 선택' })
    await user.click(checkbox)
    expect(onToggleSelect).toHaveBeenCalledTimes(1)
  })
})
