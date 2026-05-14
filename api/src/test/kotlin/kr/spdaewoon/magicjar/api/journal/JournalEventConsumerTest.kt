package kr.spdaewoon.magicjar.api.journal

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.messaging.journal.JournalEventConsumer
import kr.spdaewoon.magicjar.api.messaging.journal.JournalSummaryComposer
import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import kr.spdaewoon.magicjar.domain.journal.port.TradingJournalRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * JournalEventConsumerTest — 6건 GREEN 의무 (spec §3-1).
 *
 * - ORDER_FILLED 변환
 * - ORDER_REJECTED 변환
 * - ORDER_CANCELLED 변환
 * - SIGNAL_GENERATED 변환
 * - RISK_VIOLATION 변환 (instrumentCode nullable)
 * - 멱등성 UQ conflict 재처리 → null 반환 시 skip
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class JournalEventConsumerTest {
    private val journalRepository: TradingJournalRepository = mock()
    private val summaryComposer = JournalSummaryComposer()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }
    private val sut = JournalEventConsumer(journalRepository, summaryComposer, objectMapper)

    private fun makeRecord(
        topic: String,
        value: String,
    ): ConsumerRecord<String, String> = ConsumerRecord(topic, 0, 0L, "key", value)

    private fun makeSavedEntry(
        eventType: JournalEventType,
        sourceId: Long,
    ): TradingJournalEntry =
        TradingJournalEntry(
            id = 1001L,
            userId = 12345678L,
            eventType = eventType,
            occurredAt = Instant.now(),
            summary = "테스트",
            payload = "{}",
            sourceTable = "test",
            sourceId = sourceId,
        )

    @Test
    fun `onOrderFilled — ORDER_FILLED entry INSERT 검증`() {
        val payload =
            mapOf(
                "orderId" to 101L,
                "userId" to 12345678L,
                "code" to "005930",
                "side" to "BUY",
                "executedQuantity" to 100,
                "executedPrice" to "75000",
                "executedAt" to Instant.now().epochSecond,
                "instrumentName" to "삼성전자",
                "realizedPnl" to "150000",
            )
        val savedEntry = makeSavedEntry(JournalEventType.ORDER_FILLED, 101L)
        whenever(journalRepository.saveEntry(any())).thenReturn(savedEntry)

        sut.onOrderFilled(makeRecord("order.filled.v1", objectMapper.writeValueAsString(payload)))

        val captor = argumentCaptor<TradingJournalEntry>()
        verify(journalRepository).saveEntry(captor.capture())
        val entry = captor.firstValue
        assertThat(entry.eventType).isEqualTo(JournalEventType.ORDER_FILLED)
        assertThat(entry.userId).isEqualTo(12345678L)
        assertThat(entry.instrumentCode).isEqualTo("005930")
        assertThat(entry.sourceTable).isEqualTo("order_execution")
        assertThat(entry.sourceId).isEqualTo(101L)
        assertThat(entry.summary).contains("체결")
        assertThat(entry.summary).contains("삼성전자")
    }

    @Test
    fun `onOrderRejected — ORDER_REJECTED entry INSERT 검증`() {
        val payload =
            mapOf(
                "orderId" to 202L,
                "userId" to 12345678L,
                "code" to "035720",
                "side" to "SELL",
                "quantity" to 50,
                "rejectReason" to "POSITION_LIMIT",
                "rejectedAt" to Instant.now().epochSecond,
            )
        val savedEntry = makeSavedEntry(JournalEventType.ORDER_REJECTED, 202L)
        whenever(journalRepository.saveEntry(any())).thenReturn(savedEntry)

        sut.onOrderRejected(makeRecord("order.rejected.v1", objectMapper.writeValueAsString(payload)))

        val captor = argumentCaptor<TradingJournalEntry>()
        verify(journalRepository).saveEntry(captor.capture())
        val entry = captor.firstValue
        assertThat(entry.eventType).isEqualTo(JournalEventType.ORDER_REJECTED)
        assertThat(entry.sourceTable).isEqualTo("order_request")
        assertThat(entry.summary).contains("주문 거부")
    }

    @Test
    fun `onOrderCancelled — ORDER_CANCELLED entry INSERT 검증`() {
        val payload =
            mapOf(
                "orderId" to 303L,
                "userId" to 12345678L,
                "code" to "035420",
                "side" to "BUY",
                "quantity" to 10,
                "cancelledAt" to Instant.now().epochSecond,
            )
        val savedEntry = makeSavedEntry(JournalEventType.ORDER_CANCELLED, 303L)
        whenever(journalRepository.saveEntry(any())).thenReturn(savedEntry)

        sut.onOrderCancelled(makeRecord("order.cancelled.v1", objectMapper.writeValueAsString(payload)))

        val captor = argumentCaptor<TradingJournalEntry>()
        verify(journalRepository).saveEntry(captor.capture())
        val entry = captor.firstValue
        assertThat(entry.eventType).isEqualTo(JournalEventType.ORDER_CANCELLED)
        assertThat(entry.summary).contains("주문 취소")
    }

    @Test
    fun `onSignalGenerated — SIGNAL_GENERATED entry INSERT 검증`() {
        val payload =
            mapOf(
                "signalId" to 404L,
                "userId" to 12345678L,
                "code" to "005930",
                "side" to "BUY",
                "strength" to 78,
                "ts" to Instant.now().epochSecond,
                "instrumentName" to "삼성전자",
            )
        val savedEntry = makeSavedEntry(JournalEventType.SIGNAL_GENERATED, 404L)
        whenever(journalRepository.saveEntry(any())).thenReturn(savedEntry)

        sut.onSignalGenerated(makeRecord("signal.generated.v1", objectMapper.writeValueAsString(payload)))

        val captor = argumentCaptor<TradingJournalEntry>()
        verify(journalRepository).saveEntry(captor.capture())
        val entry = captor.firstValue
        assertThat(entry.eventType).isEqualTo(JournalEventType.SIGNAL_GENERATED)
        assertThat(entry.sourceTable).isEqualTo("signal")
        assertThat(entry.sourceId).isEqualTo(404L)
        assertThat(entry.instrumentCode).isEqualTo("005930")
        assertThat(entry.summary).contains("시그널")
    }

    @Test
    fun `onRiskViolation — RISK_VIOLATION entry INSERT 검증 (instrumentCode null 허용)`() {
        // 포트폴리오 전체 이벤트 (code null)
        val payload =
            mapOf(
                "riskEventId" to 505L,
                "userId" to 12345678L,
                "ruleType" to "DAILY_LOSS",
                "violatedAt" to Instant.now().epochSecond,
                // code 없음 — nullable
            )
        val savedEntry = makeSavedEntry(JournalEventType.RISK_VIOLATION, 505L)
        whenever(journalRepository.saveEntry(any())).thenReturn(savedEntry)

        sut.onRiskViolation(makeRecord("risk.violation.v1", objectMapper.writeValueAsString(payload)))

        val captor = argumentCaptor<TradingJournalEntry>()
        verify(journalRepository).saveEntry(captor.capture())
        val entry = captor.firstValue
        assertThat(entry.eventType).isEqualTo(JournalEventType.RISK_VIOLATION)
        assertThat(entry.instrumentCode).isNull()
        assertThat(entry.sourceTable).isEqualTo("risk_event")
        assertThat(entry.summary).contains("리스크 차단")
    }

    @Test
    fun `멱등성 UQ conflict 재처리 — saveEntry null 반환 시 skip (INSERT 0건)`() {
        val payload =
            mapOf(
                "orderId" to 101L,
                "userId" to 12345678L,
                "code" to "005930",
                "side" to "BUY",
                "executedQuantity" to 100,
                "executedPrice" to "75000",
                "executedAt" to Instant.now().epochSecond,
            )
        // UQ 충돌 — saveEntry null 반환
        whenever(journalRepository.saveEntry(any())).thenReturn(null)

        // 1회 처리
        sut.onOrderFilled(makeRecord("order.filled.v1", objectMapper.writeValueAsString(payload)))
        // 2회 처리 (재처리 시뮬레이션)
        sut.onOrderFilled(makeRecord("order.filled.v1", objectMapper.writeValueAsString(payload)))

        // saveEntry 2회 호출, null 반환 → log.warn only (예외 0)
        verify(journalRepository, org.mockito.kotlin.times(2)).saveEntry(any())
    }
}
