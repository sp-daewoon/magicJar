package com.mj.consumer.application.mock

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Mock tick generator 설정.
 * KIS WebSocket 연동 전 frontend·strategy 검증용 dev 편의 기능.
 */
@ConfigurationProperties(prefix = "magicjar.market.mock-tick")
data class MockTickProperties(
    /** 활성화 여부. 기본 false — 실 KIS 연동 전 dev 환경에서만 true. */
    val enabled: Boolean = false,
    /** 1 tick 발행 간격 (ms). 기본 1000ms. */
    val intervalMs: Long = 1_000L,
    /** 발행 대상 종목 코드. 비어있으면 instrument catalog 전체. */
    val symbols: List<String> = listOf("005930", "000660", "035720", "005380", "051910"),
    /** 종목별 시작 가격(원). 미설정 시 60000. */
    val basePrices: Map<String, Long> =
        mapOf(
            "005930" to 60_000L, // 삼성전자
            "000660" to 130_000L, // SK하이닉스
            "035720" to 190_000L, // 카카오
            "005380" to 220_000L, // 현대차
            "051910" to 380_000L, // LG화학
        ),
    /** ±변동률(%) — 0.5 = ±0.5% sin 변동. */
    val volatilityPercent: Double = 0.5,
)
