import { describe, it, expect, beforeEach } from 'vitest'
import { useMarketTickStore } from './marketTick'

describe('marketTickStore', () => {
  beforeEach(() => {
    useMarketTickStore.setState({ ticks: {}, latestTick: {} })
  })

  it('초기 상태 — 빈 객체', () => {
    const { ticks, latestTick } = useMarketTickStore.getState()
    expect(ticks).toEqual({})
    expect(latestTick).toEqual({})
  })

  it('addTick — 틱 추가', () => {
    const tick = { code: '005930', price: 78500, volume: 100, timestamp: 1_700_000_000 }
    useMarketTickStore.getState().addTick(tick)

    const state = useMarketTickStore.getState()
    expect(state.ticks['005930']).toHaveLength(1)
    expect(state.ticks['005930'][0]).toEqual(tick)
    expect(state.latestTick['005930']).toEqual(tick)
  })

  it('addTick — 여러 종목 독립 버퍼', () => {
    useMarketTickStore.getState().addTick({ code: 'AAA', price: 1000, volume: 10, timestamp: 1 })
    useMarketTickStore.getState().addTick({ code: 'BBB', price: 2000, volume: 20, timestamp: 2 })

    const state = useMarketTickStore.getState()
    expect(Object.keys(state.ticks)).toHaveLength(2)
  })

  it('clearTicks — 특정 종목만 삭제', () => {
    useMarketTickStore.getState().addTick({ code: '005930', price: 78500, volume: 10, timestamp: 1 })
    useMarketTickStore.getState().addTick({ code: '000660', price: 150000, volume: 5, timestamp: 2 })
    useMarketTickStore.getState().clearTicks('005930')

    const state = useMarketTickStore.getState()
    expect(state.ticks['005930']).toBeUndefined()
    expect(state.ticks['000660']).toHaveLength(1)
  })

  it('addTick — MAX_TICKS(500) 초과 시 앞에서 제거', () => {
    const store = useMarketTickStore.getState()
    for (let i = 0; i < 502; i++) {
      store.addTick({ code: 'TEST', price: i, volume: 1, timestamp: i })
    }
    const ticks = useMarketTickStore.getState().ticks['TEST']
    expect(ticks.length).toBe(500)
    expect(ticks[0].price).toBe(2)
  })
})
