package com.mj.consumer.config

import com.mj.domain.common.Instrument
import com.mj.domain.journal.NewsItem
import com.mj.domain.journal.TradeJournalEntry
import com.mj.domain.journal.port.NewsRepository
import com.mj.domain.journal.port.TradeJournalFilter
import com.mj.domain.journal.port.TradeJournalRepository
import com.mj.domain.market.port.InstrumentCatalog
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P4 자율 hotfix — consumer 앱이 domain port 구현체를 자체적으로 갖지 않아 ApplicationContext 기동 실패.
 *
 * 본 stub는 다음 빈을 in-memory로 제공한다:
 * - TradeJournalRepository  (TradeJournalRecorder, TradeReviewEventListener가 의존)
 * - InstrumentCatalog       (TradeJournalRecorder가 의존)
 *
 * 실 운영 어댑터(JpaTradeJournalRepositoryAdapter 등)는 api 모듈에 정의되어 있으나
 * 모듈 격리상 consumer가 import할 수 없음. Phase E의 첫 작업에서 다음 중 한 가지로 정리:
 *   (a) shared-infrastructure Gradle 모듈 신설 후 어댑터 이전
 *   (b) consumer/batch에 동일 어댑터 코드 복제
 *   (c) Kafka 메시지로 api에 위임 (api가 DB 단일 owner)
 *
 * 관련 OPEN issue: P2 모듈 경계 회귀.
 */
@Configuration
class StubRepositoriesConfig {
    private val log = LoggerFactory.getLogger(StubRepositoriesConfig::class.java)

    @Bean
    @ConditionalOnMissingBean
    fun stubTradeJournalRepository(): TradeJournalRepository {
        log.warn("TradeJournalRepository in-memory stub — Phase E에서 실제 어댑터로 교체 필요")
        val store = ConcurrentHashMap<UUID, TradeJournalEntry>()
        return object : TradeJournalRepository {
            override fun save(entry: TradeJournalEntry) {
                store[entry.id] = entry
            }

            override fun find(id: UUID): TradeJournalEntry? = store[id]

            override fun search(filter: TradeJournalFilter): List<TradeJournalEntry> =
                store.values
                    .filter { entry ->
                        (filter.mode == null || entry.mode == filter.mode) &&
                            (filter.code == null || entry.code == filter.code) &&
                            (filter.side == null || entry.side == filter.side)
                    }.drop(filter.offset)
                    .take(filter.limit)
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun stubInstrumentCatalog(): InstrumentCatalog {
        log.warn("InstrumentCatalog in-memory stub — Phase E에서 실제 어댑터로 교체 필요")
        val store = ConcurrentHashMap<String, Instrument>()
        return object : InstrumentCatalog {
            override fun findAll(): List<Instrument> = store.values.toList()

            override fun findByCode(code: String): Instrument? = store[code]

            override fun upsert(instruments: List<Instrument>) {
                instruments.forEach { store[it.code] = it }
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun stubNewsRepository(): NewsRepository {
        log.warn("NewsRepository in-memory stub — Phase E에서 실제 어댑터로 교체 필요")
        val store = CopyOnWriteArrayList<NewsItem>()
        return object : NewsRepository {
            override fun save(items: List<NewsItem>) {
                store.addAll(items)
            }

            override fun search(
                code: String?,
                from: Instant,
                to: Instant,
            ): List<NewsItem> =
                store.filter {
                    (code == null || code in it.instruments) && it.publishedAt in from..to
                }
        }
    }
}
