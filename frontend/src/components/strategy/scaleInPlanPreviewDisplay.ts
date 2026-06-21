/**
 * 저장 전 분할 매수 계획 미리보기 산식 — ADR 0128 R3 (빌더 고도화 A)
 *
 * SaveModal은 저장 전(strategyId 없음)이라 서버 `GET /api/strategies/{id}/scalein-plan`
 * (저장된 id 필요)를 호출할 수 없다. 그래서 사용자가 입력 중인 scaleIn 설정 + 미리보기 기준가만으로
 * 회차별 표를 client-side 로 합성한다.
 *
 * ★ 순수 display 산식이다 — 자본 실행이 아니다. 모든 표기는 "예상/미리보기"로 명시한다.
 *   base 예산(계좌 buyable)을 모르므로 수량은 산출하지 않고 비중·진입조건만 표시한다.
 *   기준가가 없으면 빈 결과를 반환(mock 합성 0건, feedback_no_mock_data 정합).
 *
 * 가격 하락 트리거(PRICE_DROP_PCT)일 때만 회차별 예상 진입가를 계산한다:
 *   n차 예상 진입가 = 기준가 × (1 + drop/100)^(n-1)   (drop은 음수)
 * 시그널 재발화(SIGNAL_REPEAT)는 진입가를 예측할 수 없으므로 "시그널 재발화 시" 텍스트만.
 *
 * 0% 비중 회차는 표에서 제외(⑨ 정합 — 서버 ScaleInPlan 표시 규칙과 동일).
 *
 * 룰 5: LLM provider import 0건
 */
import type { ReentryTrigger, ScaleInDto } from '../../api/strategy'
import { evenSliceWeights, parseNumberOrNull } from './scaleInExitDisplay'

export interface ScaleInPlanPreviewSlice {
  /** 표시 회차(1-base) — 0% 제외 후 재부여 */
  displayOrder: number
  /** 원래 회차 인덱스(0-base) */
  sliceIndex: number
  /** 비중% */
  weightPct: number
  /** 예상 진입가(원) — PRICE_DROP_PCT 일 때만, 아니면 null */
  expectedEntryPrice: number | null
  /** 진입 조건 설명 */
  triggerDescription: string
}

export interface ScaleInPlanPreview {
  /** 기준가(원) — 유효 입력이 없으면 null */
  basePrice: number | null
  /** 0% 제외 후 회차 목록 */
  slices: ScaleInPlanPreviewSlice[]
  /** 예상 손절가(원) = 기준가 × (1 − 손절%/100). 손절% 또는 기준가 없으면 null */
  expectedStopLossPrice: number | null
  /** floor 알람선 가격(원) = 기준가 × (1 − floor%/100). floor%≤0/기준가 없으면 null */
  floorPrice: number | null
  /** floor% (표시용, 0/null이면 비활성) */
  floorPct: number | null
  /** floor 도달 시 자동 청산 여부 */
  floorAutoLiquidate: boolean
  /** 재진입 트리거 (표시용) */
  reentryTrigger: ReentryTrigger
}

function roundPrice(value: number): number {
  return Math.round(value)
}

/** 회당 비중 배열 — 수동이면 입력값, 아니면 균등. 길이를 totalSlices에 맞춘다. */
function resolveWeights(scaleIn: ScaleInDto, manualWeights: boolean): number[] {
  const total = scaleIn.totalSlices
  if (manualWeights && scaleIn.sliceWeights && scaleIn.sliceWeights.length > 0) {
    return Array.from({ length: total }, (_, i) => {
      const raw = scaleIn.sliceWeights?.[i]
      const n = typeof raw === 'number' ? raw : Number(String(raw ?? '').trim())
      return Number.isFinite(n) ? n : 0
    })
  }
  return evenSliceWeights(total)
}

/**
 * 저장 전 분할 매수 계획 미리보기 산출.
 * @param scaleIn       입력 중인 분할 매수 설정
 * @param manualWeights 수동 비중 여부(false면 균등)
 * @param previewEntryPrice 미리보기 기준가(문자열/숫자) — 없거나 0 이하면 가격 계산 생략
 * @param stopLossPct   손절% (RiskPlan, 양수)
 */
export function buildScaleInPlanPreview(input: {
  scaleIn: ScaleInDto
  manualWeights: boolean
  previewEntryPrice: number | string | null | undefined
  stopLossPct: number | string | null | undefined
}): ScaleInPlanPreview {
  const { scaleIn, manualWeights, previewEntryPrice, stopLossPct } = input
  const basePrice = parseNumberOrNull(previewEntryPrice, { positiveOnly: true })
  const reentryTrigger: ReentryTrigger = scaleIn.reentryTrigger ?? 'SIGNAL_REPEAT'
  const isPriceDrop = reentryTrigger === 'PRICE_DROP_PCT'
  const dropPct = parseNumberOrNull(scaleIn.priceDropPct, {}) // 음수 허용

  const weights = resolveWeights(scaleIn, manualWeights)

  const slices: ScaleInPlanPreviewSlice[] = []
  let displayOrder = 0
  weights.forEach((weightPct, sliceIndex) => {
    // 0%(또는 음수) 회차 제외 (⑨)
    if (!Number.isFinite(weightPct) || weightPct <= 0) return
    displayOrder += 1

    let expectedEntryPrice: number | null = null
    let triggerDescription: string
    if (sliceIndex === 0) {
      // 1차는 시그널 진입 — 기준가 그대로
      expectedEntryPrice = basePrice
      triggerDescription = '시그널 발생 시 1차 진입 (기준가)'
    } else if (isPriceDrop && dropPct !== null && dropPct < 0 && basePrice !== null) {
      // n차 예상 진입가 = 기준가 × (1 + drop/100)^(n-1)
      expectedEntryPrice = roundPrice(basePrice * Math.pow(1 + dropPct / 100, sliceIndex))
      triggerDescription = `직전 회차 대비 ${dropPct}% 하락 시 진입`
    } else if (isPriceDrop) {
      triggerDescription = '직전 회차 대비 하락 시 진입 (하락폭·기준가 입력 시 예상가 표시)'
    } else {
      triggerDescription = '시그널 재발화 시 진입'
    }

    slices.push({ displayOrder, sliceIndex, weightPct, expectedEntryPrice, triggerDescription })
  })

  const stopPct = parseNumberOrNull(stopLossPct, { positiveOnly: true })
  const expectedStopLossPrice =
    basePrice !== null && stopPct !== null ? roundPrice(basePrice * (1 - stopPct / 100)) : null

  const floorPctRaw = scaleIn.stopLossFloorPct
  const floorPct =
    typeof floorPctRaw === 'number' && Number.isFinite(floorPctRaw) && floorPctRaw > 0 ? floorPctRaw : null
  const floorPrice =
    basePrice !== null && floorPct !== null ? roundPrice(basePrice * (1 - floorPct / 100)) : null

  return {
    basePrice,
    slices,
    expectedStopLossPrice,
    floorPrice,
    floorPct,
    floorAutoLiquidate: scaleIn.floorAutoLiquidate ?? false,
    reentryTrigger,
  }
}
