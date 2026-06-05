package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
) {
    private val log = LoggerFactory.getLogger(StrategyResearchAiDiscoveryService::class.java)

    fun discover(request: StrategyResearchAiDiscoveryRequestDto): StrategyResearchAiDiscoveryResponseDto {
        val prompt = buildPrompt(request)
        val aiResult = aiTextClient.complete(prompt)
        val rawItems = parseItems(aiResult.text).take(request.limit)
        if (rawItems.isEmpty()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 응답에서 종목 후보를 읽지 못했습니다.")
        }

        val marketFilter = request.market?.uppercase()
        val items = rawItems.map { it.toDto(marketFilter) }
        val unmatchedCount = items.count { !it.matched }
        val warnings =
            buildList {
                add("AI 후보는 종목 마스터 매핑 후 검증용으로만 표시됩니다.")
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

        return StrategyResearchAiDiscoveryResponseDto(
            query = request.query,
            provider = aiResult.provider,
            generatedAt = Instant.now().toString(),
            warnings = warnings,
            items = items,
        )
    }

    private fun buildPrompt(request: StrategyResearchAiDiscoveryRequestDto): String {
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
        ).joinToString("\n")
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
        val normalizedConfidence =
            when {
                confidence > 1.0 && confidence <= 100.0 -> confidence / 100.0
                else -> confidence
            }.coerceIn(0.0, 1.0)
        return StrategyResearchAiDiscoveryItemDto(
            code = instrument?.code,
            name = instrument?.name ?: name ?: code ?: "미확인 후보",
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
