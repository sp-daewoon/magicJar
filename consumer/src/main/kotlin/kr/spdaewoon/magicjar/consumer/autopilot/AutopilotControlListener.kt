package kr.spdaewoon.magicjar.consumer.autopilot

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * magicjar-autopilot **제어 평면 리스너** (spec 2026-06-22 §2-0-B·§3 · Phase 0 Task 4).
 *
 * api `/api/autopilot/{stop,resume,rollback}` 가 [KafkaTopics.Autopilot.CONTROL] 에 publish 한 cmd 를 소비해
 * kill switch trigger / kill 해제(deactivate) / 청산+롤백을 수행한다. api 는 consumer 모듈 의존 0 이므로 Kafka 가
 * 유일한 제어 채널이다(`ManualReconcileKafkaListener` 패턴).
 *
 * ## action 분기
 * - `STOP`     → [AutopilotKillSwitch.trigger](수동 kill — owner=AUTOPILOT PAUSE + 저널 + Slack P0).
 * - `RESUME`   → 하드 플로어 검사 후 [AutopilotStateStore.deactivateKill] + resume 저널. **플로어 초과면 거부 + P0.**
 * - `ROLLBACK` → [AutopilotLiquidator.liquidateAll](owner=AUTOPILOT 시장가 청산) + pauseAll + deactivate(kill이면).
 *
 * ## zero-regression (spec §3-bis Tier A)
 * [MagicjarAutopilotProperties.enabled]=false → 모든 action **no-op**(기존 매매 경로 byte 동일). 게이트가 1순위.
 *
 * ## 하드 플로어 (코드 강제 — 무한 reset 출혈 방어)
 * RESUME 은 net worth 로 산정한 풀 상한을 [AutopilotStateStore.deactivateKill] 에 넘긴다. lifetime 손실이
 * 풀 전소 임계 이상이면 store 가 [DeactivateOutcome.FloorBreached] 로 거부 → 본 리스너가 Slack P0 + SystemEvent
 * "사용자 재가 필요". net worth 조회 불가(Unavailable)면 **fail-closed**(deactivate 미수행 — 출혈 방어 보수측).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 제어 (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotControlListener(
    private val properties: MagicjarAutopilotProperties,
    private val killSwitch: AutopilotKillSwitch,
    private val stateStore: AutopilotStateStore,
    private val killJournalService: AutopilotKillJournalService,
    private val capitalSnapshotProvider: CapitalSnapshotProvider,
    private val strategyRepository: ConsumerStrategyJpaRepository,
    private val liquidatorProvider: ObjectProvider<AutopilotLiquidator>,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AutopilotControlListener::class.java)

    @KafkaListener(
        topics = [KafkaTopics.Autopilot.CONTROL],
        groupId = "magicjar-autopilot-control",
    )
    fun onControl(record: ConsumerRecord<String, String>) {
        // zero-regression 게이트 1순위 — enabled=false 면 어떤 제어도 no-op.
        if (!properties.enabled) {
            log.info("autopilot 제어 수신했으나 enabled=false — no-op(zero-regression). offset={}", record.offset())
            return
        }

        val cmd =
            try {
                objectMapper.readValue(record.value(), AutopilotControlCommand::class.java)
            } catch (e: Exception) {
                log.error("autopilot 제어 cmd parse 실패: offset={} err={}", record.offset(), e.message)
                return
            }

        val reason = cmd.reason?.trim().orEmpty()
        log.warn("autopilot 제어 수신: action={} requestId={} reason={}", cmd.action, cmd.requestId, reason)

        when (cmd.action.uppercase()) {
            "STOP" -> handleStop(reason)
            "RESUME" -> handleResume(reason, cmd.correctedLimits)
            "ROLLBACK" -> handleRollback(reason)
            else -> log.error("autopilot 제어 — 미지원 action={} (무시). requestId={}", cmd.action, cmd.requestId)
        }
    }

    /** STOP — 수동 kill(저널·PAUSE·Slack P0 는 killSwitch 가 처리). */
    private fun handleStop(reason: String) {
        val effective = reason.ifBlank { "수동 STOP(/autopilot stop)" }
        val triggered = killSwitch.trigger(effective)
        log.warn("autopilot STOP 처리: killTriggered={}(false=이미 kill) reason={}", triggered, effective)
    }

    /**
     * RESUME — 하드 플로어 검사 후 kill 해제 + resume 저널. 플로어 초과·net worth 미상 시 거부(fail-closed).
     */
    private fun handleResume(
        reason: String,
        correctedLimits: AutopilotLimitCorrection?,
    ) {
        val effective = reason.ifBlank { "RESUME(사유 미기재)" }
        val state = stateStore.load()

        // 풀 상한 = 순자산 × poolPct/100. net worth 조회 불가면 fail-closed(deactivate 미수행).
        val netWorth = resolveNetWorth()
        if (netWorth == null) {
            log.error("autopilot RESUME 거부 — net worth 조회 불가(fail-closed, 하드 플로어 검증 불가). reason={}", effective)
            emitP0(
                "🛑 [P0] autopilot RESUME 거부 — 순자산 조회 불가로 하드 플로어 검증 불가(fail-closed).\n사유: $effective",
                "AUTOPILOT_RESUME_REJECTED_NETWORTH_UNAVAILABLE",
            )
            return
        }
        val poolCeiling = netWorth.multiply(state.poolPct).divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)

        when (val outcome = stateStore.deactivateKill(effective, poolCeiling, correctedLimits)) {
            is DeactivateOutcome.Deactivated -> {
                killJournalService.recordResume(effective, correctedLimits)
                log.warn("autopilot RESUME 완료(kill 해제·envelope 리셋): reason={} correctedLimits={}", effective, correctedLimits)
            }
            is DeactivateOutcome.AlreadyInactive ->
                log.info("autopilot RESUME — 이미 해제 상태(멱등). reason={}", effective)
            is DeactivateOutcome.FloorBreached -> {
                log.error(
                    "★ autopilot RESUME 거부 — 하드 플로어 초과(영구 kill, 사용자 재가 필요): lifetime={} floor={} reason={}",
                    outcome.state.lifetimeRealizedLoss.toPlainString(),
                    outcome.floorThreshold.toPlainString(),
                    effective,
                )
                val lifetimeStr = outcome.state.lifetimeRealizedLoss.toPlainString()
                val floorStr = outcome.floorThreshold.toPlainString()
                emitP0(
                    "🛑 [P0] autopilot RESUME 거부 — 하드 플로어 초과(풀 전소). 코드가 재개를 막았습니다.\n" +
                        "lifetime 손실=$lifetimeStr ≥ floor=$floorStr\n" +
                        "**사용자 재가(수동 floor 리셋)만 해제 가능.** 사유: $effective",
                    "AUTOPILOT_RESUME_REJECTED_FLOOR_BREACHED",
                )
            }
        }
    }

    /**
     * ROLLBACK — owner=AUTOPILOT 포지션 시장가 청산 + 전략 PAUSE + kill 해제(kill 상태면).
     *
     * 청산 구현([AutopilotLiquidator])이 없으면(market-data T5 미배포) 청산만 graceful skip 하고 PAUSE·deactivate 는 수행.
     */
    private fun handleRollback(reason: String) {
        val effective = reason.ifBlank { "ROLLBACK(/autopilot rollback)" }

        // 1) owner=AUTOPILOT 포지션 시장가 청산(구현 빈 lazy — 부재면 skip).
        val liquidator = liquidatorProvider.ifAvailable
        val outcome =
            if (liquidator == null) {
                log.warn("autopilot ROLLBACK — AutopilotLiquidator 빈 부재(T5 미배포?) — 청산 skip, PAUSE·deactivate 만 수행. reason={}", effective)
                AutopilotLiquidationOutcome.EMPTY
            } else {
                runCatching { liquidator.liquidateAll(properties.accountUserId, effective) }
                    .getOrElse { e ->
                        log.error("autopilot ROLLBACK 청산 실패(PAUSE·deactivate 는 계속): err={}", e.message)
                        AutopilotLiquidationOutcome.EMPTY
                    }
            }

        // 2) owner=AUTOPILOT 전략 전부 PAUSE(격리 LOCK — 사용자 전략·후성 비간섭).
        val paused =
            runCatching { strategyRepository.pauseAllAutopilotStrategies() }
                .getOrElse { e ->
                    log.error("autopilot ROLLBACK — owner=AUTOPILOT PAUSE 실패: err={}", e.message)
                    0
                }

        // 3) kill 상태였으면 해제(envelope 리셋). ROLLBACK 은 하드 플로어 검사 대상 아님(되감기는 항상 허용 — 출혈 종료 행위).
        //    net worth 불요: poolCeiling=0 → store 가 floor 검사 skip(되감기 의도 정합).
        val state = stateStore.load()
        if (state.killActive) {
            stateStore.deactivateKill("$effective (rollback)", BigDecimal.ZERO, null)
            killJournalService.recordResume("$effective (rollback)", null)
        }

        log.warn(
            "autopilot ROLLBACK 완료: 청산종목={}건 SELL intent={}건 PAUSE전략={}건 wasKill={} reason={}",
            outcome.liquidatedCodes.size,
            outcome.totalSellIntents,
            paused,
            state.killActive,
            effective,
        )
        emitP0(
            "↩️ [P0] autopilot ROLLBACK 실행 — owner=AUTOPILOT 청산 ${outcome.liquidatedCodes.size}종목·" +
                "SELL intent ${outcome.totalSellIntents}건, 전략 ${paused}건 PAUSE.\n사유: $effective",
            "AUTOPILOT_ROLLBACK_EXECUTED",
        )
    }

    /** 순자산(원) 조회 — account-wide userId. Unavailable·예외면 null(fail-closed 신호). */
    private fun resolveNetWorth(): BigDecimal? =
        try {
            when (val outcome = capitalSnapshotProvider.capitalOutcome(properties.accountUserId)) {
                is CapitalOutcome.Available -> outcome.capital.takeIf { it > BigDecimal.ZERO }
                is CapitalOutcome.Unavailable -> null
            }
        } catch (e: Exception) {
            log.error("autopilot net worth 조회 예외 — fail-closed null: err={}", e.message)
            null
        }

    /** Slack P0 + SystemEvent(CRITICAL) — 기존 알림 경로 재사용(신규 wire 0). 실패 graceful. */
    private fun emitP0(
        message: String,
        action: String,
    ) {
        slackSenderProvider.ifAvailable?.let { slack ->
            runCatching { slack.sendDirect(message) }
                .onFailure { e -> log.error("autopilot 제어 Slack P0 발사 실패: err={}", e.message) }
        }
        systemEventLoggerProvider.ifAvailable?.let { logger ->
            runCatching {
                logger.emit(
                    SystemEvent(
                        category = SystemEventCategory.OTHER,
                        severity = SystemEventSeverity.CRITICAL,
                        actor = "AutopilotControlListener",
                        action = action,
                        detailJson = "{\"action\":${jsonString(action)}}",
                    ),
                )
            }.onFailure { e -> log.error("autopilot 제어 SystemEvent 발사 실패: err={}", e.message) }
        }
    }

    private fun jsonString(raw: String): String {
        val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$escaped\""
    }
}

/**
 * autopilot 제어 cmd 페이로드 (api producer ↔ consumer 리스너 wire 계약).
 *
 * @param action          STOP / RESUME / ROLLBACK (대소문자 무관 — 리스너가 uppercase).
 * @param reason          제어 사유(RESUME 은 api 에서 빈값 400 검증 — 여기선 빈값이면 placeholder).
 * @param correctedLimits RESUME 보정 한도(부분 patch — null 필드 유지). null = 한도 유지.
 * @param requestId       멱등·추적용 요청 id(UUID).
 */
data class AutopilotControlCommand(
    val action: String = "",
    val reason: String? = null,
    val correctedLimits: AutopilotLimitCorrection? = null,
    val requestId: String? = null,
)
