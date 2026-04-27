package com.mj.consumer.application.ai.service

import com.mj.consumer.application.ai.prompt.PromptLoader
import com.mj.domain.ai.port.AiClient
import com.mj.domain.journal.TradeJournalEntry
import org.springframework.stereotype.Service
import java.util.Optional

/**
 * 매매일지 항목에 대한 코칭 텍스트(markdown) 생성.
 * `AiReviewRequestedEvent` 리스너가 이 서비스를 호출한다.
 */
@Service
class TradeReviewer(
    private val aiClient: AiClient,
    private val promptLoader: PromptLoader,
) {
    companion object {
        const val PROMPT_PATH = "prompts/trade-reviewer.st"
    }

    fun review(entry: TradeJournalEntry): Optional<String> {
        val system = promptLoader.load(PROMPT_PATH)
        val user =
            buildString {
                appendLine("# 매매 기록")
                appendLine("- id: ${entry.id}")
                appendLine("- 종목: ${entry.code} ${entry.name}")
                appendLine("- 모드: ${entry.mode}")
                appendLine("- 방향: ${entry.side}")
                appendLine("- 전략: ${entry.strategyId}")
                appendLine("- 진입 시각: ${entry.entryAt}")
                appendLine("- 진입 가격: ${entry.entryPrice}")
                appendLine("- 진입 수량: ${entry.entryQty}")
                appendLine("- 진입 근거: ${entry.entryReason}")
                if (entry.exitAt != null) {
                    appendLine("- 청산 시각: ${entry.exitAt}")
                    appendLine("- 청산 가격: ${entry.exitPrice}")
                    appendLine("- 청산 수량: ${entry.exitQty}")
                    appendLine("- 청산 근거: ${entry.exitReason ?: "-"}")
                    appendLine("- 손익: ${entry.pnl ?: "-"} (${entry.pnlRate ?: "-"}%)")
                } else {
                    appendLine("- 청산 전 (보유 중)")
                }
                appendLine()
                appendLine("## 진입 스냅샷 (지표)")
                entry.entrySnapshot.forEach { (k, v) -> appendLine("- $k: $v") }
                if (entry.exitSnapshot.isNotEmpty()) {
                    appendLine()
                    appendLine("## 청산 스냅샷 (지표)")
                    entry.exitSnapshot.forEach { (k, v) -> appendLine("- $k: $v") }
                }
                if (entry.tags.isNotEmpty()) appendLine("- 태그: ${entry.tags.joinToString(",")}")
                if (!entry.userNote.isNullOrBlank()) appendLine("- 사용자 메모: ${entry.userNote}")
            }
        val text = aiClient.chat(system, user) ?: return Optional.empty()
        return Optional.of(text.trim())
    }
}
