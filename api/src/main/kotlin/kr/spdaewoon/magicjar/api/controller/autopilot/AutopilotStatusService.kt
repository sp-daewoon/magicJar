package kr.spdaewoon.magicjar.api.controller.autopilot

import kr.spdaewoon.magicjar.api.persistence.autopilot.AutopilotStateReadEntity
import kr.spdaewoon.magicjar.api.persistence.autopilot.AutopilotStateReadRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * magicjar-autopilot `/api/autopilot/status` **read-only 조회 서비스** (spec 2026-06-22 §2-0-A·§2-0-B · Task 4).
 *
 * autopilot_state(read-only projection) + owner=AUTOPILOT 전략수를 한 응답으로 합성한다. **쓰기 0** — 제어
 * (kill·resume·rollback)는 전부 Kafka cmd → consumer 경로다(api 는 autopilot_state 를 mutate 하지 않는다).
 *
 * 행 부재(autopilot 미시드 — enabled=false 환경) 시 손실 0·kill off·한도 0 의 안전측 default 응답(표시용).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 조회 (ADR 0023 §2-4 정합)
 */
@Service
class AutopilotStatusService(
    private val stateReadRepository: AutopilotStateReadRepository,
    private val strategyRepository: StrategyJpaRepository,
) {
    private val log = LoggerFactory.getLogger(AutopilotStatusService::class.java)

    companion object {
        private const val OWNER_AUTOPILOT = "AUTOPILOT"
        private const val STATUS_DELETED = "DELETED"
        private const val SINGLETON_ID = 1L
    }

    @Transactional(readOnly = true)
    fun status(): AutopilotStatusResponse {
        val state = stateReadRepository.findById(SINGLETON_ID).orElse(null)
        val strategyCount =
            runCatching { strategyRepository.countByOwnerAndStatusNot(OWNER_AUTOPILOT, STATUS_DELETED) }
                .getOrElse { e ->
                    log.warn("autopilot status — owner=AUTOPILOT 전략수 조회 실패(0 표시): err={}", e.message)
                    0L
                }
        return toResponse(state, strategyCount)
    }

    private fun toResponse(
        state: AutopilotStateReadEntity?,
        strategyCount: Long,
    ): AutopilotStatusResponse =
        if (state == null) {
            log.info("autopilot_state 행 부재(id=1) — default 응답(손실 0·kill off). autopilot 미시드 환경.")
            AutopilotStatusResponse(
                killActive = false,
                poolPct = BigDecimal.ZERO,
                perTradePct = BigDecimal.ZERO,
                maxPositions = 0,
                dailyLossPct = BigDecimal.ZERO,
                cumLossPct = BigDecimal.ZERO,
                cumRealizedLoss = BigDecimal.ZERO,
                dailyRealizedLoss = BigDecimal.ZERO,
                lifetimeRealizedLoss = BigDecimal.ZERO,
                autopilotStrategyCount = strategyCount,
            )
        } else {
            AutopilotStatusResponse(
                killActive = state.killActive,
                poolPct = state.poolPct,
                perTradePct = state.perTradePct,
                maxPositions = state.maxPositions,
                dailyLossPct = state.dailyLossPct,
                cumLossPct = state.cumLossPct,
                cumRealizedLoss = state.cumRealizedLoss,
                dailyRealizedLoss = state.dailyRealizedLoss,
                lifetimeRealizedLoss = state.lifetimeRealizedLoss,
                autopilotStrategyCount = strategyCount,
            )
        }
}
