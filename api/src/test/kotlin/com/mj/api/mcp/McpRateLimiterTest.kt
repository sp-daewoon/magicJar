package com.mj.api.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpRateLimiterTest {
    @Test
    fun `1초 한도 10건 통과 11번째 차단`() {
        var now = 1_700_000_000_000L
        val limiter = McpRateLimiter(perSecondLimit = 10, perMinuteLimit = 1000, clock = { now })
        repeat(10) { assertTrue(limiter.allow("client-A"), "i=$it") }
        assertFalse(limiter.allow("client-A"))
    }

    @Test
    fun `1초 경과 후 카운터 리셋`() {
        var now = 1_700_000_000_000L
        val limiter = McpRateLimiter(perSecondLimit = 2, perMinuteLimit = 1000, clock = { now })
        assertTrue(limiter.allow("c"))
        assertTrue(limiter.allow("c"))
        assertFalse(limiter.allow("c"))
        now += 1100
        assertTrue(limiter.allow("c"))
    }

    @Test
    fun `client별 독립 bucket`() {
        var now = 1_700_000_000_000L
        val limiter = McpRateLimiter(perSecondLimit = 1, perMinuteLimit = 1000, clock = { now })
        assertTrue(limiter.allow("A"))
        assertFalse(limiter.allow("A"))
        assertTrue(limiter.allow("B"))
    }

    @Test
    fun `1분 한도 초과 시 1초 통과 후에도 차단 + 롤백 일관성`() {
        var now = 1_700_000_000_000L
        val limiter = McpRateLimiter(perSecondLimit = 100, perMinuteLimit = 3, clock = { now })
        var passes = 0
        repeat(5) { i ->
            // 매 호출 마다 100ms 진행 — 같은 second·같은 minute window
            now += 100
            if (limiter.allow("c")) passes++
        }
        assertEquals(3, passes)
    }
}
