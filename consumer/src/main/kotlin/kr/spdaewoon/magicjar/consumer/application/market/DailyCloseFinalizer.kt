package kr.spdaewoon.magicjar.consumer.application.market

import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 일봉 마감 파이널라이저 (ADR 0013 §3-6, PR-29a).
 *
 * 실행 시점: 매 거래일 20:00 KST (@Scheduled cron)
 *   - ADR 0063 NXT 마감 시각 정합: NXT(야간 거래) 19:50 마감 + 여유 10분 = 20:00
 *   - (구: 16:00 KST KRX 마감 기준 — 2026-05-11 ADR 0063 채택으로 갱신)
 *
 * 동작 흐름:
 *   1. 현재 날짜가 영업일인지 확인 (비영업일 → skip)
 *   2. Redis candle:today:{code}:1d Hash 키 스캔 → 1d candle DB INSERT (ON CONFLICT DO NOTHING)
 *   3. KIS WS tick 집계 값 직접 사용 — 자체 계산 0 (ADR 0013 §3-7 §6 정합)
 *   4. ADR 0062 §9 NXT cover — watchlist 전 종목 당일 1m → 5m·15m·30m·90m·1h 합산 갱신
 *      (cold-start 합산 이후 당일 1m 추가 수집분이 있어 갱신 필요)
 *
 * T3 fallback source: 이 파이널라이저가 영속한 1d candle은 candle-data-policy-v2 §6 T3 소스.
 *
 * NOTE: Redis candle:today:{code}:1d 키는 KisWebSocketBridge.updateCandleToday()에서
 *   Hash 타입으로 설정 (open·high·low·close·volume 필드).
 *   본 finalizer는 Hash 타입으로 읽어 DB 영속만 담당.
 *
 * FIX(dogfooding): KisWebSocketBridge는 Hash(opsForHash)로 저장하는데 기존 구현이
 *   String(opsForValue)으로 읽어서 항상 null → DB 미삽입 결함.
 *   opsForHash().entries(key)로 변경 (마녀공장 5/5~5/7 누락 root cause).
 */
@Component
class DailyCloseFinalizer(
    private val marketSessionResolver: MarketSessionResolver,
    private val candleJpaRepository: ConsumerCandleJpaRepository,
    private val redisTemplate: StringRedisTemplate,
    private val watchlistJpaRepository: ConsumerWatchlistJpaRepository,
    private val aggregateBackfillRunner: AggregateBackfillRunner,
) {
    private val log = LoggerFactory.getLogger(DailyCloseFinalizer::class.java)

    companion object {
        private const val CANDLE_TODAY_PREFIX = "candle:today:"
        private const val CANDLE_TODAY_SUFFIX = ":1d"
        private const val INTERVAL_1D = "1d"
        private const val SOURCE_FINALIZER = "KIS_DAILY_CLOSE"
        private val KST = ZoneId.of("Asia/Seoul")
    }

    /**
     * 매 거래일 20:00 KST 실행 (ADR 0063 NXT 마감 시각 정합).
     * 비영업일은 자동 skip (MarketSessionResolver.isBusinessDay 체크).
     *
     * 함정: @Transactional + @Scheduled 조합 시 Kotlin CGLIB proxy 결함으로
     * marketSessionResolver final field가 null로 보임 (NPE). 본 finalizer는
     * 단일 Repository INSERT만 호출하므로 트랜잭션 전체 wrap 불필요 —
     * candleJpaRepository.insertIgnore 자체 @Transactional로 충분.
     */
    @Scheduled(cron = "0 0 20 * * *", zone = "Asia/Seoul")
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

                // FIX(dogfooding): candle:today:{code}:1d 는 KisWebSocketBridge.updateCandleToday()가
                // opsForHash(HMSET)로 저장. 기존 opsForValue().get() 호출은 WRONGTYPE 에러 → null 반환
                // → DB 미삽입 결함. Hash 타입으로 읽도록 수정.
                val hash = redisTemplate.opsForHash<String, String>().entries(key)
                if (hash.isEmpty()) continue

                val candle = parseCandleHash(hash, code, today) ?: continue

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

        val elapsed1d = Instant.now().epochSecond - startTs.epochSecond
        log.info(
            "DailyCloseFinalizer 1d 완료: date={} flushed={}건 failed={}건 elapsed={}s",
            today,
            flushed,
            failed,
            elapsed1d,
        )

        // ADR 0062 §9 NXT cover — 당일 1m 전량 → 5m·15m·30m·90m·1h 합산 갱신.
        // cold-start 이후 당일 1m 추가 수집분(장중 누적) 이 있어 20:00 재합산 의무.
        // 실패 시 warn 기록 후 계속 진행 (일봉 영속 결과에 영향 없음).
        flushAggregations()

        val elapsed = Instant.now().epochSecond - startTs.epochSecond
        log.info(
            "DailyCloseFinalizer 전체 완료: date={} totalElapsed={}s",
            today,
            elapsed,
        )
    }

    /**
     * watchlist 전 종목 당일 1m → 5m·15m·30m·90m·1h 합산 갱신.
     * ADR 0062 §9 NXT cover 정합 — 20:00 KST 마감 후 당일 분봉 전량 합산.
     */
    private fun flushAggregations() {
        val watchlistEntries =
            try {
                watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc()
            } catch (e: Exception) {
                log.warn("DailyCloseFinalizer watchlist 조회 실패: err={}", e.message)
                return
            }

        if (watchlistEntries.isEmpty()) {
            log.debug("DailyCloseFinalizer aggregation: watchlist 종목 없음")
            return
        }

        log.info("DailyCloseFinalizer aggregation 시작: {} 종목", watchlistEntries.size)
        var aggregateSuccess = 0
        var aggregateFail = 0

        for (entry in watchlistEntries) {
            val code = entry.code
            try {
                val result = aggregateBackfillRunner.backfill(code)
                val totalInserted = result.values.filter { it >= 0 }.sum()
                log.debug(
                    "DailyCloseFinalizer aggregation: code={} inserted={}",
                    code,
                    totalInserted,
                )
                aggregateSuccess++
            } catch (e: Exception) {
                log.warn("DailyCloseFinalizer aggregation 실패: code={} err={}", code, e.message)
                aggregateFail++
            }
        }

        log.info(
            "DailyCloseFinalizer aggregation 완료: success={}건 fail={}건",
            aggregateSuccess,
            aggregateFail,
        )
    }

    /**
     * Redis Hash → CandleSnapshot 파싱.
     * KisWebSocketBridge.updateCandleToday() Hash 필드: open·high·low·close·volume
     * (ts 표준화: 해당 날짜 KST 00:00:00 — KIS open-trading-api stck_bsop_date 관행 정합)
     */
    private data class CandleSnapshot(
        val ts: Instant,
        val openRaw: BigDecimal?,
        val highRaw: BigDecimal?,
        val lowRaw: BigDecimal?,
        val closeRaw: BigDecimal?,
        val volume: Long?,
    )

    private fun parseCandleHash(
        hash: Map<String, String>,
        code: String,
        date: LocalDate,
    ): CandleSnapshot? {
        return try {
            val close = hash["close"]?.toBigDecimalOrNull()
            val open = hash["open"]?.toBigDecimalOrNull()
            val high = hash["high"]?.toBigDecimalOrNull()
            val low = hash["low"]?.toBigDecimalOrNull()
            val volume = hash["volume"]?.toLongOrNull()

            if (close == null) {
                log.warn("DailyCloseFinalizer candle Hash 파싱 실패: code={} close field 없음", code)
                return null
            }

            // ts: 해당 날짜 KST 00:00:00
            // KIS raw 출처: stck_bsop_date (yyyyMMdd) → atStartOfDay(KST) 변환 관행 정합
            // https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
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
            log.warn("DailyCloseFinalizer candle Hash 파싱 실패: code={} err={}", code, e.message)
            null
        }
    }
}
