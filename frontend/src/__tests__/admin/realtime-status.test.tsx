/**
 * 실시간 구독 상태 관리자 화면 — 컴포넌트 단위 테스트
 *
 * 화면: /admin/realtime (RealtimeStatusPage + 4 하위 컴포넌트)
 * 계약: GET /api/admin/realtime-status (RealtimeStatus — 필드명 LOCK)
 *
 * 검증 초점 (장애 처리용 가시화의 핵심 분기):
 *  1. Banner       — 정상/비정상 분기 + 비정상 시 한국어 장애 힌트 노출
 *  2. SlotGauge    — 사용률 임계(70%/90%) 색·힌트 + trId 분해 + 빈 상태
 *  3. SubTable     — owner 색 배지 + 빈 상태 정직 표기 (mock 0)
 *  4. CandlePanel  — 180초 임계 경고 + 15:20 마감 동시호가 정상 힌트 + 빈 상태
 *
 * 순수 props 컴포넌트라 MSW 없이 직접 주입으로 분기만 검증한다.
 */
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type {
  RealtimeCandleFreshness,
  RealtimeExecutionNotice,
  RealtimeSlots,
  RealtimeSubscription,
  RealtimeWsStatus,
} from '../../api/adminRealtime'
import { RealtimeStatusBanner } from '../../components/admin/RealtimeStatusBanner'
import { RealtimeSlotGauge } from '../../components/admin/RealtimeSlotGauge'
import { RealtimeSubscriptionTable } from '../../components/admin/RealtimeSubscriptionTable'
import { RealtimeCandleFreshnessPanel } from '../../components/admin/RealtimeCandleFreshnessPanel'

// ──────────────────────────────────────────────
// fixture
// ──────────────────────────────────────────────

const wsConnected: RealtimeWsStatus = {
  connected: true,
  sessionCid: 'sess-abc',
  reconnects24h: 0,
  lastConnectedAt: '2026-06-12T09:00:00+09:00',
}

const noticeOk: RealtimeExecutionNotice = {
  subscribed: true,
  mode: 'MOCK',
  userIdMasked: 'sp****',
}

// ──────────────────────────────────────────────
// ① Banner
// ──────────────────────────────────────────────

describe('RealtimeStatusBanner', () => {
  it('모두 정상이면 비정상 강조가 없다', () => {
    render(
      <RealtimeStatusBanner
        stale={false}
        updatedAt="2026-06-12T15:00:00+09:00"
        ws={wsConnected}
        executionNotice={noticeOk}
      />,
    )
    const banner = screen.getByTestId('realtime-banner')
    expect(banner.getAttribute('data-abnormal')).toBe('false')
    // 3 카드 모두 정상
    expect(screen.getByTestId('status-card-WS 연결').getAttribute('data-ok')).toBe('true')
    expect(screen.getByTestId('status-card-상태 신선도').getAttribute('data-ok')).toBe('true')
    expect(screen.getByTestId('status-card-체결 통보').getAttribute('data-ok')).toBe('true')
  })

  it('WS 끊김이면 비정상 강조 + 한국어 장애 힌트가 뜬다', () => {
    render(
      <RealtimeStatusBanner
        stale={false}
        updatedAt="2026-06-12T15:00:00+09:00"
        ws={{ ...wsConnected, connected: false }}
        executionNotice={noticeOk}
      />,
    )
    expect(screen.getByTestId('realtime-banner').getAttribute('data-abnormal')).toBe('true')
    expect(screen.getByTestId('status-card-WS 연결').getAttribute('data-ok')).toBe('false')
    expect(screen.getByText(/KIS WS 미연결/)).toBeInTheDocument()
  })

  it('stale=true 면 상태 신선도가 비정상으로 표시된다', () => {
    render(
      <RealtimeStatusBanner
        stale
        updatedAt="2026-06-12T15:00:00+09:00"
        ws={wsConnected}
        executionNotice={noticeOk}
      />,
    )
    expect(screen.getByTestId('status-card-상태 신선도').getAttribute('data-ok')).toBe('false')
    expect(screen.getByText(/consumer가 상태를 못 쓰고/)).toBeInTheDocument()
  })

  it('체결통보 미구독이면 비정상 + 힌트', () => {
    render(
      <RealtimeStatusBanner
        stale={false}
        updatedAt="2026-06-12T15:00:00+09:00"
        ws={wsConnected}
        executionNotice={{ subscribed: false, mode: null, userIdMasked: null }}
      />,
    )
    expect(screen.getByTestId('status-card-체결 통보').getAttribute('data-ok')).toBe('false')
    expect(screen.getByText(/체결 통보 구독 끊김/)).toBeInTheDocument()
  })
})

// ──────────────────────────────────────────────
// ② SlotGauge
// ──────────────────────────────────────────────

describe('RealtimeSlotGauge', () => {
  it('used/max 와 사용률을 표시한다', () => {
    const slots: RealtimeSlots = { used: 12, max: 40, byTrId: { H0STCNT0: 8, H0STASP0: 4 } }
    render(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/40/)).toBeInTheDocument()
    expect(screen.getByText(/30%/)).toBeInTheDocument()
    // trId 분해 표시
    expect(screen.getByText('H0STCNT0')).toBeInTheDocument()
    expect(screen.getByText('H0STASP0')).toBeInTheDocument()
  })

  it('사용률 90% 이상이면 고갈 임박 힌트가 뜬다', () => {
    const slots: RealtimeSlots = { used: 38, max: 40, byTrId: {} }
    render(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/슬롯 고갈 임박/)).toBeInTheDocument()
  })

  it('사용률 70~90% 면 사용률 높음 힌트가 뜬다', () => {
    const slots: RealtimeSlots = { used: 32, max: 40, byTrId: {} }
    render(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/슬롯 사용률 높음/)).toBeInTheDocument()
  })

  it('trId 점유가 없으면 정직하게 안내한다', () => {
    const slots: RealtimeSlots = { used: 0, max: 40, byTrId: {} }
    render(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/점유 중인 trId가 없습니다/)).toBeInTheDocument()
  })

  it('max=0 이어도 0 나눗셈 없이 0%로 처리한다', () => {
    const slots: RealtimeSlots = { used: 0, max: 0, byTrId: {} }
    render(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/0%/)).toBeInTheDocument()
  })
})

// ──────────────────────────────────────────────
// ③ SubscriptionTable
// ──────────────────────────────────────────────

describe('RealtimeSubscriptionTable', () => {
  const subs: RealtimeSubscription[] = [
    {
      trId: 'H0STCNT0',
      code: '005930',
      codeName: '삼성전자',
      owners: ['STRATEGY', 'detail'],
      since: '2026-06-12T09:01:00+09:00',
    },
  ]

  it('구독 항목과 owner 배지를 렌더링한다 (대소문자 무관)', () => {
    render(<RealtimeSubscriptionTable subscriptions={subs} />)
    expect(screen.getByText('삼성전자')).toBeInTheDocument()
    expect(screen.getByText('005930')).toBeInTheDocument()
    // owner 배지 — 표기 그대로 노출
    expect(screen.getByText('STRATEGY')).toBeInTheDocument()
    expect(screen.getByText('detail')).toBeInTheDocument()
  })

  it('빈 목록이면 정직하게 표기한다 (mock 0)', () => {
    render(<RealtimeSubscriptionTable subscriptions={[]} />)
    expect(screen.getByText(/구독 중인 종목이 없습니다/)).toBeInTheDocument()
  })

  it('owner 가 없는 구독은 (owner 없음) 으로 표기한다', () => {
    render(
      <RealtimeSubscriptionTable
        subscriptions={[{ ...subs[0], owners: [] }]}
      />,
    )
    expect(screen.getByText(/owner 없음/)).toBeInTheDocument()
  })
})

// ──────────────────────────────────────────────
// ④ CandleFreshnessPanel
// ──────────────────────────────────────────────

describe('RealtimeCandleFreshnessPanel', () => {
  it('180초 초과 항목이 있으면 경고 + 마감 동시호가 정상 힌트를 노출한다', () => {
    const items: RealtimeCandleFreshness[] = [
      { code: '005930', interval: '1m', ageSeconds: 240 },
      { code: '000660', interval: '1m', ageSeconds: 12 },
    ]
    render(<RealtimeCandleFreshnessPanel items={items} />)
    expect(screen.getByText(/시세 유입 정지 의심/)).toBeInTheDocument()
    expect(screen.getByText(/15:20 이후는 마감/)).toBeInTheDocument()
    expect(screen.getByText(/정체 1건/)).toBeInTheDocument()
  })

  it('모두 신선하면 경고가 없다', () => {
    const items: RealtimeCandleFreshness[] = [
      { code: '005930', interval: '1m', ageSeconds: 5 },
    ]
    render(<RealtimeCandleFreshnessPanel items={items} />)
    expect(screen.queryByText(/시세 유입 정지 의심/)).not.toBeInTheDocument()
    expect(screen.getByText(/5초 전/)).toBeInTheDocument()
  })

  it('빈 목록이면 정직하게 표기한다 (mock 0)', () => {
    render(<RealtimeCandleFreshnessPanel items={[]} />)
    expect(screen.getByText(/캔들 신선도 데이터가 없습니다/)).toBeInTheDocument()
  })

  it('경과 시간을 분/초 한국어로 포맷한다', () => {
    const items: RealtimeCandleFreshness[] = [
      { code: '005930', interval: '1m', ageSeconds: 200 },
    ]
    render(<RealtimeCandleFreshnessPanel items={items} />)
    expect(screen.getByText(/3분 20초 전/)).toBeInTheDocument()
  })
})
