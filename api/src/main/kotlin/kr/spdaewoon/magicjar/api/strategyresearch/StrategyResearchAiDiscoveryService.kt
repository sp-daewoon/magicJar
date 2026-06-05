package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
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
    private val aiTextClient: StrategyResearchAiTextClient,
    private val objectMapper: ObjectMapper,
    private val stateRepository: StrategyResearchAiDiscoveryStateJpaRepository,
) {
    private val log = LoggerFactory.getLogger(StrategyResearchAiDiscoveryService::class.java)

    fun latest(userId: String): StrategyResearchAiDiscoveryResponseDto? = stateRepository.findById(userId).orElse(null)?.toResponse()

    fun clear(userId: String) {
        if (stateRepository.existsById(userId)) {
            stateRepository.deleteById(userId)
        }
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

        val prompt = buildPrompt(request, keptItems, dismissedItems)
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
        val items = visibleItems + dismissedItems
        val unmatchedCount = items.count { !it.matched }
        val warnings =
            buildList {
                add("AI 후보는 종목 마스터 매핑 후 검증용으로만 표시됩니다.")
                if (keptItems.isNotEmpty()) {
                    add("유지 후보 ${keptItems.size}건은 재발굴 결과에 고정했습니다.")
                }
                if (dismissedItems.isNotEmpty()) {
                    add("제외 후보 ${dismissedItems.size}건은 초기화 전까지 숨깁니다.")
                }
                if (unmatchedCount > 0) {
                    add("종목 마스터와 매칭되지 않은 후보 ${unmatchedCount}건이 있습니다.")
                }
            }

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
                    market = request.market,
                    limit = request.limit,
                    provider = aiResult.provider,
                    generatedAt = Instant.now().toString(),
                    warnings = warnings,
                    items = items,
                ),
        )
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

    private fun buildPrompt(
        request: StrategyResearchAiDiscoveryRequestDto,
        keptItems: List<StrategyResearchAiDiscoveryItemDto>,
        dismissedItems: List<StrategyResearchAiDiscoveryItemDto>,
    ): String {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val marketText = request.market ?: "KOSPI/KOSDAQ/KONEX 전체"
        return listOf(
            "당신은 한국 주식 종목 탐색 보조자입니다.",
            "오늘 날짜는 $today KST입니다.",
            "사용자의 문장을 KRX 상장 보통주/우선주 후보로 바꿔주세요.",
            "ETF, ETN, ELW, 펀드, 리츠, 해외주식, 파생상품은 제외하세요.",
            "매수/매도 추천이나 확정적 수익 표현은 쓰지 말고, 사용자가 전략 리서치에서 검증할 후보만 제시하세요.",
            "가능한 경우 KRX 6자리 종목코드를 함께 적고, 모르면 code는 null로 두세요.",
            "",
            "사용자 요청: ${request.query}",
            "시장 필터: $marketText",
            "후보 수: ${request.limit}",
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
}
