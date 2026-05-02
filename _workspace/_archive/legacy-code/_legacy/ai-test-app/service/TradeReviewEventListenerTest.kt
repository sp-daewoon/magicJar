package com.mj.domain.ai.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.mj.domain.common.Mode
import com.mj.domain.common.Side
import com.mj.domain.common.TradeJournalEntry
import com.mj.domain.common.event.AiReviewRequestedEvent
import kr.spdaewoon.magicjar.core.port.TradeJournalRepository
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID

class TradeReviewEventListenerTest {
    private val reviewer = mockk<TradeReviewer>()
    private val journalRepo = mockk<TradeJournalRepository>()
    private val listener = TradeReviewEventListener(reviewer, journalRepo)

    @Test
    fun `이벤트 수신 시 저장소를 통해 aiReview 업데이트`() {
        val id = UUID.randomUUID()
        val entry = sampleEntry(id)
        every { journalRepo.find(id) } returns entry
        every { reviewer.review(entry) } returns Optional.of("### 요약\n잘 진입함")
        val saved = slot<TradeJournalEntry>()
        every { journalRepo.save(capture(saved)) } just Runs

        listener.onReviewRequested(AiReviewRequestedEvent(id))

        verify(exactly = 1) { journalRepo.save(any()) }
        assert(saved.captured.aiReview != null)
        assert(saved.captured.aiReview!!.contains("요약"))
    }

    @Test
    fun `엔트리 없으면 save 호출 안함`() {
        val id = UUID.randomUUID()
        every { journalRepo.find(id) } returns null
        listener.onReviewRequested(AiReviewRequestedEvent(id))
        verify(exactly = 0) { journalRepo.save(any()) }
    }

    @Test
    fun `리뷰 empty면 save 호출 안함 (AI 비활성)`() {
        val id = UUID.randomUUID()
        val entry = sampleEntry(id)
        every { journalRepo.find(id) } returns entry
        every { reviewer.review(entry) } returns Optional.empty()
        listener.onReviewRequested(AiReviewRequestedEvent(id))
        verify(exactly = 0) { journalRepo.save(any()) }
    }

    private fun sampleEntry(id: UUID): TradeJournalEntry =
        TradeJournalEntry(
            id = id,
            tradeId = UUID.randomUUID(),
            mode = Mode.BACKTEST,
            code = "005930",
            name = "삼성전자",
            side = Side.BUY,
            entryAt = Instant.now(),
            entryPrice = BigDecimal("70000"),
            entryQty = 10,
            strategyId = "rsi-mean-reversion",
            entrySnapshot = mapOf("rsi14" to BigDecimal("28")),
            entryReason = "RSI 과매도",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}
