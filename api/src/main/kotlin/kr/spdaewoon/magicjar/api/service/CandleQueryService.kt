package kr.spdaewoon.magicjar.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.config.ChartCandleProperties
import kr.spdaewoon.magicjar.api.controller.CandleDto
import kr.spdaewoon.magicjar.api.controller.CandlesResponseDto
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

/**
 * 캔들 시계열 조회 서비스 — DB candle + Redis current·today 합류 (spec §3-3, ADR 0019·0029).
 *
 * 인터벌별 source:
 * - 1m: DB candle + Redis candle:current:{code}:1m (장중 마지막 미확정 bar)
 * - 1d: DB candle + Redis candle:today:{code}:1d (당일 진행 중)
 * - 5m·15m·30m·90m·1h·4h·12h: DB only
 *
 * 페이지네이션: before(ISO8601) cursor 기반 — before 이전 ts DESC LIMIT limit.
 * 응답: ts ASC 정렬 (LWC v4 setData() 기대).
 * hasMore: 응답 row count == limit
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class CandleQueryService(
    private val candleJpaRepository: CandleJpaRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val chartCandleProperties: ChartCandleProperties,
) {
    private val log = LoggerFactory.getLogger(CandleQueryService::class.java)
    private val kst: ZoneId = ZoneId.of("Asia/Seoul")

    val supportedIntervals = setOf("1m", "5m", "15m", "30m", "90m", "1h", "4h", "12h", "1d", "1wk", "1mo", "1y")
    val defaultLimit = 600

    /**
     * `?limit=` 사용자 입력 cap — ADR 0093 §3-1 LOCK (500 → 5000 확장).
     *
     * property override: `magicjar.candle.max-response-limit` (default 5000).
     * clamp: 사용자 입력 limit는 `coerceIn(1, maxLimit)` 적용 — 5000 초과 시 5000으로 cap.
     */
    val maxLimit: Int
        get() = chartCandleProperties.maxResponseLimit

    // interval → DB candle interval code 매핑 (DB CHECK constraint 11종 중 API 노출 8종)
    private val intervalCodeMap =
        mapOf(
            "1m" to "1m",
            "5m" to "5m",
            "15m" to "15m",
            "30m" to "30m",
            "90m" to "90m",
            "1h" to "1h",
            "4h" to "4h",
            "12h" to "12h",
            "1d" to "1d",
            "1wk" to "1wk",
            "1mo" to "1mo",
            "1y" to "1y",
        )

    fun getCandles(
        code: String,
        interval: String,
        before: String?,
        limit: Int,
    ): CandlesResponseDto {
        val effectiveInterval = if (interval in supportedIntervals) interval else "1d"
        val effectiveLimit = limit.coerceIn(1, maxLimit)
        val dbInterval = intervalCodeMap[effectiveInterval] ?: effectiveInterval

        // DB 조회
        val beforeInstant =
            if (before != null) {
                try {
                    Instant.parse(before)
                } catch (e: Exception) {
                    log.warn("before 파싱 실패: before={} — 최신 기준 사용", before)
                    null
                }
            } else {
                null
            }

        val pageable = PageRequest.of(0, effectiveLimit)

        val dbRows =
            if (beforeInstant != null) {
                candleJpaRepository.findByCodeAndIntervalAndTsBefore(code, dbInterval, beforeInstant, pageable)
            } else {
                candleJpaRepository.findByCodeAndIntervalLatest(code, dbInterval, pageable)
            }

        // DB rows → CandleDto 변환 (DESC → ASC 변환)
        val sortedRows = dbRows.sortedBy { it.ts }
        val candleDtos =
            sortedRows
                .mapIndexed { index, entity ->
                    toCandleDto(entity, sortedRows.getOrNull(index - 1)?.closeRaw)
                }.toMutableList()

        // Redis current/today 합류 (장중 마지막 미확정 bar)
        val redisCandle =
            when (effectiveInterval) {
                "1m" -> readRedisCandleHash("candle:current:$code:1m")
                "1d" -> readRedisCandleHash("candle:today:$code:1d")
                else -> null
            }

        if (redisCandle != null && beforeInstant == null) {
            // before 없는 최신 요청일 때만 합류 (페이지네이션 시 제외)
            val redisTs = redisCandle["startTs"] ?: redisCandle["ts"]
            if (redisTs != null) {
                // DB 마지막 candle과 중복 방지 (같은 ts이면 replace)
                val redisInstant =
                    try {
                        Instant.parse(redisTs)
                    } catch (_: Exception) {
                        null
                    }
                if (redisInstant != null) {
                    val lastDbTs =
                        candleDtos.lastOrNull()?.ts?.let {
                            try {
                                Instant.parse(it)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    // hotfix 2026-05-15: same ts 일 때 replace 패턴 (자본 보호 critical sync).
                    // DB 마지막 row 와 Redis ts 가 같으면 (오늘 진행 중 1d 또는 1m) Redis 값으로 덮어쓰기.
                    // 이전 > 만 add → same ts 시 skip → 차트 + 자동매매 stale DB close 사용 risk.
                    val redisCandleDto =
                        CandleDto(
                            ts = redisTs,
                            open = redisCandle["open"] ?: "0",
                            high = redisCandle["high"] ?: "0",
                            low = redisCandle["low"] ?: "0",
                            close = redisCandle["close"] ?: "0",
                            volume = redisCandle["volume"]?.toLongOrNull() ?: 0L,
                            previousClose = candleDtos.lastOrNull()?.previousClose,
                            change = candleDtos.lastOrNull()?.change,
                            changeRate = candleDtos.lastOrNull()?.changeRate,
                        )
                    if (lastDbTs == null || redisInstant > lastDbTs) {
                        candleDtos.add(redisCandleDto)
                    } else if (redisInstant == lastDbTs && candleDtos.isNotEmpty()) {
                        candleDtos[candleDtos.size - 1] = redisCandleDto
                    }
                }
            }
        }

        if (effectiveInterval == "1d" && beforeInstant == null) {
            mergeQuoteLastDailyCandle(code, candleDtos)
        }

        val hasMore = dbRows.size == effectiveLimit
        val nextBefore = if (hasMore && dbRows.isNotEmpty()) dbRows.last().ts.toString() else null

        return CandlesResponseDto(
            code = code,
            interval = effectiveInterval,
            candles = candleDtos,
            hasMore = hasMore,
            nextBefore = nextBefore,
        )
    }

    private fun toCandleDto(
        entity: CandleJpaEntity,
        previousClose: BigDecimal?,
    ): CandleDto {
        val close = entity.closeRaw ?: entity.closeAdj
        val change = if (close != null && previousClose != null) close - previousClose else null
        return CandleDto(
            ts = entity.ts.toString(),
            open = (entity.openRaw ?: entity.openAdj)?.toPlainString() ?: "0",
            high = (entity.highRaw ?: entity.highAdj)?.toPlainString() ?: "0",
            low = (entity.lowRaw ?: entity.lowAdj)?.toPlainString() ?: "0",
            close = close?.toPlainString() ?: "0",
            volume = entity.volume ?: 0L,
            previousClose = previousClose?.toPlainString(),
            change = change?.toPlainString(),
            changeRate = formatChangeRate(change, previousClose),
        )
    }

    private fun mergeQuoteLastDailyCandle(
        code: String,
        candleDtos: MutableList<CandleDto>,
    ) {
        val latest = candleDtos.lastOrNull() ?: return
        val quoteMap = readQuoteLast(code)
        val metrics = resolveQuoteChangeMetrics(quoteMap) ?: return
        val quoteTs = quoteMap["ts"]?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return
        val latestTs = runCatching { Instant.parse(latest.ts) }.getOrNull() ?: return
        if (quoteTs.atZone(kst).toLocalDate() != latestTs.atZone(kst).toLocalDate()) return

        val price = metrics.price
        val high = maxOf(latest.high.toBigDecimalOrNull() ?: price, price)
        val low = minOf(latest.low.toBigDecimalOrNull() ?: price, price)
        candleDtos[candleDtos.lastIndex] =
            latest.copy(
                high = high.toPlainString(),
                low = low.toPlainString(),
                close = price.toPlainString(),
                previousClose = metrics.previousClose.toPlainString(),
                change = metrics.signedChange.toPlainString(),
                changeRate = metrics.signedChangeRate.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            )
    }

    private fun readQuoteLast(code: String): Map<String, Any?> {
        val quoteJson =
            try {
                redisTemplate.opsForValue().get("quote:last:$code")
            } catch (e: Exception) {
                log.debug("quote:last 조회 실패: code={} err={}", code, e.message)
                null
            } ?: return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(quoteJson, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            log.debug("quote:last 파싱 실패: code={} err={}", code, e.message)
            emptyMap()
        }
    }

    private fun formatChangeRate(
        change: BigDecimal?,
        previousClose: BigDecimal?,
    ): String? {
        if (change == null || previousClose == null || previousClose == BigDecimal.ZERO) return null
        return change
            .divide(previousClose, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString()
    }

    private fun readRedisCandleHash(key: String): Map<String, String>? =
        try {
            val entries = redisTemplate.opsForHash<String, String>().entries(key)
            if (entries.isEmpty()) null else entries
        } catch (e: Exception) {
            log.debug("Redis candle hash 조회 실패: key={} err={}", key, e.message)
            null
        }
}
