package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.dto.StrategyStartResponse
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.api.service.market.LivePriceCacheReader
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.api.service.strategy.StrategyStartService
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.ExitLimits
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyFavoritePort
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInPlanPreview
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInPlanProjector
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * 전략 REST 컨트롤러 Phase 2 — CRUD + status 토글 + reactivate (ADR 0066 §2-6 F-1).
 *
 * 경로: `/api/strategies` (Phase 2 신규 — 복수형)
 * 기존 `/api/strategy` (StrategyController)는 하위 호환성 유지.
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 룰 5 위반 금지: LLM provider import 0건.
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Tag(name = "Strategies v2", description = "전략 CRUD + status 토글 — T-SIG Phase 2 (ADR 0066 §2-6 F-1)")
@RestController
@RequestMapping("/api/strategies")
class StrategiesController(
    private val strategyService: StrategyService,
    private val kisYamlDslParser: KisYamlDslParser,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val strategyPositionReadJpaRepository: StrategyPositionReadJpaRepository,
    private val livePriceCacheReader: LivePriceCacheReader,
    private val strategyStartService: StrategyStartService,
    private val favoritePort: StrategyFavoritePort,
) {
    private val log = LoggerFactory.getLogger(StrategiesController::class.java)

    // ─── GET /api/strategies/start ────────────────────────────────────────────

    @Operation(
        summary = "전략 시작 화면 조회",
        description =
            "검증 템플릿(SYSTEM) + 내 전략(USER) + 즐겨찾기를 단일 호출로 반환합니다 (spec 2026-06-18 §6.1). " +
                "데이터 0건이면 빈 배열 — 프론트는 '아직 없음 + 빈 캔버스로 직접' 안내(mock 합성 0건).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "시작 화면 조회 성공 (재료 0건이면 빈 배열)"),
    )
    @GetMapping("/start")
    fun start(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: Long,
    ): StrategyStartResponse = strategyStartService.load(userId)

    // ─── POST /api/strategies/{id}/favorite ───────────────────────────────────

    @Operation(
        summary = "전략 즐겨찾기 추가(멱등)",
        description = "이미 즐겨찾기면 UNIQUE 충돌을 흡수해 멱등 동작합니다 (spec 2026-06-18 §6.2).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "즐겨찾기 추가(또는 이미 존재)"),
    )
    @PostMapping("/{id}/favorite")
    @ResponseStatus(HttpStatus.CREATED)
    fun addFavorite(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: Long,
    ) = favoritePort.add(userId, id)

    // ─── DELETE /api/strategies/{id}/favorite ─────────────────────────────────

    @Operation(
        summary = "전략 즐겨찾기 해제",
        description = "즐겨찾기가 아니어도 no-op으로 204를 반환합니다 (spec 2026-06-18 §6.2).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "즐겨찾기 해제(또는 없음)"),
    )
    @DeleteMapping("/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeFavorite(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: Long,
    ) = favoritePort.remove(userId, id)

    // ─── POST /api/strategies ─────────────────────────────────────────────────

    @Operation(
        summary = "전략 등록",
        description =
            "텍스트 DSL 1줄(예: `ma(5) crosses_above ma(20)`)을 등록합니다. " +
                "ADR 0047 정합 — static 검증 후 complexity 응답. " +
                "등록 후 status=PAUSED로 시작하며 백테스트 준비 후 사용자가 ACTIVE로 전환합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "전략 등록 성공 — strategyId + complexity 반환"),
        ApiResponse(
            responseCode = "400",
            description = "DSL 문법 오류 / AST depth 초과 / 노드 수 초과",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody req: StrategyCreateRequest,
    ): StrategyDetailResponse {
        log.info("전략 등록 요청: userId={} name={}", req.userId, req.name)
        val (entryAst, exitAst) = parseDslTexts(req.entry, req.exit)
        val strategy =
            strategyService.create(
                userId = req.userId,
                name = req.name,
                nameKo = req.nameKo,
                entryAst = entryAst,
                exitAst = exitAst,
                universe = Universe(market = req.universe),
                tf = req.tf,
                entryText = req.entry,
                exitText = req.exit,
                codes = req.codes,
                intervals = req.intervals,
                stopLossPct = req.stopLossPct,
                takeProfitPct = req.takeProfitPct,
                scaleIn = toScaleInDomain(req.scaleIn),
                partialExits = toPartialExitsDomain(req.partialExits),
                minHoldBars = req.minHoldBars,
                viActionPolicy = parseViActionPolicy(req.viActionPolicy),
                requireCodes = true,
            )
        return toDetailResponse(strategy)
    }

    // ─── GET /api/strategies ──────────────────────────────────────────────────

    @Operation(
        summary = "전략 목록 조회 (페이징)",
        description = "사용자의 전략 목록을 반환합니다. DELETED 상태 제외. userId 파라미터로 소유자 필터.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 목록 조회 성공"),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: Long,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): StrategyPageResponse {
        val result = strategyService.listByUserId(userId, page, size)
        return StrategyPageResponse(
            content = result.content.map { toDetailResponse(it) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            last = result.isLast,
        )
    }

    // ─── GET /api/strategies/{id} ─────────────────────────────────────────────

    @Operation(
        summary = "전략 단건 조회",
        description = "전략 ID로 단건 조회합니다. DELETED 상태는 404 반환.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}")
    fun get(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ): StrategyDetailResponse {
        val strategy =
            strategyService.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        return toDetailResponse(strategy)
    }

    // ─── PUT /api/strategies/{id} ─────────────────────────────────────────────

    @Operation(
        summary = "전략 수정",
        description =
            "전략 DSL을 수정합니다. " +
                "수정 후 complexity 재계산. DELETED 상태 전략은 수정 불가.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "DSL 문법 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/{id}")
    fun update(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @RequestBody req: StrategyUpdateRequest,
    ): StrategyDetailResponse {
        strategyService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")

        val (entryAst, exitAst) = parseDslTexts(req.entry, req.exit)
        val strategy =
            strategyService.update(
                id = id,
                userId = req.userId,
                name = req.name,
                nameKo = req.nameKo,
                entryAst = entryAst,
                exitAst = exitAst,
                universe = Universe(market = req.universe),
                tf = req.tf,
                entryText = req.entry,
                exitText = req.exit,
                codes = req.codes,
                intervals = req.intervals,
                stopLossPct = req.stopLossPct,
                takeProfitPct = req.takeProfitPct,
                scaleIn = toScaleInDomain(req.scaleIn),
                partialExits = toPartialExitsDomain(req.partialExits),
                minHoldBars = req.minHoldBars,
                viActionPolicy = parseViActionPolicy(req.viActionPolicy),
                requireCodes = true,
            )
        log.info("전략 수정: id={}", id)
        return toDetailResponse(strategy)
    }

    // ─── DELETE /api/strategies/{id} ──────────────────────────────────────────

    @Operation(
        summary = "전략 삭제 (soft delete)",
        description =
            "전략을 soft delete 합니다 (status=DELETED). " +
                "audit 보존 — DB 레코드는 유지됨. ADR 0066 §2-6 F-1 ANY → DELETED.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "전략 삭제 성공"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ) {
        strategyService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        strategyService.softDelete(id)
    }

    // ─── PUT /api/strategies/{id}/status ──────────────────────────────────────

    @Operation(
        summary = "전략 상태 토글",
        description =
            "전략 status를 전환합니다 (ADR 0066 §2-6 F-1 transition 매트릭스).\n\n" +
                "허용 전환:\n" +
                "- `ACTIVE → PAUSED` (일시 중지)\n" +
                "- `PAUSED → ACTIVE` (재개)\n" +
                "- `BUY_BLOCKED → ACTIVE` (신규 매수 재개)\n" +
                "- `BUY_BLOCKED → PAUSED` (전체 자동매매 중지)\n" +
                "- `DISABLED → ACTIVE` (사용자 명시 reactivate)\n" +
                "- `ANY → DELETED` (soft delete — DELETE /api/strategies/{id} 권장)\n\n" +
                "DISABLED·BUY_BLOCKED 직접 설정 불가 (시스템 보호 장치 전용).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "상태 전환 성공"),
        ApiResponse(
            responseCode = "400",
            description = "허용되지 않은 전환 (예: DISABLED 직접 설정)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/{id}/status")
    fun toggleStatus(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @RequestBody req: StrategyStatusRequest,
    ): StrategyDetailResponse {
        val newStatus =
            runCatching { StrategyStatus.valueOf(req.status.uppercase()) }.getOrElse {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "유효하지 않은 status 값: ${req.status} (허용: ACTIVE·PAUSED·DELETED)",
                )
            }
        val updated = strategyService.toggleStatus(id, newStatus)
        return toDetailResponse(updated)
    }

    // ─── GET /api/strategies/{id}/position ────────────────────────────────────

    @Operation(
        summary = "전략별 포지션 조회",
        description =
            "이 전략이 산 물량(전략별 포지션 귀속 — ADR 0110)을 조회합니다. " +
                "수량·평단·진행 회차(filledLots)·실현손익을 반환합니다. " +
                "보유가 없으면 quantity=0(또는 행 없음) — '보유 없음'으로 표시하세요(mock 합성 0건). " +
                "전략에 종목 1개 매핑이 일반적이므로 단건이지만 다종목 전략 대비 배열로 반환합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략별 포지션 조회 성공 (보유 없으면 빈 배열)"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}/position")
    fun getPosition(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ): List<StrategyPositionResponse> {
        strategyService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        val entities = strategyPositionReadJpaRepository.findByStrategyId(id)
        if (entities.isEmpty()) return emptyList()

        val codes = entities.map { it.code }
        val codeNames = codeNamesFor(codes)
        // 폴백 체인: LIVE(quote:last WS 캐시) → CLOSE(최신 캔들 종가) → 없으면 map 제외(null).
        // 새 KIS 호출 0 — 마감·장전 WS 캐시 만료 시에도 종가로 평가손익이 끊기지 않는다.
        val resolved = livePriceCacheReader.resolvePrices(codes)

        return entities.map { entity ->
            val price = resolved[entity.code]
            val currentPrice = price?.price
            val unrealizedPnl = unrealizedPnlOf(currentPrice, entity.avgPrice, entity.quantity)
            StrategyPositionResponse(
                strategyId = entity.strategyId,
                userId = entity.userId,
                code = entity.code,
                codeName = codeNames[entity.code],
                quantity = entity.quantity,
                avgPrice = entity.avgPrice,
                filledLots = entity.filledLots,
                realizedPnl = entity.realizedPnl,
                isClosed = entity.quantity == 0,
                currentPrice = currentPrice,
                priceSource = price?.source?.name,
                unrealizedPnl = unrealizedPnl,
                unrealizedPnlPct = unrealizedPnlPctOf(unrealizedPnl, entity.avgPrice, entity.quantity),
                updatedAt = entity.updatedAt.toString(),
            )
        }
    }

    // ─── GET /api/strategies/{id}/scalein-plan ──────────────────────────────────

    @Operation(
        summary = "분할 매수 계획 조회 (회차별 목표 수량·진입 조건·예상 손절가·floor)",
        description =
            "분할 매수 전략의 회차별 계획을 조회합니다 (ADR 0128 R3 · ScaleInPlanProjector 투영). " +
                "1차 진입 시점 buyable 박제값(고정 base)이 있으면(보유 중) 회차별 목표 수량을 정확히 계산하고, " +
                "미진입(박제 부재)이면 비중·진입 조건만 노출하는 미리 보기입니다(targetQuantity=null). " +
                "0% 비중 회차는 제외하며(⑨), floor는 알람만(자동 청산 X ⑦)입니다. " +
                "분할 매수 미설정 전략은 400을 반환합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "분할 매수 계획 조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "분할 매수가 설정되지 않은 전략",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}/scalein-plan")
    fun getScaleInPlan(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @Parameter(
            description =
                "회차 수량·가격 산정 기준가(원). 생략 시 보유 평단 → 라이브 현재가(WS 캐시·종가) 순으로 폴백. " +
                    "모두 없으면 목표 수량·가격류는 null(비중·조건만 노출).",
            example = "70000",
        )
        @RequestParam(required = false) currentPrice: BigDecimal? = null,
    ): ScaleInPlanResponse {
        val strategy =
            strategyService.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        val plan =
            strategy.definition.scaleIn
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "분할 매수가 설정되지 않은 전략입니다: id=$id")

        // 보유 행(전략당 종목 1개 가정 — 다종목이면 보유 중인 첫 행). 박제된 고정 base·평단을 회차 산정 기준으로 사용.
        val held = strategyPositionReadJpaRepository.findByStrategyId(id).firstOrNull { it.quantity > 0 }
        val baseBudget = held?.scaleInBaseBudget?.takeIf { it > BigDecimal.ZERO }

        // 기준가: 명시 currentPrice > 보유 평단 > 라이브 현재가(WS 캐시→종가) 순. 모두 없으면 null(목표 수량 미산출).
        val livePrice =
            held?.code?.let { code -> livePriceCacheReader.resolvePrices(listOf(code))[code]?.price }
        val referencePrice = currentPrice ?: held?.avgPrice?.takeIf { it > BigDecimal.ZERO } ?: livePrice
        // 손절가·floor 기준 평단: 보유 평단(진행 중) > 기준가(1차 미진입 미리 보기).
        val referenceAvgPrice = held?.avgPrice?.takeIf { it > BigDecimal.ZERO } ?: referencePrice

        val preview =
            ScaleInPlanProjector.project(
                plan = plan,
                fixedStopLossPct = strategy.definition.fixedStopLoss,
                baseBudget = baseBudget,
                referencePrice = referencePrice,
                referenceAvgPrice = referenceAvgPrice,
            )

        return toScaleInPlanResponse(
            strategyId = id,
            code = held?.code,
            held = held != null,
            filledLots = held?.filledLots ?: 0,
            avgPrice = held?.avgPrice?.takeIf { it > BigDecimal.ZERO },
            baseBudget = baseBudget,
            referencePrice = referencePrice,
            preview = preview,
        )
    }

    private fun toScaleInPlanResponse(
        strategyId: Long,
        code: String?,
        held: Boolean,
        filledLots: Int,
        avgPrice: BigDecimal?,
        baseBudget: BigDecimal?,
        referencePrice: BigDecimal?,
        preview: ScaleInPlanPreview,
    ): ScaleInPlanResponse =
        ScaleInPlanResponse(
            strategyId = strategyId,
            code = code,
            held = held,
            filledLots = filledLots,
            avgPrice = avgPrice,
            baseBudget = baseBudget,
            referencePrice = referencePrice,
            totalSlices = preview.totalSlices,
            effectiveSlices = preview.effectiveSlices,
            slices =
                preview.slices.map {
                    ScaleInSlicePlanResponse(
                        sliceIndex = it.sliceIndex,
                        displayOrder = it.displayOrder,
                        weightPct = it.weightPct,
                        targetQuantity = it.targetQuantity,
                        triggerDescription = it.triggerDescription,
                    )
                },
            expectedStopLossPrice = preview.expectedStopLossPrice,
            fixedStopLossPct = preview.fixedStopLossPct,
            floorPct = preview.floorPct,
            floorPrice = preview.floorPrice,
        )

    /**
     * 미실현 평가손익 = (현재가 − 평단) × 수량. currentPrice null 이면 null.
     * 롱 온리(보유분 청산만) 정책상 quantity ≥ 0 — 수량 0 이면 0.
     */
    private fun unrealizedPnlOf(
        currentPrice: BigDecimal?,
        avgPrice: BigDecimal,
        quantity: Int,
    ): BigDecimal? = currentPrice?.let { (it - avgPrice).multiply(BigDecimal(quantity)) }

    /**
     * 미실현 수익률% = 평가손익 / (평단 × 수량) × 100. 분모(취득원가) 0·null 이면 null.
     */
    private fun unrealizedPnlPctOf(
        unrealizedPnl: BigDecimal?,
        avgPrice: BigDecimal,
        quantity: Int,
    ): BigDecimal? {
        val pnl = unrealizedPnl ?: return null
        val cost = avgPrice.multiply(BigDecimal(quantity))
        if (cost.signum() == 0) return null
        return pnl.divide(cost, 6, java.math.RoundingMode.HALF_UP).multiply(BigDecimal(100))
    }

    // ─── private helper ───────────────────────────────────────────────────────

    private fun parseDslTexts(
        entry: String,
        exit: String?,
    ): Pair<DslNode, DslNode?> {
        val entryAst = parseOneDsl(entry)
        val exitAst = exit?.let { parseOneDsl(it) }
        return entryAst to exitAst
    }

    private fun parseOneDsl(text: String): DslNode {
        if (text.isBlank()) {
            throw StrategyException.DslSyntaxException("DSL 표현식이 비어 있습니다")
        }
        val ast = kisYamlDslParser.parse(text)
        kisYamlDslParser.validateStatic(ast)
        return ast
    }

    private fun parseViActionPolicy(value: String?): ViActionPolicy? {
        val normalized = value?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { ViActionPolicy.valueOf(normalized) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 VI 정책: $value")
        }
    }

    private fun toDetailResponse(strategy: Strategy): StrategyDetailResponse {
        val activationBlockedReason = strategyService.activationBlockedReason(strategy)
        return StrategyDetailResponse(
            id = strategy.id ?: 0L,
            // SYSTEM 시드는 userId null — 상세 응답은 USER 전략 전용 경로라 0L sentinel(실제 노출 없음).
            userId = strategy.userId ?: 0L,
            name = strategy.definition.dsl.name,
            nameKo = strategy.definition.dsl.nameKo ?: "",
            origin = strategy.origin.name,
            universe = strategy.definition.universe.market,
            tf = strategy.definition.tf,
            stopLossPct = strategy.definition.fixedStopLoss,
            takeProfitPct = strategy.definition.takeProfitPct,
            minHoldBars = strategy.definition.minHoldBars,
            viActionPolicy = strategy.definition.viActionPolicy.name,
            warnings = strategyService.exitNoiseWarnings(strategy),
            codes = strategy.codes,
            codeNames = codeNamesFor(strategy.codes),
            intervals = strategy.intervals.ifEmpty { listOf(strategy.definition.tf) },
            buyCondition = strategy.definition.dsl.buyConditionText,
            sellCondition = strategy.definition.dsl.sellConditionText,
            complexity = toComplexityDto(strategy.complexity),
            scaleIn = toScaleInResponse(strategy.definition.scaleIn),
            partialExits = toPartialExitsResponse(strategy.definition.partialExits),
            disabledReason = strategy.disabledReason.name,
            lastDisabledAt = strategy.lastDisabledAt?.toString(),
            enabled = strategy.isEnabled(),
            status = strategy.status.name,
            activationReady = activationBlockedReason == null,
            activationBlockedReason = activationBlockedReason,
            createdAt = strategy.createdAt.toString(),
            updatedAt = strategy.updatedAt.toString(),
        )
    }

    /**
     * 분할 매수 요청 DTO → 도메인 [ScaleInPlan] (ADR 0111 · 분할매수 R3-a).
     *
     * 도메인 init이 cap(회수 1~10·비중 합 100±0.01) 검증 — [IllegalArgumentException]을 400으로 변환해
     * 사용자에게 입력 오류를 그대로 노출한다. null이면 단일 진입(현행 하위호환).
     */
    private fun toScaleInDomain(req: ScaleInRequest?): ScaleInPlan? {
        if (req == null) return null
        return runCatching {
            ScaleInPlan(
                totalSlices = req.totalSlices,
                sliceWeights = req.sliceWeights?.takeIf { it.isNotEmpty() },
                reentryTrigger =
                    req.reentryTrigger
                        ?.let { ReentryTrigger.valueOf(it.uppercase()) }
                        ?: ReentryTrigger.SIGNAL_REPEAT,
                priceDropPct = req.priceDropPct,
                minSliceIntervalSec = req.minSliceIntervalSec,
            )
        }.getOrElse { e ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "분할 매수 설정이 올바르지 않습니다: ${e.message}")
        }
    }

    /**
     * 분할 익절 요청 DTO 리스트 → 도메인 [PartialExit] 리스트 (ADR 0111 · 분할매수 R3-a).
     *
     * 개별 단계 검증(triggerProfitPct>0·exitWeightPct∈(0,100])은 [PartialExit] init,
     * 단계 cap(≤5)·weight 합(≤100)은 [StrategyDefinition] init 책임 — 위반 시 400.
     */
    private fun toPartialExitsDomain(reqs: List<PartialExitRequest>?): List<PartialExit>? {
        if (reqs.isNullOrEmpty()) return null
        return runCatching {
            val exits = reqs.map { PartialExit(triggerProfitPct = it.triggerProfitPct, exitWeightPct = it.exitWeightPct) }
            // 단계 cap(≤5)·weight 합(≤100) 교차 검증 — StrategyDefinition init과 동일 규칙을 컨트롤러에서 선검증해
            // service의 StrategyDefinition 생성 시 IllegalArgumentException(→500)이 아니라 400으로 노출.
            ExitLimits.validatePartialExits(exits)
            exits
        }.getOrElse { e ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "분할 익절 설정이 올바르지 않습니다: ${e.message}")
        }
    }

    private fun toScaleInResponse(plan: ScaleInPlan?): ScaleInResponse? {
        if (plan == null) return null
        return ScaleInResponse(
            totalSlices = plan.totalSlices,
            sliceWeights = plan.sliceWeights,
            reentryTrigger = plan.reentryTrigger.name,
            priceDropPct = plan.priceDropPct,
            minSliceIntervalSec = plan.minSliceIntervalSec,
        )
    }

    private fun toPartialExitsResponse(exits: List<PartialExit>?): List<PartialExitResponse>? {
        if (exits.isNullOrEmpty()) return null
        return exits.map { PartialExitResponse(triggerProfitPct = it.triggerProfitPct, exitWeightPct = it.exitWeightPct) }
    }

    private fun codeNamesFor(codes: List<String>): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        return instrumentJpaRepository
            .findAllById(codes)
            .associate { it.code to it.name }
    }

    private fun toComplexityDto(complexity: StrategyComplexity): StrategyComplexityResponse =
        StrategyComplexityResponse(
            astDepth = complexity.astDepth,
            nodeCount = complexity.nodeCount,
            indicatorCallCount = complexity.indicatorCallCount,
            estimatedEvalTimeMs = complexity.estimatedEvalTimeMs,
            complexityClass = complexity.complexityClass.name,
        )

    @Suppress("unused")
    private val complexityClassRef = ComplexityClass.LOW
}

// ─── DTO ──────────────────────────────────────────────────────────────────────

@Schema(description = "전략 등록 요청 (Phase 2)")
data class StrategyCreateRequest(
    @field:Schema(description = "소유자 사용자 ID", example = "1", required = true)
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20", required = true)
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String? = null,
    @field:Schema(
        description = "매수 조건 DSL 텍스트",
        example = "ma(5) crosses_above ma(20)",
        required = true,
    )
    val entry: String,
    @field:Schema(description = "매도 조건 DSL 텍스트", example = "ma(5) crosses_below ma(20)")
    val exit: String? = null,
    @field:Schema(description = "유니버스 시장 (KOSPI·KOSDAQ)", example = "KOSPI")
    val universe: String = "KOSPI",
    @field:Schema(description = "타임프레임 (D1·5m·15m 등)", example = "D1")
    val tf: String = "D1",
    @field:Schema(description = "자동매매 hard stop 손절 비율 (%)", example = "5.00")
    val stopLossPct: BigDecimal? = null,
    @field:Schema(description = "보유 포지션 평가 수익률 기준 익절 비율 (%)", example = "5.00")
    val takeProfitPct: BigDecimal? = null,
    @field:Schema(
        description = "잔익절 방어 B안 — 최소 보유 캔들 수. 진입 후 N캔들 동안 DSL 매도 시그널만 무시(손절·익절은 항상 작동). null·0=비활성(현행)",
        example = "3",
    )
    val minHoldBars: Int? = null,
    @field:Schema(description = "VI 발동 행동 정책(EXIT_MARKET·ALERT_ONLY·HOLD). 생략 시 전략명 기반 기본값", example = "ALERT_ONLY")
    val viActionPolicy: String? = null,
    @field:Schema(description = "감시 종목 코드 목록", example = "[\"005930\"]")
    val codes: List<String> = emptyList(),
    @field:Schema(description = "감시 인터벌 목록", example = "[\"5m\"]")
    val intervals: List<String> = emptyList(),
    @field:Schema(description = "분할 매수 설정 (ADR 0111 · null이면 단일 진입). toggle OFF 동안 설정 시 ACTIVE 차단(DRAFT 저장 가능)")
    val scaleIn: ScaleInRequest? = null,
    @field:Schema(description = "분할 익절 단계 (ADR 0111 · null이면 전량 익절). 단계 ≤ 5 · weight 합 ≤ 100")
    val partialExits: List<PartialExitRequest>? = null,
)

@Schema(description = "전략 수정 요청 (Phase 2)")
data class StrategyUpdateRequest(
    @field:Schema(description = "소유자 사용자 ID", example = "1", required = true)
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20", required = true)
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String? = null,
    @field:Schema(description = "매수 조건 DSL 텍스트", example = "ma(5) crosses_above ma(20)", required = true)
    val entry: String,
    @field:Schema(description = "매도 조건 DSL 텍스트", example = "ma(5) crosses_below ma(20)")
    val exit: String? = null,
    @field:Schema(description = "유니버스 시장", example = "KOSPI")
    val universe: String = "KOSPI",
    @field:Schema(description = "타임프레임", example = "D1")
    val tf: String = "D1",
    @field:Schema(description = "자동매매 hard stop 손절 비율 (%)", example = "5.00")
    val stopLossPct: BigDecimal? = null,
    @field:Schema(description = "보유 포지션 평가 수익률 기준 익절 비율 (%)", example = "5.00")
    val takeProfitPct: BigDecimal? = null,
    @field:Schema(
        description = "잔익절 방어 B안 — 최소 보유 캔들 수. 진입 후 N캔들 동안 DSL 매도 시그널만 무시(손절·익절은 항상 작동). null·0=비활성(현행)",
        example = "3",
    )
    val minHoldBars: Int? = null,
    @field:Schema(description = "VI 발동 행동 정책(EXIT_MARKET·ALERT_ONLY·HOLD). 생략 시 기존값 유지", example = "ALERT_ONLY")
    val viActionPolicy: String? = null,
    @field:Schema(description = "감시 종목 코드 목록", example = "[\"005930\"]")
    val codes: List<String> = emptyList(),
    @field:Schema(description = "감시 인터벌 목록", example = "[\"5m\"]")
    val intervals: List<String> = emptyList(),
    @field:Schema(description = "분할 매수 설정 (ADR 0111 · null이면 단일 진입)")
    val scaleIn: ScaleInRequest? = null,
    @field:Schema(description = "분할 익절 단계 (ADR 0111 · null이면 전량 익절). 단계 ≤ 5 · weight 합 ≤ 100")
    val partialExits: List<PartialExitRequest>? = null,
)

@Schema(description = "전략 상태 전환 요청")
data class StrategyStatusRequest(
    @field:Schema(
        description = "새 status (ACTIVE·PAUSED·DELETED — DISABLED·BUY_BLOCKED 직접 설정 불가)",
        example = "PAUSED",
        required = true,
        allowableValues = ["ACTIVE", "PAUSED", "DELETED"],
    )
    val status: String,
)

@Schema(description = "전략 상세 응답 (Phase 2)")
data class StrategyDetailResponse(
    @field:Schema(description = "전략 ID", example = "1")
    val id: Long,
    @field:Schema(description = "소유자 사용자 ID", example = "1")
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20")
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String = "",
    @field:Schema(
        description = "전략 출처 (USER_DSL=일반 전략, PAPER_DISCOVERY=단타 발굴 페이퍼 승격 전략)",
        example = "USER_DSL",
        allowableValues = ["USER_DSL", "PAPER_DISCOVERY"],
    )
    val origin: String = "USER_DSL",
    @field:Schema(description = "유니버스 시장", example = "KOSPI")
    val universe: String,
    @field:Schema(description = "타임프레임", example = "D1")
    val tf: String,
    @field:Schema(description = "자동매매 hard stop 손절 비율 (%)", example = "5.00")
    val stopLossPct: BigDecimal? = null,
    @field:Schema(description = "보유 포지션 평가 수익률 기준 익절 비율 (%)", example = "5.00")
    val takeProfitPct: BigDecimal? = null,
    @field:Schema(description = "잔익절 방어 B안 — 최소 보유 캔들 수. null=비활성(현행)", example = "3")
    val minHoldBars: Int? = null,
    @field:Schema(description = "VI 발동 행동 정책(EXIT_MARKET·ALERT_ONLY·HOLD)", example = "ALERT_ONLY")
    val viActionPolicy: String = "ALERT_ONLY",
    @field:Schema(
        description = "잔익절 방어 A안 — 저장 경고(비차단). 익절 목표가 왕복 거래비용 근접·미만 시 1건. 빈 배열=경고 없음",
        example = "[]",
    )
    val warnings: List<String> = emptyList(),
    @field:Schema(description = "감시 종목 코드 목록", example = "[\"005930\"]")
    val codes: List<String> = emptyList(),
    @field:Schema(description = "종목 코드별 종목명", example = "{\"005930\":\"삼성전자\"}")
    val codeNames: Map<String, String> = emptyMap(),
    @field:Schema(description = "감시 인터벌 목록", example = "[\"5m\"]")
    val intervals: List<String> = emptyList(),
    @field:Schema(description = "매수 조건 DSL 텍스트", example = "ma(5) crosses_above ma(20)")
    val buyCondition: String? = null,
    @field:Schema(description = "매도 조건 DSL 텍스트", example = "ma(5) crosses_below ma(20)")
    val sellCondition: String? = null,
    @field:Schema(description = "DSL 복잡도 메타")
    val complexity: StrategyComplexityResponse,
    @field:Schema(description = "분할 매수 설정 (ADR 0111 · null이면 단일 진입)")
    val scaleIn: ScaleInResponse? = null,
    @field:Schema(description = "분할 익절 단계 (ADR 0111 · null이면 전량 익절)")
    val partialExits: List<PartialExitResponse>? = null,
    @field:Schema(description = "비활성화 사유 (NULL=정상)", example = "NULL")
    val disabledReason: String,
    @field:Schema(description = "마지막 auto-disable 시각", example = "null")
    val lastDisabledAt: String?,
    @field:Schema(description = "전략 활성 여부", example = "true")
    val enabled: Boolean,
    @field:Schema(
        description = "전략 상태 (ACTIVE·BUY_BLOCKED·PAUSED·DISABLED·DELETED)",
        example = "ACTIVE",
        allowableValues = ["ACTIVE", "BUY_BLOCKED", "PAUSED", "DISABLED", "DELETED"],
    )
    val status: String,
    @field:Schema(description = "자동매매 시작 가능 여부", example = "false")
    val activationReady: Boolean = false,
    @field:Schema(description = "자동매매 시작 차단 사유", example = "성공한 백테스트가 있어야 전략을 ACTIVE로 전환할 수 있습니다")
    val activationBlockedReason: String? = null,
    @field:Schema(description = "생성 시각", example = "2026-05-29T03:51:36Z")
    val createdAt: String,
    @field:Schema(description = "수정 시각", example = "2026-05-29T03:51:36Z")
    val updatedAt: String,
)

@Schema(description = "전략 페이징 응답")
data class StrategyPageResponse(
    @field:Schema(description = "전략 목록")
    val content: List<StrategyDetailResponse>,
    @field:Schema(description = "현재 페이지 (0-based)", example = "0")
    val page: Int,
    @field:Schema(description = "페이지 크기", example = "20")
    val size: Int,
    @field:Schema(description = "총 전략 수", example = "5")
    val totalElements: Long,
    @field:Schema(description = "총 페이지 수", example = "1")
    val totalPages: Int,
    @field:Schema(description = "마지막 페이지 여부", example = "true")
    val last: Boolean,
)

@Schema(description = "DSL 복잡도 응답")
data class StrategyComplexityResponse(
    @field:Schema(description = "AST 최대 depth", example = "3")
    val astDepth: Int,
    @field:Schema(description = "AST 총 노드 수", example = "7")
    val nodeCount: Int,
    @field:Schema(description = "지표 호출 수", example = "2")
    val indicatorCallCount: Int,
    @field:Schema(description = "예상 평가 시간 ms", example = "4")
    val estimatedEvalTimeMs: Int,
    @field:Schema(description = "복잡도 등급 (LOW·MEDIUM·HIGH·CRITICAL)", example = "LOW")
    val complexityClass: String,
)

@Schema(description = "분할 매수(scale-in) 요청 — ADR 0111 §2-1")
data class ScaleInRequest(
    @field:Schema(description = "분할 회수 N (1~10). 1이면 단일 진입(현행)", example = "3", required = true)
    val totalSlices: Int,
    @field:Schema(description = "회당 비중%(합 100±0.01). null이면 균등 분할", example = "[50, 30, 20]")
    val sliceWeights: List<BigDecimal>? = null,
    @field:Schema(
        description = "추가 진입 트리거 (SIGNAL_REPEAT·PRICE_DROP_PCT). default SIGNAL_REPEAT",
        example = "SIGNAL_REPEAT",
        allowableValues = ["SIGNAL_REPEAT", "PRICE_DROP_PCT"],
    )
    val reentryTrigger: String? = null,
    @field:Schema(description = "PRICE_DROP_PCT 시 직전 회차 체결가 대비 하락폭%(음수)", example = "-3.0")
    val priceDropPct: BigDecimal? = null,
    @field:Schema(description = "회차 간 최소 간격(초). default 60", example = "60")
    val minSliceIntervalSec: Int? = null,
)

@Schema(description = "분할 매수(scale-in) 응답 — ADR 0111 §2-1")
data class ScaleInResponse(
    @field:Schema(description = "분할 회수 N", example = "3")
    val totalSlices: Int,
    @field:Schema(description = "회당 비중%(null이면 균등)", example = "[50, 30, 20]")
    val sliceWeights: List<BigDecimal>? = null,
    @field:Schema(description = "추가 진입 트리거", example = "SIGNAL_REPEAT")
    val reentryTrigger: String,
    @field:Schema(description = "PRICE_DROP_PCT 하락폭%(음수)", example = "-3.0")
    val priceDropPct: BigDecimal? = null,
    @field:Schema(description = "회차 간 최소 간격(초)", example = "60")
    val minSliceIntervalSec: Int? = null,
)

@Schema(description = "분할 익절(partial take-profit) 단계 요청 — ADR 0111 §2-2")
data class PartialExitRequest(
    @field:Schema(description = "평단 대비 수익률%(양수) 도달 시 발화", example = "3.0", required = true)
    val triggerProfitPct: BigDecimal,
    @field:Schema(description = "보유 수량의 청산 비율%((0, 100])", example = "50", required = true)
    val exitWeightPct: BigDecimal,
)

@Schema(description = "분할 익절(partial take-profit) 단계 응답 — ADR 0111 §2-2")
data class PartialExitResponse(
    @field:Schema(description = "평단 대비 수익률%", example = "3.0")
    val triggerProfitPct: BigDecimal,
    @field:Schema(description = "보유 수량의 청산 비율%", example = "50")
    val exitWeightPct: BigDecimal,
)

@Schema(description = "전략별 포지션 응답 — ADR 0110 전략별 포지션 귀속")
data class StrategyPositionResponse(
    @field:Schema(description = "전략 ID", example = "1")
    val strategyId: Long,
    @field:Schema(description = "사용자 ID", example = "1")
    val userId: String,
    @field:Schema(description = "종목 코드 (KIS 6자리)", example = "005930")
    val code: String,
    @field:Schema(description = "종목명", example = "삼성전자")
    val codeName: String? = null,
    @field:Schema(description = "이 전략 보유 수량 (분할 누적 - 청산). 0=청산", example = "10")
    val quantity: Int,
    @field:Schema(description = "이 전략 평단 (분할 가중평균)", example = "70000.0000")
    val avgPrice: BigDecimal,
    @field:Schema(description = "scale-in 진행 회차 (filledLots)", example = "2")
    val filledLots: Int,
    @field:Schema(description = "이 전략 누적 실현 손익", example = "15000.0000")
    val realizedPnl: BigDecimal,
    @field:Schema(description = "전량 청산 여부 (quantity=0)", example = "false")
    val isClosed: Boolean,
    @field:Schema(
        description =
            "현재가 — 폴백 체인 LIVE→CLOSE. ① WS 시세 캐시(quote:last, KIS WS H0UNCNT0 체결가) read, " +
                "② WS 캐시 miss(TTL 5분 만료·장전·마감) 시 마지막 캔들 종가(1m 우선, 없으면 일봉)로 폴백. " +
                "둘 다 없으면 null (새 KIS 호출 없음). 출처는 priceSource 참조.",
        example = "71500.0000",
        nullable = true,
    )
    val currentPrice: BigDecimal? = null,
    @field:Schema(
        description =
            "현재가 출처 — LIVE=실시간 WS 시세 캐시, CLOSE=마지막 캔들 종가(WS 캐시 만료 폴백), " +
                "null=가격 없음(WS·캔들 모두 부재).",
        example = "LIVE",
        nullable = true,
        allowableValues = ["LIVE", "CLOSE"],
    )
    val priceSource: String? = null,
    @field:Schema(
        description =
            "미실현 평가손익 = (현재가 − 평단) × 수량. " +
                "현재가가 null(WS 캐시·캔들 종가 모두 부재) 이면 null.",
        example = "15000.0000",
        nullable = true,
    )
    val unrealizedPnl: BigDecimal? = null,
    @field:Schema(
        description =
            "미실현 수익률% = 평가손익 / (평단 × 수량) × 100. " +
                "현재가 null 또는 취득원가(평단×수량)=0 이면 null.",
        example = "2.1428",
        nullable = true,
    )
    val unrealizedPnlPct: BigDecimal? = null,
    @field:Schema(description = "마지막 갱신 시각 (ISO-8601 UTC)", example = "2026-06-05T09:30:00Z")
    val updatedAt: String,
)

@Schema(description = "분할 매수 계획 응답 — ADR 0128 R3 (ScaleInPlanProjector 투영). 0% 회차 제외 · floor 알람만")
data class ScaleInPlanResponse(
    @field:Schema(description = "전략 ID", example = "1")
    val strategyId: Long,
    @field:Schema(description = "보유 종목 코드(보유 중일 때). 미보유면 null", example = "005930", nullable = true)
    val code: String? = null,
    @field:Schema(description = "보유 여부 — true면 박제 base·평단으로 정확 계산, false면 미리 보기", example = "true")
    val held: Boolean,
    @field:Schema(description = "현재 진행 회차(filledLots). 미보유=0", example = "1")
    val filledLots: Int,
    @field:Schema(description = "현재 평단(보유 중). 미보유면 null", example = "70000.0000", nullable = true)
    val avgPrice: BigDecimal? = null,
    @field:Schema(
        description = "고정 base(원) — 1차 진입 시점 buyable 박제값(ADR 0128 ①). 미진입(미박제)이면 null → 목표 수량 미산출",
        example = "1000000.0000",
        nullable = true,
    )
    val baseBudget: BigDecimal? = null,
    @field:Schema(description = "회차 수량 산정 기준가(원) — currentPrice→평단→라이브 폴백. 없으면 null", example = "70000", nullable = true)
    val referencePrice: BigDecimal? = null,
    @field:Schema(description = "설정된 총 회차 수(0% 포함)", example = "3")
    val totalSlices: Int,
    @field:Schema(description = "실효 회차 수(0% 제외 — slices.size와 동일)", example = "3")
    val effectiveSlices: Int,
    @field:Schema(description = "회차별 계획(0% 회차 제외)")
    val slices: List<ScaleInSlicePlanResponse>,
    @field:Schema(
        description = "예상 손절가 = 기준 평단 × (1 − 손절%/100). 분할 완료 후 적용(⑥). 평단·손절% 미정 시 null",
        example = "66500.0000",
        nullable = true,
    )
    val expectedStopLossPrice: BigDecimal? = null,
    @field:Schema(description = "손절%(전략 fixedStopLoss). null이면 미설정", example = "5.00", nullable = true)
    val fixedStopLossPct: BigDecimal? = null,
    @field:Schema(description = "catastrophic floor%(양수)", example = "30.00")
    val floorPct: BigDecimal,
    @field:Schema(
        description = "floor 도달가 = 기준 평단 × (1 − floor%/100). 알람만(자동 청산 X ⑦). 평단 미정 시 null",
        example = "49000.0000",
        nullable = true,
    )
    val floorPrice: BigDecimal? = null,
)

@Schema(description = "분할 매수 회차 1건 계획 — ADR 0128 R3")
data class ScaleInSlicePlanResponse(
    @field:Schema(description = "0-기반 원본 회차 인덱스(reason #{sliceIndex+1} 정합). 0% skip 시 비연속 가능", example = "0")
    val sliceIndex: Int,
    @field:Schema(description = "화면 표시 순번(1-기반, 0% 제외 후)", example = "1")
    val displayOrder: Int,
    @field:Schema(description = "회차 비중%", example = "50")
    val weightPct: BigDecimal,
    @field:Schema(
        description = "목표 수량 = floor(고정 base × weight% / 100 / 기준가). base·기준가 미정 시 null",
        example = "7",
        nullable = true,
    )
    val targetQuantity: Long? = null,
    @field:Schema(description = "진입 조건 사람 설명", example = "1차 진입 — BUY 시그널 발화 시 즉시")
    val triggerDescription: String,
)
