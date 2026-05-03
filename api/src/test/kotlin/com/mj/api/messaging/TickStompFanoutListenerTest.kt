package com.mj.api.messaging

import com.mj.api.messaging.listener.TickStompFanoutListener
import com.mj.domain.market.event.TickEvent
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketSessionResolver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * TickStompFanoutListener 단위 테스트. T2-T15, ADR 0033.
 *
 * 시나리오:
 *   1. Open 장 → stale=false 그대로 fanout
 *   2. PostMarket → stale=true 마킹 후 fanout
 *   3. Closed → stale=true 마킹 후 fanout
 *   4. MOCK source → stale=false 그대로 (mock은 항상 LIVE)
 *   5. sessionResolver 예외 → 원본 그대로 fanout
 */
class TickStompFanoutListenerTest {
    private lateinit var stomp: SimpMessagingTemplate
    private lateinit var sessionResolver: MarketSessionResolver
    private lateinit var listener: TickStompFanoutListener

    @BeforeEach
    fun setUp() {
        stomp = mockk(relaxed = true)
        sessionResolver = mockk()
        listener = TickStompFanoutListener(stomp, sessionResolver)
    }

    private val zone = ZoneId.of("Asia/Seoul")
    private val testCode = "005930"
    private val now = Instant.now()
    private val testPrice = BigDecimal("75000")

    private fun makeSession(state: MarketSessionState) =
        MarketSession(
            state = state,
            kstAt = OffsetDateTime.now(zone),
            lastBusinessDate = LocalDate.now(zone),
            nextOpenAt = null,
        )

    private fun makeTick(
        source: String = "KIS_WS",
        stale: Boolean = false,
    ): TickEvent =
        TickEvent(
            code = testCode,
            at = now,
            price = testPrice,
            volume = 1_000_000L,
            source = source,
            stale = stale,
        )

    @Test
    fun `Open 장 - stale=false 그대로 fanout`() {
        every { sessionResolver.resolve() } returns makeSession(MarketSessionState.Open)

        val slot = slot<TickEvent>()
        every { stomp.convertAndSend(any<String>(), capture(slot)) } returns Unit

        listener.onTick(makeTick())

        verify { stomp.convertAndSend("/topic/market.tick.$testCode", any<TickEvent>()) }
        slot.captured.stale shouldBe false
    }

    @Test
    fun `PostMarket - stale=true 마킹`() {
        every { sessionResolver.resolve() } returns makeSession(MarketSessionState.PostMarket)

        val slot = slot<TickEvent>()
        every { stomp.convertAndSend(any<String>(), capture(slot)) } returns Unit

        listener.onTick(makeTick())

        slot.captured.stale shouldBe true
        slot.captured.asOf shouldBe now
    }

    @Test
    fun `Closed 장 - stale=true 마킹`() {
        every { sessionResolver.resolve() } returns makeSession(MarketSessionState.Closed("Saturday"))

        val slot = slot<TickEvent>()
        every { stomp.convertAndSend(any<String>(), capture(slot)) } returns Unit

        listener.onTick(makeTick())

        slot.captured.stale shouldBe true
    }

    @Test
    fun `Holiday 장 - stale=true 마킹`() {
        every { sessionResolver.resolve() } returns makeSession(MarketSessionState.Holiday("어린이날", "PUBLIC_HOLIDAY"))

        val slot = slot<TickEvent>()
        every { stomp.convertAndSend(any<String>(), capture(slot)) } returns Unit

        listener.onTick(makeTick())

        slot.captured.stale shouldBe true
    }

    @Test
    fun `MOCK source - 항상 stale=false (LIVE 처리)`() {
        // MOCK 소스는 sessionResolver 호출 없이 원본 반환
        val slot = slot<TickEvent>()
        every { stomp.convertAndSend(any<String>(), capture(slot)) } returns Unit

        listener.onTick(makeTick(source = "MOCK"))

        slot.captured.stale shouldBe false
        slot.captured.source shouldBe "MOCK"
        // MOCK 때는 sessionResolver 호출 안 함
        verify(exactly = 0) { sessionResolver.resolve() }
    }

    @Test
    fun `sessionResolver 예외 - 원본 그대로 fanout`() {
        every { sessionResolver.resolve() } throws RuntimeException("DB 연결 실패")

        val slot = slot<TickEvent>()
        every { stomp.convertAndSend(any<String>(), capture(slot)) } returns Unit

        listener.onTick(makeTick())

        // 예외 발생해도 원본 tick 그대로 fanout
        slot.captured.stale shouldBe false
        verify { stomp.convertAndSend("/topic/market.tick.$testCode", any<TickEvent>()) }
    }

    @Test
    fun `PreMarket - stale 변경 없음`() {
        every { sessionResolver.resolve() } returns makeSession(MarketSessionState.PreMarket)

        val slot = slot<TickEvent>()
        every { stomp.convertAndSend(any<String>(), capture(slot)) } returns Unit

        listener.onTick(makeTick())

        slot.captured.stale shouldBe false
    }
}
