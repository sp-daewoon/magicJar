package com.mj.domain.strategy.backtest

import com.mj.domain.common.Market
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 백테스트 비용 모델 (master-architecture §3-5).
 *
 * 한국 주식 매매 비용 3원소:
 * - **수수료** (commission): 매수·매도 모두 발생. 위탁수수료 + 유관기관 비용. 마스터 기본 0.015% (KIS 모의 평균).
 * - **거래세** (transaction tax): 매도 시에만. KOSPI 0.18% (증권거래세 0.05% + 농어촌특별세 0.15%; 합계 0.18%).
 *   KOSDAQ 0.18% 동일. ETF·해외주는 다름(현재 범위 외).
 * - **슬리피지** (slippage): 시장가·체결 지연으로 인한 체결가 미스매치. 마스터 기본 5bp = 0.05%.
 *   매수 시 + 슬리피지(불리하게 비싸게), 매도 시 - 슬리피지(불리하게 싸게).
 *
 * 모든 계산은 `BigDecimal` + `RoundingMode.HALF_UP`.
 *
 * Floating point 사용 금지(마스터 §3 결정론).
 */
data class CostModel(
    /** 수수료율 (예: 0.00015 = 0.015%). 매수·매도 양쪽 적용. */
    val commissionRate: BigDecimal = BigDecimal("0.00015"),
    /** KOSPI 거래세율 (예: 0.0018 = 0.18%). 매도 시에만. */
    val taxRateKospi: BigDecimal = BigDecimal("0.0018"),
    /** KOSDAQ 거래세율 (현재 0.18%, 추후 시장별 분리 위해 별도 필드). */
    val taxRateKosdaq: BigDecimal = BigDecimal("0.0018"),
    /** 슬리피지 (예: 0.0005 = 0.05% = 5bp). 매수=상향, 매도=하향. */
    val slippageRate: BigDecimal = BigDecimal("0.0005"),
) {
    init {
        require(commissionRate.signum() >= 0) { "commissionRate must be >= 0" }
        require(taxRateKospi.signum() >= 0) { "taxRateKospi must be >= 0" }
        require(taxRateKosdaq.signum() >= 0) { "taxRateKosdaq must be >= 0" }
        require(slippageRate.signum() >= 0) { "slippageRate must be >= 0" }
    }

    /** 매수 체결가에 슬리피지 적용 (불리하게 상향). */
    fun applyBuySlippage(price: BigDecimal): BigDecimal =
        price.multiply(BigDecimal.ONE.add(slippageRate), MC).setScale(2, RoundingMode.HALF_UP)

    /** 매도 체결가에 슬리피지 적용 (불리하게 하향). */
    fun applySellSlippage(price: BigDecimal): BigDecimal =
        price.multiply(BigDecimal.ONE.subtract(slippageRate), MC).setScale(2, RoundingMode.HALF_UP)

    /** 매수 시 수수료 (gross × commissionRate). */
    fun buyCommission(gross: BigDecimal): BigDecimal = gross.multiply(commissionRate, MC)

    /** 매도 시 수수료 + 거래세 (gross × commissionRate + gross × taxRate). */
    fun sellCost(
        gross: BigDecimal,
        market: Market,
    ): SellCost {
        val commission = gross.multiply(commissionRate, MC)
        val taxRate = if (market == Market.KOSDAQ) taxRateKosdaq else taxRateKospi
        val tax = gross.multiply(taxRate, MC)
        return SellCost(commission = commission, tax = tax)
    }

    data class SellCost(
        val commission: BigDecimal,
        val tax: BigDecimal,
    ) {
        val total: BigDecimal get() = commission.add(tax)
    }

    companion object {
        private val MC = MathContext(12, RoundingMode.HALF_UP)

        /** 마스터 기본 비용 모델 (§3-5). */
        val DEFAULT = CostModel()

        /** 비용 0 (백테스트 디버깅용). */
        val ZERO =
            CostModel(
                commissionRate = BigDecimal.ZERO,
                taxRateKospi = BigDecimal.ZERO,
                taxRateKosdaq = BigDecimal.ZERO,
                slippageRate = BigDecimal.ZERO,
            )
    }
}
