package kr.spdaewoon.magicjar.consumer.application.market

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 일봉 마감 파이널라이저 (ADR 0013 §3-6, PR-29a).
 *
 * 실행 시점: 매 거래일 16:00 KST (@Scheduled cron, ADR 0013 §3-7 §6)
 *   - KRX 정규장 마감 15:30 + 시간외 단일가 15:40~16:00 마감 후 여유 포함
 *   - PostMarket 종료 직후 최종 일봉 영속
 *
 * 동작 흐름:
 *   1. 현재 날짜가 영업일인지 확인 (비영업일 → skip)
 *   2. Redis candle:today:{code}:1d 키 스캔 → 1d candle DB INSERT (ON CONFLICT DO NOTHING)
 *   3. KIS REST 응답 직접 사용 — 자체 계산 0 (ADR 0013 §3-7 §6 정합)
 *
 * T3 fallback source: 이 파이널라이저가 영속한 1d candle은 candle-data-policy-v2 §6 T3 소스.
 *
 * NOTE: Redis candle:today:{code}:1d 키는 TickToMinuteAggregator 또는 ColdStartExecutor에서
 *   KIS REST fetchCandles(interval=1d)로 설정. 본 finalizer는 만료 전 DB 영속만 담당.
 */
@Component
class DailyCloseFinalizer(
    private val marketSessionResolver: MarketSessionResolver,
    private val candleJpaRepository: ConsumerCandleJpaRepository,
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(DailyCloseFinalizer::class.java)

    companion object {
        private const val CANDLE_TODAY_PREFIX = "candle:today:"
        private const val CANDLE_TODAY_SUFFIX = ":1d"
        private const val INTERVAL_1D = "1d"
        private const val SOURCE_FINALIZER = "KIS_DAILY_CLOSE"
        private val KST = ZoneId.of("Asia/Seoul")
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(KST)
    }

    /**
     * 매 거래일 16:00 KST 실행 (ADR 0013 §3-6).
     * 비영업일은 자동 skip (MarketSessionResolver.isBusinessDay 체크).
     *
     * 함정: @Transactional + @Scheduled 조합 시 Kotlin CGLIB proxy 결함으로
     * marketSessionResolver final field가 null로 보임 (NPE). 본 finalizer는
     * 단일 Repository INSERT만 호출하므로 트랜잭션 전체 wrap 불필요 —
     * candleJpaRepository.insertIgnore 자체 @Transactional로 충분.
     */
    @Scheduled(cron = "0 0 16 * * *", zone = "Asia/Seoul")
    fun finalize() {
        val today = LocalDate.now(KST)

        // 비영업일 skip
        if (!marketSessionResolver.isBusinessDay(today)) {
            log.debug("DailyCloseFinalizer skip: {} 비영업일", today)
            return
        }

        log.info("DailyCloseFinalizer 시작: date={}", today)
        val startTs = Instant.now()

        // Redis scan: candle:today:*:1d 패턴
        val pattern = "$CANDLE_TODAY_PREFIX*$CANDLE_TODAY_SUFFIX"
        val keys = redisTemplate.keys(pattern)

        if (keys.isNullOrEmpty()) {
            log.info("DailyCloseFinalizer: Redis 1d 캔들 없음 — skip")
            return
        }

        var flushed = 0
        var failed = 0

        for (key in keys) {
            try {
                // key 예: candle:today:005930:1d → code = "005930"
                val code =
                    key
                        .removePrefix(CANDLE_TODAY_PREFIX)
                        .removeSuffix(CANDLE_TODAY_SUFFIX)

                val json = redisTemplate.opsForValue().get(key) ?: continue
                val candle = parseCandleJson(json, code, today) ?: continue

                val count =
                    candleJpaRepository.insertIgnore(
                        code = code,
                        interval = INTERVAL_1D,
                        ts = candle.ts,
                        openRaw = candle.openRaw,
                        highRaw = candle.highRaw,
                        lowRaw = candle.lowRaw,
                        closeRaw = candle.closeRaw,
                        volume = candle.volume,
                        source = SOURCE_FINALIZER,
                    )
                flushed += count
            } catch (e: Exception) {
                log.warn("DailyCloseFinalizer candle flush 실패: key={} err={}", key, e.message)
                failed++
            }
        }

        val elapsed = Instant.now().epochSecond - startTs.epochSecond
        log.info(
            "DailyCloseFinalizer 완료: date={} flushed={}건 failed={}건 elapsed={}s",
            today,
            flushed,
            failed,
            elapsed,
        )
    }

    /**
     * Redis JSON → CandleSnapshot 파싱.
     * Redis value 형식: {"close":"73600","open":"73000","high":"74200","low":"72800","volume":"12345678","ts":"..."}
     */
    private data class CandleSnapshot(
        val ts: Instant,
        val openRaw: BigDecimal?,
        val highRaw: BigDecimal?,
        val lowRaw: BigDecimal?,
        val closeRaw: BigDecimal?,
        val volume: Long?,
    )

    @Suppress("UNCHECKED_CAST")
    private fun parseCandleJson(
        json: String,
        code: String,
        date: LocalDate,
    ): CandleSnapshot? =
        try {
            // 간단한 JSON 파싱 (jackson dependency 직접 사용보다 안전)
            @Suppress("UNCHECKED_CAST")
            val map =
                com.fasterxml.jackson.databind
                    .ObjectMapper()
                    .readValue(json, Map::class.java) as Map<String, Any?>

            val close = (map["close"] as? String)?.toBigDecimalOrNull()
            val open = (map["open"] as? String)?.toBigDecimalOrNull()
            val high = (map["high"] as? String)?.toBigDecimalOrNull()
            val low = (map["low"] as? String)?.toBigDecimalOrNull()
            val volume = (map["volume"] as? String)?.toLongOrNull()

            // ts: 해당 날짜 KST 00:00:00
            val ts = date.atStartOfDay(KST).toInstant()

            CandleSnapshot(
                ts = ts,
                openRaw = open,
                highRaw = high,
                lowRaw = low,
                closeRaw = close,
                volume = volume,
            )
        } catch (e: Exception) {
            log.warn("DailyCloseFinalizer candle JSON 파싱 실패: code={} err={}", code, e.message)
            null
        }
}
