package com.mj.consumer.application.ai.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.consumer.application.ai.prompt.PromptLoader
import com.mj.domain.ai.port.AiClient
import com.mj.domain.ai.util.JsonMapper
import com.mj.domain.common.Position
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.Optional

/**
 * 포트폴리오 리밸런싱 조언.
 */
@Service
class RebalancingAdvisor(
    private val aiClient: AiClient,
    private val promptLoader: PromptLoader,
) {
    private val log = LoggerFactory.getLogger(RebalancingAdvisor::class.java)

    companion object {
        const val PROMPT_PATH = "prompts/rebalancing-advisor.st"
        const val SCHEMA = """
            {
              "actions": [
                {"code":"005930","action":"REDUCE","deltaQty":-5,"rationale":"..."}
              ],
              "riskBefore": {"concentration": 0.45},
              "riskAfter":  {"concentration": 0.28},
              "rationale": "전체 조정 요약 (한국어 200자 이내)",
              "reasoning": "상세 추론 (한국어 400자 이내)"
            }
        """
    }

    fun advise(
        positions: List<Position>,
        totalValue: BigDecimal,
        targetAllocation: Map<String, BigDecimal>,
        marketSnapshot: String = "",
    ): Optional<RebalancingSuggestion> {
        val system = promptLoader.load(PROMPT_PATH)
        val user =
            buildString {
                appendLine("# 현재 포지션")
                positions.forEach {
                    appendLine("- ${it.code}: qty=${it.qty}, avgPrice=${it.avgPrice}")
                }
                appendLine("# 포트폴리오 가치(KRW)")
                appendLine("- total: $totalValue")
                appendLine("# 목표 배분")
                targetAllocation.forEach { (k, v) -> appendLine("- $k: $v") }
                if (marketSnapshot.isNotBlank()) {
                    appendLine("# 시장 스냅샷")
                    appendLine(marketSnapshot)
                }
            }
        val raw = aiClient.chatJson(system, user, SCHEMA) ?: return Optional.empty()
        val parsed =
            runCatching {
                val dto = JsonMapper.instance.readValue(raw, AdvisorDto::class.java)
                RebalancingSuggestion(
                    actions =
                        dto.actions.map {
                            RebalanceAction(
                                code = it.code,
                                action = it.action,
                                deltaQty = it.deltaQty,
                                rationale = it.rationale,
                            )
                        },
                    riskBefore = dto.riskBefore?.concentration?.let { BigDecimal(it.toString()) },
                    riskAfter = dto.riskAfter?.concentration?.let { BigDecimal(it.toString()) },
                    rationale = dto.rationale,
                    reasoning = dto.reasoning,
                    parsingFailed = false,
                )
            }.getOrElse {
                log.warn("RebalancingAdvisor parse failed: {}", it.message)
                RebalancingSuggestion(
                    actions = emptyList(),
                    riskBefore = null,
                    riskAfter = null,
                    rationale = "",
                    reasoning = "parsing_failed",
                    parsingFailed = true,
                )
            }
        return Optional.of(parsed)
    }
}

data class RebalancingSuggestion(
    val actions: List<RebalanceAction>,
    val riskBefore: BigDecimal?,
    val riskAfter: BigDecimal?,
    val rationale: String,
    val reasoning: String,
    val parsingFailed: Boolean = false,
)

data class RebalanceAction(
    val code: String,
    val action: String,
    val deltaQty: Int,
    val rationale: String,
)

private data class AdvisorDto(
    @JsonProperty("actions") val actions: List<ActionDto> = emptyList(),
    @JsonProperty("riskBefore") val riskBefore: RiskDto? = null,
    @JsonProperty("riskAfter") val riskAfter: RiskDto? = null,
    @JsonProperty("rationale") val rationale: String = "",
    @JsonProperty("reasoning") val reasoning: String = "",
)

private data class ActionDto(
    @JsonProperty("code") val code: String = "",
    @JsonProperty("action") val action: String = "HOLD",
    @JsonProperty("deltaQty") val deltaQty: Int = 0,
    @JsonProperty("rationale") val rationale: String = "",
)

private data class RiskDto(
    @JsonProperty("concentration") val concentration: Double? = null,
)
