/**
 * Account REST API 클라이언트 (T-O Phase 3 — ADR 0068 §16)
 *
 * PUT    /api/user-account/real-trading-consent  → ConsentToggleResponseDto
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// DTO
// ──────────────────────────────────────────────

/** 실거래 동의 토글 요청 */
export interface ConsentToggleRequestDto {
  consented: boolean
}

/** 실거래 동의 토글 응답 */
export interface ConsentToggleResponseDto {
  userId: string
  /** 동의 시각 (ISO 8601). null = 동의 해제 상태 */
  realTradingConsentAt: string | null
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const accountApi = {
  /**
   * PUT /api/user-account/real-trading-consent
   * 실거래 모드 동의 토글 (ADR 0068 의제 A-1 — 3중 게이트 세 번째 조건)
   *
   * consented=true  → real_trading_consent_at = NOW()
   * consented=false → real_trading_consent_at = NULL
   */
  updateRealTradingConsent: async (
    userId: string,
    req: ConsentToggleRequestDto,
  ): Promise<ConsentToggleResponseDto> => {
    const res = await apiClient.put<ConsentToggleResponseDto>(
      `/user-account/real-trading-consent?userId=${encodeURIComponent(userId)}`,
      req,
    )
    return res.data
  },

  /**
   * GET /api/user-account/real-trading-consent?userId=
   * 현재 동의 상태 조회
   */
  getRealTradingConsent: async (userId: string): Promise<ConsentToggleResponseDto> => {
    const res = await apiClient.get<ConsentToggleResponseDto>(
      `/user-account/real-trading-consent?userId=${encodeURIComponent(userId)}`,
    )
    return res.data
  },
}
