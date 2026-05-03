package com.mj.api.controller.dto

import com.mj.domain.market.instrument.InstrumentDetail
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

/**
 * 종목 상세 메타 응답 DTO (T20-T5).
 *
 * _warnings: KIS·DART 키 미설정 또는 외부 API 실패 시 frontend에 사유를 명시.
 * null인 필드가 많을 때 사용자에게 "—" 표시하며 이유를 알려줄 수 있다.
 */
@Schema(description = "종목 상세 메타 정보 (KIS 기본정보 + DART 재무정보 병합)")
data class InstrumentDetailResponse(
    @Schema(description = "KRX 종목 코드 6자리", example = "005930")
    val code: String,
    @Schema(description = "한글 종목명", example = "삼성전자")
    val name: String,
    @Schema(description = "시장 구분 (KOSPI / KOSDAQ)", example = "KOSPI")
    val market: String?,
    @Schema(description = "업종명", example = "반도체 및 반도체장비")
    val sector: String?,
    @Schema(description = "시가총액 (원)", example = "400000000000000")
    val marketCap: BigDecimal?,
    @Schema(description = "상장주식수", example = "5969782550")
    val sharesOutstanding: Long?,
    @Schema(description = "외국인 보유율 (%)", example = "52.34")
    val foreignRatio: BigDecimal?,
    @Schema(description = "기관 보유율 (%)", example = "10.12")
    val institutionRatio: BigDecimal?,
    @Schema(description = "매출액 (원, 최근 연간)", example = "302202000000000")
    val revenue: BigDecimal?,
    @Schema(description = "영업이익 (원)", example = "6566900000000")
    val operatingIncome: BigDecimal?,
    @Schema(description = "당기순이익 (원)", example = "15484700000000")
    val netIncome: BigDecimal?,
    @Schema(description = "PER (주가수익비율)", example = "12.34")
    val per: BigDecimal?,
    @Schema(description = "PBR (주가순자산비율)", example = "1.23")
    val pbr: BigDecimal?,
    @Schema(description = "EPS (주당순이익, 원)", example = "5765")
    val eps: BigDecimal?,
    @Schema(description = "BPS (주당순자산가치, 원)", example = "57890")
    val bps: BigDecimal?,
    @Schema(description = "배당수익률 (%)", example = "2.15")
    val dividendYield: BigDecimal?,
    @Schema(description = "최대주주 지분율 (%)", example = "21.45")
    val majorShareholderRatio: BigDecimal?,
    @Schema(description = "대표이사", example = "한종희")
    val representativeName: String?,
    @Schema(description = "업종 분류명", example = "전자부품, 컴퓨터, 영상, 음향 및 통신장비 제조업")
    val industry: String?,
    @Schema(
        description = "데이터 소스 경고 목록. KIS·DART 키 미설정 또는 외부 API 실패 시 항목 포함. null이면 경고 없음.",
        example = "[\"KIS_KEY_NOT_SET\", \"DART_CORP_CODE_NOT_FOUND\"]",
    )
    val warnings: List<String>? = null,
)

/**
 * InstrumentDetail → InstrumentDetailResponse 변환.
 *
 * @param warnings KIS/DART 데이터 소스 경고 목록 (null = 경고 없음)
 */
fun InstrumentDetail.toDetailResponse(warnings: List<String>? = null): InstrumentDetailResponse =
    InstrumentDetailResponse(
        code = code,
        name = name,
        market = market,
        sector = sector,
        marketCap = marketCap,
        sharesOutstanding = sharesOutstanding,
        foreignRatio = foreignRatio,
        institutionRatio = institutionRatio,
        revenue = revenue,
        operatingIncome = operatingIncome,
        netIncome = netIncome,
        per = per,
        pbr = pbr,
        eps = eps,
        bps = bps,
        dividendYield = dividendYield,
        majorShareholderRatio = majorShareholderRatio,
        representativeName = representativeName,
        industry = industry,
        warnings = warnings?.takeIf { it.isNotEmpty() },
    )
