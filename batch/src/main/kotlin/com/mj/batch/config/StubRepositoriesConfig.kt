package com.mj.batch.config

import com.mj.domain.common.Candle
import com.mj.domain.common.Instrument
import com.mj.domain.common.Interval
import com.mj.domain.market.port.CandleRepository
import com.mj.domain.market.port.InstrumentCatalog
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * P4 자율 hotfix — batch 앱이 domain port 구현체를 자체적으로 갖지 않아 ApplicationContext 기동 실패.
 *
 * 본 stub는 backtest 잡이 실제 호출되기 전까지 컨텍스트 부팅을 가능케 한다.
 * 실 운영 어댑터(JpaCandleRepositoryAdapter 등)를 batch 모듈에 도입하는 작업은 Phase E 첫 작업으로 처리.
 *
 * 관련 OPEN issue: P2 모듈 경계 회귀 — JpaRepositoryAdapter가 api 모듈에만 정의되어
 * batch/consumer가 동일 port를 사용할 때 빈을 못 찾음.
 */
@Configuration
class StubRepositoriesConfig {
    private val log = LoggerFactory.getLogger(StubRepositoriesConfig::class.java)

    @Bean
    @ConditionalOnMissingBean
    fun stubCandleRepository(): CandleRepository {
        log.warn("CandleRepository stub bean — Phase E에서 실제 JPA 어댑터로 교체 필요")
        return object : CandleRepository {
            override fun save(candles: List<Candle>) {
                log.debug("stub.save({} candles) — no-op", candles.size)
            }

            override fun find(
                code: String,
                interval: Interval,
                from: Instant,
                to: Instant,
            ): List<Candle> {
                log.debug("stub.find(code={}, interval={}) — empty", code, interval)
                return emptyList()
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun stubInstrumentCatalog(): InstrumentCatalog {
        log.warn("InstrumentCatalog stub bean — Phase E에서 실제 어댑터로 교체 필요")
        val store = ConcurrentHashMap<String, Instrument>()
        return object : InstrumentCatalog {
            override fun findAll(): List<Instrument> = store.values.toList()

            override fun findByCode(code: String): Instrument? = store[code]

            override fun upsert(instruments: List<Instrument>) {
                instruments.forEach { store[it.code] = it }
            }
        }
    }
}
