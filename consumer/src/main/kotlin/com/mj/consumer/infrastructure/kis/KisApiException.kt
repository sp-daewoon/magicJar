package com.mj.consumer.infrastructure.kis

/**
 * KIS API 응답 에러 카테고리. spec §3 D10, §9.1.
 *
 * `category` 별 retry 정책:
 * - AUTH: 1회 토큰 invalidate+재발급 후 retry
 * - RATE_LIMIT / SERVER: exponential backoff 최대 3회
 * - 나머지: retry 없음 (즉시 OrderResult.REJECTED)
 *
 * batch 모듈의 `com.mj.batch.infrastructure.kis.KisApiException`은 시세 어댑터용으로
 * 별도 잔존(legacy). 후속 트랙에서 통합 예정 (D5 — KIS 외부 시스템 consumer 통합).
 */
enum class KisErrorCategory {
    AUTH,
    RATE_LIMIT,
    INSUFFICIENT_FUNDS,
    INVALID_ORDER,
    MARKET_CLOSED,
    SERVER,
    UNKNOWN,
    ;

    companion object {
        fun classify(
            httpStatus: Int,
            msgCd: String?,
        ): KisErrorCategory =
            when {
                httpStatus == 401 || msgCd in AUTH_CODES -> AUTH
                httpStatus == 429 || msgCd in RATE_CODES -> RATE_LIMIT
                httpStatus in 500..599 -> SERVER
                msgCd in FUNDS_CODES -> INSUFFICIENT_FUNDS
                msgCd in INVALID_CODES -> INVALID_ORDER
                msgCd in MARKET_CLOSED_CODES -> MARKET_CLOSED
                else -> UNKNOWN
            }

        // KIS 공식 코드 일부 — 운영하면서 카테고리 추가 (운영 가이드 §3.5에 매핑 표 명시 예정)
        private val AUTH_CODES = setOf("EGW00121", "EGW00123", "OPSP9999")
        private val RATE_CODES = setOf("EGW00201")
        private val FUNDS_CODES = setOf("APBK0918", "APBK0919")
        private val INVALID_CODES = setOf("APBK0067", "APBK0068", "APBK1535") // FUNDS와 별도
        private val MARKET_CLOSED_CODES = setOf("APBK0086")
    }
}

class KisApiException(
    val rtCd: String,
    val msgCd: String,
    val msgRaw: String,
    val httpStatus: Int,
    val category: KisErrorCategory = KisErrorCategory.classify(httpStatus, msgCd),
) : RuntimeException("[$category][rt=$rtCd msg=$msgCd] $msgRaw")
