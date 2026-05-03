package com.mj.domain.market.corp_action.port

import com.mj.domain.market.corp_action.CorpAction
import java.time.LocalDate

/**
 * KIS Corp Action 조회 포트 (ADR 0038).
 *
 * KIS 예탁원정보 API를 통해 Corp Action을 조회한다.
 *
 * 구현체:
 * - dev·prd profile: RealKisCorpActionAdapter (실제 KIS REST API 호출)
 * - mock·stub-kis profile: StubKisCorpActionAdapter (고정 fixture 반환)
 *
 * 모든 조회는 ex_date 기준 범위 조회를 지원한다.
 */
interface KisCorpActionPort {
    /**
     * 액면분할 조회 (예탁원정보 — 액면교체일정).
     * KIS trId: HHKDB669105C0
     */
    fun fetchSplits(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.Split>

    /**
     * 무상증자 조회 (예탁원정보 — 무상증자일정).
     * KIS trId: HHKDB669101C0
     */
    fun fetchBonusIssues(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.BonusIssue>

    /**
     * 유상증자 조회 (예탁원정보 — 유상증자일정).
     * KIS trId: HHKDB669100C0
     */
    fun fetchRightsIssues(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.RightsIssue>

    /**
     * 배당 조회 (예탁원정보 — 배당일정).
     * KIS trId: HHKDB669102C0
     */
    fun fetchDividends(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.Dividend>

    /**
     * 합병·분할 조회 (예탁원정보 — 합병_분할일정).
     * KIS trId: HHKDB669104C0
     * 합병(Merger)과 분할(Spinoff) 둘 다 반환 (type으로 구분).
     */
    fun fetchMergersAndSpinoffs(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>

    /**
     * 특정 종목의 모든 Corp Action을 조회한다 (5종 합산).
     * 순서: exDate ASC.
     */
    fun fetchAll(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> =
        buildList {
            addAll(fetchSplits(instrumentCode, from, to))
            addAll(fetchBonusIssues(instrumentCode, from, to))
            addAll(fetchRightsIssues(instrumentCode, from, to))
            addAll(fetchDividends(instrumentCode, from, to))
            addAll(fetchMergersAndSpinoffs(instrumentCode, from, to))
        }.sortedBy { it.exDate }
}
