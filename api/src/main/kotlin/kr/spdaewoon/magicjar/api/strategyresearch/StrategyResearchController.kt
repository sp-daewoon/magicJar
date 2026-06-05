package kr.spdaewoon.magicjar.api.strategyresearch

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Strategy Research", description = "전략 후보 리서치 QA API")
@RestController
@RequestMapping("/api/strategy-research")
class StrategyResearchController(
    private val strategyResearchService: StrategyResearchService,
) {
    private val log = LoggerFactory.getLogger(StrategyResearchController::class.java)

    @Operation(
        summary = "전략 후보 생성",
        description =
            "저장된 candle만 읽어 하락장 반등·과매도 회복·거래량 투매 반등 후보를 평가합니다. " +
                "전략 저장, KIS 주문, live order Kafka topic을 호출하지 않습니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 후보 생성 성공"),
        ApiResponse(responseCode = "400", description = "요청 파라미터 또는 candle 검증 실패"),
    )
    @PostMapping("/proposals")
    fun propose(
        @RequestBody @Valid request: StrategyResearchRequestDto,
    ): StrategyResearchReportDto {
        log.info(
            "POST /api/strategy-research/proposals: userId={} code={} interval={} from={} to={}",
            request.userId,
            request.code,
            request.interval,
            request.from,
            request.to,
        )
        return strategyResearchService.propose(request)
    }
}
