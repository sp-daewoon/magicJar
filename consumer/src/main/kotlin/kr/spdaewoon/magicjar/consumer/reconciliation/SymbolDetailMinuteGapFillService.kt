package kr.spdaewoon.magicjar.consumer.reconciliation

import kr.spdaewoon.magicjar.consumer.aggregation.CandleBackfillWorker
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * SymbolDetail ENTER path minute gap-fill enqueue.
 *
 * 상세 화면 진입은 KIS WS current candle을 즉시 요청하고, DB 1m이 마지막 닫힌 분봉보다 뒤처졌을 때만
 * REST backfill을 비동기로 붙인다. Listener ack와 사용자의 화면 진입은 backfill 완료를 기다리지 않는다.
 */
@Component
class SymbolDetailMinuteGapFillService(
    private val candleRepository: CandleRepository,
    private val candleBackfillWorker: CandleBackfillWorker,
) {
    private val log = LoggerFactory.getLogger(SymbolDetailMinuteGapFillService::class.java)
    private val inFlightUntilMs = ConcurrentHashMap<String, Long>()

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val DEDUPE_MS = 60_000L
    }

    fun submitIfNeeded(
        code: String,
        reason: String = "symbol-detail-enter",
    ) {
        val targetTo = latestClosedMinute()
        val lastTs =
            try {
                candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN)
            } catch (e: Exception) {
                log.warn("SymbolDetail 1m maxTs 조회 실패 — backfill 큐잉: code={} err={}", code, e.message)
                null
            }

        if (lastTs != null && !lastTs.isBefore(targetTo)) {
            log.debug("SymbolDetail 1m gap fill 불필요: code={} lastTs={} targetTo={}", code, lastTs, targetTo)
            return
        }

        val now = System.currentTimeMillis()
        val inFlightUntil = inFlightUntilMs[code]
        if (inFlightUntil != null && now < inFlightUntil) {
            log.debug("SymbolDetail 1m gap fill dedupe: code={} until={}", code, inFlightUntil)
            return
        }

        inFlightUntilMs[code] = now + DEDUPE_MS
        candleBackfillWorker.submit(code, CandleInterval.ONE_MIN, reason)
        log.info("SymbolDetail 1m gap fill 큐잉: code={} lastTs={} targetTo={}", code, lastTs, targetTo)
    }

    private fun latestClosedMinute(): Instant =
        ZonedDateTime
            .now(KST)
            .truncatedTo(ChronoUnit.MINUTES)
            .minusMinutes(1)
            .toInstant()
}
