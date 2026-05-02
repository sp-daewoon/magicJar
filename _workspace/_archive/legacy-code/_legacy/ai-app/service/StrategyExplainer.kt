package com.mj.domain.ai.service

import com.mj.domain.ai.prompt.PromptLoader
import com.mj.domain.common.BacktestResult
import com.mj.domain.ai.port.AiClient
import org.springframework.stereotype.Service
import java.util.Optional

/**
 * 백테스트 결과를 한국어 마크다운 리포트로 설명.
 * 출력은 단순 텍스트(마크다운)이며 구조화 JSON이 아니다.
 */
@Service
class StrategyExplainer(
    private val aiClient: AiClient,
    private val promptLoader: PromptLoader,
) {
    companion object {
        const val PROMPT_PATH = "prompts/strategy-explainer.st"
    }

    fun explain(
        result: BacktestResult,
        riskTolerance: String? = null,
        preferredSectors: List<String> = emptyList(),
    ): Optional<String> {
        val system = promptLoader.load(PROMPT_PATH)
        val user =
            buildString {
                appendLine("# 백테스트 결과")
                appendLine("- 전략: ${result.strategy.strategyId} (v${result.strategy.version})")
                appendLine("- 기간: ${result.from} ~ ${result.to}")
                appendLine("- 총수익률: ${result.metrics.totalReturn}")
                appendLine("- CAGR: ${result.metrics.cagr}")
                appendLine("- MDD: ${result.metrics.mdd}")
                appendLine("- 샤프: ${result.metrics.sharpe}")
                appendLine("- 승률: ${result.metrics.winRate}")
                appendLine("- 거래 수: ${result.metrics.tradeCount}")
                appendLine()
                appendLine("# 사용자 프로필")
                appendLine("- 리스크 성향: ${riskTolerance ?: "미지정"}")
                appendLine("- 선호 섹터: ${if (preferredSectors.isEmpty()) "미지정" else preferredSectors.joinToString(",")}")
            }
        val text = aiClient.chat(system, user) ?: return Optional.empty()
        return Optional.of(text.trim())
    }
}
