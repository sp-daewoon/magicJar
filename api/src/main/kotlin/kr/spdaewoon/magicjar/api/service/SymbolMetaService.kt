package kr.spdaewoon.magicjar.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.SymbolDto
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.domain.market.PriceMetrics
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * 종목 메타 + 현재가 조립 서비스 (spec §3-2).
 *
 * 2026-05-20 가격 source LOCK 정정 (사용자 dogfooding evidence — SK하이닉스):
 * - 이전: Redis quote:last → KIS metrics.currentPrice 우선
 *   결함: quote:last가 NXT 라이브 가격(source=live-nxt) publish → 정규장 종가와 다른 가격 표시
 * - 신규: candle DB 1d 최신 row → currentPrice·previousClose·시·고·저·거래량 single source
 *   효과: 차트 ↔ 좌측 시세창 LOCK 동기 (모두 candle DB), MTS 정규장 종가 정합 보장
 *
 * 데이터 우선순위:
 * 0. instrument 테이블 1순위: name·market·industry(sector)
 * 1. candle DB 1d 최신 row → currentPrice + previousClose + high/low/open/volume (정규장 source)
 *    - 차트와 동일 endpoint(inquire-daily-itemchartprice) source
 *    - 장중 실시간 변동은 Redis quote:last 호가 변동 별도 표시 path (좌측 시세창 정규장 종가 표시 책임)
 * 2. KIS inquire-daily-itemchartprice output1 → PER·PBR·EPS·시총·종목명 등 메타 (5분 TTL cache)
 * 3. KIS 호출 실패 시 503
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class SymbolMetaService(
    private val redisTemplate: StringRedisTemplate,
    private val priceMetricsPort: KisRestPriceMetricsPort,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val candleJpaRepository: CandleJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SymbolMetaService::class.java)

    private val metaCacheTtl: Duration = Duration.ofMinutes(5)

    fun getSymbol(code: String): SymbolDto {
        // 0. instrument 테이블 1순위 (PR-11b 정정 — design §11-8)
        // market='UNKNOWN' 또는 부재 → KIS inquire-price fallback
        val instrumentEntity =
            try {
                instrumentJpaRepository.findById(code).orElse(null)
            } catch (e: Exception) {
                log.debug("instrument 조회 실패: code={} err={}", code, e.message)
                null
            }
        val instrumentName = instrumentEntity?.name?.takeIf { instrumentEntity.market != "UNKNOWN" }
        val instrumentMarket = instrumentEntity?.market?.takeIf { it != "UNKNOWN" }
        val instrumentSector = instrumentEntity?.sector
        // PR-21b: 신규 KIS-first 필드 추출 (V5 sync 전: null/false graceful)
        val instrumentType = instrumentEntity?.type?.takeIf { it != "UNKNOWN" }
        val instrumentSecuritiesGroup = instrumentEntity?.securitiesGroup
        val instrumentTradingHalt = instrumentEntity?.tradingHalt ?: false
        val instrumentManagementStock = instrumentEntity?.managementStock ?: false
        val instrumentIndustryMedium = instrumentEntity?.industryMedium
        val instrumentMarketCapPrev = instrumentEntity?.marketCapPrev
        val instrumentBasePrice = instrumentEntity?.basePrice?.toPlainString()
        val instrumentPrevVolume = instrumentEntity?.prevVolume

        // 1. candle DB 1d 최근 5 row (ts DESC) → NXT 야간 일봉 휴리스틱 제외
        //    chart adapter가 fid_cond_mrkt_div_code=UN(NXT 통합)으로 fetch하므로 candle DB에는
        //    NXT 야간 일봉이 정규장 일봉과 별도 row로 들어옴(volume 5-10% 수준 → 1-2 order 차이).
        //    SK하이닉스 사례: 2026-05-20 NXT 야간 vol=223K vs 2026-05-19 정규장 vol=7.8M (2.85%).
        //    룰: 최신 row volume이 그 직전 row volume의 10% 미만이면 NXT 의심 → skip.
        //    근본 해결은 chart adapter NXT 분리 (별도 후속 dispatch).
        val rawCandles =
            try {
                candleJpaRepository.findByCodeAndIntervalLatest(code, "1d", PageRequest.of(0, 5))
            } catch (e: Exception) {
                log.warn("candle 1d 조회 실패: code={} err={}", code, e.message)
                emptyList()
            }
        val validCandles = rawCandles.filter { it.closeRaw != null && (it.volume ?: 0L) > 0L }
        val normalCandles =
            buildList {
                for (i in validCandles.indices) {
                    val cur = validCandles[i]
                    val next = validCandles.getOrNull(i + 1)
                    val curVol = cur.volume ?: 0L
                    val nextVol = next?.volume ?: 0L
                    val isNxtNight = next != null && nextVol > 0L && curVol * 10L < nextVol
                    if (!isNxtNight) add(cur)
                }
            }
        val candleLatest = normalCandles.getOrNull(0)
        val candlePrev = normalCandles.getOrNull(1)

        // 2. Redis quote:last:{code} — 장중 실시간 변동 (NXT 오염 검사 LOCK)
        //    source ∈ {"live", "ws", "tick", ...} 정규장 출처만 허용. "live-nxt"·"nxt-*"는 무시.
        val quoteJson = redisTemplate.opsForValue().get("quote:last:$code")
        val quoteMap: Map<String, Any?> =
            if (quoteJson != null) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.readValue(quoteJson, Map::class.java) as Map<String, Any?>
                } catch (e: Exception) {
                    log.warn("quote:last 파싱 실패: code={} err={}", code, e.message)
                    emptyMap()
                }
            } else {
                emptyMap()
            }

        val quoteSource = quoteMap["source"]?.toString() ?: "polling"
        val isNxtTainted = quoteSource.contains("nxt", ignoreCase = true)
        val quoteCurrentPrice = if (isNxtTainted) null else quoteMap["price"]?.toString()
        val quoteTs = if (isNxtTainted) null else quoteMap["ts"]?.toString()
        val source = if (isNxtTainted) "candle-1d" else quoteSource
        val staleFromCache =
            if (isNxtTainted) false else quoteMap["stale"]?.toString()?.toBooleanStrictOrNull() ?: false

        // 가격 LOCK 우선순위:
        //  1. candle DB 1d 최신 close (정규장 종가, 차트와 LOCK 동기)
        //  2. Redis quote:last (NXT 오염 아닐 때만, 장중 실시간)
        //  3. KIS metrics fallback (candle 부재 시)
        val candleCurrentPrice = candleLatest?.closeRaw?.toPlainString()
        val currentPrice = candleCurrentPrice ?: quoteCurrentPrice

        // 2. Redis meta:{code} 5분 cache — PER·PBR·시총 등
        val metaJson = redisTemplate.opsForValue().get("meta:$code")
        val metrics: PriceMetrics? =
            if (metaJson != null) {
                try {
                    objectMapper.readValue(metaJson, PriceMetrics::class.java)
                } catch (e: Exception) {
                    log.debug("meta cache 파싱 실패: code={}", code)
                    null
                }
            } else {
                null
            }

        // 3. KIS REST 호출 — meta cache miss 시 (PER·PBR·시총·종목명 등)
        // instrument 테이블에 정확한 데이터가 있어도 PER·PBR 등은 KIS inquire-price 필요
        val resolvedMetrics: PriceMetrics? =
            metrics ?: run {
                val userId = currentUserId()
                if (userId != null) {
                    try {
                        val fresh = priceMetricsPort.fetchPriceMetrics(userId, code)
                        // Redis meta cache 저장 (5분 TTL)
                        try {
                            redisTemplate.opsForValue().set(
                                "meta:$code",
                                objectMapper.writeValueAsString(fresh),
                                metaCacheTtl,
                            )
                        } catch (e: Exception) {
                            log.warn("meta cache 저장 실패: code={} err={}", code, e.message)
                        }
                        fresh
                    } catch (e: Exception) {
                        log.warn("KIS inquire-price 실패: code={} err={}", code, e.message)
                        null
                    }
                } else {
                    log.warn("userId 없음 — KIS inquire-price 호출 불가: code={}", code)
                    null
                }
            }

        val price =
            currentPrice
                ?: resolvedMetrics?.currentPrice?.toPlainString()
                ?: resolvedMetrics?.previousClose?.toPlainString()
                ?: throw org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "종목 데이터를 가져올 수 없습니다",
                )

        // 전일종가: candle DB 1d 2번째 row close 우선 (정규장 source LOCK), 없으면 KIS metrics 응답
        val prevClose = candlePrev?.closeRaw ?: resolvedMetrics?.previousClose
        val priceDecimal = price.toBigDecimalOrNull()

        // 전일대비·등락률 — candle 기반 직접 계산 우선 (정규장 LOCK), KIS 응답값은 NXT 영향 가능
        val change =
            if (priceDecimal != null && prevClose != null) {
                (priceDecimal - prevClose).toPlainString()
            } else {
                resolvedMetrics?.priceChange?.toPlainString() ?: "0"
            }
        val changeRate =
            if (priceDecimal != null && prevClose != null && prevClose != BigDecimal.ZERO) {
                (priceDecimal - prevClose)
                    .divide(prevClose, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString()
            } else {
                resolvedMetrics?.changeRate?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "0.00"
            }

        // stale 판정: candle DB 1d 최신 row 부재 + Redis quote:last 부재 시만 stale
        val usedKisFallback = candleCurrentPrice == null && quoteCurrentPrice == null
        val stale = staleFromCache || usedKisFallback

        // name·market·industry: instrument 테이블 1순위 (design §11-8)
        // instrument 부재 또는 market='UNKNOWN'이면 KIS inquire-price fallback
        val resolvedName = instrumentName ?: resolvedMetrics?.name
        val resolvedMarket = instrumentMarket ?: resolvedMetrics?.market
        val resolvedIndustry = instrumentSector ?: resolvedMetrics?.industry

        return SymbolDto(
            code = code,
            name = resolvedName,
            market = resolvedMarket,
            industry = resolvedIndustry,
            currentPrice = price,
            previousClose = prevClose?.toPlainString() ?: "0",
            change = change,
            changeRate = changeRate,
            // 고·저·시·거래량: candle DB 1d 최신 row 우선 (정규장 source LOCK), 부재 시 KIS metrics
            high = candleLatest?.highRaw?.toPlainString() ?: resolvedMetrics?.high?.toPlainString(),
            low = candleLatest?.lowRaw?.toPlainString() ?: resolvedMetrics?.low?.toPlainString(),
            open = candleLatest?.openRaw?.toPlainString() ?: resolvedMetrics?.open?.toPlainString(),
            volume = candleLatest?.volume ?: resolvedMetrics?.volume,
            marketCap = resolvedMetrics?.marketCap?.toPlainString(),
            per = resolvedMetrics?.per?.toPlainString(),
            pbr = resolvedMetrics?.pbr?.toPlainString(),
            eps = resolvedMetrics?.eps?.toPlainString(),
            bps = resolvedMetrics?.bps?.toPlainString(),
            asOf =
                quoteTs ?: java.time.Instant
                    .now()
                    .toString(),
            source = source,
            stale = stale,
            // PR-21b: KIS-first instrument 신규 필드 (V5 sync 전: null/false graceful)
            type = instrumentType,
            securitiesGroup = instrumentSecuritiesGroup,
            tradingHalt = instrumentTradingHalt,
            managementStock = instrumentManagementStock,
            industryMedium = instrumentIndustryMedium,
            marketCapPrev = instrumentMarketCapPrev,
            basePrice = instrumentBasePrice,
            prevVolume = instrumentPrevVolume,
        )
    }

    fun currentUserId(): String? =
        try {
            userAccountJpaRepository.findAll().firstOrNull()?.userId
        } catch (e: Exception) {
            log.warn("userId 조회 실패: {}", e.message)
            null
        }
}
