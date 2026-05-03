package com.mj.api.controller.dto

import com.mj.api.application.market.QuoteResult
import com.mj.domain.market.cache.port.QuoteSource
import com.mj.domain.market.session.MarketSession
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant

/**
 * GET /api/instruments/{code}/quote 응답 DTO. ADR 0033, spec §5.
 *
 * LWC(Lightweight Charts) 실시간 시세 연동 호환.
 */
@Schema(description = "종목 현재 시세 응답 (3-tier fallback 포함)")
data class QuoteResponse(
    @Schema(description = "KRX 종목코드 6자리", example = "005930")
    val code: String,
    @Schema(description = "현재 가격 (원화)", example = "75000")
    val price: BigDecimal,
    @Schema(description = "거래량 (nullable — 전일종가 fallback 시 해당 봉 거래량)", example = "1234567")
    val volume: Long?,
    @Schema(
        description = "시세 출처. LIVE=실시간 KIS WS, CACHED=캐시(stale), LAST_DAY_CLOSE=전일종가",
        example = "LIVE",
        allowableValues = ["LIVE", "CACHED", "LAST_DAY_CLOSE"],
    )
    val source: QuoteSource,
    @Schema(
        description = "true = fallback 데이터 (캐시 stale 또는 전일종가). false = 실시간.",
        example = "false",
    )
    val stale: Boolean,
    @Schema(description = "실제 데이터 발생 시각 (ISO-8601)", example = "2026-05-02T09:30:00Z")
    val asOf: Instant,
    @Schema(description = "현재 장 상태 (nullable — 세션 정보 미제공 환경)")
    val session: MarketSessionInfo?,
)

@Schema(description = "KRX 장 상태 스냅샷")
data class MarketSessionInfo(
    @Schema(description = "장 상태 (PreMarket|Open|PostMarket|Closed|Holiday)", example = "Open")
    val state: String,
    @Schema(description = "마지막 영업일", example = "2026-05-02")
    val lastBusinessDate: String,
    @Schema(description = "다음 정규장 개시 시각 (ISO-8601, OPEN 상태면 null)", example = "2026-05-04T00:00:00+09:00")
    val nextOpenAt: String?,
)

fun QuoteResult.toResponse(): QuoteResponse =
    QuoteResponse(
        code = code,
        price = price,
        volume = volume,
        source = source,
        stale = stale,
        asOf = asOf,
        session = session?.toInfo(),
    )

fun MarketSession.toInfo(): MarketSessionInfo =
    MarketSessionInfo(
        state = state::class.simpleName ?: state.toString(),
        lastBusinessDate = lastBusinessDate.toString(),
        nextOpenAt = nextOpenAt?.toString(),
    )
