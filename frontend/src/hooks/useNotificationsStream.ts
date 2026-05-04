/**
 * useNotificationsStream — STOMP `/topic/notifications` 구독 + in-memory toast queue.
 *
 * 백엔드 NotificationEvent (subscription eviction · order · system)를 받아 banner로 표시.
 * 5초 자동 dismiss + 사용자 명시 dismiss 지원.
 *
 * 사용:
 *   const { toasts, dismiss } = useNotificationsStream()
 *   {toasts.map((t) => <Toast key={t.id} ... />)}
 */
import { useEffect, useState, useCallback } from 'react'
import { stompService } from '@/ws/stomp'
import type { NotificationMessage } from '@/api/types'

const AUTO_DISMISS_MS = 5_000

export function useNotificationsStream() {
  const [toasts, setToasts] = useState<NotificationMessage[]>([])

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  useEffect(() => {
    stompService.connect('/ws')

    const unsubscribe = stompService.subscribe('/topic/notifications', (msg) => {
      try {
        const data: NotificationMessage = JSON.parse(msg.body)
        // 중복 dedup (Kafka at-least-once 대비)
        setToasts((prev) => (prev.some((t) => t.id === data.id) ? prev : [...prev, data]))

        // 자동 dismiss
        setTimeout(() => dismiss(data.id), AUTO_DISMISS_MS)
      } catch {
        console.warn('[useNotificationsStream] parse error', msg.body)
      }
    })

    return () => unsubscribe()
  }, [dismiss])

  return { toasts, dismiss }
}
