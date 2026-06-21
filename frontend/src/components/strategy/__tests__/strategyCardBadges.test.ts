/**
 * strategyCardBadges 단위 테스트 — floor 알람/자동청산 · 분할매수 배지 파생 (list-ux-uplift)
 */
import { describe, expect, it } from 'vitest'
import type { ScaleInDto } from '../../../api/strategy'
import {
  floorBadge,
  floorMode,
  hasScaleIn,
  isFloorAlarmStrategy,
  isFloorStrategy,
  isScaleInStrategy,
  scaleInBadgeLabel,
  scaleInSliceCount,
} from '../strategyCardBadges'

function scaleIn(over: Partial<ScaleInDto>): ScaleInDto {
  return { totalSlices: 1, ...over }
}

describe('scaleInSliceCount / hasScaleIn / scaleInBadgeLabel', () => {
  it('null·단일 진입(1회)은 분할 아님', () => {
    expect(scaleInSliceCount(null)).toBe(0)
    expect(scaleInSliceCount(undefined)).toBe(0)
    expect(scaleInSliceCount(scaleIn({ totalSlices: 1 }))).toBe(0)
    expect(hasScaleIn(scaleIn({ totalSlices: 1 }))).toBe(false)
    expect(scaleInBadgeLabel(scaleIn({ totalSlices: 1 }))).toBeNull()
  })

  it('2회 이상이면 분할 N회 배지', () => {
    expect(scaleInSliceCount(scaleIn({ totalSlices: 3 }))).toBe(3)
    expect(hasScaleIn(scaleIn({ totalSlices: 3 }))).toBe(true)
    expect(scaleInBadgeLabel(scaleIn({ totalSlices: 4 }))).toBe('분할 4회')
  })
})

describe('floorMode / floorBadge', () => {
  it('floor 미설정(0·null)이면 NONE', () => {
    expect(floorMode(null)).toBe('NONE')
    expect(floorMode(scaleIn({ stopLossFloorPct: 0 }))).toBe('NONE')
    expect(floorMode(scaleIn({ stopLossFloorPct: null }))).toBe('NONE')
    expect(floorBadge(scaleIn({ stopLossFloorPct: 0 }))).toBeNull()
  })

  it('floor 설정 + 자동청산 false → ALARM 배지', () => {
    const s = scaleIn({ totalSlices: 3, stopLossFloorPct: 15, floorAutoLiquidate: false })
    expect(floorMode(s)).toBe('ALARM')
    const badge = floorBadge(s)
    expect(badge?.mode).toBe('ALARM')
    expect(badge?.label).toBe('floor 알람 -15%')
    expect(badge?.description).toContain('알람만')
  })

  it('floor 설정 + 자동청산 true → AUTO_LIQUIDATE 배지', () => {
    const s = scaleIn({ totalSlices: 3, stopLossFloorPct: 20, floorAutoLiquidate: true })
    expect(floorMode(s)).toBe('AUTO_LIQUIDATE')
    const badge = floorBadge(s)
    expect(badge?.mode).toBe('AUTO_LIQUIDATE')
    expect(badge?.label).toBe('floor 자동청산 -20%')
    expect(badge?.description).toContain('자동 청산')
  })

  it('floorAutoLiquidate 미지정(default)은 ALARM', () => {
    expect(floorMode(scaleIn({ stopLossFloorPct: 15 }))).toBe('ALARM')
  })
})

describe('상태 요약 술어 (isFloorAlarmStrategy / isFloorStrategy / isScaleInStrategy)', () => {
  it('알람 전략만 isFloorAlarmStrategy true, 자동청산은 제외', () => {
    expect(isFloorAlarmStrategy({ scaleIn: scaleIn({ stopLossFloorPct: 15 }) })).toBe(true)
    expect(
      isFloorAlarmStrategy({ scaleIn: scaleIn({ stopLossFloorPct: 15, floorAutoLiquidate: true }) }),
    ).toBe(false)
    expect(isFloorAlarmStrategy({ scaleIn: null })).toBe(false)
  })

  it('isFloorStrategy는 알람·자동청산 모두 true', () => {
    expect(isFloorStrategy({ scaleIn: scaleIn({ stopLossFloorPct: 15 }) })).toBe(true)
    expect(
      isFloorStrategy({ scaleIn: scaleIn({ stopLossFloorPct: 15, floorAutoLiquidate: true }) }),
    ).toBe(true)
    expect(isFloorStrategy({ scaleIn: null })).toBe(false)
  })

  it('isScaleInStrategy는 2회 이상만 true', () => {
    expect(isScaleInStrategy({ scaleIn: scaleIn({ totalSlices: 2 }) })).toBe(true)
    expect(isScaleInStrategy({ scaleIn: scaleIn({ totalSlices: 1 }) })).toBe(false)
    expect(isScaleInStrategy({ scaleIn: null })).toBe(false)
  })
})
