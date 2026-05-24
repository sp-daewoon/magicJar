package kr.spdaewoon.magicjar.api.ai.explain

import kr.spdaewoon.magicjar.api.persistence.AiCommentLogJpaEntity
import kr.spdaewoon.magicjar.api.persistence.AiCommentLogJpaRepository
import kr.spdaewoon.magicjar.domain.ai.AiCommentType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * `ai_comment_log` (V49) 기록 헬퍼.
 *
 * 사용 처:
 * - `AiExplainController` graceful refuse 분기 — refuse 행 기록 (graceful=TRUE / tokens=0 / cost=0)
 * - consumer `AiCommentDispatcherListener` 정상 응답 분기 — Spring AI 응답 metric 기록
 *
 * 본 클래스는 api 모듈 내 graceful refuse 만 책임 (Kafka publish 전 거부).
 * consumer 모듈은 별도 recorder (api JPA 의존 회피) — `AiCommentLogRecorder` 인터페이스 없이 별도 구현.
 *
 * ADR 0051 default fun 함정 회피 정합 — Repository interface 0건 body fun.
 */
@Component
class AiCommentLogRecorder(
    private val repository: AiCommentLogJpaRepository,
) {
    private val log = LoggerFactory.getLogger(AiCommentLogRecorder::class.java)

    /**
     * Graceful refuse 기록 (LLM 호출 0건 / tokens=0 / cost=0).
     *
     * 호출 시점: Controller KillSwitch / TokenLimit / prompt 미존재 분기.
     */
    fun recordRefusal(
        requestId: UUID,
        userId: Long,
        type: AiCommentType,
        promptTemplate: String,
        refuseCode: String,
        userMessage: String,
    ) {
        try {
            val entity =
                AiCommentLogJpaEntity(
                    requestId = requestId,
                    userId = userId,
                    commentType = type.name,
                    provider = "none",
                    model = "none",
                    promptTemplate = promptTemplate,
                    tokensIn = 0,
                    tokensOut = 0,
                    costUsd = BigDecimal.ZERO,
                    latencyMs = 0L,
                    cached = false,
                    graceful = true,
                    errorMessage = "$refuseCode: $userMessage",
                )
            repository.save(entity)
        } catch (e: Exception) {
            // audit 기록 실패가 사용자 응답을 차단하지 않도록 — 본체 흐름 영향 0 (ADR 0026 §2-6)
            log.warn(
                "ai_comment_log refuse 기록 실패 — requestId={} userId={} refuseCode={} err={}",
                requestId,
                userId,
                refuseCode,
                e.message,
            )
        }
    }
}
