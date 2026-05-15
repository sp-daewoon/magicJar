package kr.spdaewoon.magicjar.api.scheduler

import kr.spdaewoon.magicjar.api.infrastructure.naver.NaverNewsAdapter
import kr.spdaewoon.magicjar.api.infrastructure.naver.NaverNewsRateLimiter
import kr.spdaewoon.magicjar.api.infrastructure.naver.NewsKafkaProducer
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.WatchlistJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Watchlist 종목 우선 Naver 뉴스 폴링 스케줄러 (T-NEWS Phase 2 — ADR 0075 §8 spec §2-2).
 *
 * 주기: 1분 fixedRate (분당 17건 Naver hard limit — 슬롯 관리는 NaverNewsRateLimiter 책임).
 * 대상: 사용자 등록 Watchlist 종목 distinct (전체 3,000종목 X — API 한도 보호).
 *
 * 동작 흐름 (ADR 0075 §9-2 시나리오 정합):
 *   1. WatchlistJpaRepository → 활성 watchlist distinct code 목록
 *   2. InstrumentJpaRepository → 종목코드 → 종목명(name) 조회 (query 파라미터용)
 *   3. NaverNewsRateLimiter.acquireSlot() → 분당 17건 슬롯 확인
 *   4. NaverNewsAdapter.fetchAndSave(code, name) → news_article INSERT (멱등성)
 *   5. NewsKafkaProducer.publish(entity) → news.collected.v1 publish
 *   6. 슬롯 초과 시 남은 종목 skip + log WARN → 다음 cron 1분 후 재시도
 *
 * Watchlist 30 종목 가정:
 *   분당 17건 한도 → 1회전 30종목 / 17건 = 최대 2분 주기 (17건 + 13건 다음 분)
 *   display=20 (종목당 최대 20건) → 종목 1회 = 1 rate limit slot 소모
 *
 * graceful:
 *   Naver API 키 미설정 → NaverNewsAdapter 내부에서 graceful skip + log INFO
 *   rate limit 초과 → 해당 cron 회차 남은 종목 skip + 다음 분 재시도
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
@Component
class WatchlistNewsScheduler(
    private val watchlistJpaRepository: WatchlistJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val naverNewsAdapter: NaverNewsAdapter,
    private val naverNewsRateLimiter: NaverNewsRateLimiter,
    private val newsKafkaProducer: NewsKafkaProducer,
) {
    private val log = LoggerFactory.getLogger(WatchlistNewsScheduler::class.java)

    /**
     * 1분 주기 Naver 뉴스 폴링 (ADR 0075 §8 spec §2-2 결정 8 — Watchlist 우선).
     *
     * fixedRate = 60_000ms (1분) — cron 표현식 대신 fixedRate 사용 (Phase 1 DartDisclosurePollingScheduler 패턴 정합)
     */
    @Scheduled(fixedRate = 60_000L)
    fun pollWatchlistNews() {
        val allWatchlists = watchlistJpaRepository.findAll()
        val distinctCodes = allWatchlists.map { it.code }.distinct()

        if (distinctCodes.isEmpty()) {
            log.debug("[WatchlistNewsScheduler] Watchlist 종목 없음 — 폴링 skip")
            return
        }

        log.info("[WatchlistNewsScheduler] 폴링 시작 — {} 종목", distinctCodes.size)

        var fetched = 0
        var skippedByRateLimit = 0

        for (code in distinctCodes) {
            // Rate limit 슬롯 획득 시도
            if (!naverNewsRateLimiter.acquireSlot()) {
                skippedByRateLimit++
                log.warn(
                    "[WatchlistNewsScheduler] rate limit 초과 — 종목 skip (code={}, 잔여={} 종목)",
                    code,
                    distinctCodes.size - fetched - skippedByRateLimit,
                )
                continue
            }

            // 종목명 조회 (쿼리 파라미터용)
            val instrument = instrumentJpaRepository.findById(code).orElse(null)
            val query = instrument?.name ?: code // 종목명 없으면 코드로 fallback

            try {
                val savedEntities = naverNewsAdapter.fetchAndSave(code, query)
                fetched++

                // 신규 저장된 기사만 Kafka publish
                for (entity in savedEntities) {
                    newsKafkaProducer.publish(entity)
                }

                if (savedEntities.isNotEmpty()) {
                    log.info(
                        "[WatchlistNewsScheduler] 뉴스 수집 — code={} query={} 신규={}건",
                        code,
                        query,
                        savedEntities.size,
                    )
                }
            } catch (ex: Exception) {
                log.error(
                    "[WatchlistNewsScheduler] 뉴스 fetch 오류 — code={} query={} err={}",
                    code,
                    query,
                    ex.message,
                )
            }
        }

        log.info(
            "[WatchlistNewsScheduler] 폴링 완료 — {} 종목 처리 — Naver fetch {}건 (rate limit skip={}건)",
            distinctCodes.size,
            fetched,
            skippedByRateLimit,
        )
    }
}
