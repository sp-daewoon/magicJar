package kr.spdaewoon.magicjar.consumer.monitoring

import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.consumer.aggregation.CandleBackfillWorker
import kr.spdaewoon.magicjar.consumer.ingestion.ColdStartExecutor
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * `last_candle_at` 모니터링 워커 — T-CC Phase 1 (ADR 0085 §3, chart-curator 첫 발화).
 *
 * 목적:
 *   - 한국석유(004090) 일봉 5개월 stale (2025-12-24 → 2026-05-18) dogfooding evidence 재발 방지
 *   - watchlist 등록된 모든 종목 × (일봉·1분봉) 매트릭스 stale 임계치 검증
 *   - 위반 시 ColdStartExecutor.submit() 자동 backfill 트리거 + Slack alert
 *
 * 실행 시점 (KST):
 *   - 매일 18:00 KST (@Scheduled cron — KRX 정규장 15:30 마감 + NXT 진행 중 시각)
 *   - DailyCloseFinalizer(20:00 KST) 이전 — backfill 결과가 daily close 합산에 반영되도록 선행
 *
 * 임계치 (chart-curator skill §캔들 데이터 SLA 매트릭스 정합):
 *   - 일봉(ONE_DAY): 5 거래일 stale → alert + backfill 트리거
 *     (사용자 dispatch 명시 — 한국석유 case는 ≈100 거래일 stale이었음)
 *   - 1분봉(ONE_MIN): 1 거래일 stale → alert + backfill 트리거
 *     (장중 모니터링 — 분봉 파이프라인 끊김 즉시 감지)
 *
 * Rate Limit 보호 (ADR 0037 — 20 req/s):
 *   - 종목 간 100ms 간격 (cold-start 호출 비동기지만 큐잉 부담 분산)
 *   - 거래정지·정리매매 종목 자동 skip (KIS_COLD_START_SKIPPED audit)
 *
 * 자본 영향 0 — 본 워커는 데이터 정합 보강만 수행하며 주문 발행하지 않음.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class LastCandleAtMonitor(
    private val watchlistJpaRepository: ConsumerWatchlistJpaRepository,
    private val candleRepository: CandleRepository,
    private val coldStartExecutor: ColdStartExecutor,
    private val aggregateBackfillRunner: AggregateBackfillRunner,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
    // ADR 0086 T-CC Phase 2-A — 정밀 backfill 워커 추가 호출 (cold-start 병행, Phase 1 회귀 risk 0).
    private val candleBackfillWorker: CandleBackfillWorker,
) {
    private val log = LoggerFactory.getLogger(LastCandleAtMonitor::class.java)

    companion object {
        /**
         * 일봉 stale 임계치 (거래일).
         * 5 거래일 = 약 1주 — 정상 일봉 ingestion 주기(매일 20:00) 대비 충분한 마진.
         * 한국석유 case: 약 100 거래일 stale 발생 → 즉시 검출 가능.
         */
        const val STALE_THRESHOLD_DAILY_BUSINESS_DAYS = 5L

        /**
         * 1분봉 stale 임계치 (거래일).
         * 1 거래일 = 어제 09:00 KST 이전 → alert.
         * 한국석유 case: 1분봉 정상(2026-05-18 15:19) → 본 임계치 미위반.
         */
        const val STALE_THRESHOLD_MINUTE_BUSINESS_DAYS = 1L

        /** Rate limit 보호 — 종목 간 sleep (ms) */
        private const val PER_CODE_INTERVAL_MS = 100L

        private val KST = ZoneId.of("Asia/Seoul")
    }

    /**
     * stale 검출 결과 데이터 — 자료구조 (감사 + alert payload 합성용).
     */
    data class StaleEntry(
        val code: String,
        val interval: CandleInterval,
        val lastTs: Instant?,
        val staleBusinessDays: Long,
    )

    /**
     * 매일 KST 18:00 monitoring run.
     *
     * 흐름:
     *   1. watchlist 전 종목 조회
     *   2. 거래정지·정리매매 skip
     *   3. 종목별 (ONE_DAY · ONE_MIN) 마지막 ts 조회 → stale 검출
     *   4. stale 발견 시 coldStartExecutor.submit(code) — 비동기 backfill 트리거
     *   5. 결과 Slack alert (stale 0건이면 skip)
     *   6. audit_trail CANDLE_LAST_AT_MONITOR row 1건 발행 (운영 audit)
     */
    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Seoul")
    fun monitor() {
        runMonitor("schedule")
    }

    /**
     * 외부 트리거(admin endpoint 등) 또는 부팅 직후 수동 호출용 진입점.
     * dogfooding cycle: 한국석유 5개월 stale fix 즉시 발화 가능.
     */
    fun runMonitor(trigger: String): List<StaleEntry> {
        log.info("LastCandleAtMonitor 시작 — trigger={}", trigger)
        val watchlistEntries =
            try {
                watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc()
            } catch (e: Exception) {
                log.warn("LastCandleAtMonitor watchlist 조회 실패: err={}", e.message)
                return emptyList()
            }

        if (watchlistEntries.isEmpty()) {
            log.info("LastCandleAtMonitor — watchlist 비어 있음, skip")
            return emptyList()
        }

        val staleEntries = mutableListOf<StaleEntry>()
        val triggeredCodes = mutableSetOf<String>()
        val skippedCount = checkAllCodes(watchlistEntries, staleEntries, triggeredCodes)

        // audit_trail row — 운영 audit baseline (사용자 dogfooding cycle 재발 방지 기록)
        persistAuditRow(trigger, watchlistEntries.size, staleEntries.size, triggeredCodes.size, skippedCount)

        // Slack alert — stale 발견 시에만 발화 (정상 시 spam 회피)
        if (staleEntries.isNotEmpty()) {
            sendSlackAlert(trigger, staleEntries, triggeredCodes)
        }

        log.info(
            "LastCandleAtMonitor 완료 — trigger={} total={} stale={} triggered={} skipped={}",
            trigger,
            watchlistEntries.size,
            staleEntries.size,
            triggeredCodes.size,
            skippedCount,
        )
        return staleEntries
    }

    /**
     * watchlist 전체 순회 — stale 검출 + backfill 트리거.
     * @return skip된 종목 수 (거래정지·정리매매)
     */
    private fun checkAllCodes(
        watchlistEntries: List<kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaEntity>,
        staleAccum: MutableList<StaleEntry>,
        triggeredAccum: MutableSet<String>,
    ): Int {
        val nowKst = LocalDate.now(KST)
        var skippedCount = 0

        for (entry in watchlistEntries) {
            val code = entry.code

            // 거래정지·정리매매 skip (ColdStartExecutor 동일 정책 정합)
            val instrument = instrumentJpaRepository.findById(code).orElse(null)
            if (instrument != null && (instrument.tradingHalt || instrument.liquidation)) {
                log.debug(
                    "LastCandleAtMonitor skip — 거래정지·정리매매: code={} halt={} liquidation={}",
                    code,
                    instrument.tradingHalt,
                    instrument.liquidation,
                )
                skippedCount++
                continue
            }

            // ONE_DAY stale 검사
            val dailyStale = detectStale(code, CandleInterval.ONE_DAY, nowKst, STALE_THRESHOLD_DAILY_BUSINESS_DAYS)
            if (dailyStale != null) {
                staleAccum.add(dailyStale)
                log.warn(
                    "LastCandleAtMonitor stale 검출 — code={} interval={} lastTs={} staleBizDays={}",
                    code,
                    CandleInterval.ONE_DAY.code,
                    dailyStale.lastTs,
                    dailyStale.staleBusinessDays,
                )
            }

            // ONE_MIN stale 검사
            val minuteStale = detectStale(code, CandleInterval.ONE_MIN, nowKst, STALE_THRESHOLD_MINUTE_BUSINESS_DAYS)
            if (minuteStale != null) {
                staleAccum.add(minuteStale)
                log.warn(
                    "LastCandleAtMonitor stale 검출 — code={} interval={} lastTs={} staleBizDays={}",
                    code,
                    CandleInterval.ONE_MIN.code,
                    minuteStale.lastTs,
                    minuteStale.staleBusinessDays,
                )
            }

            // 어느 한쪽이라도 stale → cold-start submit (1m + 1d·1w·1m·1y 일괄 재수집 + aggregation)
            // 동일 종목 중복 trigger 방지 (set semantics)
            if ((dailyStale != null || minuteStale != null) && triggeredAccum.add(code)) {
                try {
                    coldStartExecutor.submit(code)
                    log.info(
                        "LastCandleAtMonitor cold-start submit: code={} reason={}",
                        code,
                        listOfNotNull(
                            dailyStale?.let { "daily-${it.staleBusinessDays}d" },
                            minuteStale?.let { "minute-${it.staleBusinessDays}d" },
                        ).joinToString(","),
                    )
                    // ADR 0086 Phase 2-A — 정밀 backfill 추가 호출 (cold-start 일괄 재수집 + gap detect 정밀 보강).
                    // stale interval에 한해서만 호출 (1d만 stale이면 1d만, 둘 다면 둘 다).
                    dailyStale?.let { candleBackfillWorker.submit(code, CandleInterval.ONE_DAY, "monitor-daily") }
                    minuteStale?.let { candleBackfillWorker.submit(code, CandleInterval.ONE_MIN, "monitor-minute") }
                } catch (e: Exception) {
                    log.warn("LastCandleAtMonitor cold-start submit 실패: code={} err={}", code, e.message)
                }
            }

            // Rate limit 보호 — KIS API rate limit (ADR 0037 — 20 req/s) 정합.
            // ColdStartExecutor.submit 자체는 즉시 반환되나, 큐잉 부담 분산 + DB read 부하 분산.
            try {
                Thread.sleep(PER_CODE_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        return skippedCount
    }

    /**
     * 종목·interval stale 검출.
     *
     * stale 판단 (거래일 단위 계산 — 주말 가드):
     *   - lastTs == null → stale (row 0 건 — count 미달 case는 CandleBootstrapRunner와 별도)
     *   - now - lastTs (거래일) > threshold → stale
     *
     * @return StaleEntry (stale일 경우) 또는 null (정상)
     */
    private fun detectStale(
        code: String,
        interval: CandleInterval,
        nowKst: LocalDate,
        thresholdBusinessDays: Long,
    ): StaleEntry? {
        val maxTs = candleRepository.findMaxTsByCodeAndInterval(code, interval)
        if (maxTs == null) {
            // row 0건 — CandleBootstrapRunner가 부팅 시 처리하므로 본 monitor는 stale로 표기만
            return StaleEntry(code, interval, null, Long.MAX_VALUE)
        }

        val lastDate = maxTs.atZone(KST).toLocalDate()
        val businessDaysElapsed = countBusinessDaysBetween(lastDate, nowKst)

        return if (businessDaysElapsed > thresholdBusinessDays) {
            StaleEntry(code, interval, maxTs, businessDaysElapsed)
        } else {
            null
        }
    }

    /**
     * 두 날짜 사이 거래일 계산 (주말 제외, 공휴일 미반영 — 1차 stub).
     * KRX 공휴일 캘린더 연동은 후속 T-CC Phase 후속 (MarketSessionResolver 통합 검토).
     *
     * @param from 시작 (inclusive)
     * @param to 종료 (exclusive)
     * @return 거래일 수 (from 이후, to 이전)
     */
    private fun countBusinessDaysBetween(
        from: LocalDate,
        to: LocalDate,
    ): Long {
        if (!from.isBefore(to)) return 0L
        val totalDays = ChronoUnit.DAYS.between(from, to)
        if (totalDays <= 0) return 0L

        var businessDays = 0L
        var cursor = from.plusDays(1) // exclusive start
        while (!cursor.isAfter(to)) {
            val dow = cursor.dayOfWeek
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                businessDays++
            }
            cursor = cursor.plusDays(1)
        }
        return businessDays
    }

    /**
     * audit_trail CANDLE_LAST_AT_MONITOR row 발행 — 운영 audit (실행 evidence).
     */
    private fun persistAuditRow(
        trigger: String,
        total: Int,
        staleCount: Int,
        triggeredCount: Int,
        skippedCount: Int,
    ) {
        try {
            val detail =
                """{"trigger":"$trigger","total":$total,"stale":$staleCount,""" +
                    """"triggered":$triggeredCount,"skipped":$skippedCount}"""
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "system",
                    action = "CANDLE_LAST_AT_MONITOR",
                    entityType = "candle",
                    entityId = "watchlist",
                    detailJson = detail,
                ),
            )
        } catch (e: Exception) {
            log.warn("LastCandleAtMonitor audit 저장 실패: err={}", e.message)
        }
    }

    /**
     * Slack alert 발화 — graceful (SlackWebhookSender 미등록 시 skip, webhook URL 미설정 시 skip).
     *
     * 메시지 format:
     *   [magicJar] 캔들 stale 검출 — trigger=schedule, stale=2건, backfill=1건 자동 트리거
     *   - 004090(ONE_DAY): lastTs=2025-12-24T00:00:00Z, stale=100 거래일
     *   - ...
     */
    private fun sendSlackAlert(
        trigger: String,
        staleEntries: List<StaleEntry>,
        triggeredCodes: Set<String>,
    ) {
        val sender = slackSenderProvider.getIfAvailable()
        if (sender == null) {
            log.warn("LastCandleAtMonitor — SlackWebhookSender 미등록, alert skip")
            return
        }

        val header =
            "[magicJar] 캔들 stale 검출 — trigger=$trigger, stale=${staleEntries.size}건, " +
                "backfill=${triggeredCodes.size}건 자동 트리거"

        // 상위 10건만 메시지에 노출 (Slack message 한도 보호)
        val topEntries = staleEntries.take(10)
        val body =
            topEntries.joinToString("\n") { e ->
                val lastTsStr = e.lastTs?.toString() ?: "NULL"
                val staleStr = if (e.staleBusinessDays == Long.MAX_VALUE) "row=0" else "${e.staleBusinessDays}d"
                "- ${e.code}(${e.interval.code}): lastTs=$lastTsStr, stale=$staleStr"
            }
        val overflow =
            if (staleEntries.size > topEntries.size) {
                "\n... 외 ${staleEntries.size - topEntries.size}건"
            } else {
                ""
            }
        val message = "$header\n$body$overflow"

        try {
            val sent = sender.sendDirect(message)
            log.info("LastCandleAtMonitor Slack alert: sent={} staleCount={}", sent, staleEntries.size)
        } catch (e: Exception) {
            log.warn("LastCandleAtMonitor Slack alert 실패: err={}", e.message)
        }
    }
}
