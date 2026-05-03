/**
 * 전략 빌더 관련 타입 정의
 * T7 머지 후 /api/indicators, /api/strategies endpoint와 연동 예정.
 * 현재는 stub hard-code 5종 지표.
 */

/** 지표 종류 — T7 DSL 스키마와 1:1 대응 */
export type IndicatorType = 'sma' | 'ema' | 'rsi' | 'macd' | 'bb'

/** 조건 연산자 — 7종 */
export type ConditionOperator =
  | 'cross_above'
  | 'cross_below'
  | 'greater_than'
  | 'less_than'
  | 'greater_equal'
  | 'less_equal'
  | 'equals'

/** compareTo의 대상 타입 */
export type CompareToType = 'indicator' | 'price_field' | 'number'

/** 가격 필드 */
export type PriceField = 'open' | 'high' | 'low' | 'close' | 'volume'

/** compareTo 값 */
export interface CompareTo {
  type: CompareToType
  /** type === 'indicator' → indicatorAlias, type === 'price_field' → PriceField, type === 'number' → 숫자 문자열 */
  value: string
}

/** 단일 조건 (1줄) */
export interface SingleCondition {
  id: string
  left: string          // indicator alias 또는 PriceField
  operator: ConditionOperator
  compareTo: CompareTo
}

/** 진입/청산 조건 그룹 (1차: AND only) */
export interface ConditionGroup {
  /** 1차: 'and' only, R2에서 'or' 추가 */
  logic: 'and'
  conditions: SingleCondition[]
}

/** 지표 파라미터 */
export interface SmaParams { period: number }
export interface EmaParams { period: number }
export interface RsiParams { period: number }
export interface MacdParams { fastPeriod: number; slowPeriod: number; signalPeriod: number }
export interface BbParams { period: number; multiplier: number }

export type IndicatorParams = SmaParams | EmaParams | RsiParams | MacdParams | BbParams

/** 추가된 지표 인스턴스 */
export interface IndicatorInstance {
  id: string
  type: IndicatorType
  alias: string         // 사용자 별칭 (자동 생성 + 수동 변경 가능)
  params: IndicatorParams
}

/** 리스크 관리 설정 */
export interface RiskConfig {
  stopLoss: string      // 퍼센트 문자열, 예: "5" → -5%
  takeProfit: string    // 퍼센트 문자열, 예: "10" → +10%
  trailingStop: string  // 퍼센트 문자열, 예: "" → 비활성
}

/** 전략 메타데이터 */
export interface StrategyMetadata {
  name: string
  description: string
  tags: string          // 쉼표 구분 문자열, 예: "MA,모멘텀"
  author: string
}

/** 전략 빌더 전체 상태 */
export interface StrategyBuilderState {
  metadata: StrategyMetadata
  indicators: IndicatorInstance[]
  entryConditions: ConditionGroup
  exitConditions: ConditionGroup
  risk: RiskConfig
}

/** LocalStorage 저장 단위 */
export interface SavedStrategy {
  id: string
  createdAt: string     // ISO-8601
  updatedAt: string     // ISO-8601
  state: StrategyBuilderState
}

/** 지표 타입별 한국어 표시명 */
export const INDICATOR_LABELS: Record<IndicatorType, string> = {
  sma: '단순이동평균(SMA)',
  ema: '지수이동평균(EMA)',
  rsi: '상대강도지수(RSI)',
  macd: 'MACD',
  bb: '볼린저밴드(BB)',
}

/** 지표 타입별 기본 파라미터 */
export const INDICATOR_DEFAULTS: Record<IndicatorType, IndicatorParams> = {
  sma: { period: 20 },
  ema: { period: 12 },
  rsi: { period: 14 },
  macd: { fastPeriod: 12, slowPeriod: 26, signalPeriod: 9 },
  bb: { period: 20, multiplier: 2 },
}

/** 조건 연산자 한국어 표시 */
export const OPERATOR_LABELS: Record<ConditionOperator, string> = {
  cross_above: '상향 돌파 (골든크로스)',
  cross_below: '하향 돌파 (데드크로스)',
  greater_than: '>',
  less_than: '<',
  greater_equal: '>=',
  less_equal: '<=',
  equals: '=',
}

/** 가격 필드 한국어 표시 */
export const PRICE_FIELD_LABELS: Record<PriceField, string> = {
  open: '시가',
  high: '고가',
  low: '저가',
  close: '종가',
  volume: '거래량',
}

/** 빈 전략 빌더 초기 상태 */
export function createInitialState(): StrategyBuilderState {
  return {
    metadata: {
      name: '',
      description: '',
      tags: '',
      author: '',
    },
    indicators: [],
    entryConditions: {
      logic: 'and',
      conditions: [],
    },
    exitConditions: {
      logic: 'and',
      conditions: [],
    },
    risk: {
      stopLoss: '',
      takeProfit: '',
      trailingStop: '',
    },
  }
}

/** 지표 별칭 자동 생성 */
export function generateAlias(type: IndicatorType, existingAliases: string[]): string {
  const base: string = type
  let n = 1
  let candidate: string = base
  while (existingAliases.includes(candidate)) {
    n++
    candidate = `${base}_${n}`
  }
  return candidate
}

/** 프리셋 전략 목록 (10개 hard-code) */
export const PRESET_STRATEGIES: { id: string; name: string; description: string; state: StrategyBuilderState }[] = [
  {
    id: 'preset-ma-cross',
    name: 'MA 골든크로스',
    description: '단기 이동평균이 장기 이동평균을 상향 돌파할 때 매수',
    state: {
      metadata: { name: 'MA 골든크로스', description: '단기/장기 이동평균 교차 전략', tags: 'MA,모멘텀', author: '' },
      indicators: [
        { id: 'i1', type: 'sma', alias: 'sma_5', params: { period: 5 } },
        { id: 'i2', type: 'sma', alias: 'sma_20', params: { period: 20 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'sma_5', operator: 'cross_above', compareTo: { type: 'indicator', value: 'sma_20' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c2', left: 'sma_5', operator: 'cross_below', compareTo: { type: 'indicator', value: 'sma_20' } },
        ],
      },
      risk: { stopLoss: '5', takeProfit: '10', trailingStop: '' },
    },
  },
  {
    id: 'preset-rsi-oversold',
    name: 'RSI 과매도 반등',
    description: 'RSI 30 이하 과매도 구간에서 매수, 70 이상 과매수에서 매도',
    state: {
      metadata: { name: 'RSI 과매도 반등', description: 'RSI 과매도/과매수 전략', tags: 'RSI,역추세', author: '' },
      indicators: [
        { id: 'i1', type: 'rsi', alias: 'rsi_14', params: { period: 14 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'rsi_14', operator: 'less_equal', compareTo: { type: 'number', value: '30' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c2', left: 'rsi_14', operator: 'greater_equal', compareTo: { type: 'number', value: '70' } },
        ],
      },
      risk: { stopLoss: '3', takeProfit: '8', trailingStop: '' },
    },
  },
  {
    id: 'preset-bb-breakout',
    name: '볼린저밴드 상단 돌파',
    description: '종가가 볼린저밴드 상단을 돌파할 때 매수',
    state: {
      metadata: { name: '볼린저밴드 상단 돌파', description: 'BB 돌파 모멘텀 전략', tags: 'BB,모멘텀,돌파', author: '' },
      indicators: [
        { id: 'i1', type: 'bb', alias: 'bb_20', params: { period: 20, multiplier: 2 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'close', operator: 'cross_above', compareTo: { type: 'indicator', value: 'bb_20_upper' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c2', left: 'close', operator: 'cross_below', compareTo: { type: 'indicator', value: 'bb_20_middle' } },
        ],
      },
      risk: { stopLoss: '4', takeProfit: '12', trailingStop: '' },
    },
  },
  {
    id: 'preset-macd-signal',
    name: 'MACD 시그널 교차',
    description: 'MACD가 시그널선을 상향 돌파할 때 매수',
    state: {
      metadata: { name: 'MACD 시그널 교차', description: 'MACD/Signal 교차 전략', tags: 'MACD,모멘텀', author: '' },
      indicators: [
        { id: 'i1', type: 'macd', alias: 'macd_12_26', params: { fastPeriod: 12, slowPeriod: 26, signalPeriod: 9 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'macd_12_26_line', operator: 'cross_above', compareTo: { type: 'indicator', value: 'macd_12_26_signal' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c2', left: 'macd_12_26_line', operator: 'cross_below', compareTo: { type: 'indicator', value: 'macd_12_26_signal' } },
        ],
      },
      risk: { stopLoss: '5', takeProfit: '15', trailingStop: '' },
    },
  },
  {
    id: 'preset-ema-trend',
    name: 'EMA 추세 추종',
    description: '종가가 EMA 위에 있을 때 매수 유지',
    state: {
      metadata: { name: 'EMA 추세 추종', description: 'EMA 기반 추세 전략', tags: 'EMA,추세', author: '' },
      indicators: [
        { id: 'i1', type: 'ema', alias: 'ema_26', params: { period: 26 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'close', operator: 'cross_above', compareTo: { type: 'indicator', value: 'ema_26' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c2', left: 'close', operator: 'cross_below', compareTo: { type: 'indicator', value: 'ema_26' } },
        ],
      },
      risk: { stopLoss: '5', takeProfit: '20', trailingStop: '3' },
    },
  },
  {
    id: 'preset-rsi-ma-combo',
    name: 'RSI + MA 복합',
    description: 'RSI 40 이상 AND 종가 > SMA20 조건 동시 충족 시 매수',
    state: {
      metadata: { name: 'RSI + MA 복합', description: 'RSI와 MA 복합 전략', tags: 'RSI,MA,복합', author: '' },
      indicators: [
        { id: 'i1', type: 'rsi', alias: 'rsi_14', params: { period: 14 } },
        { id: 'i2', type: 'sma', alias: 'sma_20', params: { period: 20 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'rsi_14', operator: 'greater_equal', compareTo: { type: 'number', value: '40' } },
          { id: 'c2', left: 'close', operator: 'greater_than', compareTo: { type: 'indicator', value: 'sma_20' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c3', left: 'rsi_14', operator: 'greater_equal', compareTo: { type: 'number', value: '70' } },
        ],
      },
      risk: { stopLoss: '4', takeProfit: '10', trailingStop: '' },
    },
  },
  {
    id: 'preset-bb-mean-reversion',
    name: '볼린저밴드 평균회귀',
    description: '종가가 하단 밴드 아래로 떨어질 때 매수, 중심선 복귀 시 매도',
    state: {
      metadata: { name: '볼린저밴드 평균회귀', description: 'BB 하단 매수/중심 매도 전략', tags: 'BB,평균회귀', author: '' },
      indicators: [
        { id: 'i1', type: 'bb', alias: 'bb_20', params: { period: 20, multiplier: 2 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'close', operator: 'less_than', compareTo: { type: 'indicator', value: 'bb_20_lower' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c2', left: 'close', operator: 'greater_equal', compareTo: { type: 'indicator', value: 'bb_20_middle' } },
        ],
      },
      risk: { stopLoss: '3', takeProfit: '7', trailingStop: '' },
    },
  },
  {
    id: 'preset-macd-zero-cross',
    name: 'MACD 제로선 교차',
    description: 'MACD 히스토그램이 0을 상향 돌파할 때 매수',
    state: {
      metadata: { name: 'MACD 제로선 교차', description: 'MACD 히스토그램 방향 전환 전략', tags: 'MACD', author: '' },
      indicators: [
        { id: 'i1', type: 'macd', alias: 'macd_12_26', params: { fastPeriod: 12, slowPeriod: 26, signalPeriod: 9 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'macd_12_26_histogram', operator: 'cross_above', compareTo: { type: 'number', value: '0' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c2', left: 'macd_12_26_histogram', operator: 'cross_below', compareTo: { type: 'number', value: '0' } },
        ],
      },
      risk: { stopLoss: '5', takeProfit: '15', trailingStop: '' },
    },
  },
  {
    id: 'preset-sma-triple',
    name: 'SMA 3선 배열',
    description: '단·중·장기 이동평균이 정배열(5>20>60) 시 매수',
    state: {
      metadata: { name: 'SMA 3선 배열', description: '5/20/60 이동평균 정배열 전략', tags: 'MA,추세', author: '' },
      indicators: [
        { id: 'i1', type: 'sma', alias: 'sma_5', params: { period: 5 } },
        { id: 'i2', type: 'sma', alias: 'sma_20', params: { period: 20 } },
        { id: 'i3', type: 'sma', alias: 'sma_60', params: { period: 60 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'sma_5', operator: 'greater_than', compareTo: { type: 'indicator', value: 'sma_20' } },
          { id: 'c2', left: 'sma_20', operator: 'greater_than', compareTo: { type: 'indicator', value: 'sma_60' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c3', left: 'sma_5', operator: 'less_than', compareTo: { type: 'indicator', value: 'sma_20' } },
        ],
      },
      risk: { stopLoss: '6', takeProfit: '18', trailingStop: '' },
    },
  },
  {
    id: 'preset-ema-rsi-trend',
    name: 'EMA + RSI 추세',
    description: 'EMA 상승 추세 + RSI 50 이상 동시 충족 시 매수',
    state: {
      metadata: { name: 'EMA + RSI 추세', description: 'EMA 방향과 RSI 모멘텀 복합', tags: 'EMA,RSI,복합', author: '' },
      indicators: [
        { id: 'i1', type: 'ema', alias: 'ema_12', params: { period: 12 } },
        { id: 'i2', type: 'rsi', alias: 'rsi_14', params: { period: 14 } },
      ],
      entryConditions: {
        logic: 'and',
        conditions: [
          { id: 'c1', left: 'close', operator: 'greater_than', compareTo: { type: 'indicator', value: 'ema_12' } },
          { id: 'c2', left: 'rsi_14', operator: 'greater_equal', compareTo: { type: 'number', value: '50' } },
        ],
      },
      exitConditions: {
        logic: 'and',
        conditions: [
          { id: 'c3', left: 'rsi_14', operator: 'greater_equal', compareTo: { type: 'number', value: '75' } },
        ],
      },
      risk: { stopLoss: '4', takeProfit: '12', trailingStop: '2' },
    },
  },
]
