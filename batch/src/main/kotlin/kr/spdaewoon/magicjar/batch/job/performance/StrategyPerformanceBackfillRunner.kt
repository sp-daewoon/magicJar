package kr.spdaewoon.magicjar.batch.job.performance

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * 전략 성과 백필 1회성 잡 — spec `2026-06-16-strategy-daily-performance.md` §5-2 · ADR 0126 §3-3·§5-2 정합.
 *
 * ## 트리거 (기존 batch ApplicationRunner gate 패턴 — InstrumentSeedRunner 정합)
 * 기본 비활성. 다음 property/env 가 true 일 때만 부팅 시 1회 실행:
 *   `magicjar.strategy-perf.backfill.enabled=true`
 *   (env: `MAGICJAR_STRATEGY_PERF_BACKFILL_ENABLED=true`)
 *
 * 날짜 범위(KST, 양끝 포함):
 *   from = `magicjar.strategy-perf.backfill.from` (default 2026-06-11 = canary 시작일)
 *   to   = `magicjar.strategy-perf.backfill.to`   (default = T-1, 어제)
 *
 * ## 동작
 * from..to 각 날짜에 [DailyPerformanceJob.runStrategyPerformance] 재실행 → 지수 일봉 롤업 + 전략 왕복 집계
 * 멱등 UPSERT. 기존 user pnl_daily 집계는 건드리지 않는다(ADR 0126 §3-3 add-only).
 *
 * ## 운영 안전 (ADR 0126 §3-6)
 * - 읽기 전용 소스(order_execution·market_index_snapshot)만 읽음 → **새 KIS 호출 0**.
 * - 멱등 → 중단·재실행 안전. snapshot 없는 날은 지수/α/regime null, 전략 집계는 진행.
 *
 * Order(100) — 시드 Runner(Order 1) 이후, 부팅 비핵심 경로.
 */
@Component
@Order(100)
class StrategyPerformanceBackfillRunner(
    private val dailyPerformanceJob: DailyPerformanceJob,
    @Value("\${magicjar.strategy-perf.backfill.enabled:false}")
    private val enabled: Boolean,
    @Value("\${magicjar.strategy-perf.backfill.from:2026-06-11}")
    private val fromRaw: String,
    @Value("\${magicjar.strategy-perf.backfill.to:}")
    private val toRaw: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(StrategyPerformanceBackfillRunner::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    override fun run(args: ApplicationArguments) {
        if (!enabled) {
            log.debug("[strategy-perf-backfill] 비활성 (magicjar.strategy-perf.backfill.enabled=false) — skip")
            return
        }

        val from = LocalDate.parse(fromRaw)
        val to = if (toRaw.isBlank()) LocalDate.now(kst).minusDays(1) else LocalDate.parse(toRaw)

        if (from.isAfter(to)) {
            log.warn("[strategy-perf-backfill] from={} > to={} — 범위 무효, skip", from, to)
            return
        }

        log.info("[strategy-perf-backfill] 시작 — 범위 {} ~ {} (멱등 UPSERT · 새 KIS 호출 0)", from, to)
        var processed = 0
        var failed = 0
        var date = from
        while (!date.isAfter(to)) {
            runCatching {
                dailyPerformanceJob.runStrategyPerformance(date)
                processed++
            }.onFailure {
                failed++
                log.error("[strategy-perf-backfill] 날짜 {} 처리 실패: {}", date, it.message, it)
            }
            date = date.plusDays(1)
        }
        log.info("[strategy-perf-backfill] 완료 — 처리 {}일, 실패 {}일 (범위 {} ~ {})", processed, failed, from, to)
    }
}
