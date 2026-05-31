package kr.spdaewoon.magicjar.consumer.bootstrap

import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.consumer.ingestion.ColdStartExecutor
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * 부팅 시 candle 공백·부족 종목 KIS cold-start 자동 보충 (2026-05-11 — KIS-only 정책).
 *
 * 목적:
 *   - Yahoo 삭제 또는 신규 배포 후 DB에 1d candle이 없는 watchlist 종목 자동 감지
 *   - 1m candle이 부족한 종목(MIN_1M_CANDLE_THRESHOLD 미만) cold-start 트리거
 *   - 1m 충분하나 합산봉(3m·5m·15m·30m·90m·1h·4h·12h) 미생성 종목 — aggregateBackfillRunner 직접 트리거
 *   - ColdStartExecutor.submit(code)로 비동기 KIS backfill 트리거
 *
 * 실행 순서: PositionBootstrapRunner 이후 실행 (@Order(10)).
 * 빠른 부팅을 위해 submit만 호출 (비동기 — cold-start 완료를 기다리지 않음).
 *
 * 판단 기준:
 *   - 1d candle count == 0 → cold-start 트리거 (전체 interval 재수집 — aggregation 포함)
 *   - 1m candle count < MIN_1M_CANDLE_THRESHOLD (=700) → cold-start 트리거 (aggregation 포함)
 *   - 1m 충분 + 합산 target 중 하나라도 candle count == 0 → aggregateBackfillRunner.backfill 직접 호출
 *     (기존 1m 데이터로 합산만 재실행 — cold-start 중복 호출 없음)
 *
 * ADR 0062 §9 — 합산 target 검사 분기는 배포 후 첫 부팅 시 자동 트리거 보장.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
@Order(10)
class CandleBootstrapRunner(
    private val watchlistJpaRepository: ConsumerWatchlistJpaRepository,
    private val candleRepository: CandleRepository,
    private val coldStartExecutor: ColdStartExecutor,
    private val aggregateBackfillRunner: AggregateBackfillRunner,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
    private val auditTrailJpaRepository: ConsumerAuditTrailJpaRepository,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(CandleBootstrapRunner::class.java)

    companion object {
        /**
         * 1m candle 최소 임계치.
         * ADR 0063 threshold=700 정합: NXT cover(08:00~19:55) 기준 1일 최대 720건.
         * 700건 미만이면 최소 1거래일치 1m도 부족한 상태로 판단 → cold-start 재트리거.
         */
        private const val MIN_1M_CANDLE_THRESHOLD = 700L

        /**
         * hotfix 2026-05-15: stale ts 검사 임계치 (며칠 down 후 부분 gap 헷징).
         * 1d candle: 2 거래일 (주말 가드) 이상 stale → 재트리거.
         * 1m candle: 1일 (오늘 09:00 KST 이전) 이상 stale → 재트리거.
         */
        private const val STALE_1D_DAYS = 2L
        private const val STALE_1M_DAYS = 1L
    }

    override fun run(args: ApplicationArguments) {
        log.info("CandleBootstrapRunner 시작 — 1d/1m candle 공백·부족 종목 감지")
        val watchlistEntries = watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc()
        if (watchlistEntries.isEmpty()) {
            log.info("CandleBootstrapRunner — watchlist 종목 없음, 건너뜀")
            return
        }

        var coldStartCount = 0
        var aggregateOnlyCount = 0
        var skippedCount = 0

        for (entry in watchlistEntries) {
            val code = entry.code

            // 거래정지·정리매매 종목 skip (KIS API 응답 0 — 외부 결함, 시스템 보강 불가)
            // instrument 조회 실패(stub/UNKNOWN) 시에는 skip 하지 않고 cold-start 시도
            val instrument = instrumentJpaRepository.findById(code).orElse(null)
            if (instrument != null && (instrument.tradingHalt || instrument.liquidation)) {
                log.info(
                    "CandleBootstrapRunner — 거래정지·정리매매 종목 skip: code={} halt={} liquidation={}",
                    code,
                    instrument.tradingHalt,
                    instrument.liquidation,
                )
                try {
                    val detail =
                        """{"code":"$code","reason":"TRADING_HALT",""" +
                            """"tradingHalt":${instrument.tradingHalt},"liquidation":${instrument.liquidation}}"""
                    auditTrailJpaRepository.save(
                        ConsumerAuditTrailJpaEntity(
                            actor = "system",
                            action = "KIS_COLD_START_SKIPPED",
                            entityType = "candle",
                            entityId = code,
                            detailJson = detail,
                        ),
                    )
                } catch (e: Exception) {
                    log.warn("CandleBootstrapRunner skip audit 저장 실패: code={} err={}", code, e.message)
                }
                skippedCount++
                continue
            }

            val count1d = candleRepository.countByCodeAndInterval(code, CandleInterval.ONE_DAY)
            val count1m = candleRepository.countByCodeAndInterval(code, CandleInterval.ONE_MIN)

            // hotfix 2026-05-15: stale ts 검사 — 며칠 down 후 부분 gap 헷징
            // 마지막 ts가 N일 stale 이면 count 충분해도 cold-start 재트리거
            val maxTs1d = candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY)
            val maxTs1m = candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN)
            val nowKst = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
            val stale1dThreshold = nowKst.minusDays(STALE_1D_DAYS).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant()
            val stale1mThreshold = nowKst.minusDays(STALE_1M_DAYS).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant()

            val needsColdStart =
                when {
                    count1d == 0L -> {
                        log.info("CandleBootstrapRunner — 1d candle 없음, cold-start 트리거: code={}", code)
                        true
                    }
                    count1m < MIN_1M_CANDLE_THRESHOLD -> {
                        log.info(
                            "CandleBootstrapRunner — 1m candle 부족 (count={} < threshold={}), cold-start 트리거: code={}",
                            count1m,
                            MIN_1M_CANDLE_THRESHOLD,
                            code,
                        )
                        true
                    }
                    maxTs1d != null && maxTs1d.isBefore(stale1dThreshold) -> {
                        log.info(
                            "CandleBootstrapRunner — 1d candle stale (maxTs={} < threshold={}), cold-start 트리거: code={}",
                            maxTs1d,
                            stale1dThreshold,
                            code,
                        )
                        true
                    }
                    maxTs1m != null && maxTs1m.isBefore(stale1mThreshold) -> {
                        log.info(
                            "CandleBootstrapRunner — 1m candle stale (maxTs={} < threshold={}), cold-start 트리거: code={}",
                            maxTs1m,
                            stale1mThreshold,
                            code,
                        )
                        true
                    }
                    else -> false
                }

            if (needsColdStart) {
                // cold-start는 1m 수집 + AggregateBackfillRunner 내부에서 합산까지 처리
                coldStartExecutor.submit(code)
                coldStartCount++
                // Rate limit 보호: 종목 간 100ms 간격 (각 종목 ~4 call × 30일 × 50ms = ~6s 예상)
                Thread.sleep(100)
                continue
            }

            // ADR 0062 §9 — 1m 충분하나 합산 candle 일부가 없으면 aggregation만 재실행.
            // 배포 후 첫 부팅(PR 이전 1m 적재된 상태)에서 자동 트리거됨.
            val missingAggregateIntervals =
                AggregateBackfillRunner.TARGET_INTERVALS.filter { interval ->
                    candleRepository.countByCodeAndInterval(code, interval) == 0L
                }
            if (missingAggregateIntervals.isNotEmpty()) {
                log.info(
                    "CandleBootstrapRunner — 1m 충분하나 합산봉 누락, aggregate 트리거: code={} 1m={} missing={}",
                    code,
                    count1m,
                    missingAggregateIntervals.map { it.code },
                )
                try {
                    aggregateBackfillRunner.backfill(code)
                    aggregateOnlyCount++
                } catch (e: Exception) {
                    log.warn("CandleBootstrapRunner aggregate 실패: code={} err={}", code, e.message)
                }
            } else {
                log.debug(
                    "CandleBootstrapRunner — candle 충분: code={} 1d={} 1m={} aggregateTargets={}",
                    code,
                    count1d,
                    count1m,
                    AggregateBackfillRunner.TARGET_INTERVALS.map { it.code },
                )
            }
        }
        log.info(
            "CandleBootstrapRunner 완료 — cold-start 트리거 {}건 / aggregate-only {}건 / skip {}건 (total watchlist={}건)",
            coldStartCount,
            aggregateOnlyCount,
            skippedCount,
            watchlistEntries.size,
        )
    }
}
