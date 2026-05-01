import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

export type ConnectionState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'ERROR'

type StateListener = (state: ConnectionState) => void
type MessageListener = (msg: IMessage) => void

class StompService {
  private client: Client | null = null
  private subscriptions = new Map<string, StompSubscription>()
  private stateListeners = new Set<StateListener>()
  private pendingSubscriptions = new Map<string, MessageListener>()
  private _state: ConnectionState = 'DISCONNECTED'

  private setState(state: ConnectionState) {
    this._state = state
    this.stateListeners.forEach((l) => l(state))
  }

  get state(): ConnectionState {
    return this._state
  }

  connect(url = '/ws') {
    if (this.client?.connected) return

    this.setState('CONNECTING')

    // brokerURL은 raw WebSocket 절대 URL 필요 (ws:// 또는 wss://).
    // 상대 경로 '/ws'면 location.protocol·host 기반으로 동적 생성.
    const brokerURL =
      url.startsWith('ws://') || url.startsWith('wss://')
        ? url
        : (() => {
            const proto =
              typeof window !== 'undefined' && window.location.protocol === 'https:'
                ? 'wss:'
                : 'ws:'
            const host = typeof window !== 'undefined' ? window.location.host : 'localhost:3000'
            const path = url.startsWith('/') ? url : `/${url}`
            return `${proto}//${host}${path}`
          })()

    this.client = new Client({
      brokerURL,
      reconnectDelay: 5_000,
      onConnect: () => {
        this.setState('CONNECTED')
        // 재연결 시 pending 구독 복원
        this.pendingSubscriptions.forEach((handler, topic) => {
          const sub = this.client!.subscribe(topic, handler)
          this.subscriptions.set(topic, sub)
        })
      },
      onStompError: () => {
        this.setState('ERROR')
      },
      onDisconnect: () => {
        this.setState('DISCONNECTED')
      },
      onWebSocketError: () => {
        this.setState('ERROR')
      },
    })

    this.client.activate()
  }

  disconnect() {
    this.client?.deactivate()
    this.subscriptions.clear()
    this.pendingSubscriptions.clear()
    this.setState('DISCONNECTED')
  }

  subscribe(topic: string, handler: MessageListener): () => void {
    // 핸들러 항상 저장 (재연결 대비)
    this.pendingSubscriptions.set(topic, handler)

    if (this.client?.connected) {
      const sub = this.client.subscribe(topic, handler)
      this.subscriptions.set(topic, sub)
    }

    return () => {
      this.pendingSubscriptions.delete(topic)
      const sub = this.subscriptions.get(topic)
      if (sub) {
        sub.unsubscribe()
        this.subscriptions.delete(topic)
      }
    }
  }

  publish(destination: string, body: unknown) {
    if (!this.client?.connected) {
      console.warn('[STOMP] publish skipped — not connected', destination)
      return
    }
    this.client.publish({
      destination,
      body: JSON.stringify(body),
    })
  }

  onStateChange(listener: StateListener): () => void {
    this.stateListeners.add(listener)
    return () => this.stateListeners.delete(listener)
  }
}

// 싱글턴
export const stompService = new StompService()

/**
 * STOMP 연결 상태 React hook 용 helper
 * 실제 hook은 src/hooks/useStomp.ts에서 정의 (P4)
 */
export function subscribeToTopic(topic: string, handler: MessageListener) {
  return stompService.subscribe(topic, handler)
}
