package com.mj.api.application.market

import com.mj.api.messaging.producer.MarketCandleBackfillCommandPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 매일 16:30 KST에 default5 + watchlist 종목의 일봉 backfill 명령을 일괄 발행한다.
 *
 * 시각 결정 사유: KIS 정규장 마감 15:30 + 시간외 단일가 종료 16:00 + 데이터 안정화 30분.
 * batch listener가 dedup·중복 fetch 방지 처리.
 *
 * Watchlist 통합은 R2-A2 또는 그 이후. 본 사이클은 default5 한정.
 *
 * 운영 토글: `magicjar.candle.backfill.scheduler.enabled` (default true).
 *
 * Refs: handoff 2026-05-04 D + plan T2-R2-A3 영역.
 */
@Component
@ConditionalOnProperty(
    prefix = "magicjar.candle.backfill.scheduler",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DailyCandleBackfillScheduler(
    private val publisher: MarketCandleBackfillCommandPublisher,
    @Value("\${magicjar.market.default-codes:005930,000660,035720,005380,051910}")
    private val defaultCodesRaw: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val defaultCodes: List<String>
        get() = defaultCodesRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }

    /** 매일 16:30 KST 발행. */
    @Scheduled(cron = "0 30 16 * * *", zone = "Asia/Seoul")
    fun runDaily() {
        val codes = defaultCodes
        if (codes.isEmpty()) {
            log.info("[BACKFILL_SCHEDULER] no codes — skip")
            return
        }
        log.info("[BACKFILL_SCHEDULER] dispatching backfill {} code(s): {}", codes.size, codes)
        codes.forEach { code ->
            try {
                publisher.ensureBackfill(code)
            } catch (e: Exception) {
                log.warn("[BACKFILL_SCHEDULER] publish failed code={}: {}", code, e.message)
            }
        }
    }
}
