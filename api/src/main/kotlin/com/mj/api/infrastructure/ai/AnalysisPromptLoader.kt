package com.mj.api.infrastructure.ai

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * resources/prompts/analysis 디렉토리의 .st 파일들을 읽고 `{{key}}` 단순 치환을 수행하는 경량 로더.
 *
 * - 파일 본문은 메모리 캐시(첫 호출 후 immutable).
 * - 변수 미공급 시 `{{key}}` 그대로 둠 (LLM이 "데이터 부족"으로 자연어 처리).
 */
@Component
class AnalysisPromptLoader {
    private val cache = ConcurrentHashMap<String, String>()

    fun load(path: String): String =
        cache.computeIfAbsent(path) {
            val resource = ClassPathResource(it)
            require(resource.exists()) { "prompt not found: $it" }
            resource.inputStream.use { stream -> stream.readBytes().toString(StandardCharsets.UTF_8) }
        }

    fun render(
        path: String,
        vars: Map<String, String>,
    ): String {
        var text = load(path)
        for ((k, v) in vars) {
            text = text.replace("{{$k}}", v)
        }
        return text
    }

    /**
     * 모든 분석 프롬프트의 system base — 한국어 강제·면책 강제·매매 결정 금지.
     */
    fun systemBase(): String = load(SYSTEM_BASE_PATH)

    companion object {
        const val SYSTEM_BASE_PATH = "prompts/analysis/_system_base.st"
        const val SYMBOL_COMMENTARY_PATH = "prompts/analysis/symbol_commentary.st"
        const val COMPANY_ANALYSIS_PATH = "prompts/analysis/company_analysis.st"
        const val CHART_INTERPRETATION_PATH = "prompts/analysis/chart_interpretation.st"
        const val INDICATOR_MEANING_PATH = "prompts/analysis/indicator_meaning.st"
        const val JOURNAL_RETROSPECTIVE_PATH = "prompts/analysis/journal_retrospective.st"

        const val PROMPT_VERSION = "v1"
    }
}
