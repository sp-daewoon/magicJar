package kr.spdaewoon.magicjar.batch.job.instrument

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentAliasJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentJpaRepository
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.instrument.port.KisInstrumentMasterPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * InstrumentSyncJob smoke test (T-IM1 PR-11a).
 *
 * 검증:
 * 1. diff logic (INSERT NEW · UPDATE CHANGED · DELIST 폐지)
 * 2. delisted 처리 — markDelisted 호출 확인 (DELETE 금지 — ADR 0017 §2-2)
 * 3. KIS fetch 실패 → graceful (DB 변경 0건)
 */
class InstrumentSyncJobTest {
    private val kisPort: KisInstrumentMasterPort = mockk()
    private val instrumentRepo: BatchInstrumentJpaRepository = mockk()
    private val aliasRepo: BatchInstrumentAliasJpaRepository = mockk()
    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val kafkaTemplate: KafkaTemplate<String, String> = mockk()
    private val objectMapper = ObjectMapper()

    private lateinit var job: InstrumentSyncJob

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
        // 락 획득 성공
        every {
            valueOps.setIfAbsent(any(), "true", Duration.ofSeconds(600))
        } returns true
        // 락 해제
        every { redisTemplate.delete(any<String>()) } returns true
        // Redis keys() 빈 set
        every { redisTemplate.keys(any()) } returns emptySet()
        // lastAt SET (아무 인수나 허용)
        every { valueOps.set(any(), any(), any<Duration>()) } returns Unit

        job =
            InstrumentSyncJob(
                kisPort = kisPort,
                instrumentRepo = instrumentRepo,
                aliasRepo = aliasRepo,
                redisTemplate = redisTemplate,
                kafkaTemplate = kafkaTemplate,
                objectMapper = objectMapper,
            )
    }

    @Test
    fun `diff logic — INSERT NEW + DELIST 처리`() {
        // given
        val existingActive =
            listOf(
                makeEntity("005930", "삼성전자", "KOSPI"),
                makeEntity("000660", "SK하이닉스", "KOSPI"), // KIS 응답에서 제거 → DELIST
            )
        val kisResponse =
            listOf(
                makeInstrument("005930", "삼성전자", InstrumentMarket.KOSPI),
                makeInstrument("099999", "신규상장종목A", InstrumentMarket.KOSPI), // NEW
            )

        every { kisPort.fetchAll() } returns kisResponse
        every { instrumentRepo.findAllByActiveTrue() } returns existingActive
        justRun { instrumentRepo.upsert(any(), any(), any(), any(), any(), any(), any()) }
        justRun { aliasRepo.upsert(any(), any(), any()) }
        justRun { instrumentRepo.markDelisted(any(), any()) }
        every { kafkaTemplate.send(any<String>(), any(), any()) } returns mockk {
            every { isDone } returns false
        }

        // when
        val result = job.run("test")

        // then
        assertEquals(2, result.fetched, "fetched = KIS 응답 건수")
        assertEquals(1, result.inserted, "신규 상장 1건 INSERT")
        assertEquals(0, result.updated, "변경 없음")
        assertEquals(1, result.delisted, "폐지 종목 1건 DELIST")
        assertEquals(0, result.failed, "실패 0건")

        // DELETE 금지 검증 — markDelisted 호출 확인
        verify(exactly = 1) { instrumentRepo.markDelisted("000660", any()) }
    }

    @Test
    fun `UPDATE CHANGED — 종목명 변경 시 UPDATE 실행`() {
        // given
        val existingActive =
            listOf(
                makeEntity("005930", "삼성전자(구)", "KOSPI"), // 이름 변경됨
            )
        val kisResponse =
            listOf(
                makeInstrument("005930", "삼성전자", InstrumentMarket.KOSPI),
            )

        every { kisPort.fetchAll() } returns kisResponse
        every { instrumentRepo.findAllByActiveTrue() } returns existingActive
        justRun { instrumentRepo.upsert(any(), any(), any(), any(), any(), any(), any()) }
        justRun { aliasRepo.upsert(any(), any(), any()) }
        every { kafkaTemplate.send(any<String>(), any(), any()) } returns mockk {
            every { isDone } returns false
        }

        // when
        val result = job.run("test")

        // then
        assertEquals(0, result.inserted, "신규 없음")
        assertEquals(1, result.updated, "이름 변경 UPDATE 1건")
        assertEquals(0, result.delisted, "폐지 없음")
    }

    @Test
    fun `KIS fetch 실패 — graceful (DB 변경 0건 + failed=1)`() {
        // given
        every { kisPort.fetchAll() } throws RuntimeException("KIS 종목 마스터 fetch 실패")

        // when
        val result = job.run("test")

        // then
        assertEquals(0, result.fetched, "fetch 실패 → fetched=0")
        assertEquals(1, result.failed, "failed=1 기록")
        assertEquals(0, result.inserted, "INSERT 없음")
        assertEquals(0, result.delisted, "DELIST 없음")

        // DB 미호출 검증
        verify(exactly = 0) { instrumentRepo.findAllByActiveTrue() }
        verify(exactly = 0) { instrumentRepo.upsert(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { instrumentRepo.markDelisted(any(), any()) }
    }

    // ─── Helper ──────────────────────────────────────────────────────────

    private fun makeEntity(
        code: String,
        name: String,
        market: String,
    ) = BatchInstrumentJpaEntity(
        code = code,
        name = name,
        market = market,
        type = "STOCK",
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun makeInstrument(
        code: String,
        name: String,
        market: InstrumentMarket,
    ) = Instrument(
        code = code,
        isin = null,
        name = name,
        nameEn = null,
        market = market,
        type = InstrumentType.STOCK,
        sector = null,
        listedAt = null,
        delistedAt = null,
        active = true,
    )
}
