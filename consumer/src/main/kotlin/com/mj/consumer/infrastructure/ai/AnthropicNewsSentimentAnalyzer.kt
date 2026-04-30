package com.mj.consumer.infrastructure.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.consumer.application.ai.prompt.PromptLoader
import com.mj.domain.ai.SentimentScore
import com.mj.domain.ai.port.NewsSentimentAnalyzer
import com.mj.domain.ai.util.JsonMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt

/**
 * Anthropic Claude 기반 뉴스 단건 감성 분석 어댑터.
 *
 * - Spring AI ChatClient(Anthropic 모델 바인딩)로 호출
 * - prompt: `prompts/sentiment-analyzer.st`(공통) + 단건 스키마 강제
 * - 응답: JSON({sentiment, confidence, rationale}) → [SentimentScore]
 * - 호출 실패·parsing 실패 시 1회 재시도 후 `null` 반환(로그 후 호출 측이 진행)
 */
class AnthropicNewsSentimentAnalyzer(
    private val chatClient: ChatClient,
    private val promptLoader: PromptLoader,
) : NewsSentimentAnalyzer {
    private val log = LoggerFactory.getLogger(AnthropicNewsSentimentAnalyzer::class.java)

    companion object {
        const val PROMPT_PATH = "prompts/sentiment-analyzer.st"
        const val SCHEMA = """
            {
              "sentiment": "POSITIVE|NEUTRAL|NEGATIVE",
              "confidence": 0.0,
              "rationale": "한국어 1~2문장 근거"
            }
        """
        const val MAX_BODY_CHARS = 1500
    }

    override fun analyze(news: NewsSentimentAnalyzer.NewsAnalysisInput): SentimentScore? {
        val systemBase = promptLoader.load(PROMPT_PATH)
        val systemPrompt = wrapWithSchema(systemBase)
        val userMessage = buildUserMessage(news)

        val raw =
            invokeWithRetry(systemPrompt, userMessage)
                ?: return null

        val parsed = parse(raw)
        if (parsed == null) {
            log.warn(
                "AnthropicNewsSentimentAnalyzer parsing failed (newsId={}) raw={}",
                news.newsId,
                raw.take(500),
            )
        }
        return parsed
    }

    private fun invokeWithRetry(
        systemPrompt: String,
        userMessage: String,
    ): String? {
        repeat(2) { attempt ->
            try {
                val prompt = Prompt(listOf(SystemMessage(systemPrompt), UserMessage(userMessage)))
                val response = chatClient.prompt(prompt).call().content()
                if (!response.isNullOrBlank()) return extractJson(response)
                log.warn("AnthropicNewsSentimentAnalyzer empty content (attempt={})", attempt)
            } catch (ex: Exception) {
                log.warn(
                    "AnthropicNewsSentimentAnalyzer call failed (attempt={}): {}",
                    attempt,
                    ex.message,
                )
            }
        }
        return null
    }

    private fun parse(raw: String): SentimentScore? =
        runCatching {
            val dto = JsonMapper.instance.readValue(raw, SentimentDto::class.java)
            SentimentScore.of(
                label = SentimentScore.parseLabel(dto.sentiment),
                confidence = dto.confidence,
                rationale = dto.rationale,
            )
        }.getOrNull()

    private fun wrapWithSchema(systemBase: String): String =
        buildString {
            appendLine(systemBase.trim())
            appendLine()
            appendLine("## 단건 분석 출력 포맷 (반드시 준수)")
            appendLine("아래 JSON 스키마를 엄격히 따르는 **JSON 하나**만 반환하라. 주석/마크다운/설명문 금지.")
            appendLine("```json")
            appendLine(SCHEMA.trim())
            appendLine("```")
            appendLine("- sentiment: POSITIVE / NEUTRAL / NEGATIVE 중 하나")
            appendLine("- confidence: 0.0 ~ 1.0 사이 실수")
            appendLine("- rationale: 한국어 1~2 문장, 본문 근거 인용")
        }

    private fun buildUserMessage(news: NewsSentimentAnalyzer.NewsAnalysisInput): String =
        buildString {
            appendLine("# 분석 대상 뉴스 1건")
            appendLine("## [${news.source}] ${news.title}")
            appendLine("- publishedAt: ${news.publishedAt}")
            if (news.instruments.isNotEmpty()) {
                appendLine("- 관련 종목: ${news.instruments.joinToString(",")}")
            }
            appendLine("- 본문:")
            appendLine(news.body.take(MAX_BODY_CHARS))
        }

    /** LLM이 ```json ... ``` 펜스를 두르더라도 JSON 본문만 추출. */
    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val fenceStart = trimmed.indexOf("```")
        if (fenceStart < 0) return trimmed
        val afterFence = trimmed.substring(fenceStart + 3)
        val nl = afterFence.indexOf('\n')
        val body = if (nl >= 0) afterFence.substring(nl + 1) else afterFence
        val end = body.lastIndexOf("```")
        return if (end >= 0) body.substring(0, end).trim() else body.trim()
    }

    private data class SentimentDto(
        @JsonProperty("sentiment") val sentiment: String = "NEUTRAL",
        @JsonProperty("confidence") val confidence: Double = 0.0,
        @JsonProperty("rationale") val rationale: String = "",
    )
}
