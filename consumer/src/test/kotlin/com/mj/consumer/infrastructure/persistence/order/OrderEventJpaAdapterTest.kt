package com.mj.consumer.infrastructure.persistence.order

import com.mj.domain.order.OrderEventRecord
import com.mj.domain.order.OrderEventSource
import com.mj.domain.order.OrderEventType
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.OffsetDateTime
import java.util.UUID

class OrderEventJpaAdapterTest {
    private val repo = mockk<OrderEventJpaRepository>()
    private val adapter = OrderEventJpaAdapter(repo)

    private fun event(cumulativeQty: Int = 1) =
        OrderEventRecord(
            eventId = UUID.randomUUID(),
            orderId = UUID.fromString("00000000-0000-0000-0000-00000000aa10"),
            userId = "74683223",
            accountNo = "50185338",
            kisMode = "MOCK_KIS",
            kisOrderNo = "0000999998",
            eventType = OrderEventType.EXECUTED,
            cumulativeQty = cumulativeQty,
            partialQty = 1,
            execPrice = null,
            source = OrderEventSource.WS,
            payload = null,
            occurredAt = OffsetDateTime.now(),
            ingestedAt = OffsetDateTime.now(),
        )

    @Test
    fun `insertIdempotent returns true on first insert`() {
        every { repo.saveAndFlush(any()) } returns mockk()
        adapter.insertIdempotent(event()) shouldBe true
    }

    @Test
    fun `insertIdempotent returns false on UNIQUE conflict (DataIntegrityViolation)`() {
        every { repo.saveAndFlush(any()) } throws DataIntegrityViolationException("unique conflict")
        adapter.insertIdempotent(event()) shouldBe false
    }

    @Test
    fun `insertIdempotent rethrows other exceptions`() {
        every { repo.saveAndFlush(any()) } throws RuntimeException("other")
        try {
            adapter.insertIdempotent(event())
            error("expected RuntimeException")
        } catch (e: RuntimeException) {
            // expected (NOT DataIntegrityViolationException)
        }
    }
}
