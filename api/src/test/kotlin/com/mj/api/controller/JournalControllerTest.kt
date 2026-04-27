package com.mj.api.controller

import com.mj.api.application.journal.JournalService
import com.mj.api.config.WebSecurityConfig
import com.mj.domain.common.Mode
import com.mj.domain.common.Side
import com.mj.domain.journal.ChartSnapshot
import com.mj.domain.journal.SnapshotSource
import com.mj.domain.journal.TradeJournalEntry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    controllers = [JournalController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class JournalControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun journalService(): JournalService = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var journalService: JournalService

    private val tradeId = UUID.randomUUID()

    private fun sampleEntry() =
        TradeJournalEntry(
            id = tradeId,
            tradeId = tradeId,
            mode = Mode.BACKTEST,
            code = "005930",
            name = "삼성전자",
            side = Side.BUY,
            entryAt = Instant.now(),
            entryPrice = BigDecimal("70000"),
            entryQty = 10,
            strategyId = "sma-cross",
            entrySnapshot = emptyMap(),
            entryReason = "SMA cross",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `GET api journal returns 200 with list`() {
        every { journalService.list(any()) } returns listOf(sampleEntry())

        mvc
            .get("/api/journal")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].code") { value("005930") }
            }
    }

    @Test
    fun `GET api journal tradeId returns 200 for existing`() {
        every { journalService.get(tradeId) } returns sampleEntry()

        mvc
            .get("/api/journal/$tradeId")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
            }
    }

    @Test
    fun `GET api journal tradeId returns 404 for unknown`() {
        val unknownId = UUID.randomUUID()
        every { journalService.get(unknownId) } returns null

        mvc
            .get("/api/journal/$unknownId")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `POST api journal tradeId snapshots returns 201`() {
        val snapId = UUID.randomUUID()
        every { journalService.saveSnapshot(any(), any(), any(), any(), any(), any()) } returns
            ChartSnapshot(
                id = snapId,
                tradeId = tradeId,
                symbol = "",
                timeframe = "",
                capturedAt = Instant.now(),
                source = SnapshotSource.CLIENT_LWC,
                storageKey = "snapshots/$tradeId/$snapId.png",
                publicUrl = "/api/journal/snapshots/$snapId",
            )

        mvc
            .post("/api/journal/$tradeId/snapshots") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"imageBase64":"abc==","contentType":"image/png","width":1,"height":1}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.source") { value("CLIENT_LWC") }
            }
    }
}
