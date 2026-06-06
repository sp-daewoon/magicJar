package kr.spdaewoon.magicjar.api.strategyresearch

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Strategy Research", description = "전략 후보 리서치 QA API")
@RestController
@RequestMapping("/api/strategy-research")
class StrategyResearchController(
    private val strategyResearchService: StrategyResearchService,
    private val strategyResearchAiDiscoveryService: StrategyResearchAiDiscoveryService,
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

    @Operation(
        summary = "AI 종목발굴",
        description =
            "사용자 자연어를 Claude CLI 기반 후보 목록으로 변환한 뒤, 종목 마스터와 매핑합니다. " +
                "KIS 주문, 전략 저장, live order Kafka topic을 호출하지 않습니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "AI 종목발굴 성공"),
        ApiResponse(responseCode = "400", description = "요청 파라미터 검증 실패"),
        ApiResponse(responseCode = "503", description = "Claude CLI 또는 브릿지 사용 불가"),
    )
    @PostMapping("/ai-discovery")
    fun discoverWithAi(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryRequestDto,
    ): StrategyResearchAiDiscoveryResponseDto {
        log.info(
            "POST /api/strategy-research/ai-discovery: userId={} market={} limit={} queryLength={}",
            request.userId,
            request.market,
            request.limit,
            request.query.length,
        )
        return strategyResearchAiDiscoveryService.discover(request)
    }

    @Operation(
        summary = "AI 종목발굴 프리셋 목록",
        description = "AI 종목발굴 UI에 노출할 intent preset과 prompt 본문을 조회합니다.",
    )
    @GetMapping("/ai-discovery/presets")
    fun aiDiscoveryPresets(): List<StrategyResearchAiDiscoveryPresetDto> = strategyResearchAiDiscoveryService.presets()

    @Operation(
        summary = "AI 종목발굴 프리셋 생성",
        description = "사용자 정의 intent preset을 추가합니다. 정렬값을 비우면 기존 최대 정렬값 + 10으로 생성합니다.",
    )
    @PostMapping("/ai-discovery/presets")
    fun createAiDiscoveryPreset(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryPresetCreateRequestDto,
    ): StrategyResearchAiDiscoveryPresetDto = strategyResearchAiDiscoveryService.createPreset(request)

    @Operation(
        summary = "AI 종목발굴 프리셋 수정",
        description = "프리셋 노출명, 설명, intent prompt, 활성 여부를 수정합니다. Core wrapper는 서비스 코드 권위로 유지됩니다.",
    )
    @PutMapping("/ai-discovery/presets/{presetId}")
    fun updateAiDiscoveryPreset(
        @PathVariable presetId: String,
        @RequestBody @Valid request: StrategyResearchAiDiscoveryPresetUpdateRequestDto,
    ): StrategyResearchAiDiscoveryPresetDto = strategyResearchAiDiscoveryService.updatePreset(presetId, request)

    @Operation(
        summary = "AI 종목발굴 프롬프트 미리보기",
        description = "Claude 호출 없이 현재 조건, 선택 프리셋, 유지/제외 후보를 합성한 최종 프롬프트를 반환합니다.",
    )
    @PostMapping("/ai-discovery/prompt-preview")
    fun previewAiDiscoveryPrompt(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryPromptPreviewRequestDto,
    ): StrategyResearchAiDiscoveryPromptPreviewDto = strategyResearchAiDiscoveryService.previewPrompt(request)

    @Operation(
        summary = "최근 AI 종목발굴 상태 조회",
        description = "사용자별 마지막 AI 종목발굴 조건과 후보 상태를 조회합니다.",
    )
    @GetMapping("/ai-discovery/latest")
    fun latestAiDiscovery(
        @RequestParam userId: String,
    ): StrategyResearchAiDiscoveryLatestDto = StrategyResearchAiDiscoveryLatestDto(strategyResearchAiDiscoveryService.latest(userId))

    @Operation(
        summary = "AI 종목발굴 후보 상태 변경",
        description = "후보를 NEW·KEPT·DISMISSED 상태로 변경합니다. KEPT는 재발굴 시 유지되고 DISMISSED는 제외됩니다.",
    )
    @PatchMapping("/ai-discovery/item-status")
    fun updateAiDiscoveryItemStatus(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryItemStatusRequestDto,
    ): StrategyResearchAiDiscoveryResponseDto = strategyResearchAiDiscoveryService.updateItemStatus(request)

    @Operation(
        summary = "AI 종목발굴 상태 초기화",
        description = "사용자별 마지막 AI 종목발굴 조건과 후보 상태를 삭제합니다.",
    )
    @DeleteMapping("/ai-discovery/latest")
    fun clearAiDiscovery(
        @RequestParam userId: String,
    ) {
        strategyResearchAiDiscoveryService.clear(userId)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(ex.statusCode).body(
            mapOf(
                "error" to ex.statusCode.toString(),
                "message" to (ex.reason ?: "전략 리서치 처리 중 오류가 발생했습니다."),
            ),
        )
}
