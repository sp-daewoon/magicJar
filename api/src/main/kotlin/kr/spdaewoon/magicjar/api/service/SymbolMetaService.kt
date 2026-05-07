package kr.spdaewoon.magicjar.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.SymbolDto
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.domain.market.PriceMetrics
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * 종목 메타 + 현재가 조립 서비스 (spec §3-2).
 *
 * 데이터 우선순위 (PR-11b 정정 — design §11-8 정합):
 * 0. instrument 테이블 1순위: name·market·industry(sector) → SymbolDto 직접 채움
 *    - market='UNKNOWN' 또는 instrument 부재 시 KIS inquire-price fallback
 * 1. Redis quote:last:{code} (PR-9 가동 중 — 현재가·timestamp·source·stale)
 * 2. KIS inquire-price REST (FHKST01010100 — PER·PBR·EPS·BPS·시총·업종·종목명·시장)
 * 3. Redis meta:{code} 5분 TTL cache (KIS inquire-price 결과 캐시)
 *
 * timeout: 3s. 실패 시 cache만 응답 + stale:true. Redis+KIS 모두 실패 시 503.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class SymbolMetaService(
    private val redisTemplate: StringRedisTemplate,
    private val priceMetricsPort: KisRestPriceMetricsPort,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
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

        // 1. Redis quote:last:{code} — 현재가
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

        val currentPrice = quoteMap["price"]?.toString()
        val quoteTs = quoteMap["ts"]?.toString()
        val source = quoteMap["source"]?.toString() ?: "polling"
        val staleFromCache = quoteMap["stale"]?.toString()?.toBooleanStrictOrNull() ?: false

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

        // 현재가: Redis quote 우선, 없으면 metrics의 previousClose fallback
        val price =
            currentPrice
                ?: resolvedMetrics?.previousClose?.toPlainString()
                ?: throw org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "종목 데이터를 가져올 수 없습니다",
                )

        // 전일대비 계산
        val prevClose = resolvedMetrics?.previousClose
        val priceDecimal = price.toBigDecimalOrNull()
        val change =
            if (priceDecimal != null && prevClose != null) {
                (priceDecimal - prevClose).toPlainString()
            } else {
                "0"
            }
        val changeRate =
            if (priceDecimal != null && prevClose != null && prevClose != BigDecimal.ZERO) {
                (priceDecimal - prevClose)
                    .divide(prevClose, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString()
            } else {
                "0.00"
            }

        val stale = staleFromCache || (currentPrice == null)

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
            high = resolvedMetrics?.high?.toPlainString(),
            low = resolvedMetrics?.low?.toPlainString(),
            open = resolvedMetrics?.open?.toPlainString(),
            volume = resolvedMetrics?.volume,
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
