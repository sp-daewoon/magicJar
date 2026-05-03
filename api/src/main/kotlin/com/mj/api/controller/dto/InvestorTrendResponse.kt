package com.mj.api.controller.dto

import com.mj.domain.market.InvestorTrend
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

/**
 * 일자별 투자자 동향 응답 DTO.
 * KIS FHKST01010900 (inquire-investor) 기반.
 */
@Schema(description = "일자별 외국인·기관·개인 동향 응답")
data class InvestorTrendResponse(
    @Schema(description = "종목 코드", example = "005930")
    val code: String,
    @Schema(description = "영업일 (yyyyMMdd)", example = "20260430")
    val bizDate: String,
    @Schema(description = "종가 (원)", example = "220500")
    val closePrice: BigDecimal,
    @Schema(description = "전일 대비 (원, 부호 포함)", example = "-5500")
    val priceChange: BigDecimal,
    @Schema(description = "전일 대비 부호 코드 (1=상한/2=상승/3=보합/4=하한/5=하락)", example = "5")
    val priceChangeSign: String,
    @Schema(description = "개인 순매수 수량 (주, 음수=순매도)", example = "617606")
    val personNetBuyQty: Long,
    @Schema(description = "외국인 순매수 수량 (주, 음수=순매도)", example = "-840944")
    val foreignNetBuyQty: Long,
    @Schema(description = "기관 순매수 수량 (주, 음수=순매도)", example = "158634")
    val orgNetBuyQty: Long,
    @Schema(description = "개인 순매수 금액 (백만원)", example = "129042")
    val personNetBuyAmt: BigDecimal,
    @Schema(description = "외국인 순매수 금액 (백만원)", example = "-173923")
    val foreignNetBuyAmt: BigDecimal,
    @Schema(description = "기관 순매수 금액 (백만원)", example = "30727")
    val orgNetBuyAmt: BigDecimal,
    @Schema(description = "개인 매수 수량 (주)", example = "6483445")
    val personBuyVol: Long,
    @Schema(description = "외국인 매수 수량 (주)", example = "7619033")
    val foreignBuyVol: Long,
    @Schema(description = "기관 매수 수량 (주)", example = "7868939")
    val orgBuyVol: Long,
    @Schema(description = "개인 매도 수량 (주)", example = "5865839")
    val personSellVol: Long,
    @Schema(description = "외국인 매도 수량 (주)", example = "8459977")
    val foreignSellVol: Long,
    @Schema(description = "기관 매도 수량 (주)", example = "7710305")
    val orgSellVol: Long,
)

fun InvestorTrend.toResponse(): InvestorTrendResponse =
    InvestorTrendResponse(
        code = code,
        bizDate = bizDate,
        closePrice = closePrice,
        priceChange = priceChange,
        priceChangeSign = priceChangeSign,
        personNetBuyQty = personNetBuyQty,
        foreignNetBuyQty = foreignNetBuyQty,
        orgNetBuyQty = orgNetBuyQty,
        personNetBuyAmt = personNetBuyAmt,
        foreignNetBuyAmt = foreignNetBuyAmt,
        orgNetBuyAmt = orgNetBuyAmt,
        personBuyVol = personBuyVol,
        foreignBuyVol = foreignBuyVol,
        orgBuyVol = orgBuyVol,
        personSellVol = personSellVol,
        foreignSellVol = foreignSellVol,
        orgSellVol = orgSellVol,
    )
