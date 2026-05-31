package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PreDestroy
import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.CandleReconciliationStateStore
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * KIS-only cold-start 실행기 (2026-05-11 Yahoo 전면 폐기 — KIS-only 정책 적용).
 *
 * Yahoo Finance 의존성 완전 제거. KIS OpenAPI 단일 출처.
 *
 * cold-start 5 interval:
 *   - 1m  : FHKST03010230 — ADR 0105 one-minute lookback 설정 기반 1m 분봉 (KisRestCandleAdapter 내부 페이지네이션)
 *   - 1d  : FHKST03010100 — 일봉 200건
 *   - 1wk : FHKST03010100 — 주봉 200건
 *   - 1mo : FHKST03010100 — 월봉 120건
 *   - 1y  : FHKST03010100 — 연봉 10건
 *
 * KIS 미제공 합산 interval (3m·5m·15m·30m·90m·1h·4h·12h)은 KIS 직접 fetch에서 제외.
 * 해당 interval은 candle 요청 시 빈 결과 반환 (mock 합성 금지 — feedback_no_mock_data).
 *
 * audit action: KIS_COLD_START_DONE / KIS_COLD_START_PARTIAL_FAIL / KIS_COLD_START_FAILED
 * (구: YAHOO_COLD_START_* — 2026-05-11 deprecated, DB 기존 row는 그대로 유지)
 *
 * 별도 thread pool size=2 (cold-start-N).
 */
@Component
class ColdStartExecutor(
    private val kisRestPort: KisRestPort,
    private val candleRepository: CandleRepository,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val aggregateBackfillRunner: AggregateBackfillRunner,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
    private val reconciliationStateStore: CandleReconciliationStateStore,
) {
    private val log = LoggerFactory.getLogger(ColdStartExecutor::class.java)

    companion object {
        /**
         * KIS cold-start interval 정의.
         * (interval, count) — count는 fetchCandles 에 전달되는 상한 힌트.
         * 1m은 KisRestCandleAdapter 내부에서 ADR 0105 one-minute lookback 설정으로 처리 (count 무시).
         *
         * KIS 미제공 합산봉(3m·5m·15m·30m·90m·1h·4h·12h)은 제외 — 1m 적재 후 AggregateBackfillRunner가 생성.
         */
        val KIS_COLD_START_INTERVALS =
            listOf(
                CandleInterval.ONE_MIN to 1, // KisRestCandleAdapter 내부 one-minute lookback 페이지네이션
                CandleInterval.ONE_DAY to 200,
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
     * KIS cold-start 비동기 제출 (별도 thread — 즉시 반환).
     */
    fun submit(code: String) {
        executor.submit {
            runColdStart(code)
        }
    }

    private fun runColdStart(code: String) {
        reconciliationStateStore.markReconciling(code, CandleInterval.ONE_MIN, "cold-start")
        // 거래정지·정리매매 종목 early exit — KIS API 응답 0 (외부 결함, 재시도 무의미)
        // instrument 조회 실패(stub/UNKNOWN) 시에는 cold-start 시도 (안전 기본값)
        val instrument = instrumentJpaRepository.findById(code).orElse(null)
        if (instrument != null && (instrument.tradingHalt || instrument.liquidation)) {
            log.info(
                "KIS cold-start skip — 거래정지·정리매매: code={} halt={} liquidation={}",
                code,
                instrument.tradingHalt,
                instrument.liquidation,
            )
            try {
                val detail =
                    """{"code":"$code","reason":"TRADING_HALT",""" +
                        """"tradingHalt":${instrument.tradingHalt},"liquidation":${instrument.liquidation}}"""
                auditRepository.save(
                    ConsumerAuditTrailJpaEntity(
                        actor = "system",
                        action = "KIS_COLD_START_SKIPPED",
                        entityType = "candle",
                        entityId = code,
                        detailJson = detail,
                    ),
                )
            } catch (e: Exception) {
                log.warn("cold-start skip audit 저장 실패: code={} err={}", code, e.message)
            }
            reconciliationStateStore.markFailed(code, CandleInterval.ONE_MIN, "cold-start-trading-halt")
            return
        }

        log.info("KIS cold-start 시작: code={}", code)
        var successCount = 0
        var failCount = 0
        var oneMinReady = false

        for ((interval, count) in KIS_COLD_START_INTERVALS) {
            try {
                val candles = kisRestPort.fetchCandles(code, interval, count)
                if (candles.isNotEmpty()) {
                    val inserted = candleRepository.saveAll(candles)
                    log.debug(
                        "KIS cold-start fetch 완료: code={} interval={} total={} inserted={}",
                        code,
                        interval.code,
                        candles.size,
                        inserted,
                    )
                    successCount++
                    if (interval == CandleInterval.ONE_MIN) oneMinReady = true
                } else {
                    log.warn("KIS cold-start 빈 응답: code={} interval={}", code, interval.code)
                    failCount++
                }
            } catch (e: Exception) {
                log.warn("KIS cold-start 실패: code={} interval={} err={}", code, interval.code, e.message)
                failCount++
            }
        }

        // ADR 0062 §9 — 1m 적재 직후 3m·5m·15m·30m·90m·1h·4h·12h 합산 일괄 INSERT.
        // 실패는 warn 기록 후 계속 진행 (cold-start 전체를 막지 않음).
        var aggregateResult: Map<String, Int> = emptyMap()
        try {
            aggregateResult = aggregateBackfillRunner.backfill(code)
            log.info("AggregateBackfill 완료: code={} result={}", code, aggregateResult)
        } catch (e: Exception) {
            log.warn("AggregateBackfill 실패: code={} err={}", code, e.message)
        }

        val aggregateTotalInserted = aggregateResult.values.filter { it >= 0 }.sum()

        val auditAction =
            when {
                failCount == 0 -> "KIS_COLD_START_DONE"
                successCount == 0 -> "KIS_COLD_START_FAILED"
                else -> "KIS_COLD_START_PARTIAL_FAIL"
            }
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "system",
                    action = auditAction,
                    entityType = "candle",
                    entityId = code,
                    detailJson =
                        """{"code":"$code","success":$successCount,"fail":$failCount,"aggregateInserted":$aggregateTotalInserted}""",
                ),
            )
        } catch (e: Exception) {
            log.warn("cold-start audit 저장 실패: code={} err={}", code, e.message)
        }

        if (oneMinReady) {
            reconciliationStateStore.markReady(code, CandleInterval.ONE_MIN, "cold-start-$auditAction")
        } else {
            reconciliationStateStore.markFailed(code, CandleInterval.ONE_MIN, "cold-start-$auditAction")
        }

        log.info(
            "KIS cold-start 완료: code={} action={} success={} fail={} aggregateInserted={}",
            code,
            auditAction,
            successCount,
            failCount,
            aggregateTotalInserted,
        )
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }
}
