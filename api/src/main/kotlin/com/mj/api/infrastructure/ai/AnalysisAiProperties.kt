package com.mj.api.infrastructure.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `magicjar.ai.*` — AI 분석 보조 옵션 (사용자 트리거 한정, ADR 0046).
 */
@ConfigurationProperties(prefix = "magicjar.ai")
data class AnalysisAiProperties(
    /** 마스터 토글 — false면 어떤 어댑터도 등록되지 않고 controller 503. default false (mock 환경 비용 0). */
    val enabled: Boolean = false,
    /** provider — `anthropic` (1차), 향후 `gemini` · `openai`. */
    val provider: String = "anthropic",
    /** 모델 ID. */
    val model: String = "claude-sonnet-4-5",
    /** mock-mode: `auto`(키 없으면 mock) · `mock`(강제 mock) · `real`(키 필수). */
    val mockMode: String = "auto",
    /** 호출 타임아웃 (controller 응답 보호). */
    val timeout: Duration = Duration.ofSeconds(45),
    /** 입력 token당 USD 단가 (audit 로그 cost_estimate 산출). */
    val pricePerInputToken: Double = 0.000_003,
    /** 출력 token당 USD 단가. */
    val pricePerOutputToken: Double = 0.000_015,
    /** 응답 max_tokens. */
    val maxTokens: Int = 1024,
    /** temperature. */
    val temperature: Double = 0.4,
)
