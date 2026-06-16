package kr.spdaewoon.magicjar.api.controller.performance

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyNoteDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyNoteTimelineResponse
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyNoteUpsertRequest
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyPerformanceHistoryResponse
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyPerformanceSummaryResponse
import kr.spdaewoon.magicjar.api.service.performance.StrategyPerformanceService
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 전략 성과 히스토리 + 메모 컨트롤러 — spec `2026-06-16-strategy-daily-performance.md` §6 (ADR 0126).
 *
 * ## 목적 — "전략이 효과가 있었는지" 하루하루 누적을 화면에 노출
 * 배치(웨이브 B)가 영속한 전략×일자 성과 원장을 읽어 히스토리/요약/메모를 내려준다.
 * 본 컨트롤러는 **읽기 전용(메모 UPSERT 제외)·새 KIS 호출 0** — canary 운영 영향 0.
 * 배치 미가동(perf 0건) 시에도 빈 배열/빈 요약으로 정상 응답한다(mock 합성 금지).
 *
 * 기존 StrategyController(`/api/strategy`)와 base path 를 공유하되 서브경로(`/{id}/performance/...`, `/{id}/note`)가
 * 겹치지 않아 매핑 충돌 0.
 *
 * Swagger 4단 어노테이션 (feedback_swagger_pattern 정합).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (spec §6)
 */
@Tag(name = "StrategyPerformance", description = "전략별 일자별 성과 히스토리 + 일자별 메모 (읽기 전용 · 메모만 UPSERT)")
@RestController
@RequestMapping("/api/strategy")
class StrategyPerformanceController(
    private val strategyPerformanceService: StrategyPerformanceService,
) {
    private val log = LoggerFactory.getLogger(StrategyPerformanceController::class.java)

    @Operation(
        summary = "전략 성과 히스토리 조회",
        description =
            "전략의 일자별 성과 행(왕복수·승패·실현손익·수익률·벤치마크 등락률·초과수익 α·시장 레짐)을 최신일 우선으로 반환합니다. " +
                "배치가 아직 집계하지 않았거나 청산된 거래가 없으면 rows 는 빈 배열입니다(mock 합성 안 함). " +
                "그날 지수 OHLC 조인은 후속에서 보강 예정이며, 현재는 성과 행의 benchmark/alpha/regime 컬럼만 내려갑니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "히스토리 조회 성공 (데이터 없으면 빈 배열)"),
        ApiResponse(
            responseCode = "500",
            description = "서버 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}/performance/history")
    fun getHistory(
        @Parameter(description = "전략 ID", example = "86", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @Parameter(description = "조회 시작일 (KST, inclusive, yyyy-MM-dd)", example = "2026-06-11", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate,
        @Parameter(description = "조회 종료일 (KST, inclusive, yyyy-MM-dd)", example = "2026-06-16", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate,
    ): StrategyPerformanceHistoryResponse {
        log.debug("strategy performance history: strategyId={} userId={} from={} to={}", id, userId, from, to)
        return strategyPerformanceService.getHistory(id, userId, from, to)
    }

    @Operation(
        summary = "전략 성과 누적 요약 조회",
        description =
            "전략의 누적 평균 수익률·승률 + 신뢰도(라벨+표본수) + 레짐별 승률 브레이크다운 + 평균 초과수익 α 를 반환합니다. " +
                "신뢰도는 도메인 StrategyConfidenceCalculator(표본수·일관성)로 계산하며 표본 부족은 라벨로 명시합니다(예 '신뢰도 낮음 3건'). " +
                "레짐별 승률은 지수 레짐이 채워진 행만 GROUP BY 합니다(지수 부재 행 제외). " +
                "데이터 0건이면 hasData=false 로 내려갑니다(mock 합성 안 함).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "요약 조회 성공 (데이터 없으면 hasData=false)"),
        ApiResponse(
            responseCode = "500",
            description = "서버 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}/performance/summary")
    fun getSummary(
        @Parameter(description = "전략 ID", example = "86", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
    ): StrategyPerformanceSummaryResponse {
        log.debug("strategy performance summary: strategyId={} userId={}", id, userId)
        return strategyPerformanceService.getSummary(id, userId)
    }

    @Operation(
        summary = "전략 메모 타임라인 조회",
        description =
            "전략의 일자별 메모(자유텍스트 + 태그)를 최신일 우선으로 반환합니다. " +
                "메모가 없으면 notes 는 빈 배열입니다. '이런 장에 이 전략이 좋았다'를 누적한 정성 관찰 기록입니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "메모 타임라인 조회 성공 (없으면 빈 배열)"),
        ApiResponse(
            responseCode = "500",
            description = "서버 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}/note")
    fun getNotes(
        @Parameter(description = "전략 ID", example = "86", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @Parameter(description = "조회 시작일 (KST, inclusive, yyyy-MM-dd)", example = "2026-06-11", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate,
        @Parameter(description = "조회 종료일 (KST, inclusive, yyyy-MM-dd)", example = "2026-06-16", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate,
    ): StrategyNoteTimelineResponse {
        log.debug("strategy note timeline: strategyId={} userId={} from={} to={}", id, userId, from, to)
        return strategyPerformanceService.getNotes(id, userId, from, to)
    }

    @Operation(
        summary = "전략 일자별 메모 UPSERT",
        description =
            "전략×일자 단일 메모를 저장합니다. 같은 (사용자, 전략, 일자) 메모가 이미 있으면 수정(updated_at 갱신)하고, " +
                "없으면 새로 생성합니다. noteText 와 tags 는 모두 선택이며(태그만 저장 허용), 추천 태그 시드는 " +
                "거래량부족·변동성과다·뉴스이벤트·손절잘됨·익절놓침·추세양호·슬리피지큼·갭상승·갭하락 입니다(자유 입력 허용).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "메모 UPSERT 성공"),
        ApiResponse(
            responseCode = "500",
            description = "서버 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/{id}/note/{date}")
    fun upsertNote(
        @Parameter(description = "전략 ID", example = "86", required = true)
        @PathVariable id: Long,
        @Parameter(description = "메모 귀속일 (KST 영업일, yyyy-MM-dd)", example = "2026-06-16", required = true)
        @PathVariable
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate,
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @RequestBody request: StrategyNoteUpsertRequest,
    ): StrategyNoteDto {
        log.debug("strategy note upsert: strategyId={} userId={} date={}", id, userId, date)
        return strategyPerformanceService.upsertNote(id, userId, date, request)
    }
}
