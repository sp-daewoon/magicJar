package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.ActiveTradingModePort
import kr.spdaewoon.magicjar.domain.order.execution.port.RealTradingConsentPort
import kr.spdaewoon.magicjar.domain.order.execution.port.TradingModeGate
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 3중 게이트 어댑터 (ADR 0068 §14-3 LOCK · 자본 보호 핵심).
 *
 * ### 3중 게이트 (§14-3 본문 인용)
 * ```kotlin
 * 1. env MAGICJAR_MODE == "REAL" 미일치 → MOCK fallback (안전 default)
 * 2. env MAGICJAR_ALLOW_REAL == "true" 미일치 → DENIED("MAGICJAR_ALLOW_REAL=false")
 * 3. user_account.real_trading_consent_at == null → DENIED("CONSENT_NOT_GIVEN")
 * ```
 *
 * ### env source LOCK
 * `@Value`로 env 주입 — 테스트 시점 override 용이.
 *
 * ### Phase 2 graceful fallback
 * RealTradingConsentPort 미합류 시 — REAL 모드 조건 3 충족 불가 → 안전하게 CONSENT_NOT_GIVEN denied.
 *
 * 단위 테스트 7 케이스 LOCK (§14-7 + ADR 0122 §6 GAP-0 hotfix):
 *   (a) MOCK fallback (env=MOCK)
 *   (b) REAL 정상 (3 조건 모두 true)
 *   (c) ALLOW_REAL=false denied
 *   (d) CONSENT_NOT_GIVEN denied
 *   (e) paper + REAL 3조건 충족 → MOCK 강제
 *   (f) paper + env=MOCK → MOCK
 *   (g) paper 시 consentPort 미호출 (3중 게이트 평가 자체 skip)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class TradingModeGateImpl(
    private val realTradingConsentPortProvider: ObjectProvider<RealTradingConsentPort>,
    private val activeTradingModePortProvider: ObjectProvider<ActiveTradingModePort>,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.allow-real:false}")
    private val magicjarAllowReal: String,
) : TradingModeGate {
    private val log = LoggerFactory.getLogger(TradingModeGateImpl::class.java)

    override fun resolveMode(
        userId: String,
        paperStrategy: Boolean,
    ): OrderResult<DenyReason.RealGateDenied, TradingMode> {
        // ADR 0122 §6 LOCK (GAP-0 hotfix) — 페이퍼(origin=PAPER_DISCOVERY) 전략 주문은
        // env 3중 게이트와 무관하게 MOCK 강제. REAL canary 진입 후에도 실 주문 발사 0.
        if (paperStrategy) {
            log.info(
                "TradingModeGate PAPER MOCK 강제: userId={} env.mode={} 무관 (ADR 0122 §6)",
                userId,
                magicjarMode,
            )
            return OrderResult.Right(TradingMode.MOCK)
        }

        // 조건 1: env mode가 REAL이 아니면 → MOCK 강제 (안전 상한 — §14-3).
        // env 상한이 MOCK이면 DB active_trading_mode 와 무관하게 무조건 MOCK (상한이 절대 · 자본 사고 방지).
        if (!RuntimeModeParser.isRealMode(magicjarMode)) {
            log.debug("TradingModeGate MOCK fallback: userId={} env.mode={}", userId, magicjarMode)
            return OrderResult.Right(TradingMode.MOCK)
        }

        // 조건 2: ALLOW_REAL flag 필수 (env 상한 이중 잠금)
        if (!RuntimeModeParser.allowReal(magicjarAllowReal)) {
            log.warn(
                "TradingModeGate REJECTED — MAGICJAR_ALLOW_REAL=false: userId={}",
                userId,
            )
            return OrderResult.Left(DenyReason.RealGateDenied("MAGICJAR_ALLOW_REAL=false"))
        }

        // 조건 3: 사용자 명시 동의 필수 (의제 A-1)
        val consentPort = realTradingConsentPortProvider.getIfAvailable()
        if (consentPort == null) {
            log.warn(
                "TradingModeGate REJECTED — RealTradingConsentPort 미합류 (Phase 2 미완): userId={}",
                userId,
            )
            return OrderResult.Left(DenyReason.RealGateDenied("CONSENT_PORT_UNAVAILABLE"))
        }

        val consentAt =
            try {
                consentPort.findConsentAt(userId)
            } catch (e: Exception) {
                log.error("TradingModeGate consentPort.findConsentAt 실패: userId={} err={}", userId, e.message)
                null
            }

        if (consentAt == null) {
            log.warn("TradingModeGate REJECTED — CONSENT_NOT_GIVEN: userId={}", userId)
            return OrderResult.Left(DenyReason.RealGateDenied("CONSENT_NOT_GIVEN"))
        }

        // 조건 4 (감사 2026-09-19 §5 A): env REAL 상한이 모두 열린 뒤, DB 활성 모드가 최종 스위치.
        // DB active_trading_mode=MOCK 이면 사용자가 (재기동 없이) MOCK 으로 내린 것 → MOCK 존중.
        // DB=REAL 일 때만 REAL 진입. 즉 min(env 상한=REAL, DB 활성모드).
        // 이 스위치는 상한을 "넘어" REAL 을 열지 못한다 — 상한이 MOCK이면 조건 1에서 이미 차단됨.
        // 포트 미합류/조회 실패는 어댑터가 MOCK fail-closed 로 반환 → 안전.
        val activeModePort = activeTradingModePortProvider.getIfAvailable()
        val dbActiveMode = activeModePort?.activeMode() ?: TradingMode.MOCK
        if (dbActiveMode != TradingMode.REAL) {
            log.info(
                "TradingModeGate DB 활성모드=MOCK — REAL 상한 열렸으나 MOCK 존중(재기동 없는 전환): userId={}",
                userId,
            )
            return OrderResult.Right(TradingMode.MOCK)
        }

        log.info("TradingModeGate ALLOWED REAL: userId={} consentAt={} dbActiveMode=REAL", userId, consentAt)
        return OrderResult.Right(TradingMode.REAL)
    }
}
