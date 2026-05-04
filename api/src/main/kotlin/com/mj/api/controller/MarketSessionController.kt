package com.mj.api.controller

import com.mj.api.controller.dto.MarketSessionInfo
import com.mj.api.controller.dto.toInfo
import com.mj.domain.market.session.port.MarketSessionResolver
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * KRX 장 상태 스냅샷 REST 컨트롤러. ADR 0032, plan T2-R2-A3 §T18.
 *
 * GET /api/market/session
 *
 * - 1분 in-memory cache (ApiMarketSessionResolverImpl) — 부담 적음.
 * - frontend Dashboard 헤더에서 1분 polling으로 사용.
 */
@Tag(
    name = "MarketSession",
    description = """
        KRX 단일 거래소 장 상태(FSM) 스냅샷.

        상태 전이: PreMarket(08:30~09:00) → Open(09:00~15:30) → PostMarket(15:30~18:00) → Closed.
        주말은 Closed(reason=Saturday|Sunday). 캘린더 휴장은 Holiday.

        SymbolDetail 차트 우상단 + Dashboard 헤더 배지 표시용.
    """,
)
@RestController
@RequestMapping("/api/market")
class MarketSessionController(
    private val sessionResolver: MarketSessionResolver,
) {
    @Operation(
        summary = "현재 장 상태 조회",
        description = """
            현재 KST 시각 기준 KRX 장 상태를 반환한다.

            - state: PreMarket / Open / PostMarket / Closed / Holiday
            - lastBusinessDate: 가장 최근 영업일 (휴장 시 fallback asOf 기준)
            - nextOpenAt: 다음 정규장 개시 시각 (Open 상태면 null)
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "장 상태 조회 성공",
                content = [Content(schema = Schema(implementation = MarketSessionInfo::class))],
            ),
        ],
    )
    @GetMapping("/session")
    fun session(): ResponseEntity<MarketSessionInfo> = ResponseEntity.ok(sessionResolver.resolve().toInfo())
}
