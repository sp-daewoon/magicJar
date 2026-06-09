/**
 * 단타 발굴(Discovery) REST API 클라이언트 (ADR 0117 R2 + spec §3-R2)
 *
 *   POST /api/screener/daytrade-discover — 룰 기반 단타 발굴 (전략 없이 빌트인 프리셋)
 *
 * ### 기존 evaluate 와의 차이
 * - evaluate: strategyId 필수 (사용자가 미리 저장한 DSL 평가).
 * - daytrade-discover: strategyId 없음 — 시스템 빌트인 단타 setup 프리셋(V75 시드 DAYTRADE_*)으로 발굴.
 *
 * ### 가드 (방향성 보존)
 * - 룰 기반 — LLM 호출 0 (anthropic·openai import 0건). ai-discovery(LLM)와 개념·path 분리.
 * - 발굴은 후보 출발점 — 주문 트리거 필드 0, watchlist write 0.
 * - startRealtimeWatch=true 는 STRATEGY owner WS 수요 등록일 뿐 watchlist 자동 삽입이 아니다 (owner 가드).
 */
import apiClient from '../../../lib/axios'
import type { CandleInterval } from '../../../api/symbol'
import type { ScreenerFilterDto } from './screenerApi'

// ──────────────────────────────────────────────
// 빌트인 단타 setup 프리셋 (V75 시드 candidate_key 3종 — spec §3-R2)
// ──────────────────────────────────────────────

export interface DaytradeDiscoverPresetOption {
  /** V75 시드 candidate_key — null이면 활성 DAYTRADE_* 전체 합집합 */
  key: string | null
  label: string
  description: string
}

/**
 * UI 선택용 빌트인 프리셋 목록.
 * key=null(전체)는 활성 DAYTRADE_* 프리셋 합집합 발굴을 의미한다.
 * 응답 `presets[]`(서버 권위 = V75 시드 row)와 별개의 진입 옵션이며, 실제 진입 조건은 응답 presets.entryRules로 노출된다.
 */
export const DAYTRADE_PRESET_OPTIONS: DaytradeDiscoverPresetOption[] = [
  {
    key: null,
    label: '전체 (합집합)',
    description: '활성 단타 빌트인 프리셋 전체로 후보를 발굴합니다.',
  },
  {
    key: 'DAYTRADE_GAP_RVOL_TURNOVER',
    label: '갭·RVOL·거래대금',
    description: '장전 갭상승 + 상대거래량(RVOL) + 거래대금 충족 종목.',
  },
  {
    key: 'DAYTRADE_VOLATILITY_EXPANSION',
    label: '변동성 확장',
    description: 'NATR 등 변동성 확장 구간에 진입한 종목.',
  },
  {
    key: 'DAYTRADE_MOMENTUM_BREAKOUT',
    label: '모멘텀 돌파',
    description: '최근 고점 돌파 + 거래량 동반 모멘텀 종목.',
  },
]

// ──────────────────────────────────────────────
// Request / Response 타입 (백엔드 DTO 1:1)
// ──────────────────────────────────────────────

/** POST /api/screener/daytrade-discover 요청 (DaytradeDiscoverRequest.kt 정합) */
export interface DaytradeDiscoverRequest {
  /** 사용자 ID (multi-tenant 격리) */
  userId: number
  /** 단타 setup 프리셋 key — null/생략이면 활성 DAYTRADE_* 전체 합집합 */
  presetKey?: string | null
  /** universe 필터 (기본 KOSPI+KOSDAQ active STOCK+ETF) */
  filters?: ScreenerFilterDto
  /** 평가 기준봉 (장전 setup = 일봉 1d 기본) */
  interval?: CandleInterval
  /** 발굴 TOP N (1~100, default 30) */
  limit?: number
  /**
   * 장중 감시 시작 — true면 후보를 STRATEGY owner WS 수요로 등록 (watchlist write 0).
   * ⚠ watchlist 자동 삽입이 아니다. STRATEGY owner TTL 30분 실시간 관찰일 뿐.
   */
  startRealtimeWatch?: boolean
}

/**
 * 점수 구성 4 기준 (백엔드 ScreenerScoreBreakdown.toComponentMap 정합).
 * key LOCK = signal·volume·atr·cap (backend domain/ScreenerScoreBreakdown.kt:26).
 */
export interface DaytradeScoreComponentsDto {
  /** DSL BUY 강도 (0~1) */
  signal: number
  /** 거래대금(유동성) normalize */
  volume: number
  /** 변동성(ATR/close) sweet spot */
  atr: number
  /** 시총 안정성 normalize (key=cap) */
  cap: number
}

/** 발굴 후보(매칭) 1건 (ScreenerMatchDto.kt 1:1) */
export interface DaytradeMatchDto {
  /** 랭킹 (1-base) */
  rank: number
  /** 종목 코드 (KIS 6자리) */
  instrumentCode: string
  /** 종목명 */
  instrumentName: string
  /** 랭킹 점수 (0~1 normalize) */
  score: number
  /** 점수 구성 (signal/volume/atr/cap) */
  scoreComponents: DaytradeScoreComponentsDto
  /** BUY 시그널 사유 (DSL evalResult.reason) */
  reason: string
}

/** 발굴에 사용된 빌트인 프리셋 메타 (DaytradeDiscoverPresetDto.kt 1:1) */
export interface DaytradeDiscoverPresetMetaDto {
  /** 프리셋 key (V75 시드 candidate_key) */
  presetKey: string
  /** 프리셋 제목 */
  title: string
  /** 사람이 읽는 진입 조건 목록 */
  entryRules: string[]
}

/** POST /api/screener/daytrade-discover 응답 (DaytradeDiscoverResultDto.kt 1:1) */
export interface DaytradeDiscoverResultDto {
  /** 발굴에 사용된 빌트인 프리셋 메타 목록 */
  presets: DaytradeDiscoverPresetMetaDto[]
  /** 전부 시스템 빌트인 여부 (항상 true) */
  isBuiltin: boolean
  /** universe 사이즈 (필터 후 종목 수) */
  universeSize: number
  /** 평가 완료 수 (skip 제외) */
  evaluatedCount: number
  /** 발굴 후보(매칭) 수 */
  matchedCount: number
  /** skip 사유별 카운트 (캔들 미수집·부족·평가 오류 — mock 합성 0, 정직 표기) */
  skippedReasons: Record<string, number>
  /** 랭킹된 발굴 후보 (limit 적용, descending score) */
  matches: DaytradeMatchDto[]
  /** 전체 발굴 elapsed (ms) */
  elapsedMs: number
  /**
   * 장중 감시 STRATEGY owner 등록 종목 수 (startRealtimeWatch=true일 때만 >0).
   * watchlist write는 항상 0.
   */
  realtimeWatchRegistered: number
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const daytradeDiscoverApi = {
  /**
   * POST /api/screener/daytrade-discover
   * 룰 기반 단타 발굴 — 시스템 빌트인 프리셋으로 오늘의 후보 TOP N + 랭킹 반환 (동기 inline).
   */
  discover: async (req: DaytradeDiscoverRequest): Promise<DaytradeDiscoverResultDto> => {
    const res = await apiClient.post<DaytradeDiscoverResultDto>(
      '/screener/daytrade-discover',
      req,
      { timeout: 60_000 }, // inline universe 평가 최대 60s (evaluate 정합)
    )
    return res.data
  },
}
