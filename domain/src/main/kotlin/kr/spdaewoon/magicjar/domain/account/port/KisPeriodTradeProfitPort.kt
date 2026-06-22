package kr.spdaewoon.magicjar.domain.account.port

import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * KIS 기간별매매손익현황조회(TTTC8715R) read-only port (2026-06-22 market-data 신설 — 개선 A).
 *
 * ### 왜 신규인가 (TTTC8494R 한계 정정)
 * 직전 #1097 의 [KisRealizedProfitPort](TTTC8494R, 주식잔고조회_실현손익) 는 **체결기준잔고(보유 행)** 기준이라
 * 당일 전량 청산(hldg_qty=0) 종목이 output1 에서 빠진다 → "당일 총 실현손익" 비교에 부적합(drift 발생 root).
 * TTTC8715R 은 HTS [0856] 기간별 매매손익 "종목별" 화면으로, **잔고 보유 여부와 무관하게 매도가 일어난 모든 종목**의
 * 실현손익·수수료·제세금을 매매 기준으로 반환한다 → 당일 전량 청산 종목까지 포함 = "당일 총 실현손익" 권위.
 *
 * ### KIS-first raw 출처 (헌법 §3 — 추측 0)
 * open-trading-api `examples_llm/domestic_stock/inquire_period_trade_profit/{inquire_period_trade_profit,chk_...}.py`
 *  - tr_id `TTTC8715R`, URL `/uapi/domestic-stock/v1/trading/inquire-period-trade-profit`, HTS [0856] "종목별".
 *  - 필수 파라미터: CANO·ACNT_PRDT_CD·SORT_DVSN·INQR_STRT_DT·INQR_END_DT·CBLC_DVSN(00=전체)·PDNO·CTX_AREA_FK100/NK100.
 *  - output1(종목별): trad_dt·pdno·prdt_name·sll_qty·sll_amt·rlzt_pfls(실현손익)·pfls_rt(손익률)·fee(수수료)·tl_tax(제세금).
 *  - output2(계좌합계): tot_rlzt_pfls(총실현손익)·sll_fee_smtl(매도수수료합계)·sll_tltx_smtl(매도제세금합계)·tot_fee·tot_tltx.
 *
 * ### REAL 전용
 * 공식 예제에 모의 TR 변형이 없다 → REAL 계좌 reconcile/검증 전용. 주문 hot path 에서 호출 금지.
 */
interface KisPeriodTradeProfitPort {
    fun fetchPeriodTradeProfit(
        userId: String,
        mode: TradingMode,
        from: LocalDate,
        to: LocalDate,
    ): KisPeriodTradeProfitSnapshot
}

/**
 * TTTC8715R 응답 스냅샷. output1 종목별 매매 행 + output2 계좌합계.
 *
 * KIS 의 output1 은 (매매일자×종목×매매구분) 단위 행이라, 같은 종목이 여러 행으로 쪼개질 수 있다(여러 날·여러 청산).
 * [byCode] 가 종목 단위로 합산한 결과 — 크로스체크 종목별 대조에 사용한다.
 */
data class KisPeriodTradeProfitSnapshot(
    val userId: String,
    val mode: TradingMode,
    val from: LocalDate,
    val to: LocalDate,
    /** output1 raw 행(매매일자×종목×매매구분 단위 — 원본 보존). */
    val rows: List<KisPeriodTradeProfitRow>,
    /** output2 계좌합계. */
    val summary: KisPeriodTradeProfitSummary?,
    val fetchedAt: Instant = Instant.now(),
) {
    /** 종목코드 단위로 실현손익·수수료·제세금을 합산. */
    fun byCode(): Map<String, KisPeriodTradeProfitCodeAgg> =
        rows
            .groupBy { it.code }
            .mapValues { (code, group) ->
                KisPeriodTradeProfitCodeAgg(
                    code = code,
                    name = group.firstOrNull { it.name != null }?.name,
                    realizedPnl = group.sumOrNull { it.realizedPnl },
                    fee = group.sumOrNull { it.fee },
                    tax = group.sumOrNull { it.tax },
                    sellQty = group.sumOrNull { it.sellQty },
                    sellAmount = group.sumOrNull { it.sellAmount },
                )
            }

    private fun List<KisPeriodTradeProfitRow>.sumOrNull(selector: (KisPeriodTradeProfitRow) -> BigDecimal?): BigDecimal? {
        val present = mapNotNull(selector)
        return if (present.isEmpty()) null else present.fold(BigDecimal.ZERO, BigDecimal::add)
    }
}

/**
 * output1 한 행 (매매일자×종목×매매구분 단위).
 *
 * @property realizedPnl rlzt_pfls — 실현손익(KIS 산정, fee·tax 반영 후 net 으로 추정 — 크로스체크에서 gross/net 양쪽 대조로 실증).
 * @property fee fee — 수수료. @property tax tl_tax — 제세금.
 */
data class KisPeriodTradeProfitRow(
    val tradeDate: String?,
    val code: String,
    val name: String?,
    val sellQty: BigDecimal?,
    val sellAmount: BigDecimal?,
    val realizedPnl: BigDecimal?,
    val profitRate: BigDecimal?,
    val fee: BigDecimal?,
    val tax: BigDecimal?,
    val loanInterest: BigDecimal?,
    val raw: Map<String, String> = emptyMap(),
)

/** 종목 단위 합산 결과(byCode). */
data class KisPeriodTradeProfitCodeAgg(
    val code: String,
    val name: String?,
    val realizedPnl: BigDecimal?,
    val fee: BigDecimal?,
    val tax: BigDecimal?,
    val sellQty: BigDecimal?,
    val sellAmount: BigDecimal?,
)

/**
 * output2 계좌합계.
 *
 * @property totalRealizedPnl tot_rlzt_pfls — 총실현손익(전 종목, 전량청산 포함). "당일 총 실현손익" 권위 값.
 * @property sellFeeTotal sll_fee_smtl — 매도수수료합계. @property sellTaxTotal sll_tltx_smtl — 매도제세금합계.
 */
data class KisPeriodTradeProfitSummary(
    val totalRealizedPnl: BigDecimal?,
    val sellFeeTotal: BigDecimal?,
    val sellTaxTotal: BigDecimal?,
    val totalFee: BigDecimal?,
    val totalTax: BigDecimal?,
    val totalProfitRate: BigDecimal?,
    val raw: Map<String, String> = emptyMap(),
)
