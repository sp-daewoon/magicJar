package com.mj.consumer.application.mock

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Mock 뉴스 감성 분석 발행기 설정.
 * 실 LLM(Anthropic/Gemini) 없이 AI 카드 시각 검증용.
 */
@ConfigurationProperties(prefix = "magicjar.market.mock-news")
data class MockNewsProperties(
    val enabled: Boolean = false,
    /** 1건 발행 간격 (ms). 기본 30초 — 실제 뉴스 빈도 가정. */
    val intervalMs: Long = 30_000L,
    /** 회전할 종목 코드 — symbols + 시각마다 1종목 골라 1건 publish. */
    val symbols: List<String> = listOf("005930", "000660", "035720", "005380", "051910"),
)
