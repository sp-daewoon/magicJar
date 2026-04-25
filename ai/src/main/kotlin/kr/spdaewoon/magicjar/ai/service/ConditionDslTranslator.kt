package kr.spdaewoon.magicjar.ai.service

import com.fasterxml.jackson.annotation.JsonProperty
import kr.spdaewoon.magicjar.ai.prompt.PromptLoader
import kr.spdaewoon.magicjar.ai.util.JsonMapper
import kr.spdaewoon.magicjar.core.port.AiClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.Optional

/**
 * 자연어 → strategy 모듈이 사용할 DSL 문자열 변환.
 *
 * 실제 DSL 문법 검증(파서 호출)은 app/strategy 모듈이 담당한다. 본 클래스는 변환만.
 */
@Service
class ConditionDslTranslator(
    private val aiClient: AiClient,
    private val promptLoader: PromptLoader,
) {
    private val log = LoggerFactory.getLogger(ConditionDslTranslator::class.java)

    companion object {
        const val PROMPT_PATH = "prompts/condition-dsl-translator.st"
        const val SCHEMA = """
            {"dsl": "...", "confidence": 0.0, "ambiguities": [], "warnings": [], "reasoning": "..."}
        """
    }

    fun translate(userQuery: String): Optional<DslTranslation> {
        if (userQuery.isBlank()) return Optional.empty()
        val system = promptLoader.load(PROMPT_PATH)
        val user = "# 사용자 질의\n$userQuery"
        val raw = aiClient.chatJson(system, user, SCHEMA) ?: return Optional.empty()
        val parsed =
            runCatching {
                val dto = JsonMapper.instance.readValue(raw, DslDto::class.java)
                DslTranslation(
                    dsl = dto.dsl,
                    confidence = BigDecimal(dto.confidence.toString()).setScale(2, java.math.RoundingMode.HALF_UP),
                    ambiguities = dto.ambiguities,
                    warnings = dto.warnings,
                    reasoning = dto.reasoning,
                    parsingFailed = false,
                )
            }.getOrElse {
                log.warn("ConditionDslTranslator parse failed: {}", it.message)
                DslTranslation(
                    dsl = "",
                    confidence = BigDecimal.ZERO,
                    ambiguities = emptyList(),
                    warnings = listOf("parsing_failed"),
                    reasoning = "parsing_failed",
                    parsingFailed = true,
                )
            }
        return Optional.of(parsed)
    }
}

data class DslTranslation(
    val dsl: String,
    val confidence: BigDecimal,
    val ambiguities: List<String>,
    val warnings: List<String>,
    val reasoning: String,
    val parsingFailed: Boolean = false,
)

private data class DslDto(
    @JsonProperty("dsl") val dsl: String = "",
    @JsonProperty("confidence") val confidence: Double = 0.0,
    @JsonProperty("ambiguities") val ambiguities: List<String> = emptyList(),
    @JsonProperty("warnings") val warnings: List<String> = emptyList(),
    @JsonProperty("reasoning") val reasoning: String = "",
)
