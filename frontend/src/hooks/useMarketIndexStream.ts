/**
 * useMarketIndexStream — 단일 지수 코드 STOMP 실시간 구독 훅.
 *
 * 동작 흐름:
 *   1. 마운트 시 STOMP `/topic/market.index.{code}` 구독.
 *   2. 메시지 수신 시 `latest` 상태 업데이트 (1초 KIS WS tick 주기).
 *   3. unmount 또는 code 변경 시 cleanup.
 *
 * fallback: STOMP 미연결·메시지 없음이면 `latest=null` — 상위 컴포넌트가 REST 응답으로 표시.
 *
 * Refs: plan T2-R2-A2 §T16, ADR 0049 §2-A (지수는 부팅 자동 구독 OK).
 */
import { useEffect, useState } from 'react'
import { stompService } from '@/ws/stomp'
import type { IndexTickMessage } from '@/api/types'

export function useMarketIndexStream(code: string | null | undefined): IndexTickMessage | null {
  const [latest, setLatest] = useState<IndexTickMessage | null>(null)

  useEffect(() => {
    if (!code) return

    setLatest(null)
    stompService.connect('/ws')

    const topic = `/topic/market.index.${code}`
    const unsubscribe = stompService.subscribe(topic, (msg) => {
      try {
        const data: IndexTickMessage = JSON.parse(msg.body)
        if (data.code !== code) {
          if (import.meta.env.DEV) {
            console.warn('[useMarketIndexStream] topic/code mismatch', { topic, expected: code, got: data.code })
          }
          return
        }
        setLatest(data)
      } catch {
        console.warn('[useMarketIndexStream] parse error', msg.body)
      }
    })

    return () => unsubscribe()
  }, [code])

  return latest
}
