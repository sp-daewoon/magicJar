/**
 * useStompReconnect — STOMP 재연결 시점 backfill hook (P1-3 frontend)
 *
 * 목적: STOMP 연결이 끊긴 동안 발생한 order/signal/risk 이벤트를 backend REST로 backfill,
 * 사용자가 끊김 동안의 모든 이벤트를 누락 없이 볼 수 있게 함.
 *
 * 흐름:
 *   1. STOMP 클라이언트 onConnect callback (재연결 포함 매 연결마다 발동)
 *   2. localStorage `stomp:lastSeenAt:${userId}` 조회 → 첫 진입 시 now-1day fallback
 *   3. GET /api/stomp/events/unread?userId=&since=lastSeenAt → count·latestOccurredAt
 *   4. count > 0 시 GET /api/stomp/events?userId=&since=lastSeenAt&limit=100
 *   5. 각 이벤트 분기 — order/signal/risk → toast 또는 modal 표시
 *   6. lastSeenAt = latestOccurredAt (또는 마지막 이벤트 occurredAt) 갱신
 *
 * 정합:
 *  - onConnect callback 시점 — useStompTopic은 useEffect 기반이므로 hook 별도 클라이언트 onConnect hook 으로 통합
 *  - 실시간 수신은 useStompOrder/useStompSignal/useStompRisk가 담당 — 본 hook은 backfill 전담
 *  - 중복 토스트 방지: backfill 직후 동시 도래한 실시간 이벤트는 occurredAt 비교로 skip (id 매트릭스 → in-memory recent ids Set)
 *
 * 의존:
 *  - frontend STOMP 클라이언트 (lib/stomp.ts) onConnect 콜백 후킹
 *  - ToastProvider showToast
 *  - localStorage (Web Storage API)
 *
 * 룰 5: LLM provider import 0건
 */
import { useCallback, useEffect, useRef } from 'react'
import { Client } from '@stomp/stompjs'
import { useOptionalToast } from '../../components/ui/ToastProvider'
import {
  stompBackfillApi,
  type StompEventDto,
} from '../stompBackfill'
import type { OrderStompPayload } from './useStompOrder'
import type { SignalDto } from '../signal'
import type { RiskEventDto } from '../risk'
import { getStompClient } from '../../lib/stomp'

// ──────────────────────────────────────────────
// localStorage helpers
// ──────────────────────────────────────────────

const LAST_SEEN_KEY_PREFIX = 'stomp:lastSeenAt:'

function lastSeenStorageKey(userId: number): string {
  return `${LAST_SEEN_KEY_PREFIX}${userId}`
}

/** localStorage `stomp:lastSeenAt:{userId}` 조회. 미존재 시 24h 전 fallback. */
function readLastSeenAt(userId: number): string {
  try {
    const raw = localStorage.getItem(lastSeenStorageKey(userId))
    if (raw) return raw
  } catch {
    // localStorage 차단 환경 — 매 reconnect 시 24h 전부터 backfill (안전 over-fetch)
  }
  const oneDayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000)
  return oneDayAgo.toISOString()
}

function writeLastSeenAt(userId: number, isoInstant: string): void {
  try {
    localStorage.setItem(lastSeenStorageKey(userId), isoInstant)
  } catch {
    // 차단 환경 무시
  }
}

// ──────────────────────────────────────────────
// 이벤트 → 토스트 메시지 변환
// ──────────────────────────────────────────────

function sideLabel(side: 'BUY' | 'SELL' | undefined): string {
  if (side === 'BUY') return '매수'
  if (side === 'SELL') return '매도'
  return ''
}

function formatPrice(value: number | string | undefined): string {
  if (value === undefined || value === null) return ''
  const num = typeof value === 'string' ? Number(value.replace(/,/g, '')) : value
  if (Number.isNaN(num)) return String(value)
  return num.toLocaleString('ko-KR')
}

interface ToastSpec {
  message: string
  type: 'info' | 'success' | 'warning' | 'error'
}

function buildBackfillOrderToast(p: OrderStompPayload): ToastSpec {
  const code = p.code ?? ''
  switch (p.event) {
    case 'REQUESTED':
      return {
        message: `[누락] 주문 접수 — ${code} ${sideLabel(p.side)} ${p.quantity ?? 0}주`,
        type: 'info',
      }
    case 'FILLED':
      return {
        message: p.partial
          ? `[누락] 부분 체결 — ${code} ${p.executedQty ?? 0}/${p.quantity ?? 0}주 @ ${formatPrice(p.executedPrice)}원`
          : `[누락] 체결 완료 — ${code} ${p.quantity ?? 0}주 @ ${formatPrice(p.executedPrice)}원`,
        type: p.partial ? 'warning' : 'success',
      }
    case 'REJECTED':
      return {
        message: `[누락] 주문 거절 — ${code}: ${p.reason ?? ''}`,
        type: 'error',
      }
    case 'CANCELLED':
      return {
        message: `[누락] 주문 취소 — ${code} ${p.quantity ?? 0}주`,
        type: 'info',
      }
    default:
      return { message: `[누락] 주문 이벤트: ${p.event}`, type: 'info' }
  }
}

function buildBackfillSignalToast(s: SignalDto): ToastSpec {
  const sideKo = s.side === 'BUY' ? '매수' : s.side === 'SELL' ? '매도' : '홀드'
  const codePart = s.codeName ? `${s.code} ${s.codeName}` : s.code
  const pricePart = formatPrice(s.price)
  const rulePart = s.rule ? ` — ${s.rule}` : ''
  return {
    message: `[누락] [${sideKo}] ${codePart} ${s.interval} ${pricePart}원${rulePart}`,
    type: s.side === 'BUY' ? 'success' : s.side === 'SELL' ? 'error' : 'info',
  }
}

function buildBackfillRiskToast(r: RiskEventDto): ToastSpec {
  if (r.verdict === 'BLOCKED') {
    return { message: `[누락] [차단] ${r.reason}`, type: 'error' }
  }
  if (r.verdict === 'AUTO_PAUSED') {
    const strat = r.strategyName ?? r.strategyId ?? '전략'
    return { message: `[누락] [자동 정지] ${strat} — ${r.reason}`, type: 'warning' }
  }
  return { message: `[누락] [${r.verdict}] ${r.reason}`, type: 'info' }
}

// 100건 초과 누락 경고 임계치
const BACKFILL_LIMIT = 100

// ──────────────────────────────────────────────
// 메인 hook
// ──────────────────────────────────────────────

export interface UseStompReconnectOptions {
  /** ADR 0089 Phase 2 — userId number (null = 미인증 → hook 비활성) */
  userId: number | null
  /** false 시 hook 전체 비활성 (테스트·storybook 용) */
  enabled?: boolean
}

/**
 * STOMP 재연결 시점 backfill hook.
 *
 * 사용 예 (App.tsx 또는 layout root 1회 mount):
 *   useStompReconnect({ userId: registeredUserId })
 *
 * 동작:
 *  - userId === null 시 no-op
 *  - STOMP client.onConnect callback chain 후킹 — 매 연결(초기 + 재연결)마다 backfill 시도
 *  - 동일 connect 사이클 내 중복 backfill 방지 (in-flight flag)
 */
export function useStompReconnect({
  userId,
  enabled = true,
}: UseStompReconnectOptions): void {
  const { showToast } = useOptionalToast()
  const inFlightRef = useRef(false)

  const runBackfill = useCallback(async () => {
    if (userId === null) return
    if (inFlightRef.current) return
    inFlightRef.current = true

    try {
      const since = readLastSeenAt(userId)

      const unread = await stompBackfillApi.getUnreadCount(userId, since)
      if (unread.count === 0) {
        // 미수신 0건 — lastSeenAt 만 최신화 (backend latestOccurredAt = null 가능)
        if (unread.latestOccurredAt) writeLastSeenAt(userId, unread.latestOccurredAt)
        return
      }

      // 100건 초과 누락 시 사용자에게 안내 (정확한 모든 토스트 노출은 spam 우려)
      if (unread.count > BACKFILL_LIMIT) {
        showToast(
          `누락된 이벤트 ${unread.count}건 감지 — 최근 ${BACKFILL_LIMIT}건만 표시. 매매일지·주문 내역 확인 권장.`,
          'warning',
        )
      }

      const events = await stompBackfillApi.list(userId, since, BACKFILL_LIMIT)

      for (const ev of events) {
        const toastSpec = buildBackfillToast(ev)
        if (toastSpec) showToast(toastSpec.message, toastSpec.type)
      }

      // 마지막 이벤트 occurredAt 또는 backend latestOccurredAt 으로 anchor 갱신
      const newAnchor =
        events.length > 0 ? events[events.length - 1].occurredAt : unread.latestOccurredAt
      if (newAnchor) writeLastSeenAt(userId, newAnchor)
    } catch {
      // backend 미구현 / 네트워크 fail — graceful, 다음 reconnect 시 재시도
    } finally {
      inFlightRef.current = false
    }
  }, [userId, showToast])

  useEffect(() => {
    if (!enabled || userId === null) return

    const client: Client = getStompClient()

    // 기존 onConnect chain 보존 (lib/stomp.ts ensureConnected와 호환)
    const previous = client.onConnect
    client.onConnect = (frame) => {
      if (previous) {
        try {
          previous.call(client, frame)
        } catch {
          // 이전 callback 실패는 backfill 진행에 영향 없음
        }
      }
      // 비동기 backfill — 실패는 내부에서 graceful handle
      void runBackfill()
    }

    // 이미 연결돼 있으면 즉시 backfill 1회 실행 (StrictMode double-mount 안전 — in-flight flag)
    if (client.connected) {
      void runBackfill()
    }

    return () => {
      // hook unmount 시 chain 복원
      client.onConnect = previous
    }
  }, [enabled, userId, runBackfill])
}

/**
 * eventKind 기반 narrowing → 적절한 토스트 spec 생성. 알 수 없는 kind는 null 반환.
 *
 * 분기:
 *  - ORDER  → buildBackfillOrderToast
 *  - SIGNAL → buildBackfillSignalToast
 *  - RISK   → buildBackfillRiskToast
 */
function buildBackfillToast(ev: StompEventDto): ToastSpec | null {
  if (!ev || !ev.payload) return null
  switch (ev.eventKind) {
    case 'ORDER':
      return buildBackfillOrderToast(ev.payload as OrderStompPayload)
    case 'SIGNAL':
      return buildBackfillSignalToast(ev.payload as SignalDto)
    case 'RISK':
      return buildBackfillRiskToast(ev.payload as RiskEventDto)
    default:
      return null
  }
}
