package com.mj.domain.ai.config

import com.mj.domain.ai.port.AiClient
import org.springframework.stereotype.Component

/**
 * AI 기능 활성 여부를 노출하는 플래그.
 * 주입된 `AiClient`가 NoOp가 아니면 enabled=true로 본다.
 *
 * app의 `NoOpAiClient`는 object 싱글톤이므로 클래스 비교로 판별한다.
 */
@Component
class AiFeatureFlag(
    private val aiClient: AiClient,
) {
    val enabled: Boolean
        get() = aiClient::class.qualifiedName != NO_OP_AI_CLIENT_CLASS_NAME

    companion object {
        const val NO_OP_AI_CLIENT_CLASS_NAME: String = "kr.spdaewoon.magicjar.config.NoOpAiClient"
    }
}
