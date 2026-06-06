package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryPresetJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryPresetJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryStateJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryStateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class StrategyResearchAiDiscoveryService(
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val candleJpaRepository: CandleJpaRepository,
    private val aiTextClient: StrategyResearchAiTextClient,
    private val objectMapper: ObjectMapper,
    private val stateRepository: StrategyResearchAiDiscoveryStateJpaRepository,
    private val presetRepository: StrategyResearchAiDiscoveryPresetJpaRepository,
) {
    private val log = LoggerFactory.getLogger(StrategyResearchAiDiscoveryService::class.java)
    private val coreSystemPrompt =
        """
        너는 한국 주식 자동매매 시스템 magicJar의 '종목 발굴 전문 퀀트 리서처'다.
        사용자의 요청을 분석하여 조건에 부합하는 한국 주식 후보를 제안하라.
        단, 너의 제안은 절대적인 매수 추천이 아니며, 이후 magicJar의 자체 Screener와 과거 캔들 Backtest 검증을 거쳐야 하는 '가설 후보'다.

        [분석 원칙]
        1. 단순 시총 상위주 나열을 금지한다. 사용자의 조건에 맞는 구체적인 모멘텀, 수급, 차트 패턴적 근거를 reason에 작성하라.
        2. 종목의 섹터와 테마 트렌드를 반영하라. 예: HBM, 전력설비, 조선, 방산, AI 인프라 등.
        3. KRX 상장 보통주/우선주 후보만 제안한다. ETF, ETN, ELW, 펀드, 리츠, 해외주식, 파생상품은 제외한다.
        4. 최신 가격·뉴스를 확정적으로 단정하지 말고, 검증이 필요한 후보 가설로 표현한다.
        5. 매수/매도 권유, 확정 수익, 자동 주문을 암시하는 표현을 금지한다.
        """.trimIndent()

    fun latest(userId: String): StrategyResearchAiDiscoveryResponseDto? = stateRepository.findById(userId).orElse(null)?.toResponse()

    fun clear(userId: String) {
        if (stateRepository.existsById(userId)) {
            stateRepository.deleteById(userId)
        }
    }

    fun presets(): List<StrategyResearchAiDiscoveryPresetDto> = presetRepository.findAllByOrderBySortOrderAscIdAsc().map { it.toDto() }

    fun createPreset(request: StrategyResearchAiDiscoveryPresetCreateRequestDto): StrategyResearchAiDiscoveryPresetDto {
        val id = request.id?.trim()?.takeIf { it.isNotBlank() } ?: "custom_${Instant.now().toEpochMilli()}"
        if (presetRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 AI 종목발굴 프리셋 ID입니다: $id")
        }
        val sortOrder =
            request.sortOrder
                ?: ((presetRepository.findAllByOrderBySortOrderAscIdAsc().maxOfOrNull { it.sortOrder } ?: 0) + 10)
        val now = Instant.now()
        val entity =
            StrategyResearchAiDiscoveryPresetJpaEntity(
                id = id,
                label = request.label.trim(),
                description = request.description.trim(),
                promptText = request.promptText.trim(),
                sortOrder = sortOrder,
                active = request.active,
                builtIn = false,
                createdAt = now,
                updatedAt = now,
            )
        return presetRepository.save(entity).toDto()
    }

    fun updatePreset(
        presetId: String,
        request: StrategyResearchAiDiscoveryPresetUpdateRequestDto,
    ): StrategyResearchAiDiscoveryPresetDto {
        val entity =
            presetRepository
                .findById(presetId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "AI 종목발굴 프리셋을 찾지 못했습니다: $presetId") }
        entity.label = request.label.trim()
        entity.description = request.description.trim()
        entity.promptText = request.promptText.trim()
        entity.sortOrder = request.sortOrder
        entity.active = request.active
        entity.updatedAt = Instant.now()
        return presetRepository.save(entity).toDto()
    }

    fun previewPrompt(request: StrategyResearchAiDiscoveryPromptPreviewRequestDto): StrategyResearchAiDiscoveryPromptPreviewDto {
        val discoveryRequest =
            StrategyResearchAiDiscoveryRequestDto(
                userId = request.userId,
                query = request.query,
                presetId = request.presetId,
                market = request.market,
                limit = request.limit,
            )
        val previous = stateRepository.findById(request.userId).orElse(null)
        val keptItems = previous?.items()?.filter { it.status == StrategyResearchAiDiscoveryItemStatus.KEPT } ?: emptyList()
        val dismissedItems = previous?.items()?.filter { it.status == StrategyResearchAiDiscoveryItemStatus.DISMISSED } ?: emptyList()
        val preset = resolveActivePreset(request.presetId)
        return StrategyResearchAiDiscoveryPromptPreviewDto(
            systemPrompt = coreSystemPrompt,
            preset = preset?.toDto(),
            userQuery = request.query,
            finalPrompt = buildPrompt(discoveryRequest, keptItems, dismissedItems, preset),
            generatedAt = Instant.now().toString(),
            warnings =
                buildList {
                    add("이 프롬프트는 후보 가설 생성을 위한 미리보기이며 Claude 호출을 실행하지 않습니다.")
                    if (keptItems.isNotEmpty()) add("유지 후보 ${keptItems.size}건이 중복 금지 문맥에 포함됩니다.")
                    if (dismissedItems.isNotEmpty()) add("제외 후보 ${dismissedItems.size}건이 재제안 금지 문맥에 포함됩니다.")
                },
        )
    }

    fun updateItemStatus(request: StrategyResearchAiDiscoveryItemStatusRequestDto): StrategyResearchAiDiscoveryResponseDto {
        val entity =
            stateRepository
                .findById(request.userId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "저장된 AI 종목발굴 결과가 없습니다.") }
        val items = entity.items()
        var found = false
        val updatedItems =
            items.map { item ->
                if (item.itemKey == request.itemKey) {
                    found = true
                    item.copy(status = request.status)
                } else {
                    item
                }
            }
        if (!found) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "AI 종목발굴 후보를 찾지 못했습니다.")
        }
        entity.itemsJson = objectMapper.writeValueAsString(updatedItems)
        entity.updatedAt = Instant.now()
        return stateRepository.save(entity).toResponse()
    }

    fun discover(request: StrategyResearchAiDiscoveryRequestDto): StrategyResearchAiDiscoveryResponseDto {
        val previous = stateRepository.findById(request.userId).orElse(null)
        val previousItems = previous?.items() ?: emptyList()
        val keptItems = previousItems.filter { it.status == StrategyResearchAiDiscoveryItemStatus.KEPT }
        val dismissedItems = previousItems.filter { it.status == StrategyResearchAiDiscoveryItemStatus.DISMISSED }
        val dismissedKeys = dismissedItems.map { it.itemKey }.toSet()
        val keptKeys = keptItems.map { it.itemKey }.toSet()
        val preset = resolveActivePreset(request.presetId)

        if (keptItems.size >= request.limit) {
            val items = enrichCandleAvailability(keptItems + dismissedItems, request)
            return saveState(
                request = request,
                response =
                    StrategyResearchAiDiscoveryResponseDto(
                        query = request.query,
                        presetId = preset?.id,
                        market = request.market,
                        limit = request.limit,
                        provider = "state-cache",
                        generatedAt = Instant.now().toString(),
                        warnings = discoveryWarnings(keptItems, dismissedItems, items, request.limit, aiSkipped = true),
                        items = items,
                    ),
            )
        }

        val prompt = buildPrompt(request, keptItems, dismissedItems, preset)
        val aiResult = aiTextClient.complete(prompt)
        val rawItems = parseItems(aiResult.text).take(12)
        if (rawItems.isEmpty()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 응답에서 종목 후보를 읽지 못했습니다.")
        }

        val marketFilter = request.market?.uppercase()
        val freshItems =
            rawItems
                .map { it.toDto(marketFilter) }
                .filterNot { it.itemKey in dismissedKeys || it.itemKey in keptKeys }
        val availableSlots = (request.limit - keptItems.size).coerceAtLeast(0)
        val visibleItems = keptItems + freshItems.take(availableSlots)
        val items = enrichCandleAvailability(visibleItems + dismissedItems, request)

        log.info(
            "StrategyResearch AI 종목발굴: userId={} queryLength={} rawItems={} matched={}",
            request.userId,
            request.query.length,
            rawItems.size,
            items.count { it.matched },
        )

        return saveState(
            request = request,
            response =
                StrategyResearchAiDiscoveryResponseDto(
                    query = request.query,
                    presetId = preset?.id,
                    market = request.market,
                    limit = request.limit,
                    provider = aiResult.provider,
                    generatedAt = Instant.now().toString(),
                    warnings = discoveryWarnings(keptItems, dismissedItems, items, request.limit),
                    items = items,
                ),
        )
    }

    private fun discoveryWarnings(
        keptItems: List<StrategyResearchAiDiscoveryItemDto>,
        dismissedItems: List<StrategyResearchAiDiscoveryItemDto>,
        items: List<StrategyResearchAiDiscoveryItemDto>,
        requestedLimit: Int,
        aiSkipped: Boolean = false,
    ): List<String> {
        val unmatchedCount = items.count { !it.matched }
        return buildList {
            add("AI 후보는 종목 마스터 매핑 후 검증용으로만 표시됩니다.")
            if (keptItems.isNotEmpty()) {
                add("유지 후보 ${keptItems.size}건은 재발굴 결과에 고정했습니다.")
            }
            if (keptItems.size > requestedLimit) {
                add("유지 후보가 요청 개수 ${requestedLimit}건보다 많아 ${keptItems.size}건을 표시합니다.")
            }
            if (aiSkipped) {
                add("표시 슬롯이 유지 후보로 이미 채워져 Claude 호출은 건너뛰었습니다.")
            }
            if (dismissedItems.isNotEmpty()) {
                add("제외 후보 ${dismissedItems.size}건은 초기화 전까지 숨깁니다.")
            }
            if (unmatchedCount > 0) {
                add("종목 마스터와 매칭되지 않은 후보 ${unmatchedCount}건이 있습니다.")
            }
            val candleMissingCount =
                items.count {
                    it.status != StrategyResearchAiDiscoveryItemStatus.DISMISSED &&
                        it.matched &&
                        it.candleCount == 0L
                }
            if (candleMissingCount > 0) {
                add("선택한 interval·기간에 저장된 candle이 없는 후보 ${candleMissingCount}건은 차트 수집 후 리서치할 수 있습니다.")
            }
        }
    }

    /**
     * 후보별 candle 보유량을 재계산해 candleCount·hasResearchCandles를 채운다.
     * request에 interval/from/to가 없으면 기존 동작 유지(둘 다 null).
     * state-cache 반환 경로·신규 발굴 경로 양쪽에서 호출되어 stale candleCount를 예방한다.
     */
    private fun enrichCandleAvailability(
        items: List<StrategyResearchAiDiscoveryItemDto>,
        request: StrategyResearchAiDiscoveryRequestDto,
    ): List<StrategyResearchAiDiscoveryItemDto> {
        val window = candleAvailabilityWindow(request) ?: return items
        return items.map { item ->
            val code = item.code
            if (code.isNullOrBlank()) {
                item.copy(candleCount = null, hasResearchCandles = null)
            } else {
                val count =
                    runCatching {
                        candleJpaRepository.countByCodeAndIntervalAndTsBetween(
                            code = code,
                            interval = window.interval,
                            fromTs = window.from,
                            toTs = window.to,
                        )
                    }.onFailure { e ->
                        log.warn(
                            "StrategyResearch AI 후보 candle 보유량 조회 실패: code={} interval={} err={}",
                            code,
                            window.interval,
                            e.message,
                        )
                    }.getOrNull()
                if (count == null) {
                    item.copy(candleCount = null, hasResearchCandles = null)
                } else {
                    item.copy(candleCount = count, hasResearchCandles = count > 0)
                }
            }
        }
    }

    private fun candleAvailabilityWindow(request: StrategyResearchAiDiscoveryRequestDto): CandleAvailabilityWindow? {
        val interval = request.interval?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val from =
            request.from
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val to =
            request.to
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        if (from.isAfter(to)) return null
        return CandleAvailabilityWindow(interval = interval, from = from, to = to)
    }

    private fun saveState(
        request: StrategyResearchAiDiscoveryRequestDto,
        response: StrategyResearchAiDiscoveryResponseDto,
    ): StrategyResearchAiDiscoveryResponseDto {
        val now = Instant.now()
        val generatedAt = Instant.parse(response.generatedAt)
        val entity =
            stateRepository.findById(request.userId).orElse(
                StrategyResearchAiDiscoveryStateJpaEntity(
                    userId = request.userId,
                    query = request.query,
                    market = request.market,
                    presetId = response.presetId,
                    candidateLimit = request.limit,
                    provider = response.provider,
                    generatedAt = generatedAt,
                    warningsJson = "[]",
                    itemsJson = "[]",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        entity.query = request.query
        entity.presetId = response.presetId
        entity.market = request.market
        entity.candidateLimit = request.limit
        entity.provider = response.provider
        entity.generatedAt = generatedAt
        entity.warningsJson = objectMapper.writeValueAsString(response.warnings)
        entity.itemsJson = objectMapper.writeValueAsString(response.items)
        entity.updatedAt = now
        return stateRepository.save(entity).toResponse()
    }

    private fun StrategyResearchAiDiscoveryStateJpaEntity.toResponse(): StrategyResearchAiDiscoveryResponseDto =
        StrategyResearchAiDiscoveryResponseDto(
            query = query,
            presetId = presetId,
            market = market,
            limit = candidateLimit,
            provider = provider,
            generatedAt = generatedAt.toString(),
            warnings = warnings(),
            items = items(),
        )

    private fun StrategyResearchAiDiscoveryStateJpaEntity.warnings(): List<String> =
        runCatching { objectMapper.readValue<List<String>>(warningsJson) }.getOrDefault(emptyList())

    private fun StrategyResearchAiDiscoveryStateJpaEntity.items(): List<StrategyResearchAiDiscoveryItemDto> =
        runCatching { objectMapper.readValue<List<StrategyResearchAiDiscoveryItemDto>>(itemsJson) }.getOrDefault(emptyList())

    private fun resolveActivePreset(presetId: String?): StrategyResearchAiDiscoveryPresetJpaEntity? {
        val normalized = presetId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val preset =
            presetRepository
                .findById(normalized)
                .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "AI 종목발굴 프리셋을 찾지 못했습니다: $normalized") }
        if (!preset.active) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성 AI 종목발굴 프리셋은 사용할 수 없습니다: $normalized")
        }
        return preset
    }

    private fun StrategyResearchAiDiscoveryPresetJpaEntity.toDto(): StrategyResearchAiDiscoveryPresetDto =
        StrategyResearchAiDiscoveryPresetDto(
            id = id,
            label = label,
            description = description,
            promptText = promptText,
            sortOrder = sortOrder,
            active = active,
            builtIn = builtIn,
            updatedAt = updatedAt.toString(),
        )

    private fun buildPrompt(
        request: StrategyResearchAiDiscoveryRequestDto,
        keptItems: List<StrategyResearchAiDiscoveryItemDto>,
        dismissedItems: List<StrategyResearchAiDiscoveryItemDto>,
        preset: StrategyResearchAiDiscoveryPresetJpaEntity?,
    ): String {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val marketText = request.market ?: "KOSPI/KOSDAQ/KONEX 전체"
        return listOf(
            coreSystemPrompt,
            "",
            "[요청 컨텍스트]",
            "오늘 날짜: $today KST",
            "시장 필터: $marketText",
            "후보 수: ${request.limit}",
            "",
            if (preset != null) {
                listOf(
                    "[선택 프리셋]",
                    "프리셋 ID: ${preset.id}",
                    "프리셋 이름: ${preset.label}",
                    "프리셋 설명: ${preset.description}",
                    "프리셋 지시문:",
                    preset.promptText,
                    "",
                ).joinToString("\n")
            } else {
                "[선택 프리셋]\n없음. 사용자 입력만 바탕으로 검증 후보를 제안한다.\n"
            },
            "[사용자 입력]",
            request.query,
            "",
            if (keptItems.isNotEmpty()) {
                "이미 사용자가 유지하기로 한 후보(중복 제안 금지): ${keptItems.joinToString { it.nameWithCode() }}"
            } else {
                null
            },
            if (dismissedItems.isNotEmpty()) {
                "사용자가 제외한 후보(다시 제안 금지): ${dismissedItems.joinToString { it.nameWithCode() }}"
            } else {
                null
            },
            "",
            "가능한 경우 KRX 6자리 종목코드를 함께 적고, 모르면 code는 null로 두세요.",
            "아래 JSON 형식만 출력하세요. 마크다운, 설명문, 코드블록은 출력하지 마세요.",
            "{",
            "  \"items\": [",
            "    {",
            "      \"name\": \"종목명\",",
            "      \"code\": \"005930\",",
            "      \"market\": \"KOSPI\",",
            "      \"themes\": [\"테마1\", \"테마2\"],",
            "      \"reason\": \"후보로 볼 만한 한두 문장 근거\",",
            "      \"confidence\": 0.72",
            "    }",
            "  ]",
            "}",
        ).filterNotNull().joinToString("\n")
    }

    private fun parseItems(text: String): List<ClaudeDiscoveryRawItem> {
        val json = extractJson(text)
        val root =
            runCatching { objectMapper.readTree(json) }
                .getOrElse {
                    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude JSON 파싱 실패: ${it.message}", it)
                }
        val itemsNode =
            when {
                root.has("items") -> root["items"]
                root.isArray -> root
                else -> null
            }
        if (itemsNode == null || !itemsNode.isArray) return emptyList()
        return itemsNode.mapNotNull { node ->
            val name = node.text("name")?.takeIf { it.isNotBlank() }
            val code = node.text("code")?.let { sixDigitCode(it) }
            if (name == null && code == null) return@mapNotNull null
            ClaudeDiscoveryRawItem(
                name = name,
                code = code,
                market = node.text("market"),
                themes = node.stringList("themes"),
                reason = node.text("reason") ?: node.text("thesis") ?: "AI 후보",
                confidence = node.number("confidence") ?: 0.5,
            )
        }
    }

    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val fenced =
            Regex("```(?:json)?\\s*([\\s\\S]*?)```")
                .find(trimmed)
                ?.groupValues
                ?.get(1)
                ?.trim()
        if (!fenced.isNullOrBlank()) return fenced
        val startObject = trimmed.indexOf('{')
        val endObject = trimmed.lastIndexOf('}')
        if (startObject >= 0 && endObject > startObject) return trimmed.substring(startObject, endObject + 1)
        val startArray = trimmed.indexOf('[')
        val endArray = trimmed.lastIndexOf(']')
        if (startArray >= 0 && endArray > startArray) return trimmed.substring(startArray, endArray + 1)
        return trimmed
    }

    private fun ClaudeDiscoveryRawItem.toDto(marketFilter: String?): StrategyResearchAiDiscoveryItemDto {
        val instrument = resolveInstrument(this, marketFilter)
        val resolvedCode = instrument?.code
        val resolvedName = instrument?.name ?: name ?: code ?: "미확인 후보"
        val normalizedConfidence =
            when {
                confidence > 1.0 && confidence <= 100.0 -> confidence / 100.0
                else -> confidence
            }.coerceIn(0.0, 1.0)
        return StrategyResearchAiDiscoveryItemDto(
            itemKey = discoveryItemKey(resolvedCode, code, resolvedName),
            code = resolvedCode,
            name = resolvedName,
            market = instrument?.market ?: market?.uppercase(),
            sector = instrument?.sector,
            theme = instrument?.theme,
            aiReason = reason,
            themes = themes.take(5),
            confidence = "%.2f".format(normalizedConfidence).toDouble(),
            matched = instrument != null,
            rawName = name,
            rawCode = code,
            tradingHalt = instrument?.tradingHalt ?: false,
            managementStock = instrument?.managementStock ?: false,
            status = StrategyResearchAiDiscoveryItemStatus.NEW,
        )
    }

    private fun resolveInstrument(
        raw: ClaudeDiscoveryRawItem,
        marketFilter: String?,
    ): InstrumentJpaEntity? {
        raw.code
            ?.let { code ->
                instrumentJpaRepository.findById(code).orElse(null)
            }?.takeIf { it.isResearchEligible(marketFilter) }
            ?.let { return it }

        val queries =
            listOfNotNull(raw.name, raw.code)
                .flatMap { listOf(it, normalizeQuery(it)) }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

        queries.forEach { query ->
            val found =
                instrumentJpaRepository
                    .searchByNameOrCode(query, marketFilter, PageRequest.of(0, 5))
                    .firstOrNull { it.isResearchEligible(marketFilter) }
            if (found != null) return found
        }
        return null
    }

    private fun InstrumentJpaEntity.isResearchEligible(marketFilter: String?): Boolean {
        if (!active) return false
        if (securitiesGroup != null && securitiesGroup != "ST") return false
        if (marketFilter != null && market != marketFilter) return false
        return true
    }

    private fun normalizeQuery(value: String): String = value.replace(Regex("[\\s.·()（）]"), "").lowercase()

    private fun sixDigitCode(value: String): String? = Regex("\\d{6}").find(value)?.value

    private fun discoveryItemKey(
        code: String?,
        rawCode: String?,
        name: String,
    ): String =
        when {
            !code.isNullOrBlank() -> "CODE:$code"
            !rawCode.isNullOrBlank() -> "RAW:${sixDigitCode(rawCode) ?: normalizeQuery(rawCode)}"
            else -> "NAME:${normalizeQuery(name).take(80)}"
        }

    private fun StrategyResearchAiDiscoveryItemDto.nameWithCode(): String = if (code.isNullOrBlank()) name else "$name($code)"

    private fun JsonNode.text(fieldName: String): String? =
        get(fieldName)
            ?.takeIf { !it.isNull }
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonNode.number(fieldName: String): Double? =
        get(fieldName)
            ?.takeIf { it.isNumber || it.isTextual }
            ?.asText()
            ?.toDoubleOrNull()

    private fun JsonNode.stringList(fieldName: String): List<String> {
        val node = get(fieldName) ?: return emptyList()
        if (node.isArray) {
            return node.mapNotNull { it.asText()?.trim()?.takeIf { value -> value.isNotBlank() } }
        }
        return node
            .asText("")
            .split(",", "/", "|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private data class ClaudeDiscoveryRawItem(
        val name: String?,
        val code: String?,
        val market: String?,
        val themes: List<String>,
        val reason: String,
        val confidence: Double,
    )

    private data class CandleAvailabilityWindow(
        val interval: String,
        val from: Instant,
        val to: Instant,
    )
}
