package com.mj.consumer.application.market

import com.mj.consumer.messaging.producer.MarketCandleBackfillCommandPublisher
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 매일 16:30 KST에 `SubscriptionManager.activeCodes()` 일괄 backfill 명령 발행 (ADR 0049 §2-C).
 *
 * 시각: KIS 정규장 마감 15:30 + 시간외 단일가 종료 16:00 + 데이터 안정화 30분.
 * 대상: 활성 구독 종목 (사용자가 최근 진입한 종목). 빈 set이면 no-op.
 *
 * batch `MarketCandleBackfillCommandListener`가 dedup·DB max(openTime) 검사로 중복 fetch 방지.
 *
 * Profile dev|prd 한정 — mock에서는 SubscriptionManager 빈 미등록이라 비활성.
 * 운영 토글: `magicjar.candle.backfill.scheduler.enabled` (default true).
 *
 * Refs: ADR 0049 §2-A·§2-C — default5 폐기 + activeCodes 기반.
 */
@Component
@Profile("dev | prd")
@ConditionalOnProperty(
    prefix = "magicjar.candle.backfill.scheduler",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DailyCandleBackfillScheduler(
    private val subscriptionManager: SubscriptionManager,
    private val publisher: MarketCandleBackfillCommandPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 매일 16:30 KST 발행. */
    @Scheduled(cron = "0 30 16 * * *", zone = "Asia/Seoul")
    fun runDaily() {
        val codes = subscriptionManager.activeCodes()
        if (codes.isEmpty()) {
            log.info("[BACKFILL_SCHEDULER] activeCodes=0 — skip")
            return
        }
        log.info("[BACKFILL_SCHEDULER] dispatching backfill for {} active code(s): {}", codes.size, codes)
        codes.forEach { code ->
            try {
                publisher.ensureBackfill(code)
            } catch (e: Exception) {
                log.warn("[BACKFILL_SCHEDULER] publish failed code={}: {}", code, e.message)
            }
        }
    }
}
