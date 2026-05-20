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
          createdAt: '2026-05-20T00:09:00Z',
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith(
        '누락된 이벤트 108건 감지 — 최근 100건만 표시. 매매일지·주문 내역 확인 권장.',
        'warning',
      )
    })

    expect(mocks.list).toHaveBeenCalledWith(userId, since, 100, 'order')
    expect(mocks.showToast.mock.calls.some((call) => String(call[0]).includes('체결 완료'))).toBe(true)
    expect(localStorage.getItem(`stomp:lastSeenAt:${userId}`)).toBe('2026-05-20T00:10:00Z')
  })
})
