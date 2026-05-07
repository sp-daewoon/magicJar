package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PreDestroy
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import kr.spdaewoon.magicjar.domain.market.port.YahooCandleFetcher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Yahoo cold-start + KIS REST backfill 실행기 (ADR 0019 §5-3 정합).
 *
 * - Yahoo 7 interval 직렬 fetch → DB INSERT
 * - KIS REST 1wk·1mo·1y backfill 직렬 fetch → DB INSERT
 * - 총 ≤ 30s 기대
 * - audit row INSERT (YAHOO_COLD_START_DONE / PARTIAL_FAIL / FAILED)
 * - 별도 thread pool size=2 (cold-start-N)
 */
@Component
class ColdStartExecutor(
    private val yahooCandleFetcher: YahooCandleFetcher,
    private val kisRestPort: KisRestPort,
    private val candleRepository: CandleRepository,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
) {
    private val log = LoggerFactory.getLogger(ColdStartExecutor::class.java)

    companion object {
        /** Yahoo cold-start 7 interval 정의 (ADR 0019 §5-2) */
        val YAHOO_INTERVALS =
            listOf(
                CandleInterval.ONE_MIN to 7,
                CandleInterval.FIVE_MIN to 60,
                CandleInterval.FIFTEEN_MIN to 60,
                CandleInterval.THIRTY_MIN to 60,
                CandleInterval.NINETY_MIN to 60,
                CandleInterval.ONE_HOUR to 730,
                CandleInterval.ONE_DAY to 3650,
            )

        /** KIS REST backfill intervals */
        val KIS_REST_INTERVALS =
            listOf(
                CandleInterval.ONE_WEEK to 200,
                CandleInterval.ONE_MONTH to 120,
                CandleInterval.ONE_YEAR to 10,
            )
    }

    private val threadCounter = AtomicInteger(0)
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(
            2,
            ThreadFactory { r ->
                Thread(r, "cold-start-${threadCounter.incrementAndGet()}").also {
                    it.isDaemon = true
                }
            },
        )

    /**
     * cold-start 비동기 제출 (별도 thread — 즉시 반환).
     */
    fun submit(code: String) {
        executor.submit {
            runColdStart(code)
        }
    }

    private fun runColdStart(code: String) {
        log.info("Yahoo cold-start 시작: code={}", code)
        var successCount = 0
        var failCount = 0

        // 1. Yahoo 7 interval 직렬 fetch
        for ((interval, rangeDays) in YAHOO_INTERVALS) {
            try {
                val candles = yahooCandleFetcher.fetch(code, interval, rangeDays)
                if (candles.isNotEmpty()) {
                    val inserted = candleRepository.saveAll(candles)
                    log.debug(
                        "Yahoo fetch 완료: code={} interval={} total={} inserted={}",
                        code,
                        interval.code,
                        candles.size,
                        inserted,
                    )
                    successCount++
                } else {
                    log.warn("Yahoo fetch 빈 응답: code={} interval={}", code, interval.code)
                    failCount++
                }
            } catch (e: Exception) {
                log.warn("Yahoo fetch 실패: code={} interval={} err={}", code, interval.code, e.message)
                failCount++
            }
        }

        // 2. KIS REST 1wk·1mo·1y backfill
        for ((interval, count) in KIS_REST_INTERVALS) {
            try {
                val candles = kisRestPort.fetchCandles(code, interval, count)
                if (candles.isNotEmpty()) {
                    val inserted = candleRepository.saveAll(candles)
                    log.debug(
                        "KIS REST backfill 완료: code={} interval={} total={} inserted={}",
                        code,
                        interval.code,
                        candles.size,
                        inserted,
                    )
                    successCount++
                }
            } catch (e: Exception) {
                log.warn("KIS REST backfill 실패: code={} interval={} err={}", code, interval.code, e.message)
            }
        }

        // 3. audit row INSERT
        val auditAction =
            when {
                failCount == 0 -> "YAHOO_COLD_START_DONE"
                successCount == 0 -> "YAHOO_COLD_START_FAILED"
                else -> "YAHOO_COLD_START_PARTIAL_FAIL"
            }
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "system",
                    action = auditAction,
                    entityType = "candle",
                    entityId = code,
                    detailJson = """{"code":"$code","success":$successCount,"fail":$failCount}""",
                ),
            )
        } catch (e: Exception) {
            log.warn("cold-start audit 저장 실패: code={} err={}", code, e.message)
        }

        log.info(
            "Yahoo cold-start 완료: code={} action={} success={} fail={}",
            code,
            auditAction,
            successCount,
            failCount,
        )
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }
}
