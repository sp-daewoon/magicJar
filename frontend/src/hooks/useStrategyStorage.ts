/**
 * LocalStorage 기반 전략 저장/불러오기 훅
 */

import { useState, useCallback } from 'react'
import type { SavedStrategy, StrategyBuilderState } from '@/api/strategy'

const STORAGE_KEY = 'magicjar_strategies_v1'

function loadStrategies(): SavedStrategy[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    return JSON.parse(raw) as SavedStrategy[]
  } catch {
    return []
  }
}

function saveStrategies(strategies: SavedStrategy[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(strategies))
}

export function useStrategyStorage() {
  const [savedStrategies, setSavedStrategies] = useState<SavedStrategy[]>(loadStrategies)

  const saveStrategy = useCallback((state: StrategyBuilderState): SavedStrategy => {
    const now = new Date().toISOString()
    // 같은 이름이면 업데이트, 아니면 신규 추가
    const existing = savedStrategies.find((s) => s.state.metadata.name === state.metadata.name)

    if (existing) {
      const updated: SavedStrategy = { ...existing, state, updatedAt: now }
      const next = savedStrategies.map((s) => (s.id === existing.id ? updated : s))
      saveStrategies(next)
      setSavedStrategies(next)
      return updated
    }

    const newStrategy: SavedStrategy = {
      id: `strategy-${Date.now()}`,
      createdAt: now,
      updatedAt: now,
      state,
    }
    const next = [...savedStrategies, newStrategy]
    saveStrategies(next)
    setSavedStrategies(next)
    return newStrategy
  }, [savedStrategies])

  const deleteStrategy = useCallback((id: string) => {
    const next = savedStrategies.filter((s) => s.id !== id)
    saveStrategies(next)
    setSavedStrategies(next)
  }, [savedStrategies])

  const refreshStrategies = useCallback(() => {
    setSavedStrategies(loadStrategies())
  }, [])

  return {
    savedStrategies,
    saveStrategy,
    deleteStrategy,
    refreshStrategies,
  }
}
