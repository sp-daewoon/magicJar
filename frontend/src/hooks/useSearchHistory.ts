/**
 * useSearchHistory — 종목 검색 기록 LRU 20개 관리 훅
 *
 * localStorage key: magicjar:search-history:v1
 * 데이터: [{code, name, market, lastVisitedAt}], 최신 순, 최대 20건
 * API: addToHistory / getHistory / removeFromHistory / clearHistory
 */
import { useState, useCallback } from 'react'
import type { Market } from '@/api/types'

const STORAGE_KEY = 'magicjar:search-history:v1'
const MAX_HISTORY = 20

export interface SearchHistoryItem {
  code: string
  name: string
  market: Market
  lastVisitedAt: string // ISO-8601
}

function loadHistory(): SearchHistoryItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (item): item is SearchHistoryItem =>
        typeof item === 'object' &&
        item !== null &&
        typeof item.code === 'string' &&
        typeof item.name === 'string' &&
        typeof item.market === 'string' &&
        typeof item.lastVisitedAt === 'string',
    )
  } catch {
    return []
  }
}

function saveHistory(items: SearchHistoryItem[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items))
  } catch {
    // localStorage 비활성·할당 초과 시 무시
  }
}

/**
 * 기록에 항목 추가 (중복 시 lastVisitedAt 갱신 후 맨 앞으로 이동).
 * 20건 초과 시 가장 오래된 항목 제거 (LRU).
 */
function addItem(
  history: SearchHistoryItem[],
  item: Omit<SearchHistoryItem, 'lastVisitedAt'>,
): SearchHistoryItem[] {
  const now = new Date().toISOString()
  const filtered = history.filter((h) => h.code !== item.code)
  const updated: SearchHistoryItem[] = [
    { ...item, lastVisitedAt: now },
    ...filtered,
  ]
  return updated.slice(0, MAX_HISTORY)
}

export function useSearchHistory() {
  const [history, setHistory] = useState<SearchHistoryItem[]>(() => loadHistory())

  const addToHistory = useCallback(
    (item: Omit<SearchHistoryItem, 'lastVisitedAt'>) => {
      setHistory((prev) => {
        const next = addItem(prev, item)
        saveHistory(next)
        return next
      })
    },
    [],
  )

  const removeFromHistory = useCallback((code: string) => {
    setHistory((prev) => {
      const next = prev.filter((h) => h.code !== code)
      saveHistory(next)
      return next
    })
  }, [])

  const clearHistory = useCallback(() => {
    setHistory([])
    saveHistory([])
  }, [])

  const getHistory = useCallback((): SearchHistoryItem[] => {
    return history
  }, [history])

  return {
    history,
    addToHistory,
    removeFromHistory,
    clearHistory,
    getHistory,
  }
}
