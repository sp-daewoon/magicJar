/**
 * useNewsAnalyzed — 전역 STOMP `/topic/news` 구독 + 종목 코드 필터 + 최근 N건 버퍼.
 *
 * 백엔드는 종목별 분리 토픽 안 만들고 단일 `/topic/news`로 fanout하는 구조.
 * 클라이언트가 NewsAnalyzedMessage.instruments 배열을 보고 매칭 코드만 보존.
 */
import { useEffect, useState } from 'react'
import { stompService } from '@/ws/stomp'
import type { NewsAnalyzedMessage } from '@/api/types'

export function useNewsAnalyzed(code: string, max = 10): NewsAnalyzedMessage[] {
  const [items, setItems] = useState<NewsAnalyzedMessage[]>([])

  useEffect(() => {
    if (!code) return

    setItems([])
    stompService.connect('/ws')

    const unsubscribe = stompService.subscribe('/topic/news', (msg) => {
      try {
        const data: NewsAnalyzedMessage = JSON.parse(msg.body)
        if (!data.instruments?.includes(code)) return
        setItems((prev) => {
          // newsId 중복 제거 + 최신순 정렬
          const filtered = prev.filter((p) => p.newsId !== data.newsId)
          const next = [data, ...filtered]
          return next.slice(0, max)
        })
      } catch {
        // ignore parse error
      }
    })

    return () => {
      unsubscribe()
    }
  }, [code, max])

  return items
}
