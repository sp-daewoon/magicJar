package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.BackfillGuardProperties
import kr.spdaewoon.magicjar.consumer.config.ChartCandleProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BackfillThrottleGate 단위 테스트 (rate-limit safety #3 — 2026-06-04 dogfooding fix).
 *
 * 검증:
 *   (1) maxConcurrentBackfills=1 직렬화 — 동시 진입 시 1개만 동시 실행
 *   (2) permit timeout 시 null 반환 (run skip — 다음 사이클로 미룸)
 *   (3) min-interval run 간 간격 적용
 */
class BackfillThrottleGateTest {
    private fun gate(
        maxConcurrent: Int = 1,
        minIntervalMillis: Long = 0L,
        acquireTimeoutMillis: Long = 60_000L,
    ): BackfillThrottleGate {
        val props =
            ChartCandleProperties(
                backfillGuard =
                    BackfillGuardProperties(
                        maxConcurrentBackfills = maxConcurrent,
                        minIntervalMillis = minIntervalMillis,
                        acquireTimeoutMillis = acquireTimeoutMillis,
                    ),
            )
        return BackfillThrottleGate(props)
    }

    @Test
    fun `(safety-3) maxConcurrent 1 직렬화 — 동시 실행은 최대 1개`() {
        val gate = gate(maxConcurrent = 1)
        val concurrent = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val pool = Executors.newFixedThreadPool(4)

        repeat(4) { idx ->
            pool.submit {
                start.await()
                gate.runThrottled("code-$idx") {
                    val now = concurrent.incrementAndGet()
                    maxObserved.updateAndGet { m -> maxOf(m, now) }
                    Thread.sleep(50)
                    concurrent.decrementAndGet()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue
        pool.shutdownNow()

        // 직렬화 — 동시 실행 1 초과 관측 0
        assertThat(maxObserved.get()).isEqualTo(1)
    }

    @Test
    fun `(safety-3) permit timeout 시 null 반환 (run skip)`() {
        // permit 1개를 점유한 상태에서 두 번째는 짧은 timeout으로 실패 → null
        val gate = gate(maxConcurrent = 1, acquireTimeoutMillis = 100L)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        pool.submit {
            gate.runThrottled("holder") {
                holding.countDown()
                release.await()
            }
        }
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue

        // 두 번째 진입은 permit 없음 → 100ms timeout 후 null
        val result = gate.runThrottled("waiter") { "executed" }
        assertThat(result).isNull()

        release.countDown()
        pool.shutdownNow()
    }

    @Test
    fun `(safety-3) min-interval — 연속 run 간 간격 적용`() {
        val gate = gate(maxConcurrent = 1, minIntervalMillis = 200L)
        val t0 = System.currentTimeMillis()
        gate.runThrottled("a") { /* 즉시 종료 */ }
        gate.runThrottled("b") { /* 두 번째 — 직전 종료 + 200ms 대기 */ }
        val elapsed = System.currentTimeMillis() - t0
        // 최소 간격 200ms 이상 (timing 마진 고려 150ms 하한)
        assertThat(elapsed).isGreaterThanOrEqualTo(150L)
    }
}
