package kr.spdaewoon.magicjar.ai.service

import com.fasterxml.jackson.annotation.JsonProperty
import kr.spdaewoon.magicjar.ai.prompt.PromptLoader
import kr.spdaewoon.magicjar.ai.util.JsonMapper
import kr.spdaewoon.magicjar.core.domain.NewsItem
import kr.spdaewoon.magicjar.core.port.AiClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.Optional

/**
 * 뉴스/공시 감성 분석.
 *
 * 입력: 최대 20개의 NewsItem
 * 출력: 종목별 -1.0 ~ +1.0 점수 + 근거 문장 + confidence
 */
@Service
class SentimentAnalyzer(
    private val aiClient: AiClient,
    private val promptLoader: PromptLoader,
) {
    private val log = LoggerFactory.getLogger(SentimentAnalyzer::class.java)

    companion object {
        const val MAX_NEWS = 20
        const val PROMPT_PATH = "prompts/sentiment-analyzer.st"

        const val SCHEMA = """
            {
              "overallSentiment": "POSITIVE|NEUTRAL|NEGATIVE",
              "score": -1.0,
              "perInstrument": [
                {"code": "005930", "sentiment": "POSITIVE", "score": 0.7}
              ],
              "eventTags": ["earnings","merger","regulation"],
              "tradingHint": "BUY_BIAS|SELL_BIAS|NONE",
              "summary": "한국어 120자 이내",
              "confidence": 0.0,
              "reasoning": "한국어 300자 이내"
            }
        """
    }

    fun analyze(news: List<NewsItem>): Optional<SentimentResult> {
        if (news.isEmpty()) return Optional.empty()
        val trimmed = news.take(MAX_NEWS)
        val system = promptLoader.load(PROMPT_PATH)
        val user = buildUserMessage(trimmed)

        val raw = aiClient.chatJson(system, user, SCHEMA) ?: return Optional.empty()
        val result =
            runCatching {
                val dto = JsonMapper.instance.readValue(raw, SentimentDto::class.java)
                SentimentResult(
                    scoreByCode =
                        dto.perInstrument.associate {
                            it.code to BigDecimal(it.score.toString()).setScale(2, java.math.RoundingMode.HALF_UP)
                        },
                    overallSentiment = dto.overallSentiment,
                    tradingHint = dto.tradingHint,
                    eventTags = dto.eventTags,
                    summary = dto.summary,
                    confidence = BigDecimal(dto.confidence.toString()).setScale(2, java.math.RoundingMode.HALF_UP),
                    reasoning = dto.reasoning,
                    parsingFailed = false,
                )
            }.getOrElse {
                log.warn("SentimentAnalyzer JSON parse failed: {}. raw={}", it.message, raw.take(500))
                SentimentResult(
                    scoreByCode = emptyMap(),
                    overallSentiment = "NEUTRAL",
                    tradingHint = "NONE",
                    eventTags = emptyList(),
                    summary = "",
                    confidence = BigDecimal.ZERO,
                    reasoning = "parsing_failed",
                    parsingFailed = true,
                )
            }
        return Optional.of(result)
    }

    private fun buildUserMessage(news: List<NewsItem>): String =
        buildString {
            appendLine("# 분석 대상 뉴스 (${news.size}건)")
            news.forEachIndexed { idx, n ->
                appendLine("## ${idx + 1}. [${n.source}] ${n.title}")
                appendLine("- publishedAt: ${n.publishedAt}")
                appendLine("- 관련 종목: ${n.instruments.joinToString(",")}")
                appendLine("- 본문:")
                appendLine(n.body.take(600))
                appendLine()
            }
        }
}

data class SentimentResult(
    val scoreByCode: Map<String, BigDecimal>,
    val overallSentiment: String,
    val tradingHint: String,
    val eventTags: List<String>,
    val summary: String,
    val confidence: BigDecimal,
    val reasoning: String,
    val parsingFailed: Boolean = false,
)

private data class SentimentDto(
    @JsonProperty("overallSentiment") val overallSentiment: String = "NEUTRAL",
    @JsonProperty("score") val score: Double = 0.0,
    @JsonProperty("perInstrument") val perInstrument: List<PerInstrumentDto> = emptyList(),
    @JsonProperty("eventTags") val eventTags: List<String> = emptyList(),
    @JsonProperty("tradingHint") val tradingHint: String = "NONE",
    @JsonProperty("summary") val summary: String = "",
    @JsonProperty("confidence") val confidence: Double = 0.0,
    @JsonProperty("reasoning") val reasoning: String = "",
)

private data class PerInstrumentDto(
    @JsonProperty("code") val code: String = "",
    @JsonProperty("sentiment") val sentiment: String = "NEUTRAL",
    @JsonProperty("score") val score: Double = 0.0,
)
