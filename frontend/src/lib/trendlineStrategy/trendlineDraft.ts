/**
 * 추세선 → 전략 프리셋 draft (추세매매 Phase 1·2)
 *
 * 차트 종목 상세에서 사용자가 그은 추세선을 전략 빌더 프리셋으로 연결한다.
 *
 * - **수평선**(Phase 1): 기존 DSL(THRESHOLD/CROSSOVER = `close crosses_above/below {상수}`)로
 *   표현 — 백엔드 변경 0. Phase 2에서도 그대로 유지(회귀 0).
 * - **기울선**(Phase 2, ADR 0140): 시간에 따라 기준 가격이 변하므로 상수로 표현 불가 →
 *   `close crosses_above trendline({id})` / `close crosses_below trendline({id})` 로 표현.
 *   평가 시점 ts에서 anchor 2점 선형보간으로 기준 가격을 산출(backend TrendlineRef 노드).
 *   **저장된 선(양의 정수 id)만** 참조 가능 — 미저장 선이면 저장을 먼저 유도한다.
 *
 * 딥링크 관례(builderDraft.ts의 researchDraft 패턴 정합):
 *   1. 차트에서 draft를 sessionStorage에 저장 → id 획득
 *   2. `/strategy/builder?trendlineDraftId=<id>` 로 이동
 *   3. StrategyBuilderPage가 id로 consume → 시작 화면 건너뛰고 프리셋 트리 로드
 *
 * 자동 저장·자동 활성화 절대 금지 — draft는 프리셋 출발점일 뿐 매매 결정을 대신하지 않는다.
 * 자동 추세선(source=AUTO)은 시각 보조선 — 전략 연결 비목표(ADR 0140 §4, overlay 게이트).
 */
import type { TrendlineType } from '../../api/trendlines'
import { parseDslTextToTree } from '../dsl/parser'

/** 수평 추세선 판정 허용 오차 — |a1-a2|/|a1| < 0.1% */
export const HORIZONTAL_TREND_TOLERANCE = 0.001

/** 선 가격 기준 기본 손절 제안(%) — 편집 가능, 휩쏘 주의 대상 */
export const TRENDLINE_DEFAULT_STOP_LOSS_PCT = 2

const STORAGE_PREFIX = 'magicjar.trendlineStrategy.builderDraft.'

export interface TrendlineStrategyDraft {
  version: 1
  source: 'trendline'
  /** 현재 종목코드 (6자리) */
  code: string
  /** 현재 차트 timeframe (예: 1d·5m) */
  interval: string
  /** 대표 가격 (두 앵커 평균) — 표시용. 기울선은 시점마다 변하므로 참고값일 뿐이다. */
  linePrice: number
  /** 선 성격 — SUPPORT(지지) | RESISTANCE(저항) */
  lineType: TrendlineType
  /** 사용자 지정 라벨(선택) */
  label?: string
  /**
   * 기울선 여부 (Phase 2). true 면 DSL 이 `trendline(id)` 참조, false 면 수평 상수(Phase 1).
   * 미지정(옛 Phase 1 draft)은 수평(false)으로 간주.
   */
  sloped?: boolean
  /** 참조 trendline 테이블 id (기울선일 때 필수 — 저장된 선). 수평선은 미사용. */
  trendlineId?: number
  /** 진입 DSL — 수평 `close crosses_above {linePrice}` · 기울 `close crosses_above trendline({id})` */
  entryDsl: string
  /** 청산 DSL — 수평 `close crosses_below {linePrice}` · 기울 `close crosses_below trendline({id})` */
  exitDsl: string
  /** 손절 제안(%) — 선 가격 기준 기본 2%, 저장 모달에서 편집 가능 */
  stopLossPct: number
  /** 전략 식별용 name (ascii-safe) */
  name: string
  /** 전략 표시명 — `추세선 {돌파|지지} {label||code} @{price}` */
  nameKo: string
}

export interface TrendlineStrategyDraftBuildResult {
  draft: TrendlineStrategyDraft | null
  reason?: string
}

/**
 * 수평 추세선 판정 — 두 앵커 가격이 0.1% 이내로 근접하면 수평선으로 본다.
 * 기울어진 선은 THRESHOLD 상수로 표현할 수 없어 Phase 1에서 비활성(false).
 */
export function isHorizontalTrendline(anchor1Price: number, anchor2Price: number): boolean {
  if (!Number.isFinite(anchor1Price) || !Number.isFinite(anchor2Price)) return false
  if (anchor1Price === 0) return false
  return Math.abs(anchor1Price - anchor2Price) / Math.abs(anchor1Price) < HORIZONTAL_TREND_TOLERANCE
}

/** 수평선 대표 가격 — 두 앵커의 평균(미세한 픽셀 오차 흡수) */
export function trendlineLinePrice(anchor1Price: number, anchor2Price: number): number {
  return (anchor1Price + anchor2Price) / 2
}

/** DSL 리터럴로 넣을 가격 문자열 — 정수면 정수, 소수면 4자리 이내 trailing zero 제거 */
export function formatLinePriceForDsl(price: number): string {
  if (Number.isInteger(price)) return String(price)
  return String(Number(price.toFixed(4)))
}

function sanitizeName(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_|_$/g, '')
}

export function trendlineStrategyDraftStorageKey(id: string): string {
  return `${STORAGE_PREFIX}${id}`
}

export function createTrendlineStrategyDraftId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function storeTrendlineStrategyDraft(draft: TrendlineStrategyDraft): string {
  const id = createTrendlineStrategyDraftId()
  window.sessionStorage.setItem(trendlineStrategyDraftStorageKey(id), JSON.stringify(draft))
  return id
}

export function consumeTrendlineStrategyDraft(id: string): TrendlineStrategyDraft | null {
  const key = trendlineStrategyDraftStorageKey(id)
  const raw = window.sessionStorage.getItem(key)
  if (!raw) return null
  window.sessionStorage.removeItem(key)
  try {
    const parsed = JSON.parse(raw) as Partial<TrendlineStrategyDraft>
    if (
      parsed.version !== 1 ||
      parsed.source !== 'trendline' ||
      !parsed.code ||
      !parsed.interval ||
      !parsed.entryDsl ||
      !parsed.exitDsl ||
      typeof parsed.linePrice !== 'number'
    ) {
      return null
    }
    return parsed as TrendlineStrategyDraft
  } catch {
    return null
  }
}

export interface BuildTrendlineDraftInput {
  code: string
  interval: string
  anchor1Price: number
  anchor2Price: number
  lineType: TrendlineType
  label?: string | null
  /**
   * 저장된 trendline 테이블 id — 기울선 전략 연결(Phase 2)에 필수.
   * 수평선은 상수 DSL 이라 없어도 되지만, 있으면 name 식별에 사용한다.
   */
  trendlineId?: number | null
}

/**
 * DSL 텍스트에서 `trendline(id)` 참조 id 를 모두 추출한다 (중복 제거·순서 보존).
 *
 * 백테스트 lookahead 경고 판정(BacktestDetail)·저장 전 참조 확인 등에서 재사용한다.
 * 파서를 돌리지 않고 정규식으로 스캔 — 부분 문자열/공백에 견고하고 DSL 무효여도 안전하다.
 */
export function parseTrendlineRefIds(dsl: string | null | undefined): number[] {
  if (!dsl) return []
  const ids: number[] = []
  const seen = new Set<number>()
  const re = /trendline\(\s*(\d+)\s*\)/g
  let match: RegExpExecArray | null
  while ((match = re.exec(dsl)) !== null) {
    const id = Number(match[1])
    if (Number.isInteger(id) && id > 0 && !seen.has(id)) {
      seen.add(id)
      ids.push(id)
    }
  }
  return ids
}

/**
 * 추세선 → 전략 draft 변환 (수평 Phase 1 · 기울 Phase 2, ADR 0140).
 *
 * 진입: 선 위로 종가 진입 시 매수 (SUPPORT 반등·RESISTANCE 돌파 공통)
 * 청산: 선 아래 이탈 시 매도
 *
 * - 수평선: `close crosses_above/below {상수}` — Phase 1 그대로(회귀 0).
 * - 기울선: `close crosses_above/below trendline({id})` — 저장된 선 id 필수.
 *   id 없으면(미저장) mock 없이 reason 으로 저장을 유도한다.
 *
 * DSL 파싱 실패 시 mock 없이 reason 으로 정직하게 실패를 알린다.
 */
export function buildTrendlineStrategyDraft(input: BuildTrendlineDraftInput): TrendlineStrategyDraftBuildResult {
  const code = (input.code ?? '').trim()
  if (!/^\d{6}$/.test(code)) {
    return { draft: null, reason: '유효한 6자리 종목코드가 없습니다.' }
  }

  const linePrice = trendlineLinePrice(input.anchor1Price, input.anchor2Price)
  if (!Number.isFinite(linePrice) || linePrice <= 0) {
    return { draft: null, reason: '추세선 가격을 해석할 수 없습니다.' }
  }

  const horizontal = isHorizontalTrendline(input.anchor1Price, input.anchor2Price)
  const label = input.label?.trim() || undefined
  const kindKo = input.lineType === 'RESISTANCE' ? '돌파' : '지지'
  const trendlineId =
    typeof input.trendlineId === 'number' && Number.isInteger(input.trendlineId) && input.trendlineId > 0
      ? input.trendlineId
      : undefined

  let entryDsl: string
  let exitDsl: string
  let name: string
  let nameKo: string
  let sloped: boolean

  if (horizontal) {
    // Phase 1 — 수평 상수 DSL (회귀 0, 백엔드 변경 0)
    sloped = false
    const priceLiteral = formatLinePriceForDsl(linePrice)
    entryDsl = `close crosses_above ${priceLiteral}`
    exitDsl = `close crosses_below ${priceLiteral}`
    nameKo = `추세선 ${kindKo} ${label ?? code} @${Number(priceLiteral).toLocaleString('ko-KR')}`
    name = sanitizeName(`trendline_${code}_${input.lineType}_${priceLiteral}`).slice(0, 64)
  } else {
    // Phase 2 — 기울선은 저장된 선 id 를 참조(선형보간 평가). 미저장이면 저장 유도.
    if (trendlineId === undefined) {
      return {
        draft: null,
        reason: '기울어진 추세선으로 전략을 만들려면 먼저 추세선을 저장해 주세요.',
      }
    }
    sloped = true
    entryDsl = `close crosses_above trendline(${trendlineId})`
    exitDsl = `close crosses_below trendline(${trendlineId})`
    nameKo = `추세선 ${kindKo} ${label ?? code} (기울선 #${trendlineId})`
    name = sanitizeName(`trendline_${code}_${input.lineType}_id${trendlineId}`).slice(0, 64)
  }

  const draft: TrendlineStrategyDraft = {
    version: 1,
    source: 'trendline',
    code,
    interval: input.interval,
    linePrice,
    lineType: input.lineType,
    label,
    sloped,
    trendlineId,
    entryDsl,
    exitDsl,
    stopLossPct: TRENDLINE_DEFAULT_STOP_LOSS_PCT,
    name,
    nameKo,
  }

  try {
    parseDslTextToTree(draft.entryDsl, draft.exitDsl)
  } catch (error) {
    return {
      draft: null,
      reason:
        error instanceof Error
          ? `추세선 조건을 빌더 DSL로 변환하지 못했습니다: ${error.message}`
          : '추세선 조건을 빌더 DSL로 변환하지 못했습니다.',
    }
  }

  return { draft }
}
