package kr.spdaewoon.magicjar.consumer.signal

import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 진입 가드 ③ — **연패 서킷** (2026-07-08 즉효 3종, 최고 관리자 결재).
 *
 * ## 근거 (2026-07-08)
 * 105 전략 25왕복 10승15패, 연패를 방치해 손실이 누적됐다. 본 서킷은 전략별 **당일 실현 왕복 연속 N패**(default 4, yml)
 * 도달 시 해당 전략을 자동 **BUY_BLOCKED** 로 전환한다(기존 status — 신규 BUY 만 차단, SELL/손절/익절 청산은 계속).
 * ACTIVE 복귀는 **자동 안 함**(사용자 수동 — 다음날 판단). "지는 날 계속 태우는" churn 을 룰로 끊는다.
 *
 * ## 왕복 손익 판정 (조사 후 최소 침습 선택)
 * strategy_position 원장([ConsumerExecutionStrategyPositionAdapter.applySell])이 SELL 체결마다
 * `realizedDelta = (fillPrice − avgPrice) × sellQty` 를 정확히 산출한다. **전량 청산(nextQty==0) = 왕복 1회 완료** 시점의
 * realizedDelta 부호로 승/패를 본다(신규 집계 테이블·재계산 0 — 기존 산출값 재사용). 부분매도는 왕복 미완이라 집계 제외.
 *  - realizedDelta < 0 → 패(streak++). N 도달 시 BUY_BLOCKED.
 *  - realizedDelta > 0 → 승(streak reset 0 — "연속" 패만 집계).
 *  - realizedDelta == 0 → 변화 없음(무손익 청산).
 *
 * ## 카운터 — 인메모리 + KST 일자 리셋 (신규 Redis 0)
 * 전략별 (kstDate, streak) 를 인메모리로 든다. 평가 시 현재 KST 날짜가 저장 날짜와 다르면 streak 를 0 으로 리셋한 뒤
 * 적용한다 → **자정/장 시작(날짜 경계) 자연 리셋**. BUY_BLOCKED 전환 자체는 DB 영속(status)이라 재기동 생존하며, 인메모리
 * 카운터가 재기동에 소실돼도 이미 전환된 status 는 유지된다(보수적 — 재기동 후 카운터는 0 부터, 과다 차단 위험 0).
 *
 * ## 쓰기 트랜잭션 경계 (D3 교훈)
 * BUY_BLOCKED UPDATE 는 [LossStreakBuyBlockWriter](`REQUIRES_NEW`)로 위임한다 — 체결 어댑터 트랜잭션과 분리해
 * 서킷 write 실패가 체결 반영을 롤백하지 않게 한다. 어떤 실패도 graceful(체결 권위 무손상).
 *
 * ## 알림 dedupe
 * BUY_BLOCKED 전환 시 SystemEvent WARN 1회(→ Slack). [NotificationRateLimiterPort] once-per-window 로 반복 전환 알림
 * 폭주를 억제(limiter 부재 시 1회 emit). 전환 후 streak reset(중복 block·중복 알림 회피).
 *
 * ## 토글 (rollback)
 * [enabled]=false 또는 [lossStreakThreshold] ≤ 0 → no-op = byte 동일(zero-regression).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 운영 가드 (ADR 0023 §2-4 정합)
 */
@Component
class LossStreakCircuitBreaker(
    private val buyBlockWriterProvider: ObjectProvider<LossStreakBuyBlockWriter>,
    private val rateLimiterProvider: ObjectProvider<NotificationRateLimiterPort>,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    @Value("\${magicjar.strategy.entry-guard.loss-streak.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.strategy.entry-guard.loss-streak.threshold:4}")
    private val lossStreakThreshold: Int = 4,
) {
    private val log = LoggerFactory.getLogger(LossStreakCircuitBreaker::class.java)

    private data class StreakState(
        val kstDate: LocalDate,
        val streak: Int,
    )

    /** key = strategyId → (KST 일자, 당일 연속 패 수). 날짜 경계 자연 리셋(평가 시 date 비교). */
    private val streaks = ConcurrentHashMap<Long, StreakState>()

    /**
     * 왕복(전량 청산) 실현 손익 통지 — **호출자는 SELL + 전량청산(nextQty==0) 확정 후 호출**.
     *
     * realizedDelta 부호로 당일 연속 패 카운터를 갱신하고, 임계 도달 시 전략을 BUY_BLOCKED 로 전환한다.
     * strategyId<=0(MANUAL sentinel)·비활성이면 no-op. 어떤 실패도 graceful(체결 권위 무손상).
     */
    fun onRoundTripClosed(
        strategyId: Long,
        userId: String,
        code: String,
        realizedDelta: BigDecimal,
    ) {
        if (!enabled || lossStreakThreshold <= 0 || strategyId <= 0L) return
        // 무손익(0) 청산은 승도 패도 아님 — 카운터 불변.
        val sign = realizedDelta.signum()
        if (sign == 0) return

        val today = LocalDate.now(KST_ZONE)
        val nextStreak =
            if (sign < 0) {
                // 패 — 날짜 경계면 0 에서 시작, 아니면 이어서 +1.
                val base = streaks[strategyId]?.takeIf { it.kstDate == today }?.streak ?: 0
                base + 1
            } else {
                0 // 승 — 연속 패 초기화.
            }
        streaks[strategyId] = StreakState(today, nextStreak)

        log.info(
            "LossStreakCircuitBreaker 왕복 집계: strategyId={} code={} realizedDelta={} {}streak={}/{} (KST {})",
            strategyId,
            code,
            realizedDelta.toPlainString(),
            if (sign < 0) "loss " else "win reset ",
            nextStreak,
            lossStreakThreshold,
            today,
        )

        if (sign < 0 && nextStreak >= lossStreakThreshold) {
            trip(strategyId, userId, code, nextStreak, today)
            streaks[strategyId] = StreakState(today, 0) // 중복 block·알림 회피(전환 후 reset).
        }
    }

    private fun trip(
        strategyId: Long,
        userId: String,
        code: String,
        streak: Int,
        today: LocalDate,
    ) {
        val writer = buyBlockWriterProvider.getIfAvailable()
        if (writer == null) {
            log.error(
                "LossStreakCircuitBreaker BUY_BLOCKED skip — LossStreakBuyBlockWriter 미합류: strategyId={} streak={}",
                strategyId,
                streak,
            )
            return
        }
        val updated =
            runCatching { writer.blockBuy(strategyId, StrategyDisabledReason.RISK_VIOLATION.name) }
                .onFailure { e ->
                    log.error("LossStreakCircuitBreaker autoBuyBlock 실패: strategyId={} err={}", strategyId, e.message)
                }.getOrDefault(0)
        log.warn(
            "LOSS_STREAK_CIRCUIT 발동(당일 {}연패 → BUY_BLOCKED): strategyId={} userId={} code={} updatedRows={} (KST {})",
            streak,
            strategyId,
            userId,
            code,
            updated,
            today,
        )
        emitAlertOnce(strategyId, userId, code, streak)
    }

    private fun emitAlertOnce(
        strategyId: Long,
        userId: String,
        code: String,
        streak: Int,
    ) {
        val limiter = rateLimiterProvider.getIfAvailable()
        val cooldownKey = "loss-streak-circuit.$strategyId"
        val allowed = limiter?.shouldEmitOncePerWindow(userId, cooldownKey, ALERT_WINDOW_MIN) ?: true
        if (!allowed) return

        systemEventLoggerProvider.ifAvailable?.let { logger ->
            runCatching {
                logger.emit(
                    SystemEvent(
                        category = SystemEventCategory.OTHER,
                        severity = SystemEventSeverity.WARN,
                        actor = ACTOR,
                        action = "STRATEGY_BUY_BLOCKED_LOSS_STREAK",
                        userId = userId,
                        detailJson =
                            """{"strategyId":$strategyId,"code":"$code","lossStreak":$streak,""" +
                                """"threshold":$lossStreakThreshold,"note":"당일 연속 실현 왕복 패 임계 도달 — BUY_BLOCKED 자동 전환(청산 계속). ACTIVE 복귀는 사용자 수동."}""",
                    ),
                )
            }.onFailure { e ->
                log.error("SystemEventLogger.emit 실패: action=STRATEGY_BUY_BLOCKED_LOSS_STREAK err={}", e.message)
            }
        }
    }

    private companion object {
        const val ACTOR = "LossStreakCircuitBreaker"
        const val ALERT_WINDOW_MIN = 60
        val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
