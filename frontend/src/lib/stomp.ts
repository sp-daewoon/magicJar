/**
 * STOMP 클라이언트 싱글톤 + useStompTopic 커스텀 훅
 *
 * spec §6-4 LOCK:
 * - @stomp/stompjs Client 싱글톤 (re-render 시 재연결 방지)
 * - brokerURL: ws://localhost:8080/ws (dev 환경 — Vite proxy /ws)
 * - reconnectDelay: 5000ms
 * - onConnect 시 subscribe, cleanup 시 unsubscribe
 * - 재연결 시 connection generation 증가 → useStompTopic 자동 재구독 (audit P1-⑨)
 * - 연결 상태 인디케이터 (onConnect / onStompError / onDisconnect)
 *
 * STOMP topic 매트릭스 (Phase 2 새 명명규칙 — 권위: api/stompDestinations.ts 빌더):
 *   /topic/stomp.api.market.tick.{code}
 *   /topic/stomp.api.market.candle.1m.{code}
 *   /topic/stomp.api.market.orderbook.{code}
 */
import { Client } from '@stomp/stompjs'
import { useEffect, useRef, useSyncExternalStore } from 'react'

// ──────────────────────────────────────────────
// 연결 세대(generation) 추적 — 재연결 시 useStompTopic 재구독 (P1-⑨)
// ──────────────────────────────────────────────

/**
 * 브로커 CONNECT 성공 누적 카운터.
 *
 * Client 생성 시점의 base onConnect(콜백 체인 최하단)에서 증가한다.
 * useStompReconnect / useStompConnectionState / ensureConnected 는 모두
 * `previous = client.onConnect` 를 보존 호출하는 chain-wrap 패턴이므로,
 * 어떤 조합으로 후킹돼도 매 연결(초기 + 재연결)마다 정확히 1회 증가한다.
 *
 * useStompTopic 은 이 세대를 useSyncExternalStore 로 구독해 재연결 시
 * subscribe effect 를 재실행한다 (기존 구독은 끊긴 연결의 것이므로 재구독 필수).
 */
let connectionGeneration = 0
const generationListeners = new Set<() => void>()

function subscribeConnectionGeneration(listener: () => void): () => void {
  generationListeners.add(listener)
  return () => {
    generationListeners.delete(listener)
  }
}

function getConnectionGeneration(): number {
  return connectionGeneration
}

function bumpConnectionGeneration(): void {
  connectionGeneration += 1
  for (const listener of [...generationListeners]) {
    try {
      listener()
    } catch {
      // listener 실패는 다른 listener·연결 흐름에 영향 없음
    }
  }
}

/** JSON 파싱 실패 누적 카운터 (진단용 — silent drop 방지, P3) */
let jsonParseFailureCount = 0

// ──────────────────────────────────────────────
// STOMP 클라이언트 싱글톤
// ──────────────────────────────────────────────

/** 브라우저 dev 환경: Vite proxy /ws → ws://localhost:8080/ws */
const WS_BROKER_URL =
  typeof window !== 'undefined'
    ? `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`
    : 'ws://localhost:8080/ws'

let sharedClient: Client | null = null
let connectPromise: Promise<void> | null = null

/** STOMP 싱글톤 client getter — useStompReconnect.ts에서 onConnect chain 후킹용 export */
export function getStompClient(): Client {
  if (!sharedClient) {
    sharedClient = new Client({
      brokerURL: WS_BROKER_URL,
      reconnectDelay: 5000,
      // base onConnect — 콜백 체인의 최하단 (모든 후킹 hook 이 previous 로 보존 호출).
      // 연결 세대를 올려 useStompTopic 재구독을 트리거한다 (P1-⑨).
      onConnect: () => {
        bumpConnectionGeneration()
      },
      // 연결 로그 — DEV 환경에서만 출력 (P3: prod 프레임 로그 무음)
      debug: import.meta.env.DEV
        ? (msg: string) => {
            // eslint-disable-next-line no-console
            console.debug('[STOMP]', msg)
          }
        : () => {
            // production: STOMP 프레임 로그 무음
          },
    })
  }
  return sharedClient
}

function ensureConnected(): Promise<void> {
  const client = getStompClient()
  if (client.connected) {
    return Promise.resolve()
  }
  if (connectPromise) {
    return connectPromise
  }

  connectPromise = new Promise<void>((resolve, reject) => {
    const originalOnConnect = client.onConnect
    const originalOnStompError = client.onStompError

    client.onConnect = (frame) => {
      if (originalOnConnect) originalOnConnect.call(client, frame)
      resolve()
      connectPromise = null
    }
    client.onStompError = (frame) => {
      if (originalOnStompError) originalOnStompError.call(client, frame)
      reject(new Error(`STOMP 연결 오류: ${frame.headers['message']}`))
      connectPromise = null
    }

    if (!client.active) {
      client.activate()
    }
  })

  return connectPromise
}

// ──────────────────────────────────────────────
// useStompTopic 커스텀 훅
// ──────────────────────────────────────────────

/**
 * STOMP topic 구독 커스텀 훅
 *
 * @param topic - 구독할 topic (예: `/topic/stomp.api.market.tick.005930`)
 * @param onMessage - 메시지 수신 콜백
 * @param enabled - false이면 구독하지 않음 (조건부 구독용)
 *
 * 사용 예 (raw 문자열 대신 api/stompDestinations.ts 빌더 사용 권장):
 *   useStompTopic<TickPayload>(
 *     marketTick(code),
 *     (payload) => setCurrentPrice(payload.price),
 *     isStompEnabled
 *   )
 */
export function useStompTopic<T>(
  topic: string,
  onMessage: (payload: T) => void,
  enabled = true,
): void {
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage

  // 연결 세대 구독 — 재연결(세대 증가) 시 effect 재실행으로 새 연결 위에 재구독 (P1-⑨).
  // 끊긴 연결의 subscription 은 브로커 측에서 소멸하므로 세대 dep 없이는 재연결 후 수신 0 이 된다.
  const generation = useSyncExternalStore(
    subscribeConnectionGeneration,
    getConnectionGeneration,
    () => 0,
  )

  useEffect(() => {
    // generation 값 자체는 사용하지 않는다 — 재연결 시 effect 재실행(재구독) 트리거 dep 명시용 참조.
    void generation
    if (!enabled || !topic) return

    let unsubscribeFn: (() => void) | null = null
    let cancelled = false

    ensureConnected()
      .then(() => {
        if (cancelled) return
        const client = getStompClient()
        if (!client.connected) return

        const sub = client.subscribe(topic, (stompMsg) => {
          try {
            const payload = JSON.parse(stompMsg.body) as T
            onMessageRef.current(payload)
          } catch {
            // JSON 파싱 실패 — drop 하되 진단 로그 + 누적 카운터 (P3: silent drop 방지)
            jsonParseFailureCount += 1
            // eslint-disable-next-line no-console
            console.warn(
              `[STOMP] JSON 파싱 실패 (누적 ${jsonParseFailureCount}건) — topic: ${topic}`,
            )
          }
        })
        unsubscribeFn = () => sub.unsubscribe()
      })
      .catch(() => {
        // STOMP 연결 실패 — 화면별 fallback 정책(useSymbol pollingEnabled)이 처리한다.
      })

    return () => {
      cancelled = true
      unsubscribeFn?.()
    }
  }, [topic, enabled, generation])
}

/**
 * STOMP 연결 상태 (진단용)
 * UI에서 연결 상태 배지 표시 시 사용
 */
export function isStompConnected(): boolean {
  return sharedClient?.connected ?? false
}
