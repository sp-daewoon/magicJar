package com.mj.api.application.market

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 캔들 캐시 LRU eviction 스케줄러 (T19-T7).
 *
 * 매 6시간 정각(Asia/Seoul)에 CacheEvictionService.evictIfNeeded()를 호출한다.
 * 예외는 캐치·로깅하여 Spring 스케줄러 중단을 방지한다.
 *
 * 비활성화:
 *   - 환경변수 MAGICJAR_CANDLE_EVICTION_ENABLED=false
 *   - 또는 application.yml: magicjar.candle.cache.eviction.enabled: false
 *   기본값 true — mock·dev 환경에서도 eviction 동작.
 */
@Component
@ConditionalOnProperty(
    name = ["magicjar.candle.cache.eviction.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class EvictionScheduler(
    private val cacheEvictionService: CacheEvictionService,
) {
    private val log = LoggerFactory.getLogger(EvictionScheduler::class.java)

    /**
     * 매 6시간 정각(0·6·12·18시) 실행 (Asia/Seoul).
     * cron: 초 분 시 일 월 요일
     */
    @Scheduled(cron = "0 0 */6 * * *", zone = "Asia/Seoul")
    fun runEviction() {
        log.info("[eviction-scheduler] cron triggered — starting eviction check")
        try {
            val result = cacheEvictionService.evictIfNeeded()
            log.info(
                "[eviction-scheduler] completed — evictedCount={} reclaimedBytes={}",
                result.evictedCount,
                result.reclaimedBytes,
            )
        } catch (e: Exception) {
            log.error("[eviction-scheduler] unexpected error: {}", e.message, e)
            // 스케줄러 실행 중단 방지 — 예외를 삼킴
        }
    }
}
