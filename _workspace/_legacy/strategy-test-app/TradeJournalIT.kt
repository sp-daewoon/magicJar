package com.mj.domain.strategy.journal

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.core.domain.Mode
import kr.spdaewoon.magicjar.core.domain.Side
import kr.spdaewoon.magicjar.core.port.InstrumentCatalog
import kr.spdaewoon.magicjar.core.port.TradeJournalFilter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant

@Testcontainers
@SpringBootTest(classes = [TradeJournalIT.TestApp::class])
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
class TradeJournalIT {
    @SpringBootApplication
    @EntityScan(basePackages = ["com.mj.domain.strategy.journal"])
    @EnableJpaRepositories(basePackages = ["com.mj.domain.strategy.journal"])
    open class TestApp {
        @Bean
        @Primary
        open fun stubInstrumentCatalog(): InstrumentCatalog =
            object : InstrumentCatalog {
                override fun findAll() = emptyList<kr.spdaewoon.magicjar.core.domain.Instrument>()

                override fun findByCode(code: String) = null

                override fun upsert(instruments: List<kr.spdaewoon.magicjar.core.domain.Instrument>) {}
            }
    }

    @Autowired
    lateinit var recorder: TradeJournalRecorder

    @Autowired
    lateinit var adapter: TradeJournalRepositoryAdapter

    @Test
    fun `entry then exit roundtrip persists pnl`() {
        recorder.recordEntry(
            mode = Mode.BACKTEST,
            code = "005930",
            name = "삼성전자",
            qty = 10,
            price = BigDecimal("70000"),
            at = Instant.parse("2024-01-10T00:00:00Z"),
            strategyId = "rsi_oversold_v1",
            entryReason = "RSI<30 oversold",
            snapshot = mapOf("rsi14" to BigDecimal("28.5"), "close" to BigDecimal("70000")),
        )
        recorder.recordExit(
            mode = Mode.BACKTEST,
            code = "005930",
            qty = 10,
            price = BigDecimal("75000"),
            at = Instant.parse("2024-02-10T00:00:00Z"),
            exitReason = "TP",
            snapshot = mapOf("rsi14" to BigDecimal("65.0"), "close" to BigDecimal("75000")),
        )
        val results =
            adapter.search(
                TradeJournalFilter(
                    mode = Mode.BACKTEST,
                    code = "005930",
                    side = Side.BUY,
                    strategyId = "rsi_oversold_v1",
                ),
            )
        results shouldHaveSize 1
        val entry = results.first()
        entry.entryQty shouldBe 10
        entry.exitQty shouldBe 10
        require(entry.pnl != null)
        entry.pnl!!.toDouble() shouldBe 50000.0
        require(entry.pnlRate != null)
        require(kotlin.math.abs(entry.pnlRate!!.toDouble() - 0.0714286) < 0.0001)
        entry.entrySnapshot["rsi14"]!!.toDouble() shouldBe 28.5
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("magicjar_test")
                .withUsername("test")
                .withPassword("test")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            postgres.start()
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.locations") { "classpath:db/migration" }
            registry.add("spring.flyway.baseline-on-migrate") { "true" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("magicjar.mode") { "BACKTEST" }
            registry.add("magicjar.journal.ai-review") { "false" }
        }
    }
}
