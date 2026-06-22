package kr.spdaewoon.magicjar.domain.account.port

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * KIS 실현손익 ↔ 우리 FIFO 계산 크로스체크 결과 (2026-06-22 market-data 신설 · 개선 A로 TTTC8715R 승격).
 *
 * ### 목적
 * 우리 성과 API(PerformanceService.buildPnlDailyFromTrades)가 우리 DB 체결(order_execution/order_request)에서
 * FIFO 로 계산한 실현손익이 **KIS 실제 실현손익** 과 맞는지 증명한다. "우리 숫자가 진짜인가" 를
 * KIS 권위로 대조하는 프로덕션 토대 — 애매하면 KIS 와 대조한다(에이전틱 검증 표준).
 *
 * ### 권위 endpoint 정정 (개선 A — 2026-06-22)
 * 직전 #1097 은 TTTC8494R(주식잔고조회_실현손익, HTS [0800] 체결기준잔고)만 썼는데, 이는 **보유 행 기준**이라
 * 당일 전량 청산(hldg_qty=0) 종목이 빠져 "당일 총 실현손익" 비교에 부적합 → drift +5,879 의 root.
 * 개선 A 는 **TTTC8715R(기간별매매손익현황조회, HTS [0856] 종목별)** 을 **1차 권위**로 승격.
 * 매도가 발생한 모든 종목(전량청산 포함)의 실현손익·수수료·제세금을 매매 기준으로 반환 → 당일 총 실현손익 정합.
 * TTTC8494R 은 real_evaluation·체결기준잔고 참고용으로 병행(필드 [kisAccountRealizedPnl_8494] 보존).
 *
 * ### KIS-first raw 출처 (헌법 §3 — 추측 0)
 * raw: open-trading-api `examples_llm/domestic_stock/inquire_period_trade_profit/{inquire_period_trade_profit,chk_...}.py`
 *  - tr_id `TTTC8715R`, URL `/uapi/domestic-stock/v1/trading/inquire-period-trade-profit`, HTS [0856] "종목별".
 *  - output1 종목별: `rlzt_pfls`(실현손익)·`fee`(수수료)·`tl_tax`(제세금)·`sll_qty`(매도수량) / output2 계좌:
 *    `tot_rlzt_pfls`(총실현손익)·`sll_fee_smtl`(매도수수료합계)·`sll_tltx_smtl`(매도제세금합계).
 *
 * ### 우리 계산과의 비교 축 (실측으로 net/gross 어느 쪽이 KIS rlzt_pfls 정의인지 판정)
 *  - KIS `rlzt_pfls` 가 fee·tax 반영 net 인지 gross 인지는 화면 정의가 명시 부족 → **우리 net·gross 둘 다 산출해
 *    더 작은 drift 쪽을 실측으로 확정**한다(driftCauses 에 어느 정의가 맞았는지 기록).
 *  - 우리 fee/tax 가 0(미캡처)이면 net=gross → 개선 A 의 fee/tax 캡처로 우리 net 을 정확화한다.
 *
 * @property matched 계좌합계 drift(net/gross 중 작은 쪽)가 tolerance 이내인지 (정확 일치 기대 — 원 단위)
 */
data class RealizedPnlCrossCheck(
    val userId: String,
    val period: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    /** **TTTC8715R** output2 총실현손익(tot_rlzt_pfls) — 1차 권위(전량청산 포함). null = 호출 실패. */
    val kisPeriodTotalRealizedPnl: BigDecimal?,
    /** TTTC8715R output2 매도수수료합계(sll_fee_smtl). */
    val kisPeriodSellFee: BigDecimal?,
    /** TTTC8715R output2 매도제세금합계(sll_tltx_smtl). */
    val kisPeriodSellTax: BigDecimal?,
    /** TTTC8494R output2 계좌 실현손익 합계(rlzt_pfls) — 보유 행 기준(참고). null 가능. */
    val kisAccountRealizedPnl: BigDecimal?,
    /** KIS(8494R) output2 실평가손익(real_evlu_pfls) — 참고용. */
    val kisRealEvaluationPnl: BigDecimal?,
    /** 우리 net 실현손익(매도 비용 차감) — PerformanceService 와 동일 정의. */
    val ourNetRealizedPnl: BigDecimal,
    /** 우리 gross 실현손익(비용 미차감). */
    val ourGrossRealizedPnl: BigDecimal,
    /** 우리 매도 비용 합계(fee+tax) — gross↔net 차이 설명. */
    val ourTotalCost: BigDecimal,
    /** TTTC8715R 총실현손익 - 우리 net. null = KIS 합계 부재. */
    val periodDriftNet: BigDecimal?,
    /** TTTC8715R 총실현손익 - 우리 gross. */
    val periodDriftGross: BigDecimal?,
    /** TTTC8494R - 우리 gross (참고 — 보유 행 기준이라 semantics 차이). */
    val accountDriftGross: BigDecimal?,
    /** TTTC8494R - 우리 net (참고). */
    val accountDriftNet: BigDecimal?,
    /** tolerance(원). drift 절대값이 이 값 이하이면 matched=true. */
    val toleranceWon: BigDecimal,
    val matched: Boolean,
    /** 어느 정의(NET|GROSS)가 KIS 와 일치했는지 — 실측 판정 결과. */
    val matchedDefinition: String?,
    /** drift 발생 시 원인 분류 매트릭스(미체결 지연·비용·당일/누계·페이지네이션 등). */
    val driftCauses: List<String>,
    /** 종목별 대조 행. */
    val perCode: List<RealizedPnlCrossCheckRow>,
    /** KIS 호출 성공 여부(rt_cd=0). false 면 errorMessage 참조. */
    val kisCallOk: Boolean,
    val errorMessage: String? = null,
    val checkedAt: Instant = Instant.now(),
)

/**
 * 종목별 KIS ↔ 우리 실현손익 대조 행.
 *
 * @property presentInKis KIS(TTTC8715R) output1 에 이 종목 행이 존재했는지.
 *   TTTC8715R 은 매도 발생 종목 전부를 잡으므로 우리 매도가 있으면 정상적으로 true 여야 한다(false 면 누락 신호).
 */
data class RealizedPnlCrossCheckRow(
    val code: String,
    val name: String?,
    /** TTTC8715R output1 종목 실현손익(rlzt_pfls). */
    val kisRealizedPnl: BigDecimal?,
    /** TTTC8715R output1 종목 수수료(fee). */
    val kisFee: BigDecimal?,
    /** TTTC8715R output1 종목 제세금(tl_tax). */
    val kisTax: BigDecimal?,
    /** 우리 gross 실현손익(비용 미차감). */
    val ourGrossRealizedPnl: BigDecimal,
    /** 우리 net 실현손익(비용 차감). */
    val ourNetRealizedPnl: BigDecimal,
    /** KIS - 우리 net (TTTC8715R rlzt_pfls 가 net 정의일 때 0 기대). */
    val driftNet: BigDecimal?,
    /** KIS - 우리 gross. */
    val driftGross: BigDecimal?,
    val presentInKis: Boolean,
    val matched: Boolean,
)
