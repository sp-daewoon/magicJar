package com.mj.api.controller

import com.mj.api.application.market.MarketIndexService
import com.mj.api.controller.dto.MarketIndexResponse
import com.mj.api.controller.dto.toResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시장 지수 컨트롤러 (T21-T3).
 *
 * GET /api/market/index?codes=KOSPI,KOSDAQ
 */
@Tag(
    name = "Market",
    description = """
        시장 전체 지수 정보.

        KOSPI(코드 0001) / KOSDAQ(코드 1001) 현재 지수·등락률·거래량을 제공한다.
        KIS FHPUP02100000 TR_ID를 사용하며 Redis에 1분 TTL로 캐시된다.

        KIS 키 미설정 환경에서는 빈 배열을 반환한다.
    """,
)
@RestController
@RequestMapping("/api/market")
class MarketController(
    private val marketIndexService: MarketIndexService,
) {
    @Operation(
        summary = "시장 지수 조회",
        description = """
            KOSPI·KOSDAQ 지수 현재값·전일대비·등락률·거래량을 반환한다.

            - `codes` 파라미터: 콤마 구분 지수명(KOSPI/KOSDAQ) 또는 코드(0001/1001).
              미지정 시 KOSPI·KOSDAQ 모두 반환.
            - KIS Redis 캐시 TTL 1분. 장중에도 실시간 반영(지연 최대 1분).
            - KIS 키 미설정 시 빈 배열 반환 (graceful degradation).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (0건이어도 200)"),
            ApiResponse(responseCode = "400", description = "codes 파라미터 오류"),
        ],
    )
    @GetMapping("/index")
    fun getIndex(
        @Parameter(
            description = "조회할 지수 코드 또는 이름 (콤마 구분). 미지정 시 KOSPI,KOSDAQ",
            example = "KOSPI,KOSDAQ",
        )
        @RequestParam(defaultValue = "KOSPI,KOSDAQ") codes: String,
    ): ResponseEntity<List<MarketIndexResponse>> {
        val codeList =
            codes
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { token ->
                    // 이름(KOSPI/KOSDAQ) → 코드 변환 허용
                    when (token.uppercase()) {
                        "KOSPI" -> "0001"
                        "KOSDAQ" -> "1001"
                        "0001", "1001" -> token
                        else -> null // 알 수 없는 코드 무시
                    }
                }.distinct()

        if (codeList.size > 10) return ResponseEntity.badRequest().build()

        val indexes = marketIndexService.getIndexes(codeList)
        return ResponseEntity.ok(indexes.map { it.toResponse() })
    }
}
