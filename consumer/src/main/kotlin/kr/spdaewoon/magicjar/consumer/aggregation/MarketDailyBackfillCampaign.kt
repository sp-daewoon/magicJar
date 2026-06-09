package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.config.MarketDailyBackfillProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketDailyUniverseJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisDailyCandleRangePort
import kr.spdaewoon.magicjar.domain.market.port.KisDailyRangeUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * MarketDailyBackfillCampaign — ADR 0118 R-BF 10년 전종목 일봉 멱등 backfill 캠페인.
 *
 * ## 책임 (한 PR = 한 영역 · market-data)
 *   1. [MarketDailyUniverseProvider.resolveCodes] 보통주 ~2,517 순회 (R1 재사용).
 *   2. 종목별 **candle-row 기반 progress** 산출 — 이미 적재 완료(row≥cap AND 최근까지) 종목은 skip(멱등).
 *   3. **listed_at clamp** — 시작 경계 = max(today-historyYears, instrument.listed_at). 상장 이전 fetch 0.
 *   4. 종목당 [KisDailyCandleRangePort.fetchDailyCandlesInRange] 1회 (BACKFILL 버킷) → sanity → saveAll(DO NOTHING).
 *   5. **밤당 콜 예산 cap** — [MarketDailyBackfillProperties.nightlyCallCap] 도달 시 즉시 종료(다음 실행 이연).
 *   6. throttle gate(직렬화·run 간격·circuit breaker) + audit 재사용.
 *
 * ## 재사용 자산 (신규 로직 최소)
 *   - sanity check: [CandleBackfillWorker.sanityCheck] (햇징 룰 §1 — 가격 range·OHLC·monotonic).
 *   - throttle·circuit breaker: [BackfillThrottleGate].
 *   - universe·listed_at: [MarketDailyUniverseProvider] + [ConsumerMarketDailyUniverseJpaRepository.findListedAtByCode] (R1).
 *   - 적재: [CandleRepository.saveAll] (ON CONFLICT DO NOTHING — 과거봉 immutable).
 *   - progress 산출: [CandleRepository.countByCodeAndInterval] + [CandleRepository.findMaxTsByCodeAndInterval].
 *
 * ## ENV off 중단 / 시동
 *   [MarketDailyBackfillProperties.enabled] off → [runCampaign]이 즉시 DISABLED 종료(적재분 보존).
 *   admin endpoint 또는 야간 스케줄러가 매 호출마다 progress 이어받아 미완료만 처리(멱등 재개).
 *
 * 자본 영향 0 — 데이터 적재만(주문 0). LLM import 0 (룰 5). 외부 fallback 0 (KIS-only).
 */
@Component
class MarketDailyBackfillCampaign(
    private val universeProvider: MarketDailyUniverseProvider,
    private val universeRepository: ConsumerMarketDailyUniverseJpaRepository,
    private val kisDailyRangePort: KisDailyCandleRangePort,
    private val candleRepository: CandleRepository,
    private val candleBackfillWorker: CandleBackfillWorker,
    private val backfillThrottleGate: BackfillThrottleGate,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val objectMapper: ObjectMapper,
    private val properties: MarketDailyBackfillProperties,
) {
    private val log = LoggerFactory.getLogger(MarketDailyBackfillCampaign::class.java)

    companion object {
        private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val INTERVAL = CandleInterval.ONE_DAY
    }

    enum class CampaignStatus {
        DISABLED, // ENV enabled=false — 가동 0 (적재분 보존)
        COMPLETED, // 미완료 종목 0 — 전종목 10년 적재 완료
        CAP_REACHED, // 밤당 콜 예산 소진 — 미완료분 다음 실행 이연
        EMPTY_UNIVERSE, // universe 0종목 (데이터 없음 path — mock 합성 0)
    }

    data class CampaignResult(
        val runId: String,
        val status: CampaignStatus,
        val universeSize: Int,
        val alreadyComplete: Int,
        val processedSymbols: Int,
        val newlyCompleteSymbols: Int,
        val insertedRows: Int,
        val callsConsumed: Int,
        val callCap: Int,
        val failedSymbols: Int,
        val skippedThrottle: Int,
        val remainingSymbols: Int,
        val durationMs: Long,
    )

    /**
     * 멱등 캠페인 1회 실행 — 매 호출마다 progress 이어받아 미완료 종목만 [callCap]까지 처리.
     *
     * @return 실행 요약 (status·콜 소비·잔여 종목 등 — 운영 evidence).
     */
    fun runCampaign(): CampaignResult {
        val startMs = System.currentTimeMillis()
        val runId = "mkt-daily-bf:$startMs-${UUID.randomUUID()}"

        if (!properties.enabled) {
            log.info("MarketDailyBackfillCampaign DISABLED — ENV magicjar.market-daily-backfill.enabled=false (가동 0)")
            return emptyResult(runId, CampaignStatus.DISABLED, startMs)
        }

        val codes = universeProvider.resolveCodes()
        if (codes.isEmpty()) {
            log.warn("MarketDailyBackfillCampaign EMPTY_UNIVERSE — 보통주 0종목 (데이터 없음 — mock 합성 0)")
            audit("MARKET_DAILY_BACKFILL_EMPTY", runId, mapOf("runId" to runId))
            return emptyResult(runId, CampaignStatus.EMPTY_UNIVERSE, startMs)
        }

        val callCap = properties.normalizedNightlyCallCap()
        val today = LocalDate.now(KST_ZONE)
        // today 봉은 야간 snapshot(R2) 소관 — backfill 종료 경계는 어제까지.
        val toDate = today.minusDays(1)
        val historyFloor = today.minusYears(properties.normalizedHistoryYears().toLong())

        log.info(
            "MarketDailyBackfillCampaign 시동: runId={} universe={}종목 callCap={} historyYears={} 경계={}..{}",
            runId,
            codes.size,
            callCap,
            properties.normalizedHistoryYears(),
            historyFloor,
            toDate,
        )
        audit(
            "MARKET_DAILY_BACKFILL_START",
            runId,
            mapOf(
                "runId" to runId,
                "universeSize" to codes.size,
                "callCap" to callCap,
                "historyYears" to properties.normalizedHistoryYears(),
                "from" to historyFloor.toString(),
                "to" to toDate.toString(),
            ),
        )

        var alreadyComplete = 0
        var processed = 0
        var newlyComplete = 0
        var insertedRows = 0
        var callsConsumed = 0
        var failed = 0
        var skippedThrottle = 0
        var capReached = false

        for (code in codes) {
            // 멱등 — 이미 완료된 종목 skip (candle-row 기반 progress).
            if (isSymbolComplete(code, toDate)) {
                alreadyComplete++
                continue
            }

            // 콜 예산 cap — 다음 fetch가 cap을 넘기면 즉시 종료(이연). 보수적으로 cap 도달 시 중단.
            if (callsConsumed >= callCap) {
                capReached = true
                break
            }

            val outcome = processSymbol(code, historyFloor, toDate, runId)
            when (outcome) {
                is SymbolOutcome.Throttled -> {
                    skippedThrottle++
                    // throttle/circuit OPEN — 이번 실행은 더 진행해도 동일하게 막힐 가능성. 다음 실행 이연(잔여>0 → CAP_REACHED).
                    log.warn(
                        "MarketDailyBackfillCampaign throttle/circuit — 캠페인 run 이월: code={} runId={}",
                        code,
                        runId,
                    )
                    break
                }
                is SymbolOutcome.Done -> {
                    processed++
                    callsConsumed += outcome.callsUsed
                    insertedRows += outcome.inserted
                    if (outcome.complete) newlyComplete++
                }
                is SymbolOutcome.Failed -> {
                    processed++
                    callsConsumed += outcome.callsUsed
                    failed++
                }
            }
        }

        val remaining = countRemaining(codes, toDate)
        // 잔여 0 = 전종목 완주(COMPLETED). 잔여>0 = 콜 cap·throttle 이월·루프 break 로 미완료분 다음 실행 이연(CAP_REACHED).
        val status = if (remaining == 0) CampaignStatus.COMPLETED else CampaignStatus.CAP_REACHED

        val durationMs = System.currentTimeMillis() - startMs
        val result =
            CampaignResult(
                runId = runId,
                status = status,
                universeSize = codes.size,
                alreadyComplete = alreadyComplete,
                processedSymbols = processed,
                newlyCompleteSymbols = newlyComplete,
                insertedRows = insertedRows,
                callsConsumed = callsConsumed,
                callCap = callCap,
                failedSymbols = failed,
                skippedThrottle = skippedThrottle,
                remainingSymbols = remaining,
                durationMs = durationMs,
            )

        log.info(
            "MarketDailyBackfillCampaign 종료: runId={} status={} universe={} alreadyComplete={} processed={} " +
                "newlyComplete={} inserted={} calls={}/{} failed={} remaining={} durationMs={}",
            runId,
            status,
            codes.size,
            alreadyComplete,
            processed,
            newlyComplete,
            insertedRows,
            callsConsumed,
            callCap,
            failed,
            remaining,
            durationMs,
        )
        audit(
            "MARKET_DAILY_BACKFILL_RUN_DONE",
            runId,
            mapOf(
                "runId" to runId,
                "status" to status.name,
                "universeSize" to codes.size,
                "alreadyComplete" to alreadyComplete,
                "processed" to processed,
                "newlyComplete" to newlyComplete,
                "inserted" to insertedRows,
                "callsConsumed" to callsConsumed,
                "callCap" to callCap,
                "failed" to failed,
                "remaining" to remaining,
                "durationMs" to durationMs,
            ),
        )
        return result
    }

    /** universe 보통주 카운트 (admin progress 관측 — 콜 0). */
    fun universeCount(): Long = universeProvider.count()

    /** 가동 0 종료(DISABLED·EMPTY_UNIVERSE) 결과 — 0 카운트 + callCap·durationMs 만 채움. */
    private fun emptyResult(
        runId: String,
        status: CampaignStatus,
        startMs: Long,
    ): CampaignResult =
        CampaignResult(
            runId = runId,
            status = status,
            universeSize = 0,
            alreadyComplete = 0,
            processedSymbols = 0,
            newlyCompleteSymbols = 0,
            insertedRows = 0,
            callsConsumed = 0,
            callCap = properties.normalizedNightlyCallCap(),
            failedSymbols = 0,
            skippedThrottle = 0,
            remainingSymbols = 0,
            durationMs = System.currentTimeMillis() - startMs,
        )

    private sealed interface SymbolOutcome {
        data object Throttled : SymbolOutcome

        data class Done(
            val callsUsed: Int,
            val inserted: Int,
            val complete: Boolean,
        ) : SymbolOutcome

        data class Failed(
            val callsUsed: Int,
        ) : SymbolOutcome
    }

    /**
     * 단일 종목 처리 — throttle gate 통과 → listed_at clamp → range fetch(BACKFILL) → sanity → saveAll.
     *
     * range fetch 는 종목당 페이지네이션(여러 콜) 1 묶음. callsUsed 는 보수적으로 페이지 수 추정(최소 1).
     */
    private fun processSymbol(
        code: String,
        historyFloor: LocalDate,
        toDate: LocalDate,
        runId: String,
    ): SymbolOutcome {
        // listed_at clamp — 상장 이전 구간 원천 제외(false fetch 0). listed_at null 이면 historyFloor 사용.
        val listedAt: LocalDate? =
            try {
                universeRepository.findListedAtByCode(code)
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign listed_at 조회 실패 — historyFloor 사용: code={} err={}", code, e.message)
                null
            }
        val fromDate = maxOf(historyFloor, listedAt ?: historyFloor)
        if (fromDate.isAfter(toDate)) {
            // 상장이 종료 경계 이후(초신규 상장) — 적재 대상 거래일 0. 완료로 간주(uncovered, 재시도 무의미).
            log.debug("MarketDailyBackfillCampaign 상장 이후 거래일 0 — skip: code={} listedAt={} to={}", code, listedAt, toDate)
            return SymbolOutcome.Done(callsUsed = 0, inserted = 0, complete = true)
        }

        val throttledResult =
            backfillThrottleGate.runThrottled(code) {
                fetchAndStore(code, fromDate, toDate, runId)
            }
        if (throttledResult == null) {
            return SymbolOutcome.Throttled
        }
        // circuit breaker outcome 보고 — fetch 실패(rate-limit 압박)면 trip 누적.
        backfillThrottleGate.recordRunOutcome(throttledResult.rateLimitPressure)
        return if (throttledResult.failed) {
            SymbolOutcome.Failed(callsUsed = throttledResult.callsUsed)
        } else {
            SymbolOutcome.Done(
                callsUsed = throttledResult.callsUsed,
                inserted = throttledResult.inserted,
                complete = isSymbolComplete(code, toDate),
            )
        }
    }

    private data class FetchStoreResult(
        val callsUsed: Int,
        val inserted: Int,
        val failed: Boolean,
        val rateLimitPressure: Boolean,
    )

    private fun fetchAndStore(
        code: String,
        fromDate: LocalDate,
        toDate: LocalDate,
        runId: String,
    ): FetchStoreResult {
        // 콜 수 추정 — range/100건 페이지 + 1 (보수). 실제 페이지 수는 어댑터 내부 페이지네이션.
        val estimatedCalls = estimateCalls(fromDate, toDate)
        val fetched: List<Candle> =
            try {
                kisDailyRangePort.fetchDailyCandlesInRange(code, fromDate, toDate)
            } catch (e: KisDailyRangeUnavailableException) {
                log.warn(
                    "MarketDailyBackfillCampaign range fetch 실패(fail-closed): code={} from={} to={} err={}",
                    code,
                    fromDate,
                    toDate,
                    e.message,
                )
                audit(
                    "MARKET_DAILY_BACKFILL_SYMBOL_FAIL",
                    code,
                    mapOf("runId" to runId, "code" to code, "from" to fromDate.toString(), "to" to toDate.toString(), "err" to e.message),
                )
                return FetchStoreResult(callsUsed = estimatedCalls, inserted = 0, failed = true, rateLimitPressure = true)
            }

        if (fetched.isEmpty()) {
            // KIS 정상 응답이나 0건(상장 이전·거래 부재) — uncovered(실패 아님). 갭은 갭으로 남김(mock 0).
            log.debug("MarketDailyBackfillCampaign uncovered(0건): code={} from={} to={}", code, fromDate, toDate)
            return FetchStoreResult(callsUsed = estimatedCalls, inserted = 0, failed = false, rateLimitPressure = false)
        }

        // sanity check 재사용 (햇징 룰 §1 — 가격 range·OHLC·monotonic). 위반 봉은 적재 0.
        val sane = candleBackfillWorker.sanityCheck(fetched, runId)
        val inserted =
            try {
                // 과거봉 immutable — ON CONFLICT DO NOTHING. source 는 KIS-BACKFILL 마킹.
                candleRepository.saveAll(sane.map { it.copy(source = "KIS-MARKET-DAILY-BACKFILL") })
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign saveAll 실패: code={} err={}", code, e.message)
                audit(
                    "MARKET_DAILY_BACKFILL_SYMBOL_FAIL",
                    code,
                    mapOf("runId" to runId, "code" to code, "reason" to "saveAll", "err" to e.message),
                )
                return FetchStoreResult(callsUsed = estimatedCalls, inserted = 0, failed = true, rateLimitPressure = false)
            }

        log.info(
            "MarketDailyBackfillCampaign 종목 적재: code={} from={} to={} fetched={} sane={} inserted={}",
            code,
            fromDate,
            toDate,
            fetched.size,
            sane.size,
            inserted,
        )
        return FetchStoreResult(callsUsed = estimatedCalls, inserted = inserted, failed = false, rateLimitPressure = false)
    }

    /**
     * candle-row 기반 progress — 종목이 10년 backfill 완료되었는가.
     *
     * 완료 판정 (둘 다 충족):
     *   (a) row 수 ≥ completionMinRows (충분한 이력 적재), 또는
     *   (b) 최신 봉이 toDate 로부터 recencyGraceDays 이내(최근까지 적재됨)
     *   → (a) OR (b) 충족 시 완료. 신규/단기 상장 종목은 거래일이 적어 (a)를 못 채울 수 있으므로 (b)로 보정.
     *
     * 보수적: 둘 다 미충족이면 미완료(재처리 대상). 과도 재fetch 는 DO NOTHING 이라 데이터 손상 0이나,
     * 콜 낭비 방지를 위해 (b) 최신성으로 1차 skip.
     */
    fun isSymbolComplete(
        code: String,
        toDate: LocalDate,
    ): Boolean {
        val rows =
            try {
                candleRepository.countByCodeAndInterval(code, INTERVAL)
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign count 실패 — 미완료 간주: code={} err={}", code, e.message)
                return false
            }
        if (rows == 0L) return false

        val maxTs =
            try {
                candleRepository.findMaxTsByCodeAndInterval(code, INTERVAL)
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign maxTs 실패 — row 수만으로 판정: code={} err={}", code, e.message)
                null
            }
        val recentEnough =
            maxTs?.let {
                val latestDate = it.atZone(KST_ZONE).toLocalDate()
                !latestDate.isBefore(toDate.minusDays(properties.normalizedRecencyGraceDays()))
            } ?: false

        return rows >= properties.normalizedCompletionMinRows() || recentEnough
    }

    /** 미완료 종목 수 — run 종료 후 잔여 산출 (status·이연 판단). */
    private fun countRemaining(
        codes: List<String>,
        toDate: LocalDate,
    ): Int = codes.count { !isSymbolComplete(it, toDate) }

    /** range 콜 수 보수 추정 — 거래일/100건 페이지 + 1. 실제 페이지네이션은 어댑터 내부. */
    private fun estimateCalls(
        from: LocalDate,
        to: LocalDate,
    ): Int {
        val tradingDays = countWeekdaysInclusive(from, to)
        return ((tradingDays + 99) / 100 + 1).toInt().coerceAtLeast(1)
    }

    private fun countWeekdaysInclusive(
        from: LocalDate,
        to: LocalDate,
    ): Long {
        if (from.isAfter(to)) return 0L
        var count = 0L
        var cur = from
        while (!cur.isAfter(to)) {
            val dow = cur.dayOfWeek
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) count++
            cur = cur.plusDays(1)
        }
        return count
    }

    private fun audit(
        action: String,
        entityId: String,
        detail: Map<String, Any?>,
    ) {
        val detailJson =
            try {
                objectMapper.writeValueAsString(detail)
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign audit JSON 직렬화 실패: action={} err={}", action, e.message)
                "{}"
            }
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "market-data",
                    action = action,
                    entityType = "market_daily_backfill",
                    entityId = entityId,
                    detailJson = detailJson,
                ),
            )
        } catch (e: Exception) {
            log.warn("MarketDailyBackfillCampaign audit 저장 실패: action={} err={}", action, e.message)
        }
    }
}
