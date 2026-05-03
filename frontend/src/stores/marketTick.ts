/**
 * marketTick store — 멀티 종목 실시간 시세 상태.
 * T2-R4: stale·source·asOf 필드 추가 (대시보드 종목 카드 StaleIndicator 렌더링).
 */
import { create } from 'zustand'
import type { QuoteSource } from '@/api/types'

export interface Tick {
  code: string
  price: number
  volume: number
  timestamp: number
  // T2-R4 추가: 데이터 품질 필드
  stale?: boolean
  source?: QuoteSource
  asOf?: number  // epoch ms (stale=true 시 의미 있음)
}

const MAX_TICKS = 500

interface MarketTickState {
  ticks: Record<string, Tick[]>
  latestTick: Record<string, Tick>
  addTick: (tick: Tick) => void
  clearTicks: (code: string) => void
}

export const useMarketTickStore = create<MarketTickState>((set) => ({
  ticks: {},
  latestTick: {},
  addTick: (tick) =>
    set((state) => {
      const existing = state.ticks[tick.code] ?? []
      const next = [...existing, tick]
      if (next.length > MAX_TICKS) {
        next.splice(0, next.length - MAX_TICKS)
      }
      return {
        ticks: { ...state.ticks, [tick.code]: next },
        latestTick: { ...state.latestTick, [tick.code]: tick },
      }
    }),
  clearTicks: (code) =>
    set((state) => {
      const { [code]: _removed, ...rest } = state.ticks
      const { [code]: _removedLatest, ...restLatest } = state.latestTick
      return { ticks: rest, latestTick: restLatest }
    }),
}))
