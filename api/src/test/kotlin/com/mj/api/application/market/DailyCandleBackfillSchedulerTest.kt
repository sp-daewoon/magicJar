package com.mj.api.application.market

import com.mj.api.messaging.producer.MarketCandleBackfillCommandPublisher
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * DailyCandleBackfillScheduler 단위 테스트 — handoff 2026-05-04 D.
 */
class DailyCandleBackfillSchedulerTest {
    private val publisher = mockk<MarketCandleBackfillCommandPublisher>()

    @Test
    fun `runDaily — default5 모두 ensureBackfill 호출`() {
        every { publisher.ensureBackfill(any()) } just Runs
        val scheduler = DailyCandleBackfillScheduler(publisher, "005930,000660,035720,005380,051910")

        scheduler.runDaily()

        verify(exactly = 1) { publisher.ensureBackfill("005930") }
        verify(exactly = 1) { publisher.ensureBackfill("000660") }
        verify(exactly = 1) { publisher.ensureBackfill("035720") }
        verify(exactly = 1) { publisher.ensureBackfill("005380") }
        verify(exactly = 1) { publisher.ensureBackfill("051910") }
    }

    @Test
    fun `runDaily — 빈 codes일 때 publisher 미호출`() {
        every { publisher.ensureBackfill(any()) } just Runs
        val scheduler = DailyCandleBackfillScheduler(publisher, "")

        scheduler.runDaily()

        verify(exactly = 0) { publisher.ensureBackfill(any()) }
    }

    @Test
    fun `runDaily — 한 종목 publish 실패해도 나머지 진행`() {
        every { publisher.ensureBackfill("005930") } throws RuntimeException("kafka down")
        every { publisher.ensureBackfill("000660") } just Runs
        val scheduler = DailyCandleBackfillScheduler(publisher, "005930,000660")

        // 예외 전파되지 않아야 한다
        scheduler.runDaily()

        verify(exactly = 1) { publisher.ensureBackfill("000660") }
    }
}
