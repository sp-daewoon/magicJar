package kr.spdaewoon.magicjar.marketdata.news

import kr.spdaewoon.magicjar.core.port.NewsRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 5분 주기로 RSS·DART 를 폴링해 NewsRepository 에 저장하는 수집기.
 *
 * - magicjar.marketdata.news.enabled=false 로 비활성화 가능.
 * - 모든 외부 호출은 try/catch — 한 소스 실패가 전체 수집 중단으로 번지지 않게.
 */
@Component
@ConditionalOnProperty(prefix = "magicjar.marketdata.news", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NewsCollectorScheduler(
    private val rss: NaverNewsRssClient,
    private val repository: NewsRepository,
) {
    private val log = LoggerFactory.getLogger(NewsCollectorScheduler::class.java)

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT30S")
    fun collect() {
        try {
            val items = rss.fetchAll()
            if (items.isNotEmpty()) {
                repository.save(items)
                log.info("Persisted {} RSS news items", items.size)
            }
        } catch (ex: Exception) {
            log.warn("News collection failed: {}", ex.message)
        }
    }
}
