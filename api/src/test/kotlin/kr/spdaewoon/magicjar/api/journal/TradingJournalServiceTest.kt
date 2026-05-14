package kr.spdaewoon.magicjar.api.journal

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.journal.JournalNoteRequest
import kr.spdaewoon.magicjar.api.service.journal.TradingJournalService
import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.JournalNote
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import kr.spdaewoon.magicjar.domain.journal.port.TradingJournalRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

/**
 * TradingJournalServiceTest — 4건 GREEN 의무 (spec §3-1).
 *
 * - list 페이징 조회
 * - entry 단건 + notes 포함
 * - note 작성
 * - userId 격리 (404)
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class TradingJournalServiceTest {
    private val journalRepository: TradingJournalRepository = mock()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }
    private val sut = TradingJournalService(journalRepository, objectMapper)

    private fun makeEntry(
        id: Long = 1001L,
        userId: Long = 12345678L,
        eventType: JournalEventType = JournalEventType.ORDER_FILLED,
        notes: List<JournalNote> = emptyList(),
    ): TradingJournalEntry =
        TradingJournalEntry(
            id = id,
            userId = userId,
            eventType = eventType,
            instrumentCode = "005930",
            instrumentName = "삼성전자",
            occurredAt = Instant.parse("2026-05-14T05:32:18Z"),
            summary = "체결: 삼성전자 매수 100주 @ 75,000원",
            payload = """{"code":"005930","side":"BUY"}""",
            sourceTable = "order_execution",
            sourceId = 101L,
            realizedPnl = BigDecimal("150000"),
            notes = notes,
        )

    private fun makeNote(
        id: Long = 501L,
        entryId: Long = 1001L,
        userId: Long = 12345678L,
    ): JournalNote =
        JournalNote(
            id = id,
            userId = userId,
            journalEntryId = entryId,
            noteText = "RSI 과매도 진입 테스트",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `listEntries — 페이징 조회 200 반환`() {
        val entries = listOf(makeEntry(id = 3L), makeEntry(id = 2L), makeEntry(id = 1L))
        whenever(
            journalRepository.countEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
            ),
        ).thenReturn(3L)
        whenever(
            journalRepository.findEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(0),
                eq(20),
            ),
        ).thenReturn(entries)

        val result = sut.listEntries(12345678L, null, null, null, null, 0, 20)

        assertThat(result.content).hasSize(3)
        assertThat(result.totalElements).isEqualTo(3L)
        assertThat(result.content[0].id).isEqualTo(3L)
        assertThat(result.content[0].eventType).isEqualTo("ORDER_FILLED")
    }

    @Test
    fun `findEntry — entry 단건 + notes 포함 반환`() {
        val note = makeNote()
        val entry = makeEntry(notes = listOf(note))
        whenever(journalRepository.findEntryById(1001L, 12345678L)).thenReturn(entry)

        val result = sut.findEntry(1001L, 12345678L)

        assertThat(result.id).isEqualTo(1001L)
        assertThat(result.instrumentCode).isEqualTo("005930")
        assertThat(result.notes).hasSize(1)
        assertThat(result.notes[0].noteText).isEqualTo("RSI 과매도 진입 테스트")
    }

    @Test
    fun `writeNote — 메모 작성 성공`() {
        val entry = makeEntry()
        whenever(journalRepository.findEntryById(1001L, 12345678L)).thenReturn(entry)
        val savedNote = makeNote()
        whenever(journalRepository.saveNote(any())).thenReturn(savedNote)

        val request =
            JournalNoteRequest(
                userId = 12345678L,
                journalEntryId = 1001L,
                noteText = "RSI 과매도 진입 테스트",
            )
        val result = sut.writeNote(request)

        assertThat(result.id).isEqualTo(501L)
        assertThat(result.noteText).isEqualTo("RSI 과매도 진입 테스트")
    }

    @Test
    fun `findEntry — userId 격리 — mismatch 시 404`() {
        // userId=99999로 조회, entry 없음 (userId mismatch)
        whenever(journalRepository.findEntryById(1001L, 99999L)).thenReturn(null)

        val ex =
            assertThrows<ResponseStatusException> {
                sut.findEntry(1001L, 99999L)
            }
        assertThat(ex.statusCode.value()).isEqualTo(404)
    }
}
