package com.mj.domain.strategy.indicator.catalog

import com.mj.domain.strategy.indicator.IndicatorCategory
import com.mj.domain.strategy.indicator.IndicatorCategory.MOMENTUM
import com.mj.domain.strategy.indicator.IndicatorCategory.OSCILLATOR
import com.mj.domain.strategy.indicator.IndicatorCategory.OTHER
import com.mj.domain.strategy.indicator.IndicatorCategory.TREND
import com.mj.domain.strategy.indicator.IndicatorCategory.VOLATILITY
import com.mj.domain.strategy.indicator.IndicatorCategory.VOLUME
import com.mj.domain.strategy.indicator.IndicatorDescriptor
import com.mj.domain.strategy.indicator.IndicatorParamDef
import com.mj.domain.strategy.indicator.ParamType

/**
 * KIS Strategy Builder 80개 기술지표 카탈로그.
 *
 * 출처: koreainvestment/open-trading-api strategy_builder/core/indicators.py
 * 원본 97개 함수 중 보조 유틸(get_latest_close·get_prev_close·calc_high_since 등) 제외,
 * 독립 지표 80개를 카탈로그화.
 *
 * 카테고리: TREND / MOMENTUM / VOLUME / VOLATILITY / OSCILLATOR / OTHER
 */
object IndicatorCatalog {
    // =========================================================================
    // TREND (추세) — 20개
    // =========================================================================

    val MA =
        descriptor(
            id = "ma",
            name = "이동평균(MA)",
            category = TREND,
            params = listOf(intParam("period", 20, "이동평균 기간")),
            outputKeys = listOf("ma"),
        )

    val EMA =
        descriptor(
            id = "ema",
            name = "지수이동평균(EMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("ema"),
        )

    val WMA =
        descriptor(
            id = "wma",
            name = "가중이동평균(WMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("wma"),
        )

    val HMA =
        descriptor(
            id = "hma",
            name = "헐이동평균(HMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("hma"),
        )

    val DEMA =
        descriptor(
            id = "dema",
            name = "이중지수이동평균(DEMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("dema"),
        )

    val TEMA =
        descriptor(
            id = "tema",
            name = "삼중지수이동평균(TEMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("tema"),
        )

    val TRIMA =
        descriptor(
            id = "trima",
            name = "삼각이동평균(TRIMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("trima"),
        )

    val LWMA =
        descriptor(
            id = "lwma",
            name = "선형가중이동평균(LWMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("lwma"),
        )

    val T3 =
        descriptor(
            id = "t3",
            name = "T3 이동평균",
            category = TREND,
            params = listOf(intParam("period", 20, "기간"), floatParam("volume_factor", 0.7, "볼륨 팩터")),
            outputKeys = listOf("t3"),
        )

    val ZLEMA =
        descriptor(
            id = "zlema",
            name = "지연제거지수이동평균(ZLEMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("zlema"),
        )

    val KAMA =
        descriptor(
            id = "kama",
            name = "카우프만적응이동평균(KAMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("kama"),
        )

    val ALMA =
        descriptor(
            id = "alma",
            name = "아르노-레그루 이동평균(ALMA)",
            category = TREND,
            params =
                listOf(
                    intParam("period", 20, "기간"),
                    floatParam("sigma", 6.0, "시그마"),
                    floatParam("offset", 0.85, "오프셋"),
                ),
            outputKeys = listOf("alma"),
        )

    val FRAMA =
        descriptor(
            id = "frama",
            name = "프랙탈적응이동평균(FRAMA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("frama"),
        )

    val VIDYA =
        descriptor(
            id = "vidya",
            name = "변동성 인덱스 이동평균(VIDYA)",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("vidya"),
        )

    val MACD =
        descriptor(
            id = "macd",
            name = "MACD",
            category = TREND,
            params =
                listOf(
                    intParam("fast", 12, "단기 EMA"),
                    intParam("slow", 26, "장기 EMA"),
                    intParam("signal", 9, "시그널 EMA"),
                ),
            outputKeys = listOf("macd", "signal", "histogram"),
        )

    val ICHIMOKU_TENKAN =
        descriptor(
            id = "ichimoku_tenkan",
            name = "이치모쿠 전환선",
            category = TREND,
            params = listOf(intParam("period", 9, "전환선 기간")),
            outputKeys = listOf("ichimoku_tenkan"),
        )

    val ICHIMOKU_KIJUN =
        descriptor(
            id = "ichimoku_kijun",
            name = "이치모쿠 기준선",
            category = TREND,
            params = listOf(intParam("period", 26, "기준선 기간")),
            outputKeys = listOf("ichimoku_kijun"),
        )

    val SUPERTREND =
        descriptor(
            id = "supertrend",
            name = "슈퍼트렌드",
            category = TREND,
            params = listOf(intParam("period", 14, "ATR 기간"), floatParam("multiplier", 3.0, "배수")),
            outputKeys = listOf("supertrend"),
        )

    val SAR =
        descriptor(
            id = "sar",
            name = "포물선형 SAR",
            category = TREND,
            params = listOf(floatParam("af_start", 0.02, "가속 시작"), floatParam("af_max", 0.2, "최대 가속")),
            outputKeys = listOf("sar"),
        )

    val REGRESSION_SLOPE =
        descriptor(
            id = "regression_slope",
            name = "선형회귀 기울기",
            category = TREND,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("regression_slope"),
        )

    // =========================================================================
    // MOMENTUM (모멘텀) — 20개
    // =========================================================================

    val RSI =
        descriptor(
            id = "rsi",
            name = "RSI",
            category = MOMENTUM,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("rsi"),
        )

    val STOCHASTIC_K =
        descriptor(
            id = "stochastic_k",
            name = "스토캐스틱 %K",
            category = MOMENTUM,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("stochastic_k"),
        )

    val STOCHASTIC_D =
        descriptor(
            id = "stochastic_d",
            name = "스토캐스틱 %D",
            category = MOMENTUM,
            params = listOf(intParam("k_period", 14, "%K 기간"), intParam("d_period", 3, "%D 기간")),
            outputKeys = listOf("stochastic_d"),
        )

    val STOCHRSI =
        descriptor(
            id = "stochrsi",
            name = "스토캐스틱 RSI",
            category = MOMENTUM,
            params = listOf(intParam("rsi_period", 14, "RSI 기간"), intParam("stoch_period", 14, "스토캐스틱 기간")),
            outputKeys = listOf("stochrsi"),
        )

    val CCI =
        descriptor(
            id = "cci",
            name = "상품채널지수(CCI)",
            category = MOMENTUM,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("cci"),
        )

    val WILLIAMS_R =
        descriptor(
            id = "williams_r",
            name = "윌리엄스 %R",
            category = MOMENTUM,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("williams_r"),
        )

    val ROC =
        descriptor(
            id = "roc",
            name = "변화율(ROC)",
            category = MOMENTUM,
            params = listOf(intParam("period", 10, "기간")),
            outputKeys = listOf("roc"),
        )

    val MOMENTUM_IND =
        descriptor(
            id = "momentum",
            name = "모멘텀",
            category = MOMENTUM,
            params = listOf(intParam("period", 10, "기간")),
            outputKeys = listOf("momentum"),
        )

    val APO =
        descriptor(
            id = "apo",
            name = "절대가격오실레이터(APO)",
            category = MOMENTUM,
            params = listOf(intParam("fast", 12, "단기"), intParam("slow", 26, "장기")),
            outputKeys = listOf("apo"),
        )

    val PPO =
        descriptor(
            id = "ppo",
            name = "퍼센트가격오실레이터(PPO)",
            category = MOMENTUM,
            params = listOf(intParam("fast", 12, "단기"), intParam("slow", 26, "장기")),
            outputKeys = listOf("ppo"),
        )

    val CMO =
        descriptor(
            id = "cmo",
            name = "찬드 모멘텀 오실레이터(CMO)",
            category = MOMENTUM,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("cmo"),
        )

    val AO =
        descriptor(
            id = "ao",
            name = "어썸 오실레이터(AO)",
            category = MOMENTUM,
            outputKeys = listOf("ao"),
        )

    val TRIX =
        descriptor(
            id = "trix",
            name = "TRIX",
            category = MOMENTUM,
            params = listOf(intParam("period", 15, "기간")),
            outputKeys = listOf("trix"),
        )

    val TSI =
        descriptor(
            id = "tsi",
            name = "진정강도지수(TSI)",
            category = MOMENTUM,
            params = listOf(intParam("long", 25, "장기"), intParam("short", 13, "단기")),
            outputKeys = listOf("tsi"),
        )

    val RVI =
        descriptor(
            id = "rvi",
            name = "상대활력지수(RVI)",
            category = MOMENTUM,
            params = listOf(intParam("period", 10, "기간")),
            outputKeys = listOf("rvi"),
        )

    val DPO =
        descriptor(
            id = "dpo",
            name = "차이가격오실레이터(DPO)",
            category = MOMENTUM,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("dpo"),
        )

    val KST =
        descriptor(
            id = "kst",
            name = "Know Sure Thing(KST)",
            category = MOMENTUM,
            outputKeys = listOf("kst"),
        )

    val COPPOCK =
        descriptor(
            id = "coppock",
            name = "코폭 커브",
            category = MOMENTUM,
            outputKeys = listOf("coppock"),
        )

    val SCHAFF =
        descriptor(
            id = "schaff",
            name = "샤프 추세 사이클(STC)",
            category = MOMENTUM,
            params =
                listOf(
                    intParam("period", 23, "기간"),
                    intParam("fast", 10, "단기"),
                    intParam("slow", 50, "장기"),
                ),
            outputKeys = listOf("schaff"),
        )

    val FISHER =
        descriptor(
            id = "fisher",
            name = "피셔 변환",
            category = MOMENTUM,
            params = listOf(intParam("period", 9, "기간")),
            outputKeys = listOf("fisher"),
        )

    // =========================================================================
    // VOLUME (거래량) — 12개
    // =========================================================================

    val OBV =
        descriptor(
            id = "obv",
            name = "에너지이동지표(OBV)",
            category = VOLUME,
            outputKeys = listOf("obv"),
        )

    val VOLUME_MA =
        descriptor(
            id = "volume_ma",
            name = "거래량 이동평균",
            category = VOLUME,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("volume_ma"),
        )

    val VWAP =
        descriptor(
            id = "vwap",
            name = "거래량가중평균가격(VWAP)",
            category = VOLUME,
            outputKeys = listOf("vwap"),
        )

    val MFI =
        descriptor(
            id = "mfi",
            name = "자금흐름지수(MFI)",
            category = VOLUME,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("mfi"),
        )

    val CMF =
        descriptor(
            id = "cmf",
            name = "차이킨 머니플로우(CMF)",
            category = VOLUME,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("cmf"),
        )

    val CHO =
        descriptor(
            id = "cho",
            name = "차이킨 오실레이터",
            category = VOLUME,
            params = listOf(intParam("fast", 3, "단기"), intParam("slow", 10, "장기")),
            outputKeys = listOf("cho"),
        )

    val AD =
        descriptor(
            id = "ad",
            name = "A/D 라인",
            category = VOLUME,
            outputKeys = listOf("ad"),
        )

    val ADL =
        descriptor(
            id = "adl",
            name = "누적/분배 라인(ADL)",
            category = VOLUME,
            outputKeys = listOf("adl"),
        )

    val FORCE =
        descriptor(
            id = "force",
            name = "힘 지수(Force Index)",
            category = VOLUME,
            params = listOf(intParam("period", 13, "기간")),
            outputKeys = listOf("force"),
        )

    val VWMA =
        descriptor(
            id = "vwma",
            name = "거래량가중이동평균(VWMA)",
            category = VOLUME,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("vwma"),
        )

    val EOM =
        descriptor(
            id = "eom",
            name = "시장 이동 편의(EOM)",
            category = VOLUME,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("eom"),
        )

    val KVO =
        descriptor(
            id = "kvo",
            name = "클링거 볼륨 오실레이터",
            category = VOLUME,
            params = listOf(intParam("fast", 34, "단기"), intParam("slow", 55, "장기")),
            outputKeys = listOf("kvo"),
        )

    // =========================================================================
    // VOLATILITY (변동성) — 12개
    // =========================================================================

    val BB =
        descriptor(
            id = "bb",
            name = "볼린저 밴드",
            category = VOLATILITY,
            params = listOf(intParam("period", 20, "기간"), floatParam("std_dev", 2.0, "표준편차 배수")),
            outputKeys = listOf("bb_upper", "bb_middle", "bb_lower"),
        )

    val BB_WIDTH =
        descriptor(
            id = "bb_width",
            name = "볼린저 밴드 폭",
            category = VOLATILITY,
            params = listOf(intParam("period", 20, "기간"), floatParam("std_dev", 2.0, "표준편차 배수")),
            outputKeys = listOf("bb_width"),
        )

    val BB_PERCENT =
        descriptor(
            id = "bb_percent",
            name = "볼린저 밴드 %B",
            category = VOLATILITY,
            params = listOf(intParam("period", 20, "기간"), floatParam("std_dev", 2.0, "표준편차 배수")),
            outputKeys = listOf("bb_percent"),
        )

    val ATR =
        descriptor(
            id = "atr",
            name = "평균진실범위(ATR)",
            category = VOLATILITY,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("atr"),
        )

    val NATR =
        descriptor(
            id = "natr",
            name = "정규화 ATR",
            category = VOLATILITY,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("natr"),
        )

    val KELTNER_UPPER =
        descriptor(
            id = "keltner_upper",
            name = "켈트너 채널 상단",
            category = VOLATILITY,
            params =
                listOf(
                    intParam("ema_period", 20, "EMA 기간"),
                    intParam("atr_period", 14, "ATR 기간"),
                    floatParam("multiplier", 2.0, "배수"),
                ),
            outputKeys = listOf("keltner_upper"),
        )

    val KELTNER_LOWER =
        descriptor(
            id = "keltner_lower",
            name = "켈트너 채널 하단",
            category = VOLATILITY,
            params =
                listOf(
                    intParam("ema_period", 20, "EMA 기간"),
                    intParam("atr_period", 14, "ATR 기간"),
                    floatParam("multiplier", 2.0, "배수"),
                ),
            outputKeys = listOf("keltner_lower"),
        )

    val DONCHIAN_UPPER =
        descriptor(
            id = "donchian_upper",
            name = "돈치안 채널 상단",
            category = VOLATILITY,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("donchian_upper"),
        )

    val DONCHIAN_LOWER =
        descriptor(
            id = "donchian_lower",
            name = "돈치안 채널 하단",
            category = VOLATILITY,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("donchian_lower"),
        )

    val CHOP =
        descriptor(
            id = "chop",
            name = "채핀 지수(Choppiness)",
            category = VOLATILITY,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("chop"),
        )

    val MASS_INDEX =
        descriptor(
            id = "mass_index",
            name = "질량 지수(Mass Index)",
            category = VOLATILITY,
            params = listOf(intParam("period", 25, "기간")),
            outputKeys = listOf("mass_index"),
        )

    val AUGEN =
        descriptor(
            id = "augen",
            name = "오겐 변동성",
            category = VOLATILITY,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("augen"),
        )

    // =========================================================================
    // OSCILLATOR (오실레이터) — 8개
    // =========================================================================

    val ADX =
        descriptor(
            id = "adx",
            name = "ADX",
            category = OSCILLATOR,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("adx"),
        )

    val ADXR =
        descriptor(
            id = "adxr",
            name = "ADXR",
            category = OSCILLATOR,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("adxr"),
        )

    val AROON_UP =
        descriptor(
            id = "aroon_up",
            name = "아룬 상단",
            category = OSCILLATOR,
            params = listOf(intParam("period", 25, "기간")),
            outputKeys = listOf("aroon_up"),
        )

    val AROON_DOWN =
        descriptor(
            id = "aroon_down",
            name = "아룬 하단",
            category = OSCILLATOR,
            params = listOf(intParam("period", 25, "기간")),
            outputKeys = listOf("aroon_down"),
        )

    val VORTEX_PLUS =
        descriptor(
            id = "vortex_plus",
            name = "소용돌이 +VI",
            category = OSCILLATOR,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("vortex_plus"),
        )

    val VORTEX_MINUS =
        descriptor(
            id = "vortex_minus",
            name = "소용돌이 -VI",
            category = OSCILLATOR,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("vortex_minus"),
        )

    val ULTOSC =
        descriptor(
            id = "ultosc",
            name = "최종 오실레이터(UO)",
            category = OSCILLATOR,
            params = listOf(intParam("p1", 7, "짧은 기간"), intParam("p2", 14, "중간 기간"), intParam("p3", 28, "긴 기간")),
            outputKeys = listOf("ultosc"),
        )

    val BOP =
        descriptor(
            id = "bop",
            name = "매수-매도 균형(BOP)",
            category = OSCILLATOR,
            outputKeys = listOf("bop"),
        )

    // =========================================================================
    // OTHER (기타) — 8개
    // =========================================================================

    val STD =
        descriptor(
            id = "std",
            name = "표준편차",
            category = OTHER,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("std"),
        )

    val VARIANCE =
        descriptor(
            id = "variance",
            name = "분산",
            category = OTHER,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("variance"),
        )

    val DISPARITY =
        descriptor(
            id = "disparity",
            name = "이격도",
            category = OTHER,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("disparity"),
        )

    val MIDPOINT =
        descriptor(
            id = "midpoint",
            name = "중간점",
            category = OTHER,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("midpoint"),
        )

    val MIDPRICE =
        descriptor(
            id = "midprice",
            name = "중간 가격",
            category = OTHER,
            params = listOf(intParam("period", 14, "기간")),
            outputKeys = listOf("midprice"),
        )

    val LOGR =
        descriptor(
            id = "logr",
            name = "로그 수익률",
            category = OTHER,
            outputKeys = listOf("logr"),
        )

    val PIVOT =
        descriptor(
            id = "pivot",
            name = "피봇 포인트",
            category = OTHER,
            outputKeys = listOf("pivot"),
        )

    val REGRESSION_INTERCEPT =
        descriptor(
            id = "regression_intercept",
            name = "선형회귀 절편",
            category = OTHER,
            params = listOf(intParam("period", 20, "기간")),
            outputKeys = listOf("regression_intercept"),
        )

    // =========================================================================
    // 전체 카탈로그 (80개)
    // =========================================================================

    val ALL: List<IndicatorDescriptor> =
        listOf(
            // TREND (20)
            MA,
            EMA,
            WMA,
            HMA,
            DEMA,
            TEMA,
            TRIMA,
            LWMA,
            T3,
            ZLEMA,
            KAMA,
            ALMA,
            FRAMA,
            VIDYA,
            MACD,
            ICHIMOKU_TENKAN,
            ICHIMOKU_KIJUN,
            SUPERTREND,
            SAR,
            REGRESSION_SLOPE,
            // MOMENTUM (20)
            RSI,
            STOCHASTIC_K,
            STOCHASTIC_D,
            STOCHRSI,
            CCI,
            WILLIAMS_R,
            ROC,
            MOMENTUM_IND,
            APO,
            PPO,
            CMO,
            AO,
            TRIX,
            TSI,
            RVI,
            DPO,
            KST,
            COPPOCK,
            SCHAFF,
            FISHER,
            // VOLUME (12)
            OBV,
            VOLUME_MA,
            VWAP,
            MFI,
            CMF,
            CHO,
            AD,
            ADL,
            FORCE,
            VWMA,
            EOM,
            KVO,
            // VOLATILITY (12)
            BB,
            BB_WIDTH,
            BB_PERCENT,
            ATR,
            NATR,
            KELTNER_UPPER,
            KELTNER_LOWER,
            DONCHIAN_UPPER,
            DONCHIAN_LOWER,
            CHOP,
            MASS_INDEX,
            AUGEN,
            // OSCILLATOR (8)
            ADX,
            ADXR,
            AROON_UP,
            AROON_DOWN,
            VORTEX_PLUS,
            VORTEX_MINUS,
            ULTOSC,
            BOP,
            // OTHER (8)
            STD,
            VARIANCE,
            DISPARITY,
            MIDPOINT,
            MIDPRICE,
            LOGR,
            PIVOT,
            REGRESSION_INTERCEPT,
        )

    /** id → descriptor 빠른 조회 맵 */
    val BY_ID: Map<String, IndicatorDescriptor> = ALL.associateBy { it.id }

    /** 카테고리별 그룹 */
    fun byCategory(cat: IndicatorCategory): List<IndicatorDescriptor> = ALL.filter { it.category == cat }

    // =========================================================================
    // 내부 헬퍼
    // =========================================================================

    private fun descriptor(
        id: String,
        name: String,
        category: IndicatorCategory,
        params: List<IndicatorParamDef> = emptyList(),
        outputKeys: List<String>,
    ): IndicatorDescriptor =
        IndicatorDescriptor(
            id = id,
            name = name,
            category = category,
            params = params,
            outputKeys = outputKeys,
        )

    private fun intParam(
        key: String,
        default: Int,
        description: String,
    ) = IndicatorParamDef(
        key = key,
        type = ParamType.INT,
        defaultValue = default,
        description = description,
    )

    private fun floatParam(
        key: String,
        default: Double,
        description: String,
    ) = IndicatorParamDef(
        key = key,
        type = ParamType.FLOAT,
        defaultValue = default,
        description = description,
    )
}
