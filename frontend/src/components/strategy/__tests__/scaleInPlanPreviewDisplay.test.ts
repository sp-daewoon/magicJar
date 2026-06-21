/**
 * scaleInPlanPreviewDisplay 단위 테스트 — 빌더 고도화 A
 *
 * 검증:
 * - 균등 분할 회차표 + 비중
 * - 0% 회차 제외(⑨) + displayOrder 재부여
 * - PRICE_DROP_PCT 회차별 예상 진입가 = 기준가 × (1+drop/100)^n
 * - SIGNAL_REPEAT 은 예상 진입가 null
 * - 예상 손절가·floor 알람선 + floor 비활성
 * - 기준가 미입력 시 가격 null
 */
import { describe, expect, it } from 'vitest'
import { buildScaleInPlanPreview } from '../scaleInPlanPreviewDisplay'
import type { ScaleInDto } from '../../../api/strategy'

const base: ScaleInDto = {
  totalSlices: 3,
  sliceWeights: null,
  reentryTrigger: 'SIGNAL_REPEAT',
  priceDropPct: null,
  minSliceIntervalSec: 60,
  stopLossFloorPct: 15,
  floorAutoLiquidate: false,
}

describe('buildScaleInPlanPreview', () => {
  it('균등 3분할 회차표 — 비중 합 100, 회차 3개', () => {
    const plan = buildScaleInPlanPreview({
      scaleIn: base,
      manualWeights: false,
      previewEntryPrice: '100000',
      stopLossPct: '5',
    })
    expect(plan.slices).toHaveLength(3)
    const sum = plan.slices.reduce((acc, s) => acc + s.weightPct, 0)
    expect(Math.round(sum)).toBe(100)
    expect(plan.slices[0].displayOrder).toBe(1)
  })

  it('0% 회차 제외 + displayOrder 재부여 (⑨)', () => {
    const plan = buildScaleInPlanPreview({
      scaleIn: { ...base, totalSlices: 3, sliceWeights: [60, 0, 40] },
      manualWeights: true,
      previewEntryPrice: '100000',
      stopLossPct: '5',
    })
    expect(plan.slices).toHaveLength(2)
    expect(plan.slices.map((s) => s.displayOrder)).toEqual([1, 2])
    expect(plan.slices.map((s) => s.weightPct)).toEqual([60, 40])
  })

  it('PRICE_DROP_PCT — 회차별 예상 진입가 복리 하락', () => {
    const plan = buildScaleInPlanPreview({
      scaleIn: { ...base, totalSlices: 3, reentryTrigger: 'PRICE_DROP_PCT', priceDropPct: -10 },
      manualWeights: false,
      previewEntryPrice: '100000',
      stopLossPct: '5',
    })
    // 1차 = 기준가, 2차 = 100000*0.9 = 90000, 3차 = 100000*0.81 = 81000
    expect(plan.slices[0].expectedEntryPrice).toBe(100000)
    expect(plan.slices[1].expectedEntryPrice).toBe(90000)
    expect(plan.slices[2].expectedEntryPrice).toBe(81000)
  })

  it('SIGNAL_REPEAT — 2차 이후 예상 진입가 null', () => {
    const plan = buildScaleInPlanPreview({
      scaleIn: base,
      manualWeights: false,
      previewEntryPrice: '100000',
      stopLossPct: '5',
    })
    expect(plan.slices[0].expectedEntryPrice).toBe(100000)
    expect(plan.slices[1].expectedEntryPrice).toBeNull()
    expect(plan.slices[2].expectedEntryPrice).toBeNull()
  })

  it('예상 손절가 = 기준가 × (1 − 손절%/100)', () => {
    const plan = buildScaleInPlanPreview({
      scaleIn: base,
      manualWeights: false,
      previewEntryPrice: '100000',
      stopLossPct: '5',
    })
    expect(plan.expectedStopLossPrice).toBe(95000)
  })

  it('floor 알람선 = 기준가 × (1 − floor%/100), floorAutoLiquidate 반영', () => {
    const plan = buildScaleInPlanPreview({
      scaleIn: { ...base, stopLossFloorPct: 15, floorAutoLiquidate: true },
      manualWeights: false,
      previewEntryPrice: '100000',
      stopLossPct: '5',
    })
    expect(plan.floorPct).toBe(15)
    expect(plan.floorPrice).toBe(85000)
    expect(plan.floorAutoLiquidate).toBe(true)
  })

  it('floor 0/null — 비활성(floorPct null)', () => {
    const plan = buildScaleInPlanPreview({
      scaleIn: { ...base, stopLossFloorPct: 0 },
      manualWeights: false,
      previewEntryPrice: '100000',
      stopLossPct: '5',
    })
    expect(plan.floorPct).toBeNull()
    expect(plan.floorPrice).toBeNull()
  })

  it('기준가 미입력 — 가격 null, 회차/비중은 유지', () => {
    const plan = buildScaleInPlanPreview({
      scaleIn: base,
      manualWeights: false,
      previewEntryPrice: '',
      stopLossPct: '5',
    })
    expect(plan.basePrice).toBeNull()
    expect(plan.expectedStopLossPrice).toBeNull()
    expect(plan.floorPrice).toBeNull()
    expect(plan.slices).toHaveLength(3)
    expect(plan.slices[0].expectedEntryPrice).toBeNull()
  })
})
