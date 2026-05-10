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

/**
 * 일봉 마감 파이널라이저 (ADR 0013 §3-6, PR-29a).
 *
 * 실행 시점: 매 거래일 16:00 KST (@Scheduled cron, ADR 0013 §3-7 §6)
 *   - KRX 정규장 마감 15:30 + 시간외 단일가 15:40~16:00 마감 후 여유 포함
 *   - PostMarket 종료 직후 최종 일봉 영속
 *
 * 동작 흐름:
 *   1. 현재 날짜가 영업일인지 확인 (비영업일 → skip)
 *   2. Redis candle:today:{code}:1d Hash 키 스캔 → 1d candle DB INSERT (ON CONFLICT DO NOTHING)
 *   3. KIS WS tick 집계 값 직접 사용 — 자체 계산 0 (ADR 0013 §3-7 §6 정합)
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
