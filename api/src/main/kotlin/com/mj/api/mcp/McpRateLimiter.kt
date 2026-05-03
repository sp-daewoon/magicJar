package com.mj.api.mcp

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP rate limiter (T17 / spec §5-3).
 *
 * R1: in-memory bucket. 단일 인스턴스 가정. R2부터 Redis 분산.
 *
 * 한도:
 *  - 1초당 10 요청
 *  - 1분당 100 요청
 *
 * 키: client identity (X-MCP-Key sha256 prefix 또는 remoteAddress).
 */
@Component
class McpRateLimiter(
    private val perSecondLimit: Int = 10,
    private val perMinuteLimit: Int = 100,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val buckets = ConcurrentHashMap<String, ClientBucket>()

    fun allow(clientKey: String): Boolean {
        val now = clock()
        val bucket = buckets.computeIfAbsent(clientKey) { ClientBucket() }
        return bucket.tryConsume(now, perSecondLimit, perMinuteLimit)
    }

    private class ClientBucket {
        private val secondWindow = AtomicLong(0L) // (epochSec << 32) | count
        private val minuteWindow = AtomicLong(0L) // (epochMin << 32) | count

        fun tryConsume(
            nowMs: Long,
            secLimit: Int,
            minLimit: Int,
        ): Boolean {
            val nowSec = nowMs / 1000
            val nowMin = nowMs / 60000

            // 1분 window — overflow 우선 검사 (더 큰 한도)
            val minutePass = bumpAndCheck(minuteWindow, nowMin, minLimit)
            if (!minutePass) return false

            // 1초 window
            val secondPass = bumpAndCheck(secondWindow, nowSec, secLimit)
            if (!secondPass) {
                // 분 카운터는 이미 +1 됨 → 1초 거부 시 차감해 일관성 유지
                rollback(minuteWindow, nowMin)
                return false
            }
            return true
        }

        private fun bumpAndCheck(
            window: AtomicLong,
            slot: Long,
            limit: Int,
        ): Boolean {
            while (true) {
                val packed = window.get()
                val curSlot = packed ushr 32
                val curCount = (packed and 0xFFFFFFFFL).toInt()
                val (newSlot, newCount) =
                    if (curSlot == slot) slot to (curCount + 1) else slot to 1
                if (newCount > limit) return false
                val newPacked = (newSlot shl 32) or (newCount.toLong() and 0xFFFFFFFFL)
                if (window.compareAndSet(packed, newPacked)) return true
            }
        }

        private fun rollback(
            window: AtomicLong,
            slot: Long,
        ) {
            while (true) {
                val packed = window.get()
                val curSlot = packed ushr 32
                val curCount = (packed and 0xFFFFFFFFL).toInt()
                if (curSlot != slot || curCount <= 0) return
                val newPacked = (curSlot shl 32) or ((curCount - 1).toLong() and 0xFFFFFFFFL)
                if (window.compareAndSet(packed, newPacked)) return
            }
        }
    }
}
