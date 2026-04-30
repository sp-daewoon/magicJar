/**
 * useWsConnectionState — STOMP 연결 상태 React 훅
 */
import { useEffect, useState } from 'react'
import { stompService, type ConnectionState } from '@/ws/stomp'

export function useWsConnectionState(): ConnectionState {
  const [state, setState] = useState<ConnectionState>(stompService.state)

  useEffect(() => {
    const unsubscribe = stompService.onStateChange(setState)
    return unsubscribe
  }, [])

  return state
}
