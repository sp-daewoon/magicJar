/**
 * useStompReconnect 단위 테스트.
 *
 * 검증 초점:
 * - backend StompEventBackfillController 실제 DTO(content/firstTs/lastTs/createdAt)와 정합
 * - system-event 채널은 전용 알림센터가 담당하므로 주문·시그널·리스크 백필 토스트에서 제외
 */
import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useStompReconnect } from '../useStompReconnect'

const mocks = vi.hoisted(() => ({
  getUnreadCount: vi.fn(),
  list: vi.fn(),
  showToast: vi.fn(),
  client: {
    connected: true,
    onConnect: undefined as ((frame: unknown) => void) | undefined,
  },
}))

vi.mock('../../stompBackfill', () => ({
  stompBackfillApi: {
    getUnreadCount: mocks.getUnreadCount,
    list: mocks.list,
  },
}))

vi.mock('../../../lib/stomp', () => ({
  getStompClient: () => mocks.client,
}))

vi.mock('../../../components/ui/ToastProvider', () => ({
  useOptionalToast: () => ({ showToast: mocks.showToast }),
}))

const userId = 74683223
const since = '2026-05-19T00:00:00.000Z'

/** 최근(now 기준 1분 전) ISO — recency 필터(5분)를 통과해 토스트 재생 대상이 되는 시각. */
function recentIso(): string {
  return new Date(Date.now() - 60 * 1000).toISOString()
}

/**
 * 실제 재연결을 시뮬레이트: 최초 연결(mount)은 suppress 이므로,
 * onConnect 를 한 번 더 호출해 재연결 사이클을 발생시킨다(hasConnectedRef=true → 토스트 재생).
 */
async function triggerReconnect(): Promise<void> {
  mocks.showToast.mockClear()
  mocks.client.onConnect?.({})
}

beforeEach(() => {
  mocks.getUnreadCount.mockReset()
  mocks.list.mockReset()
  mocks.showToast.mockReset()
  mocks.client.connected = true
  mocks.client.onConnect = undefined
  localStorage.clear()
  localStorage.setItem(`stomp:lastSeenAt:${userId}`, since)
})

describe('useStompReconnect', () => {
  it('order/signal/risk 채널만 unread count 조회하고 system-event 는 제외한다', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) =>
      Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel }),
    )

    renderHook(() => useStompReconnect({ userId }))

    await waitFor(() => {
      expect(mocks.getUnreadCount).toHaveBeenCalledTimes(3)
    })

    expect(mocks.getUnreadCount.mock.calls.map((call) => call[2])).toEqual([
      'order',
      'signal',
      'risk',
    ])
    expect(mocks.list).not.toHaveBeenCalled()
    expect(mocks.showToast).not.toHaveBeenCalled()
  })

  it('backend content 응답을 payloadJson 기준으로 파싱하고 lastTs 로 anchor 를 갱신한다', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({
          count: 108,
          firstTs: '2026-05-20T00:00:00Z',
          lastTs: '2026-05-20T00:10:00Z',
          since,
          channel,
        })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      return Promise.resolve([
        {
          id: 1,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson:
            '{"event":"FILLED","code":"005930","quantity":3,"executedPrice":70000,"partial":false}',
          createdAt: recentIso(),
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))
    // 최초 연결(mount)은 suppress — 실제 재연결을 트리거해야 토스트가 재생된다.
    await waitFor(() => expect(mocks.getUnreadCount).toHaveBeenCalled())
    await triggerReconnect()

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith(
        '누락된 이벤트 108건 감지 — 최근 100건만 표시. 매매일지·주문 내역 확인 권장.',
        'warning',
      )
    })

    expect(mocks.showToast.mock.calls.some((call) => String(call[0]).includes('체결 완료'))).toBe(true)
  })

  // ────────────────────────────────────────────────────────────
  // hotfix-3 결함 3건 회귀 가드 (사용자 dogfooding 2026-05-21 새벽)
  // ────────────────────────────────────────────────────────────

  it('결함 3-A: FILLED + qty=null·executedQty=null 옛 row 는 toast 0건 (skip)', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({
          count: 1,
          firstTs: '2026-05-20T00:09:00Z',
          lastTs: '2026-05-20T00:09:00Z',
          since,
          channel,
        })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      // PR #636 머지 이전 row — quantity·executedQty 둘 다 null
      return Promise.resolve([
        {
          id: 99,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson: '{"event":"FILLED","code":"004090","partial":false}',
          createdAt: recentIso(),
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))
    await waitFor(() => expect(mocks.getUnreadCount).toHaveBeenCalled())
    await triggerReconnect()

    await waitFor(() => {
      expect(mocks.list).toHaveBeenCalled()
    })

    // toast 0건 검증 — "0주" 오인 표시 회귀 가드 (최근 이벤트여도 qty null 이면 skip)
    const orderToastCalls = mocks.showToast.mock.calls.filter((call) =>
      /체결|주문/.test(String(call[0])),
    )
    expect(orderToastCalls).toHaveLength(0)
  })

  it('결함 3-A: FILLED + quantity 정상 (5주) 이벤트는 정상 표시 (skip 회귀 없음)', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({
          count: 1,
          firstTs: '2026-05-20T00:44:00Z',
          lastTs: '2026-05-20T00:44:00Z',
          since,
          channel,
        })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      return Promise.resolve([
        {
          id: 100,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson:
            '{"event":"FILLED","code":"004090","quantity":5,"executedPrice":13550,"partial":false}',
          createdAt: recentIso(),
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))
    await waitFor(() => expect(mocks.getUnreadCount).toHaveBeenCalled())
    await triggerReconnect()

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalled()
    })

    const orderToastCalls = mocks.showToast.mock.calls.filter((call) =>
      String(call[0]).includes('체결 완료'),
    )
    expect(orderToastCalls).toHaveLength(1)
    expect(String(orderToastCalls[0][0])).toContain('004090')
    expect(String(orderToastCalls[0][0])).toContain('5주')
    expect(String(orderToastCalls[0][0])).toContain('13,550')
  })

  it('qty drift 정합: 백엔드 계약 필드(qty·rejectReason)로 온 REQUESTED 는 실수량 표시 (0주 회귀 방지)', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({
          count: 1,
          firstTs: '2026-07-06T00:55:00Z',
          lastTs: '2026-07-06T00:55:00Z',
          since,
          channel,
        })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      return Promise.resolve([
        {
          id: 101,
          userId,
          channel: 'order',
          eventType: 'REQUESTED',
          // StompOrderNotifier(OrderStompPayload) 실계약 — qty(quantity 아님)·limitPrice 문자열
          payloadJson:
            '{"event":"REQUESTED","code":"014910","side":"BUY","qty":9,"priceType":"LIMIT","limitPrice":"2775"}',
          createdAt: recentIso(),
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))
    await waitFor(() => expect(mocks.getUnreadCount).toHaveBeenCalled())
    await triggerReconnect()

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalled()
    })

    const orderToastCalls = mocks.showToast.mock.calls.filter((call) =>
      String(call[0]).includes('주문 접수'),
    )
    expect(orderToastCalls).toHaveLength(1)
    expect(String(orderToastCalls[0][0])).toContain('014910')
    expect(String(orderToastCalls[0][0])).toContain('매수 9주')
    expect(String(orderToastCalls[0][0])).not.toContain('0주')
  })

  it('결함 3-B: missedCount=0 이어도 lastSeenAt 을 now 로 갱신 (anchor 누적 보장)', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) =>
      Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel }),
    )

    const before = localStorage.getItem(`stomp:lastSeenAt:${userId}`)
    expect(before).toBe(since)

    renderHook(() => useStompReconnect({ userId }))

    await waitFor(() => {
      expect(mocks.getUnreadCount).toHaveBeenCalledTimes(3)
    })

    // 새로고침마다 anchor 가 now 로 전진해야 옛 이벤트 재 backfill 무한루프 차단
    const after = localStorage.getItem(`stomp:lastSeenAt:${userId}`)
    expect(after).not.toBe(since)
    expect(after).not.toBeNull()
    // ISO 형식 + since(2026-05-19) 보다 미래여야 함
    if (after) {
      expect(Date.parse(after)).toBeGreaterThan(Date.parse(since))
    }
  })

  it('결함 3-C: toast message 에 "[누락]" prefix 가 없고 시각 prefix 가 있다', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({
          count: 1,
          firstTs: '2026-05-20T00:44:00Z',
          lastTs: '2026-05-20T00:44:00Z',
          since,
          channel,
        })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      return Promise.resolve([
        {
          id: 200,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson:
            '{"event":"FILLED","code":"004090","quantity":5,"executedPrice":13550,"partial":false}',
          createdAt: recentIso(),
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))
    await waitFor(() => expect(mocks.getUnreadCount).toHaveBeenCalled())
    await triggerReconnect()

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalled()
    })

    const fillToast = mocks.showToast.mock.calls.find((call) =>
      String(call[0]).includes('체결 완료'),
    )
    expect(fillToast).toBeDefined()
    if (fillToast) {
      const message = String(fillToast[0])
      // "[누락]" prefix 제거 회귀 가드
      expect(message).not.toContain('[누락]')
      // KST HH:mm prefix 패턴 검증
      expect(message).toMatch(/^\[\d{2}:\d{2}\]\s/)
    }
  })

  // ────────────────────────────────────────────────────────────
  // 새로고침 유령 토스트 회귀 가드 (대운님 2026-09-21)
  // ────────────────────────────────────────────────────────────

  it('최초 연결(새로고침)에는 backfill 토스트를 재생하지 않는다', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({ count: 1, firstTs: recentIso(), lastTs: recentIso(), since, channel })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      return Promise.resolve([
        {
          id: 300,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson:
            '{"event":"FILLED","code":"035720","quantity":4,"executedPrice":35500,"partial":false}',
          createdAt: recentIso(),
        },
      ])
    })

    // 최초 연결(mount)만 — 재연결 트리거 없음
    renderHook(() => useStompReconnect({ userId }))
    await waitFor(() => expect(mocks.getUnreadCount).toHaveBeenCalled())

    // 최근 이벤트여도 최초 연결이면 토스트 0건 — 새로고침 유령 토스트 차단
    const orderToastCalls = mocks.showToast.mock.calls.filter((call) =>
      /체결|주문/.test(String(call[0])),
    )
    expect(orderToastCalls).toHaveLength(0)
  })

  it('재연결이어도 오래된(5분 초과) 이벤트는 토스트로 재생하지 않는다', async () => {
    const oldIso = new Date(Date.now() - 60 * 60 * 1000).toISOString() // 1시간 전
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({ count: 1, firstTs: oldIso, lastTs: oldIso, since, channel })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      return Promise.resolve([
        {
          id: 301,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson:
            '{"event":"FILLED","code":"035720","quantity":4,"executedPrice":35500,"partial":false}',
          createdAt: oldIso,
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))
    await waitFor(() => expect(mocks.getUnreadCount).toHaveBeenCalled())
    await triggerReconnect()
    await waitFor(() => expect(mocks.list).toHaveBeenCalled())

    const orderToastCalls = mocks.showToast.mock.calls.filter((call) =>
      /체결|주문/.test(String(call[0])),
    )
    expect(orderToastCalls).toHaveLength(0)
  })
})
