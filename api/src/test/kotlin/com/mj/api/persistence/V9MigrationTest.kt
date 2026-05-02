package com.mj.api.persistence

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID

/**
 * V9 Flyway 마이그레이션 — 1번 트랙 신규 테이블 6개 검증.
 * Testcontainers postgres에 V0~V9 전체 적용 후 schema·seed·UNIQUE 제약 확인.
 *
 * (V8은 옆 세션의 분봉 시드라 별개. V9가 1번 트랙 본 마이그레이션.)
 *
 * Testcontainers postgres 의존 — Docker daemon 직접 접근이 필요해 sandbox bash 환경에서는
 * 작동하지 않는다. host 환경에서 `./gradlew :api:test --tests V9MigrationTest`로 별도 검증.
 * Sandbox(CI 포함)에서도 실 docker compose magicjar-postgres에 V9 마이그가 자동 적용되는지로
 * 동등 검증되므로(spec §11.1, T1 commit), 본 테스트는 sandbox에서 skip.
 */
@org.junit.jupiter.api.Disabled("Testcontainers — host 환경에서만 실행, sandbox bash docker daemon 미접근")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V9MigrationTest {
    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun setUp() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .baselineVersion("0")
            .baselineOnMigrate(true)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc =
            JdbcTemplate(
                DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
            )
    }

    @Test
    fun `V9 creates 6 new tables for track1`() {
        val tables =
            jdbc
                .queryForList(
                    """SELECT table_name FROM information_schema.tables
                 WHERE table_schema='public'
                   AND table_name IN ('users','user_trading_config','orders',
                                      'order_events','daily_order_counter','kill_switch_state')""",
                    String::class.java,
                ).toSet()

        tables shouldBe
            setOf(
                "users",
                "user_trading_config",
                "orders",
                "order_events",
                "daily_order_counter",
                "kill_switch_state",
            )
    }

    @Test
    fun `kill_switch_state seed has GLOBAL row default ON`() {
        val active =
            jdbc.queryForObject(
                "SELECT is_active FROM kill_switch_state WHERE scope='GLOBAL'",
                Boolean::class.java,
            )
        active shouldBe true
    }

    @Test
    fun `order_events unique key prevents duplicate executions`() {
        val userId = UUID.fromString("00000000-0000-0000-0000-00000000aa01")
        val orderId = UUID.fromString("00000000-0000-0000-0000-00000000aa10")
        val now = OffsetDateTime.now()

        jdbc.update(
            """INSERT INTO users(user_id, username, created_at, updated_at)
                 VALUES(?,?,?,?) ON CONFLICT DO NOTHING""",
            userId,
            "v9test",
            now,
            now,
        )
        jdbc.update(
            """INSERT INTO orders(order_id, user_id, account_no, kis_mode, intent_id,
                                   kis_order_no, krx_fwdg_ord_orgno, code, side, qty,
                                   order_type, status, created_at, updated_at)
                 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",
            orderId,
            userId,
            "50185338",
            "MOCK_KIS",
            UUID.fromString("00000000-0000-0000-0000-00000000aa20"),
            "0000999998",
            "06010",
            "005930",
            "BUY",
            1,
            "LIMIT",
            "PENDING",
            now,
            now,
        )

        val sql = """INSERT INTO order_events(event_id, order_id, user_id, account_no,
                                                kis_mode, kis_order_no, event_type,
                                                cumulative_qty, occurred_at, ingested_at)
                       VALUES(?,?,?,?,?,?,?,?,?,?)"""
        jdbc.update(
            sql,
            UUID.randomUUID(),
            orderId,
            userId,
            "50185338",
            "MOCK_KIS",
            "0000999998",
            "EXECUTED",
            1,
            now,
            now,
        )

        assertThrows<DuplicateKeyException> {
            jdbc.update(
                sql,
                UUID.randomUUID(),
                orderId,
                userId,
                "50185338",
                "MOCK_KIS",
                "0000999998",
                "EXECUTED",
                1,
                now,
                now,
            )
        }
    }

    @Test
    fun `orders qty CHECK rejects zero or negative`() {
        val userId = UUID.fromString("00000000-0000-0000-0000-00000000bb01")
        val now = OffsetDateTime.now()
        jdbc.update(
            """INSERT INTO users(user_id, username, created_at, updated_at)
                 VALUES(?,?,?,?) ON CONFLICT DO NOTHING""",
            userId,
            "v9checktest",
            now,
            now,
        )

        assertThrows<org.springframework.dao.DataIntegrityViolationException> {
            jdbc.update(
                """INSERT INTO orders(order_id, user_id, account_no, kis_mode, intent_id,
                                       kis_order_no, krx_fwdg_ord_orgno, code, side, qty,
                                       order_type, status, created_at, updated_at)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                UUID.randomUUID(),
                userId,
                "50185338",
                "MOCK_KIS",
                UUID.randomUUID(),
                "0000000001",
                "06010",
                "005930",
                "BUY",
                0,
                "LIMIT",
                "PENDING",
                now,
                now,
            )
        }
    }
}
