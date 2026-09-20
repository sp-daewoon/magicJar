package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.MarketDailyBackfillProperties
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 일봉 backfill 자동 완주 오퍼레이션 (ADR 0143 — cap 절대값 종료의 3대 공백 보강).
 *
 * ## 왜 필요한가 (대운님 지적)
 * [MarketDailyBackfillCampaign]은 1회 run 당 nightly-call-cap(6000콜)까지만 처리하고 CAP_REACHED로 멈춘다.
 * 기존엔 그 다음 run을 **사람/외부가 반복 호출**해야만 완주했고, 완주 보장·실패 가시화 오퍼레이션이 없었다
 * ("절대값으로 자르고 마는 건 정합성 서비스가 아니다"). 본 러너가 그 공백 3가지를 채운다:
 *   1. **자동 재시도 트리거** — cap 도달(CAP_REACHED)이면 다음 tick에 자동으로 이어 호출. remaining=0까지.
 *   2. **완주 판정 + 종료** — COMPLETED 또는 remaining=0 도달 시 완주 SystemEvent(INFO) emit + idle 전환.
 *   3. **정체/실패 알람** — [STALL_THRESHOLD]회 연속 remaining 감소 0(진행 정체)이면 WARN emit + 자동 재시도 중단
 *      (무한 재시도로 KIS를 계속 때리지 않도록). 운영자가 /system 에서 확인 후 조치.
 *
 * ## 안전
 * - [MarketDailyBackfillProperties.enabled]=false면 아무것도 안 함(가동 0).
 * - rate-limit·circuit·cap은 campaign 내부에서 그대로 유효 — 본 러너는 트리거·완주판정·알람만.
 * - tick 주기 > run 소요면 자연 순차. 실행 중 재진입은 [running] 가드로 차단.
 */
@Component
class MarketDailyBackfillAutoRunner(
    private val campaign: MarketDailyBackfillCampaign,
    private val properties: MarketDailyBackfillProperties,
    @Autowired(required = false) private val systemEventLogger: SystemEventLogger? = null,
) {
    private val log = LoggerFactory.getLogger(MarketDailyBackfillAutoRunner::class.java)

    /** 재진입 가드 — run 소요가 tick보다 길어도 중복 실행 0. */
    private val running =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** 직전 tick의 remaining — 정체(감소 0) 감지용. -1 = 미측정. */
    private val lastRemaining = AtomicInteger(-1)

    /** 연속 정체(remaining 감소 0) 횟수. [STALL_THRESHOLD] 도달 시 자동 재시도 중단 + WARN. */
    private val stallStreak = AtomicInteger(0)

    /** 완주 알람 1회 LOCK — remaining=0 도달 시 중복 emit 방지. */
    private val completionEmitted =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** 정체로 자동 재시도 중단됨 — 운영자 조치(수동 run) 전까지 tick skip. */
    private val stalledStop =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private val lastRunAtMs = AtomicLong(0L)

    companion object {
        const val ACTOR = "MarketDailyBackfillAutoRunner"

        /** 연속 정체 임계 — 이 횟수만큼 remaining이 안 줄면 자동 재시도 중단 + WARN(무한 재시도 방지). */
        const val STALL_THRESHOLD = 3
    }

    /**
     * 자동 완주 tick — default 5분. enabled=true이고 미완주면 campaign 이어 호출.
     * cron ENV 외부화: `magicjar.market-daily-backfill.auto-runner-cron` (default 매 5분).
     */
    @Scheduled(cron = "\${magicjar.market-daily-backfill.auto-runner-cron:0 */5 * * * *}", zone = "Asia/Seoul")
    fun tick() {
        if (!properties.enabled) return
        if (stalledStop.get()) return // 정체로 중단됨 — 운영자 수동 재개 전까지 skip
        if (!running.compareAndSet(false, true)) {
            log.debug("MarketDailyBackfillAutoRunner tick skip — 이전 run 진행 중")
            return
        }
        try {
            val r = campaign.runCampaign()
            lastRunAtMs.set(System.currentTimeMillis())
            log.info(
                "MarketDailyBackfillAutoRunner tick — status={} newlyComplete={} failed={} remaining={} calls={}/{}",
                r.status,
                r.newlyCompleteSymbols,
                r.failedSymbols,
                r.remainingSymbols,
                r.callsConsumed,
                r.callCap,
            )
            evaluate(r.status, r.remainingSymbols, r.failedSymbols, r.universeSize)
        } catch (e: Exception) {
            log.warn("MarketDailyBackfillAutoRunner tick 실패: {}", e.message)
        } finally {
            running.set(false)
        }
    }

    /** 완주 판정 + 정체 감지 + 알람 (오퍼레이션 2·3). */
    private fun evaluate(
        status: MarketDailyBackfillCampaign.CampaignStatus,
        remaining: Int,
        failed: Int,
        universeSize: Int,
    ) {
        // 1) 완주 — remaining=0 또는 COMPLETED
        if (remaining <= 0 || status == MarketDailyBackfillCampaign.CampaignStatus.COMPLETED) {
            if (completionEmitted.compareAndSet(false, true)) {
                emit(
                    SystemEventSeverity.INFO,
                    "MARKET_DAILY_BACKFILL_COMPLETE",
                    """{"universeSize":$universeSize,"remaining":$remaining,"status":"$status"}""",
                )
                log.info("MarketDailyBackfillAutoRunner 완주 — universe={} remaining={}", universeSize, remaining)
            }
            resetProgress()
            return
        }

        // 진행 중 — 완주 플래그 해제(다음 완주 재알람 허용)
        completionEmitted.set(false)

        // 2) 정체 감지 — remaining이 직전과 같거나 늘면 stall streak++
        val prev = lastRemaining.getAndSet(remaining)
        if (prev >= 0 && remaining >= prev) {
            val streak = stallStreak.incrementAndGet()
            if (streak >= STALL_THRESHOLD) {
                stalledStop.set(true) // 자동 재시도 중단 — KIS 무한 타격 방지
                emit(
                    SystemEventSeverity.WARN,
                    "MARKET_DAILY_BACKFILL_STALLED",
                    """{"remaining":$remaining,"failed":$failed,"stallStreak":$streak,""" +
                        """"note":"${STALL_THRESHOLD}회 연속 진행 0 — 자동 재시도 중단. 원인(KIS 500 등) 확인 후 수동 run 재개"}""",
                )
                log.warn(
                    "MarketDailyBackfillAutoRunner 정체 — remaining={} {}회 연속 진행 0 → 자동 재시도 중단(WARN emit)",
                    remaining,
                    streak,
                )
            }
        } else {
            stallStreak.set(0) // 진행 있음 — streak reset
        }
    }

    private fun resetProgress() {
        lastRemaining.set(-1)
        stallStreak.set(0)
    }

    /**
     * 정체로 중단된 자동 재시도를 수동 재개 (운영자 조치 후). 테스트·admin 재개 진입점.
     */
    fun resumeAfterStall() {
        stalledStop.set(false)
        resetProgress()
        log.info("MarketDailyBackfillAutoRunner 정체 중단 해제 — 자동 재시도 재개")
    }

    private fun emit(
        severity: SystemEventSeverity,
        action: String,
        detailJson: String,
    ) {
        val logger = systemEventLogger ?: return
        runCatching {
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.MARKET_DATA,
                    severity = severity,
                    actor = ACTOR,
                    action = action,
                    userId = null,
                    correlationId = null,
                    detailJson = detailJson,
                ),
            )
        }.onFailure { e -> log.warn("MarketDailyBackfillAutoRunner SystemEvent emit 실패: {}", e.message) }
    }
}
