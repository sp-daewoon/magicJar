package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.SignalResponse
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.util.Optional
import kotlinx.datetime.Instant as KxInstant

/**
 * STOMP 시그널 `ts` ISO-8601 정규화 검증 (Invalid Date fix).
 *
 * 근본 원인: REST(`SignalController`)는 `signal.ts.toString()`(ISO-8601)로 직렬화하지만,
 * Kafka producer(`SignalKafkaProducer`)는 `signal.ts.epochSeconds`(epoch 초 Long)로 직렬화한다.
 * 기존 `StompSignalDispatcher`는 그 epoch초를 그대로 `toString()`해서 프론트 `new Date(...)` → Invalid Date.
 *
 * 검증 목표: raw `ts`가 epoch초(JSON number)·epoch밀리·ISO 문자열 어떤 형태로 와도
 * STOMP 발행 payload의 `ts`가 REST와 동일한 ISO-8601이 되는지 + 이상/빈 입력 폴백.
 */
class StompSignalDispatcherTsTest {
    private val messagingTemplate: SimpMessagingTemplate = mock()
    private val objectMapper = ObjectMapper()
    private val instrumentRepo: InstrumentJpaRepository =
        mock { whenever(it.findById(any())).thenReturn(Optional.empty()) }
    private val persister: StompEventPersister = mock()

    private val dispatcher =
        StompSignalDispatcher(messagingTemplate, objectMapper, instrumentRepo, persister)

    /** epoch초 1747008300 → 2026-05-12T00:05:00Z. REST(kotlinx Instant.toString())와 동일성 기준. */
    private val epochSec = 1_747_008_300L
    private val expectedIso = KxInstant.fromEpochSeconds(epochSec).toString()

    private fun dispatchAndCaptureTs(tsJson: String): String {
        val payload =
            """{"id":1,"strategyId":8,"userId":"1","code":"005930","interval":"5m",""" +
                """"ts":$tsJson,"side":"BUY","strength":80,"reason":"r",""" +
                """"priceAtSignal":"73400","contextJson":null,"createdAt":null}"""
        dispatcher.onSignalGenerated(ConsumerRecord("t", 0, 0L, "1", payload))
        val captor = argumentCaptor<SignalResponse>()
        verify(messagingTemplate).convertAndSend(eq(StompDestinations.signalUser("1")), captor.capture())
        return captor.firstValue.ts
    }

    @Test
    fun `epoch초(JSON number) ts - REST와 동일 ISO`() {
        // 실제 producer 형식: signal.ts.epochSeconds (number)
        assertThat(dispatchAndCaptureTs("$epochSec")).isEqualTo(expectedIso)
    }

    @Test
    fun `epoch초 문자열 ts - REST와 동일 ISO`() {
        assertThat(dispatchAndCaptureTs("\"$epochSec\"")).isEqualTo(expectedIso)
    }

    @Test
    fun `epoch밀리 ts - REST와 동일 ISO`() {
        // 자릿수 휴리스틱(>=1e12)으로 밀리초 판정 — 동일 시각이면 동일 ISO.
        val epochMilli = epochSec * 1_000L
        assertThat(dispatchAndCaptureTs("$epochMilli")).isEqualTo(expectedIso)
    }

    @Test
    fun `ISO 문자열 ts - 그대로 정규화되어 동일 ISO`() {
        assertThat(dispatchAndCaptureTs("\"$expectedIso\"")).isEqualTo(expectedIso)
    }

    @Test
    fun `빈 문자열 ts - 빈 문자열 폴백`() {
        assertThat(dispatchAndCaptureTs("\"\"")).isEqualTo("")
    }

    @Test
    fun `이상 문자열 ts - 빈 문자열 폴백`() {
        assertThat(dispatchAndCaptureTs("\"not-a-date\"")).isEqualTo("")
    }

    @Test
    fun `ts null - 빈 문자열 폴백`() {
        assertThat(dispatchAndCaptureTs("null")).isEqualTo("")
    }
}
