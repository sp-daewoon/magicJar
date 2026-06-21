package kr.spdaewoon.magicjar.consumer.autopilot

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * magicjar-autopilot **kill 사후분석 저널 기록기** (spec 2026-06-22 §2-0-D · Phase 0 Task 4).
 *
 * - [recordTrigger] — kill 발동 시점 스냅샷 1행 insert. [AutopilotKillSwitch.trigger] 가 호출.
 *   손실(cum/daily/lifetime)·한도 config·격리 대상(owner=AUTOPILOT 보유 종목·PAUSE 전략수)을 박제한다.
 * - [recordResume] — kill 해제 시 최신 미해제 행에 resumed_at·resume_reason·corrected_limits 갱신.
 *   [AutopilotControlListener] 의 RESUME 이 호출.
 *
 * **이게 내가(Claude) 왜 kill 됐는지 분석하는 evidence 원천**이다(사용자 결재 핵심).
 *
 * ## graceful (체결/kill 권위 무손상)
 * 저널 기록 실패는 kill 발동·해제 자체를 되돌리지 않는다(부가 audit). 모든 경로 runCatching.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 audit (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotKillJournalService(
    private val journalRepository: AutopilotKillJournalRepository,
    private val strategyPositionRepository: StrategyPositionJpaRepository,
    private val properties: MagicjarAutopilotProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AutopilotKillJournalService::class.java)

    /**
     * kill 발동 스냅샷 1행 insert (post-mortem). 발동 시점 손실·한도·보유종목·PAUSE 전략수를 박제한다.
     *
     * @param reason       kill 사유.
     * @param state        발동 시점 autopilot_state(손실·한도) — caller 가 read 해 넘긴다.
     * @param pausedCount  PAUSE 전환된 owner=AUTOPILOT 전략 수.
     */
    @Transactional
    fun recordTrigger(
        reason: String,
        state: AutopilotState,
        pausedCount: Int,
    ) {
        runCatching {
            val heldCodes =
                runCatching { strategyPositionRepository.findAutopilotHeldCodesByUser(properties.accountUserId) }
                    .getOrElse { e ->
                        log.warn("autopilot kill 저널 — 보유종목 조회 실패(skip held_codes): err={}", e.message)
                        emptyList()
                    }
            val entity =
                AutopilotKillJournalEntity(
                    occurredAt = Instant.now(),
                    reason = reason,
                    cumRealizedLoss = state.cumRealizedLoss,
                    dailyRealizedLoss = state.dailyRealizedLoss,
                    lifetimeRealizedLoss = state.lifetimeRealizedLoss,
                    pausedStrategyCount = pausedCount,
                    heldCodes = heldCodes.takeIf { it.isNotEmpty() }?.joinToString(","),
                    poolPct = state.poolPct,
                    perTradePct = state.perTradePct,
                    maxPositions = state.maxPositions,
                    dailyLossPct = state.dailyLossPct,
                    cumLossPct = state.cumLossPct,
                )
            val saved = journalRepository.save(entity)
            log.warn(
                "autopilot kill 저널 기록(post-mortem): id={} reason={} cum={} lifetime={} pausedStrategies={} heldCodes={}",
                saved.id,
                reason,
                state.cumRealizedLoss.toPlainString(),
                state.lifetimeRealizedLoss.toPlainString(),
                pausedCount,
                saved.heldCodes,
            )
        }.onFailure { e -> log.error("autopilot kill 저널 기록 실패(kill 자체 무손상): err={}", e.message) }
    }

    /**
     * kill 해제 — 최신 미해제 저널 행에 resumed_at·resume_reason·corrected_limits 갱신.
     *
     * @param resumeReason    해제 사유(Claude 사후분석 결론).
     * @param correctedLimits 보정 한도(있으면 JSON 직렬화 저장).
     */
    @Transactional
    fun recordResume(
        resumeReason: String,
        correctedLimits: AutopilotLimitCorrection?,
    ) {
        runCatching {
            val active = journalRepository.findFirstByResumedAtIsNullOrderByOccurredAtDesc()
            if (active == null) {
                log.info("autopilot resume 저널 — 미해제 행 없음(수동 stop 없이 resume? skip 갱신)")
                return
            }
            val correctedJson = correctedLimits?.let { runCatching { objectMapper.writeValueAsString(it) }.getOrNull() }
            val updated = journalRepository.markResumed(active.id, Instant.now(), resumeReason, correctedJson)
            log.info("autopilot resume 저널 갱신: id={} updated={} reason={} corrected={}", active.id, updated, resumeReason, correctedJson)
        }.onFailure { e -> log.error("autopilot resume 저널 갱신 실패(해제 자체 무손상): err={}", e.message) }
    }
}
