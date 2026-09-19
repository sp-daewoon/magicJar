package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.TradingMode

/**
 * 활성 주문 모드 조회 포트 (감사 2026-09-19 §5 설계 A LOCK · 자본 보호 핵심).
 *
 * MOCK/REAL 주문 모드를 "재기동 없이" 전환하기 위한 DB 단일 권위(system_config.active_trading_mode).
 * TradingModeGate가 주문 시점마다 이 포트를 읽어 `min(env 상한, DB 활성모드)`로 최종 모드를 판정한다.
 *
 * ### 안전 불변식 (절대 훼손 금지)
 * - 이 포트가 반환하는 값은 **REAL 상한이 열려 있을 때만** REAL로 유효하다.
 *   env MAGICJAR_MODE!=REAL(상한 MOCK)이면 이 값과 무관하게 무조건 MOCK — TradingModeGate 책임.
 * - 조회 실패/불명 시 [TradingMode.MOCK] fail-closed (호출자 책임). 자본 손실 방지 기본값.
 * - 시세/차트/지표는 이 포트와 무관하게 항상 REAL credential — 모드 전환이 시세 소스를 바꾸지 않는다.
 *
 * [activeMode] return [TradingMode] — DB system_config.active_trading_mode 싱글톤 값.
 *              row 없음/파싱 실패 시 구현체가 MOCK fail-closed로 반환.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface ActiveTradingModePort {
    fun activeMode(): TradingMode
}
