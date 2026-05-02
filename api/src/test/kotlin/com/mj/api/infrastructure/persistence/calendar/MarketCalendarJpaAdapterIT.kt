package com.mj.api.infrastructure.persistence.calendar

import com.mj.domain.market.session.port.MarketCalendarRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

/**
 * MarketCalendarJpaAdapter Testcontainers IT. spec §3.2, ADR 0032.
 *
 * 검증 시나리오:
 * 1. 영업일(평일, 공휴일 없음) → isBusinessDay=true, holidayReason=null
 * 2. 공휴일(어린이날 2026-05-05) → isBusinessDay=false, holidayReason=PUBLIC_HOLIDAY
 * 3. 임시휴장(연말 2026-12-31) → isBusinessDay=false, holidayReason=TEMPORARY_CLOSURE
 * 4. 토요일 → isBusinessDay=false, holidayReason=WEEKEND(fallback)
 * 5. nextBusinessDay — 금요일 이후 다음 영업일
 * 6. previousBusinessDay — 월요일 이전 직전 영업일
 * 7. V10 시드 범위 — 2026년 공휴일 수 검증
 *
 * Docker daemon 미접근 sandbox에서는 스킵(Testcontainers 특성).
 * host 환경에서 `./gradlew :api:test --tests "*.MarketCalendarJpaAdapterIT"` 로 실행.
 */
@org.junit.jupiter.api.Disabled("Testcontainers — host Docker daemon 필요, CI sandbox에서 skip")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(MarketCalendarJpaAdapter::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketCalendarJpaAdapterIT {
    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { "false" }
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var repo: MarketCalendarRepository

    @BeforeAll
    fun runFlyway() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .baselineVersion("0")
            .baselineOnMigrate(true)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    // ─── 1. 영업일 검증 ───

    @Test
    fun `2026-05-04 월요일 평일 - isBusinessDay=true`() {
        val date = LocalDate.of(2026, 5, 4) // 월요일
        repo.isBusinessDay(date) shouldBe true
        repo.getHolidayReason(date) shouldBe null
    }

    // ─── 2. 공휴일 검증 ───

    @Test
    fun `2026-05-05 어린이날 - isBusinessDay=false, holidayReason=PUBLIC_HOLIDAY`() {
        val date = LocalDate.of(2026, 5, 5) // 화요일이지만 어린이날
        repo.isBusinessDay(date) shouldBe false
        repo.getHolidayReason(date) shouldBe "PUBLIC_HOLIDAY"
    }

    @Test
    fun `2026-01-01 신정 - isBusinessDay=false`() {
        val date = LocalDate.of(2026, 1, 1)
        repo.isBusinessDay(date) shouldBe false
        repo.getHolidayReason(date) shouldNotBe null
    }

    // ─── 3. 임시휴장 검증 ───

    @Test
    fun `2026-12-31 연말 임시휴장 - isBusinessDay=false, holidayReason=TEMPORARY_CLOSURE`() {
        val date = LocalDate.of(2026, 12, 31)
        repo.isBusinessDay(date) shouldBe false
        repo.getHolidayReason(date) shouldBe "TEMPORARY_CLOSURE"
    }

    // ─── 4. 주말 검증 ───

    @Test
    fun `2026-05-02 토요일 - isBusinessDay=false, fallback WEEKEND`() {
        val date = LocalDate.of(2026, 5, 2)
        repo.isBusinessDay(date) shouldBe false
        repo.getHolidayReason(date) shouldBe "WEEKEND"
    }

    // ─── 5. nextBusinessDay ───

    @Test
    fun `nextBusinessDay - 2026-04-30 이후 다음 영업일 = 2026-05-04 월요일`() {
        // 2026-05-01 근로자의날(공휴일), 5/2(토), 5/3(일) → 5/4(월)
        val nextBiz = repo.nextBusinessDay(LocalDate.of(2026, 4, 30))
        nextBiz shouldBe LocalDate.of(2026, 5, 4)
    }

    @Test
    fun `nextBusinessDay - 주말 건너뜀`() {
        // 5/8(금) 이후 → 5/11(월)
        val nextBiz = repo.nextBusinessDay(LocalDate.of(2026, 5, 8))
        nextBiz shouldBe LocalDate.of(2026, 5, 11)
    }

    // ─── 6. previousBusinessDay ───

    @Test
    fun `previousBusinessDay - 2026-05-04 이전 직전 영업일 = 2026-04-30`() {
        // 5/1 공휴일, 5/2(토), 5/3(일) 건너뜀 → 4/30(목)
        val prevBiz = repo.previousBusinessDay(LocalDate.of(2026, 5, 4))
        prevBiz shouldBe LocalDate.of(2026, 4, 30)
    }

    // ─── 7. V10 시드 공휴일 수 검증 ───

    @Test
    fun `V10 시드 - 2026년 공휴일 18건 이상 존재`() {
        var holidayCount = 0
        val start = LocalDate.of(2026, 1, 1)
        val end = LocalDate.of(2026, 12, 31)
        var d = start
        while (!d.isAfter(end)) {
            if (!repo.isBusinessDay(d) && repo.getHolidayReason(d) != "WEEKEND") {
                holidayCount++
            }
            d = d.plusDays(1)
        }
        (holidayCount >= 18) shouldBe true
    }
}
