package kr.spdaewoon.magicjar.api.ai.explain

import io.micrometer.core.instrument.MeterRegistry
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * AI 월 비용 임계 알림 스케줄러 (ADR 0095 amend S-AI-1 · 2026-07-03 시스템 감사 §6-14).
 *
 * 배경: `AiCostTracker.isOverThreshold()`($10/월)가 caller 0건 dead code 상태였다 — 본 스케줄러가
 * 일 1회(09:00 KST) 검사해 초과 시 SystemEvent CRITICAL emit.
 *
 * 알림 경로 (api → Slack 실배선 확인, 2026-07-03):
 *   SystemEventLogger(persistence-shared SystemEventLoggerAdapter) → DB system_event + Kafka system.event.v1
 *   → consumer SystemEventNotificationDispatcher — CRITICAL은 P0 즉시 Slack 발송.
 *   AutopilotUniverseRefreshService emit 패턴 1:1 (ObjectProvider graceful).
 *
 * 게이트: `magicjar.ai.enabled=true` 일 때만 bean 등록 (AI OFF면 비용 발생 자체가 0 — 스케줄 불요).
 * 매매 결정권 0 — 관측·알림 전용, 시그널/주문 경로 미접촉 (ADR 0023 정합).
 */
@Component
@ConditionalOnProperty(name = ["magicjar.ai.enabled"], havingValue = "true")
class AiCostAlertScheduler(
    private val aiCostTracker: AiCostTracker,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
) {
    private val log = LoggerFactory.getLogger(AiCostAlertScheduler::class.java)

    companion object {
        const val ACTOR = "AiCostAlertScheduler"
        const val ACTION_THRESHOLD_EXCEEDED = "AI_COST_THRESHOLD_EXCEEDED"

        /** 임계 초과 감지 누적 Counter — Prometheus `magicjar_ai_cost_threshold_exceeded_total`. */
        const val METRIC_THRESHOLD_EXCEEDED = "magicjar.ai.cost.threshold.exceeded"
    }

    /**
     * 일 1회 09:00 KST 월 누적 비용 검사 (cron ENV 외부화 — DiscoveryScorecardScheduler 동일 SpEL placeholder 패턴).
     *
     * 초과 시:
     *   1. log.error (docker logs 1차 evidence)
     *   2. Micrometer counter increment (Prometheus alert 후속 확장 대비)
     *   3. SystemEvent CRITICAL emit → consumer가 Slack P0 즉시 발송
     *
     * 검사 자체 실패(DB 등)는 WARN — 스케줄러가 앱을 죽이지 않음 (graceful).
     */
    @Scheduled(cron = "\${magicjar.ai.cost-alert.cron:0 0 9 * * *}", zone = "Asia/Seoul")
    fun checkMonthlyCost() {
        runCatching {
            val spend = aiCostTracker.monthlySpend()
            val threshold = aiCostTracker.monthlyThresholdUsd
            if (spend < threshold) {
                log.info("AI 월 비용 임계 검사 OK: spend=\${} threshold=\${} (S-AI-1)", spend, threshold)
                return
            }

            log.error(
                "AI 월 비용 임계 초과 (S-AI-1 CRITICAL): spend=\${} threshold=\${} — MAGICJAR_AI_KILLSWITCH 검토",
                spend,
                threshold,
            )

            runCatching {
                meterRegistryProvider.getIfAvailable()?.counter(METRIC_THRESHOLD_EXCEEDED)?.increment()
            }

            val logger = systemEventLoggerProvider.getIfAvailable()
            if (logger == null) {
                log.warn("SystemEventLogger 미등록 — S-AI-1 Slack 경로 skip (log.error + counter만 발사됨)")
                return
            }
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.OTHER,
                    severity = SystemEventSeverity.CRITICAL,
                    actor = ACTOR,
                    action = ACTION_THRESHOLD_EXCEEDED,
                    detailJson =
                        """{"monthlySpendUsd":"$spend","thresholdUsd":"$threshold","guide":"MAGICJAR_AI_KILLSWITCH=true 검토 (ADR 0095 amend S-AI-1)"}""",
                ),
            )
        }.onFailure { e ->
            log.warn("AI 월 비용 임계 검사 실패 (다음 주기 재시도): err={}", e.message)
        }
    }
}
