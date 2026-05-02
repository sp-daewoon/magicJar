package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.api.controller.dto.InstrumentSearchResponse
import com.mj.api.controller.dto.toSearchResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(
    name = "SymbolSearch",
    description = """
        프론트엔드 차트 페이지의 종목 자동완성 박스용 검색 엔드포인트 (spec §11-2).

        instrument 테이블의 `code` 또는 `name` 부분 일치로 LIKE 검색하며 최대 20건 반환.
        빈 쿼리는 빈 배열을 돌려준다(전체 목록은 `GET /api/instruments` 사용).
    """,
)
@RestController
@RequestMapping("/api/symbols")
class SymbolSearchController(
    private val instrumentService: InstrumentService,
) {
    @Operation(
        summary = "심볼 자동완성 검색",
        description = "code/name 부분 일치 LIKE 검색. 결과는 최대 20건. 한국어 종목명도 가능.",
    )
    @GetMapping("/search")
    fun search(
        @Parameter(description = "검색어 (종목 코드 또는 종목명 일부)", example = "삼성")
        @RequestParam(defaultValue = "") q: String,
    ): ResponseEntity<List<InstrumentSearchResponse>> = ResponseEntity.ok(instrumentService.search(q).map { it.toSearchResponse() })
}
