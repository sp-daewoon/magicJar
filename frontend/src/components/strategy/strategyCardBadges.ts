/**
 * 전략 카드 상태 배지 파생 헬퍼 — 목록 카드 정보 밀도 개선 (list-ux-uplift)
 *
 * 카드 한눈 배지를 SigStrategyDto 설정에서 파생한다(mock 합성 0건 — 계산만).
 *   - 분할매수 진행: scaleIn.totalSlices 가 2 이상이면 "분할 N회" 배지
 *   - floor(분할 미완 catastrophic 방어선): stopLossFloorPct 활성 시
 *       floorAutoLiquidate=true → "floor 자동청산" / false → "floor 알람"
 *       (ADR 0128 amend R3 — floor 기본은 알람, 자동청산은 opt-in)
 *
 * 회차별 실시간 진행(N/M, filledLots)은 보유 데이터(StrategyPositionPanel)에서
 * 폴링으로 표시되며, 이 헬퍼는 "전략 설정상" 분할/ floor 여부만 파생한다.
 *
 * 룰 5: LLM provider import 0건
 */
import type { ScaleInDto, SigStrategyDto } from '../../api/strategy'

/** floor 방어선 동작 종류 — 'NONE'이면 floor 미설정 */
export type FloorMode = 'NONE' | 'ALARM' | 'AUTO_LIQUIDATE'

/** 분할매수 회차 수 (1 또는 미설정이면 단일 진입 → 0 반환) */
export function scaleInSliceCount(scaleIn: ScaleInDto | null | undefined): number {
  if (!scaleIn) return 0
  const n = scaleIn.totalSlices
  return Number.isFinite(n) && n > 1 ? n : 0
}

/** 분할매수 적용 여부 (totalSlices >= 2) */
export function hasScaleIn(scaleIn: ScaleInDto | null | undefined): boolean {
  return scaleInSliceCount(scaleIn) > 0
}

/**
 * floor(분할 미완 catastrophic 손절선) 동작 모드 파생.
 * stopLossFloorPct가 0/null이면 NONE.
 * floorAutoLiquidate=true면 AUTO_LIQUIDATE, 아니면 ALARM(default).
 */
export function floorMode(scaleIn: ScaleInDto | null | undefined): FloorMode {
  if (!scaleIn) return 'NONE'
  const floor = scaleIn.stopLossFloorPct
  if (floor === null || floor === undefined || !Number.isFinite(floor) || floor <= 0) {
    return 'NONE'
  }
  return scaleIn.floorAutoLiquidate === true ? 'AUTO_LIQUIDATE' : 'ALARM'
}

export interface FloorBadge {
  mode: Exclude<FloorMode, 'NONE'>
  /** 배지 라벨 */
  label: string
  /** 접근성·title 설명 */
  description: string
  /** tailwind 색조 클래스 */
  cls: string
}

/** floor 배지 표시 정보 (NONE이면 null) */
export function floorBadge(scaleIn: ScaleInDto | null | undefined): FloorBadge | null {
  const mode = floorMode(scaleIn)
  if (mode === 'NONE') return null
  const pct = scaleIn?.stopLossFloorPct
  const pctLabel = pct != null && Number.isFinite(pct) ? ` -${pct}%` : ''
  if (mode === 'AUTO_LIQUIDATE') {
    return {
      mode,
      label: `floor 자동청산${pctLabel}`,
      description: `분할 미완 중 평단 대비${pctLabel} 도달 시 전량 자동 청산`,
      cls: 'bg-red-950/70 text-red-300 border border-red-800',
    }
  }
  return {
    mode,
    label: `floor 알람${pctLabel}`,
    description: `분할 미완 중 평단 대비${pctLabel} 도달 시 알람만(자동청산 없음)`,
    cls: 'bg-amber-950/70 text-amber-300 border border-amber-800',
  }
}

/** 카드용 분할매수 배지 라벨 ("분할 N회") — 단일 진입이면 null */
export function scaleInBadgeLabel(scaleIn: ScaleInDto | null | undefined): string | null {
  const n = scaleInSliceCount(scaleIn)
  return n > 0 ? `분할 ${n}회` : null
}

/** 목록 상태 요약용 — floor 알람(자동청산 제외) 떠있는 전략인지 */
export function isFloorAlarmStrategy(strategy: Pick<SigStrategyDto, 'scaleIn'>): boolean {
  return floorMode(strategy.scaleIn) === 'ALARM'
}

/** 목록 상태 요약용 — floor(알람·자동청산 모두) 설정 전략인지 */
export function isFloorStrategy(strategy: Pick<SigStrategyDto, 'scaleIn'>): boolean {
  return floorMode(strategy.scaleIn) !== 'NONE'
}

/** 목록 상태 요약용 — 분할매수 진행 중(설정상 2회 이상) 전략인지 */
export function isScaleInStrategy(strategy: Pick<SigStrategyDto, 'scaleIn'>): boolean {
  return hasScaleIn(strategy.scaleIn)
}
