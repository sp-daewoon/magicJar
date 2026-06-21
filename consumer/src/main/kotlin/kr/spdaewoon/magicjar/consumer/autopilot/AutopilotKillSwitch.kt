package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * magicjar-autopilot **kill switch** (spec 2026-06-22 §2-0-B · Phase 0 Task 3).
 *
 * 누적 손실 한도 도달([kr.spdaewoon.magicjar.strategy.autopilot.AutopilotGuardVerdict.KillTriggered]) 또는
 * 수동 트리거(`/autopilot stop`)로 발동하며, 다음 4가지를 묶어 수행한다:
 *  1. `autopilot_state.kill_active = true` 영속([AutopilotStateStore.activateKill]).
 *  2. owner=AUTOPILOT 전략 **전부 PAUSED**([ConsumerStrategyJpaRepository.pauseAllAutopilotStrategies] —
 *     `owner='AUTOPILOT'` 격리로 사용자 수동 전략·후성 절대 비간섭, spec §2-0-C).
 *  3. REAL 경로 freeze — 별도 액션 불요. kill_active=true 면 [AutopilotBuyGuard] 가 owner=AUTOPILOT REAL 매수를
 *     차단하고 Guard.evaluate 도 0순위로 Reject("kill active") 한다(페이퍼·백테스트는 가능 — 게이트는 BUY intent 한정).
 *  4. Slack **P0** + SystemEvent(CRITICAL) — 기존 알림 경로 재사용(신규 wire 0).
 *
 * ## 멱등
 * 이미 kill ON 이면 [AutopilotStateStore.activateKill] 가 멱등 skip, PAUSE 쿼리도 이미 PAUSED 행 제외 → 반복
 * 트리거가 알림 폭주를 만들지 않도록 [trigger] 가 "상태가 실제로 변했을 때만" Slack/SystemEvent 를 1회 발사한다.
 *
 * ## 게이트 (zero-regression)
 * 본 컴포넌트는 [AutopilotBuyGuard] 가 `enabled=true` + owner=AUTOPILOT + KillTriggered 일 때만 호출한다.
 * enabled=false 면 [trigger] 가 호출되지 않으므로 기존 매매 경로에 어떤 영향도 없다.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 안전장치 (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotKillSwitch(
    private val stateStore: AutopilotStateStore,
    private val strategyRepository: ConsumerStrategyJpaRepository,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    private val killJournalService: AutopilotKillJournalService,
) {
    private val log = LoggerFactory.getLogger(AutopilotKillSwitch::class.java)

    /**
     * kill switch 발동 — 영속 + owner=AUTOPILOT PAUSE + **사후분석 저널 1행**(Task 4) + Slack P0 + SystemEvent(멱등).
     *
     * @param reason kill 사유(KillTriggered.reason 또는 수동 사유). 알림·audit 에 그대로 실린다.
     * @return true = 이번 호출로 실제 kill ON 전환됨(알림 발사) / false = 이미 ON(멱등 skip).
     */
    @Transactional
    fun trigger(reason: String): Boolean {
        val before = stateStore.load()
        if (before.killActive) {
            log.info("autopilot kill 이미 ON — 멱등 skip (reason={})", reason)
            return false
        }

        stateStore.activateKill()

        val paused =
            try {
                strategyRepository.pauseAllAutopilotStrategies()
            } catch (e: Exception) {
                log.error("autopilot kill — owner=AUTOPILOT PAUSE 실패(kill_active 는 이미 영속, 다음 평가에서 차단 유지): err={}", e.message)
                0
            }

        log.warn(
            "★ AUTOPILOT KILL SWITCH ON — owner=AUTOPILOT 전략 {}건 PAUSED, REAL 경로 freeze. reason={}",
            paused,
            reason,
        )

        // post-mortem 저널(spec §2-0-D) — 발동 시점 손실·한도·보유종목·PAUSE 전략수 박제. 실패는 kill 무손상(graceful).
        killJournalService.recordTrigger(reason, before, paused)

        emitP0(reason, paused)
        return true
    }

    /** Slack P0 + SystemEvent(CRITICAL) — 기존 알림 경로 재사용. 알림 실패가 kill 영속을 되돌리지 않는다(graceful). */
    private fun emitP0(
        reason: String,
        pausedCount: Int,
    ) {
        val message =
            "🛑 [P0] magicjar-autopilot KILL SWITCH ON\n" +
                "사유: $reason\n" +
                "owner=AUTOPILOT 전략 ${pausedCount}건 PAUSED · REAL 자율 매수 freeze (페이퍼·백테스트는 유지)"
        slackSenderProvider.ifAvailable?.let { slack ->
            runCatching { slack.sendDirect(message) }
                .onFailure { e -> log.error("autopilot kill Slack P0 발사 실패: err={}", e.message) }
        }
        systemEventLoggerProvider.ifAvailable?.let { logger ->
            runCatching {
                logger.emit(
                    SystemEvent(
                        category = SystemEventCategory.OTHER,
                        severity = SystemEventSeverity.CRITICAL,
                        actor = "AutopilotKillSwitch",
                        action = "AUTOPILOT_KILL_SWITCH_ON",
                        detailJson =
                            "{\"reason\":${jsonString(reason)},\"pausedStrategies\":$pausedCount}",
                    ),
                )
            }.onFailure { e -> log.error("autopilot kill SystemEvent 발사 실패: err={}", e.message) }
        }
    }

    /** 최소 JSON 문자열 escape — detailJson 인라인용(reason 에 따옴표·역슬래시 혼입 방어). */
    private fun jsonString(raw: String): String {
        val escaped =
            raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
        return "\"$escaped\""
    }
}
