package com.mj.domain.ai.port

/**
 * LLM 추상 포트. 구현체는 ai 모듈이 제공한다 (Spring AI ChatClient 기반).
 * API 키 미설정 시 `NoOpAiClient`가 주입되며 모든 메서드가 `null`을 반환해 호출 측이 AI 기능을 비활성 상태로 다룰 수 있다.
 */
interface AiClient {
    /**
     * 일반 텍스트 응답. 실패·비활성 시 `null`.
     */
    fun chat(
        systemPrompt: String,
        userMessage: String,
    ): String?

    /**
     * JSON 스키마를 강제하는 응답. 실패·비활성 시 `null`.
     * `schema`는 JSON Schema 또는 자연어 스키마 지시문(구현체가 결정).
     */
    fun chatJson(
        systemPrompt: String,
        userMessage: String,
        schema: String,
    ): String?
}
