package kr.spdaewoon.magicjar.domain.market.cache

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * CacheCircuitBreaker 상태 전이 테스트 (Phase B — 캐시 서킷브레이크).
 * 시간을 주입해 결정론적으로 CLOSED→OPEN→HALF_OPEN→CLOSED/OPEN 전이를 검증한다.
 */
class CacheCircuitBreakerTest {
    private var now = 0L

    private fun breaker(
        threshold: Int = 3,
        openMs: Long = 1000L,
    ) = CacheCircuitBreaker(failureThreshold = threshold, openDurationMillis = openMs, nowMillis = { now })

    @Test
    fun `초기 상태 CLOSED — 요청 허용`() {
        val b = breaker()
        b.currentState() shouldBe CacheCircuitBreaker.State.CLOSED
        b.allowRequest() shouldBe true
    }

    @Test
    fun `연속 실패 임계 도달 시 OPEN — 이후 요청 즉시 거부`() {
        val b = breaker(threshold = 3)
        b.recordFailure()
        b.recordFailure()
        b.currentState() shouldBe CacheCircuitBreaker.State.CLOSED // 아직 미만
        b.recordFailure() // 3회 = 임계
        b.currentState() shouldBe CacheCircuitBreaker.State.OPEN
        b.allowRequest() shouldBe false // open 기간 중 거부
    }

    @Test
    fun `성공 보고 시 실패 카운터 리셋 — OPEN 안 됨`() {
        val b = breaker(threshold = 3)
        b.recordFailure()
        b.recordFailure()
        b.recordSuccess() // 리셋
        b.recordFailure()
        b.recordFailure()
        b.currentState() shouldBe CacheCircuitBreaker.State.CLOSED // 2회뿐(리셋 후)
    }

    @Test
    fun `OPEN 기간 경과 후 HALF_OPEN 탐침 1회 허용`() {
        val b = breaker(threshold = 1, openMs = 1000L)
        now = 0
        b.recordFailure() // OPEN
        b.currentState() shouldBe CacheCircuitBreaker.State.OPEN

        now = 500 // 아직 open 기간
        b.allowRequest() shouldBe false

        now = 1000 // open 기간 경과
        b.allowRequest() shouldBe true // half-open 첫 탐침 허용
        b.currentState() shouldBe CacheCircuitBreaker.State.HALF_OPEN
        b.allowRequest() shouldBe false // 탐침 in-flight — 추가 요청 거부(폭주 차단)
    }

    @Test
    fun `HALF_OPEN 탐침 성공 시 CLOSED 복구`() {
        val b = breaker(threshold = 1, openMs = 1000L)
        b.recordFailure()
        now = 1000
        b.allowRequest() // half-open 진입
        b.recordSuccess()
        b.currentState() shouldBe CacheCircuitBreaker.State.CLOSED
        b.allowRequest() shouldBe true
    }

    @Test
    fun `HALF_OPEN 탐침 실패 시 즉시 재-OPEN`() {
        val b = breaker(threshold = 1, openMs = 1000L)
        b.recordFailure()
        now = 1000
        b.allowRequest() // half-open 진입
        b.recordFailure() // 탐침 실패
        b.currentState() shouldBe CacheCircuitBreaker.State.OPEN
        b.allowRequest() shouldBe false
    }

    @Test
    fun `withCache — CLOSED면 캐시 호출 결과 반환`() {
        val b = breaker()
        val r = b.withCache(cacheCall = { "cache" }, fallback = { "db" })
        r shouldBe "cache"
    }

    @Test
    fun `withCache — 캐시 예외 시 fallback + 실패 집계`() {
        val b = breaker(threshold = 1)
        val r = b.withCache<String>(cacheCall = { throw RuntimeException("redis down") }, fallback = { "db" })
        r shouldBe "db"
        b.currentState() shouldBe CacheCircuitBreaker.State.OPEN // 임계 1 → OPEN
    }

    @Test
    fun `withCache — OPEN이면 캐시 호출 skip하고 fallback 즉시 반환`() {
        val b = breaker(threshold = 1, openMs = 10_000L)
        b.recordFailure() // OPEN
        var cacheCalled = false
        val r =
            b.withCache(cacheCall = {
                cacheCalled = true
                "cache"
            }, fallback = { "db" })
        r shouldBe "db"
        cacheCalled shouldBe false // 회로 열려 캐시 호출 자체를 skip(hot path 블록 방지)
    }
}
