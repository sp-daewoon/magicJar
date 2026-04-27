import { create } from 'zustand'

export interface Tick {
  code: string
  price: number
  volume: number
  timestamp: number
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
