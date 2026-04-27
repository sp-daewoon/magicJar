import { create } from 'zustand'

export interface Position {
  code: string
  name: string
  quantity: number
  avgPrice: number
  currentPrice: number
  unrealizedPnl: number
  unrealizedPnlRate: number
}

interface PositionState {
  positions: Position[]
  totalPnl: number
  setPositions: (positions: Position[]) => void
  updateCurrentPrice: (code: string, price: number) => void
  clearPositions: () => void
}

function calcPnl(positions: Position[]): number {
  return positions.reduce((sum, p) => sum + p.unrealizedPnl, 0)
}

export const usePositionStore = create<PositionState>((set) => ({
  positions: [],
  totalPnl: 0,
  setPositions: (positions) =>
    set({ positions, totalPnl: calcPnl(positions) }),
  updateCurrentPrice: (code, price) =>
    set((state) => {
      const updated = state.positions.map((p) => {
        if (p.code !== code) return p
        const unrealizedPnl = (price - p.avgPrice) * p.quantity
        const unrealizedPnlRate = p.avgPrice > 0 ? ((price - p.avgPrice) / p.avgPrice) * 100 : 0
        return { ...p, currentPrice: price, unrealizedPnl, unrealizedPnlRate }
      })
      return { positions: updated, totalPnl: calcPnl(updated) }
    }),
  clearPositions: () => set({ positions: [], totalPnl: 0 }),
}))
