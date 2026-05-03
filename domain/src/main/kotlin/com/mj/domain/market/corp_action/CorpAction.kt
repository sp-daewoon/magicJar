package com.mj.domain.market.corp_action

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 기업 행위(Corp Action) 도메인 모델 (ADR 0038).
 *
 * sealed class 계층으로 6가지 Corp Action 타입을 표현한다.
 * ex_date 기준 retroactive 주가 보정에 사용되며, backtest/screener에서 룩어헤드를 차단한다.
 *
 * 규칙:
 * - ex_date 이전 캔들 → raw 그대로 (보정 전)
 * - ex_date 이후 캔들 → adjusted (보정 후)
 * - retroactive 재계산은 CorpActionBackfillJob에서만 수행 (backtest 런타임 X)
 */
sealed class CorpAction {
    abstract val id: Long?
    abstract val instrumentCode: String
    abstract val exDate: LocalDate
    abstract val source: CorpActionSource
    abstract val appliedAt: Instant?

    /**
     * 액면분할 (Stock Split).
     *
     * ex) 삼성전자 2024-04-26 50:1 액면분할
     * ratio = 50.0 → 1주 → 50주, 주가 ÷ 50
     */
    data class Split(
        override val id: Long? = null,
        override val instrumentCode: String,
        override val exDate: LocalDate,
        override val source: CorpActionSource = CorpActionSource.KIS,
        override val appliedAt: Instant? = null,
        /** 1주가 N주로 분할되는 비율 (e.g. 50.0 = 1:50) */
        val ratio: BigDecimal,
    ) : CorpAction()

    /**
     * 무상증자 (Bonus Issue).
     *
     * 비율만큼 신주를 무상 배분. 주가 × (1/(1+ratio)) 보정.
     * ex) 무상증자비율 0.1 → 10주당 1주 무상 배분
     */
    data class BonusIssue(
        override val id: Long? = null,
        override val instrumentCode: String,
        override val exDate: LocalDate,
        override val source: CorpActionSource = CorpActionSource.KIS,
        override val appliedAt: Instant? = null,
        /** 무상 배분 비율 (e.g. 0.1 = 10% 무상) */
        val ratio: BigDecimal,
    ) : CorpAction()

    /**
     * 유상증자 (Rights Issue / Paid-in Capital Increase).
     *
     * ex) 청약가격 하회 시 권리락 발생 → 주가 보정.
     */
    data class RightsIssue(
        override val id: Long? = null,
        override val instrumentCode: String,
        override val exDate: LocalDate,
        override val source: CorpActionSource = CorpActionSource.KIS,
        override val appliedAt: Instant? = null,
        /** 청약 비율 (e.g. 0.2 = 20% 신주 발행) */
        val ratio: BigDecimal,
        /** 청약 가격 */
        val subscriptionPrice: BigDecimal,
    ) : CorpAction()

    /**
     * 현금 배당 (Cash Dividend).
     *
     * 배당락일 기준 주가 하락 보정.
     */
    data class Dividend(
        override val id: Long? = null,
        override val instrumentCode: String,
        override val exDate: LocalDate,
        override val source: CorpActionSource = CorpActionSource.KIS,
        override val appliedAt: Instant? = null,
        /** 주당 현금 배당금 */
        val cashAmount: BigDecimal,
        /** 주식 배당 비율 (없으면 ZERO) */
        val stockRatio: BigDecimal = BigDecimal.ZERO,
    ) : CorpAction()

    /**
     * 합병 (Merger).
     *
     * 합병 후 존속법인 종목코드로 매핑.
     */
    data class Merger(
        override val id: Long? = null,
        override val instrumentCode: String,
        override val exDate: LocalDate,
        override val source: CorpActionSource = CorpActionSource.KIS,
        override val appliedAt: Instant? = null,
        /** 합병 비율 (소멸법인 1주 → 존속법인 mergeRatio주) */
        val mergeRatio: BigDecimal,
        /** 존속법인 종목코드 */
        val survivingCode: String? = null,
    ) : CorpAction()

    /**
     * 분할 (Spinoff / Company Split).
     *
     * 분할로 인한 주가 조정.
     */
    data class Spinoff(
        override val id: Long? = null,
        override val instrumentCode: String,
        override val exDate: LocalDate,
        override val source: CorpActionSource = CorpActionSource.KIS,
        override val appliedAt: Instant? = null,
        /** 분할 비율 (e.g. 0.3 = 30% 분리) */
        val splitRatio: BigDecimal,
    ) : CorpAction()
}

enum class CorpActionSource {
    /** 한국투자증권 OpenAPI */
    KIS,

    /** DART 전자공시 */
    DART,

    /** 수동 입력 */
    MANUAL,
}
