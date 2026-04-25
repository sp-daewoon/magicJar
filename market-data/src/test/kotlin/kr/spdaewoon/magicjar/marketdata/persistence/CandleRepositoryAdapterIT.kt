package kr.spdaewoon.magicjar.marketdata.persistence

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import kr.spdaewoon.magicjar.core.domain.Candle
import kr.spdaewoon.magicjar.core.domain.Interval
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan("kr.spdaewoon.magicjar.marketdata.persistence")
@EnableJpaRepositories("kr.spdaewoon.magicjar.marketdata.persistence")
@Import(CandleRepositoryAdapterIT.AdapterConfig::class)
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
class CandleRepositoryAdapterIT {
    @Configuration
    @ComponentScan(basePackageClasses = [CandleRepositoryAdapter::class])
    @EnableAutoConfiguration
    class AdapterConfig

    @Autowired lateinit var adapter: CandleRepositoryAdapter

    @Test
    fun `save then find returns same candles`() {
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val candles =
            (0L..2L).map { day ->
                Candle(
                    code = "005930",
                    interval = Interval.D1,
                    openTime = now.plusSeconds(day * 86_400),
                    open = BigDecimal("70000"),
                    high = BigDecimal("71000"),
                    low = BigDecimal("69500"),
                    close = BigDecimal("70500").add(BigDecimal(day * 100)),
                    volume = 1_000_000L + day,
                    tradeCount = 50_000L,
                )
            }
        adapter.save(candles)

        val found =
            adapter.find(
                code = "005930",
                interval = Interval.D1,
                from = now.minusSeconds(60),
                to = now.plusSeconds(86_400 * 5),
            )

        found shouldHaveSize 3
        found.first().close shouldBeGreaterThanOrEqualTo BigDecimal("70500")
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:15-alpine")
                .withDatabaseName("magicjar")
                .withUsername("magicjar")
                .withPassword("magicjar")

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            postgres.start()
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
            reg.add("spring.flyway.enabled") { "true" }
            reg.add("spring.flyway.locations") { "classpath:db/migration" }
            reg.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
