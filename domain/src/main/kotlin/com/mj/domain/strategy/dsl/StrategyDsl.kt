package com.mj.domain.strategy.dsl

import com.mj.domain.common.Interval
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Strategy DSL 도메인 모델 (ADR 0040).
 *
 * `.kis.yaml` 호환 superset — KIS Strategy Builder 표준 yaml 그대로 import 가능 +
 * magicJar 확장 4건 (다중 timeframe·시간 필터·종목 그룹·패턴 조건).
 *
 * 평가 흐름:
 * 1. [StrategyDslParser]    yaml string → [StrategyDsl]
 * 2. [StrategyDslValidator] [StrategyDsl] → [ValidationResult]
 * 3. [StrategyDslEvaluator] [StrategyDsl] + 캔들 → entry/exit 결과
 *
 * 도메인 모델은 순수하다 — Spring·Jackson·Yaml 의존 0. 직렬화/역직렬화는 외부 어댑터.
 *
 * @property version  스키마 버전 (현재 "1.0", "1.0+magicjar" 등 호환 표시)
 * @property metadata 사용자 표시용 메타 (이름·설명·작성자·태그)
 * @property strategy 본체 — id·indicators·patterns·entry·exit
 * @property risk     리스크 룰 (옵션) — stop_loss·take_profit·trailing·position_sizing
 * @property time     시간 필터 (확장, 옵션) — 거래 세션·시간대
 * @property universe 종목 그룹 (확장, 옵션) — KOSPI200·KOSDAQ150·custom 등
 */
data class StrategyDsl(
    val version: String,
    val metadata: StrategyMetadata,
    val strategy: StrategyBody,
    val risk: RiskRule? = null,
    val time: TimeFilter? = null,
    val universe: SymbolGroup? = null,
) {
    init {
        require(version.isNotBlank()) { "version must not be blank" }
    }
}

/**
 * 사용자 표시용 메타 정보.
 *
 * @property name        전략 이름 (사용자 작성)
 * @property description 설명 (옵션)
 * @property author      작성자 (옵션)
 * @property tags        태그 (옵션, 빈 리스트 가능)
 */
data class StrategyMetadata(
    val name: String,
    val description: String? = null,
    val author: String? = null,
    val tags: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "metadata.name must not be blank" }
    }
}

/**
 * 전략 본체.
 *
 * @property id         전략 식별자 (snake_case 권장, 예: "golden_cross_5_20")
 * @property category   전략 분류 (예: "trend_following", "mean_reversion")
 * @property indicators 사용 지표 목록 (alias로 entry/exit에서 참조)
 * @property patterns   사용 패턴 목록 (확장, 옵션)
 * @property entry      매수 조건 그룹
 * @property exit       매도 조건 그룹
 */
data class StrategyBody(
    val id: String,
    val category: String? = null,
    val indicators: List<IndicatorRef> = emptyList(),
    val patterns: List<PatternRef> = emptyList(),
    val entry: LogicalGroup,
    val exit: LogicalGroup,
) {
    init {
        require(id.isNotBlank()) { "strategy.id must not be blank" }
    }
}

/**
 * 지표 참조 — entry/exit 조건에서 alias로 가리킴.
 *
 * @property id        IndicatorCatalog id 소문자 (예: "ma", "rsi", "macd")
 * @property alias     조건식에서 참조할 별칭 (예: "ma5", "ma20"). 미지정 시 id 그대로.
 * @property params    파라미터 Map (예: { "period": 5 })
 * @property timeframe 다중 TF — 미지정 시 strategy 전역 TF 사용 (확장)
 */
data class IndicatorRef(
    val id: String,
    val alias: String? = null,
    val params: Map<String, Any> = emptyMap(),
    val timeframe: Interval? = null,
) {
    init {
        require(id.isNotBlank()) { "indicator.id must not be blank" }
    }

    /** alias가 있으면 alias, 없으면 id */
    fun key(): String = alias ?: id
}

/**
 * 패턴 참조 (확장).
 *
 * @property code  PatternCatalog code 소문자 snake_case (예: "morning_star")
 * @property alias 조건식에서 참조할 별칭. 미지정 시 code 그대로.
 */
data class PatternRef(
    val code: String,
    val alias: String? = null,
) {
    init {
        require(code.isNotBlank()) { "pattern.code must not be blank" }
    }

    fun key(): String = alias ?: code
}

/**
 * 논리 그룹 — AND/OR로 묶인 조건/그룹 트리.
 *
 * 재귀 구조 — group 안에 group 가능.
 *
 * @property logic AND 또는 OR
 * @property nodes 자식 노드 (조건 또는 하위 그룹)
 */
data class LogicalGroup(
    val logic: LogicalOp,
    val nodes: List<DslNode>,
) : DslNode {
    init {
        require(nodes.isNotEmpty()) { "logical group must have at least 1 node" }
    }
}

/**
 * 논리 연산자.
 */
enum class LogicalOp { AND, OR }

/**
 * 조건/그룹 공통 sealed.
 */
sealed interface DslNode

/**
 * 단일 조건 — `lhs operator rhs` 형태.
 *
 * lhs/rhs는 모두 [Operand] (지표·패턴·가격 필드·상수·다른 지표).
 *
 * 예:
 * - `RSI(14) < 30` → Condition(IndicatorOperand("rsi"), LESS_THAN, ConstantOperand(30))
 * - `MA(5) > MA(20)` → Condition(IndicatorOperand("ma5"), GREATER_THAN, IndicatorOperand("ma20"))
 * - `pattern: "morning_star"` → PatternCondition("morning_star", true)
 *
 * @property lhs        좌변 피연산자
 * @property operator   비교 연산자
 * @property rhs        우변 피연산자
 * @property timeframe  조건별 TF — 미지정 시 strategy 전역 TF 사용 (확장)
 */
data class Condition(
    val lhs: Operand,
    val operator: ConditionOperator,
    val rhs: Operand,
    val timeframe: Interval? = null,
) : DslNode

/**
 * 패턴 조건 (확장) — `pattern: "morning_star"` 형식.
 *
 * @property code     PatternCatalog code (또는 PatternRef alias)
 * @property expected true=패턴 발견 시 true / false=발견 시 false
 */
data class PatternCondition(
    val code: String,
    val expected: Boolean = true,
    val timeframe: Interval? = null,
) : DslNode {
    init {
        require(code.isNotBlank()) { "pattern code must not be blank" }
    }
}

/**
 * 조건의 좌·우변에 들어갈 피연산자.
 *
 * sealed interface — 타입 안전한 패턴 매칭으로 평가기에서 분기.
 */
sealed interface Operand

/**
 * 지표값 참조 — IndicatorRef.alias 또는 id로 가리킴. 옵션 outputKey (예: MACD의 "macd"·"signal"·"histogram").
 *
 * @property alias     IndicatorRef.alias 또는 id
 * @property outputKey 다중 출력 지표용 (미지정 시 id 자체)
 * @property offset    봉 오프셋 (0=현재 봉, 1=이전 봉, ...). 기본 0
 */
data class IndicatorOperand(
    val alias: String,
    val outputKey: String? = null,
    val offset: Int = 0,
) : Operand {
    init {
        require(alias.isNotBlank()) { "indicator alias must not be blank" }
        require(offset >= 0) { "offset must be >= 0" }
    }
}

/**
 * 가격 필드 — 캔들의 OHLCV.
 *
 * @property field  open / high / low / close / volume
 * @property offset 봉 오프셋
 */
data class PriceOperand(
    val field: PriceField,
    val offset: Int = 0,
) : Operand {
    init {
        require(offset >= 0) { "offset must be >= 0" }
    }
}

enum class PriceField { OPEN, HIGH, LOW, CLOSE, VOLUME }

/**
 * 상수.
 */
data class ConstantOperand(
    val value: BigDecimal,
) : Operand {
    constructor(value: Number) : this(BigDecimal(value.toString()))
}

/**
 * 비교 연산자.
 *
 * KIS 표준 7개 + magicJar 확장 0개 (변경 없음, 호환성 우선).
 */
enum class ConditionOperator(
    val symbol: String,
) {
    GREATER_THAN(">"),
    LESS_THAN("<"),
    GREATER_EQUAL(">="),
    LESS_EQUAL("<="),
    EQUALS("=="),
    CROSS_ABOVE("cross_above"),
    CROSS_BELOW("cross_below"),
    ;

    companion object {
        /**
         * KIS 호환 키워드 → operator. 알 수 없으면 null.
         *
         * 허용 키워드:
         * - greater_than · less_than · greater_equal · less_equal · equals
         * - cross_above · cross_below
         * - 심볼 표현(>, <, >=, <=, ==)
         */
        fun fromKeyword(keyword: String): ConditionOperator? =
            when (keyword.lowercase().trim()) {
                "greater_than", "gt", ">" -> GREATER_THAN
                "less_than", "lt", "<" -> LESS_THAN
                "greater_equal", "gte", ">=" -> GREATER_EQUAL
                "less_equal", "lte", "<=" -> LESS_EQUAL
                "equals", "eq", "==" -> EQUALS
                "cross_above", "crosses_above", "crossover" -> CROSS_ABOVE
                "cross_below", "crosses_below", "crossunder" -> CROSS_BELOW
                else -> null
            }
    }
}

/**
 * 리스크 룰 — 손절·익절·트레일링·포지션 사이징.
 *
 * @property stopLoss      손절 (옵션)
 * @property takeProfit    익절 (옵션, 확장)
 * @property trailingStop  트레일링 (옵션, 확장)
 * @property positionSizing 포지션 사이징 (옵션, 확장)
 */
data class RiskRule(
    val stopLoss: StopLoss? = null,
    val takeProfit: TakeProfit? = null,
    val trailingStop: TrailingStop? = null,
    val positionSizing: PositionSizing? = null,
)

/**
 * 손절. percent 0.05 = 5% 하락 시 청산.
 */
data class StopLoss(
    val enabled: Boolean = true,
    val percent: BigDecimal,
) {
    init {
        require(percent.signum() > 0) { "stop_loss.percent must be positive" }
        require(percent <= BigDecimal("1.0")) { "stop_loss.percent must be <= 1.0 (= 100%)" }
    }
}

/**
 * 익절 (확장).
 */
data class TakeProfit(
    val enabled: Boolean = true,
    val percent: BigDecimal,
) {
    init {
        require(percent.signum() > 0) { "take_profit.percent must be positive" }
    }
}

/**
 * 트레일링 스탑 (확장). percent 0.05 = 고점 대비 5% 하락 시 청산.
 */
data class TrailingStop(
    val enabled: Boolean = true,
    val percent: BigDecimal,
) {
    init {
        require(percent.signum() > 0) { "trailing_stop.percent must be positive" }
    }
}

/**
 * 포지션 사이징 (확장).
 */
sealed interface PositionSizing {
    /** Kelly criterion 비례. fraction 0.0~1.0 (보수 0.25 권장). */
    data class Kelly(
        val fraction: BigDecimal = BigDecimal("0.25"),
    ) : PositionSizing {
        init {
            require(fraction.signum() > 0 && fraction <= BigDecimal.ONE) {
                "kelly fraction must be in (0, 1]"
            }
        }
    }

    /** 자산의 고정 비율. 0.05 = 5%. */
    data class FixedFractional(
        val fraction: BigDecimal,
    ) : PositionSizing {
        init {
            require(fraction.signum() > 0 && fraction <= BigDecimal.ONE) {
                "fixed_fractional fraction must be in (0, 1]"
            }
        }
    }

    /** 고정 금액. */
    data class FixedAmount(
        val amount: BigDecimal,
    ) : PositionSizing {
        init {
            require(amount.signum() > 0) { "fixed_amount must be positive" }
        }
    }

    /** 고정 수량(주). */
    data class FixedLot(
        val lot: Int,
    ) : PositionSizing {
        init {
            require(lot > 0) { "fixed_lot must be positive" }
        }
    }
}

/**
 * 시간 필터 (확장). 거래 세션·시간대.
 *
 * @property session       REGULAR / PRE_MARKET / POST_MARKET / ALL
 * @property windowStart   진입 허용 시간 시작 (KST)
 * @property windowEnd     진입 허용 시간 끝 (KST)
 * @property excludeOpenMinutes  장 시작 후 N분 제외 (변동성 회피, 옵션)
 * @property excludeCloseMinutes 장 종료 전 N분 제외 (옵션)
 */
data class TimeFilter(
    val session: TradingSession = TradingSession.REGULAR,
    val windowStart: LocalTime? = null,
    val windowEnd: LocalTime? = null,
    val excludeOpenMinutes: Int? = null,
    val excludeCloseMinutes: Int? = null,
) {
    init {
        if (windowStart != null && windowEnd != null) {
            require(windowEnd.isAfter(windowStart)) {
                "windowEnd($windowEnd) must be after windowStart($windowStart)"
            }
        }
        excludeOpenMinutes?.let { require(it >= 0) { "excludeOpenMinutes must be >= 0" } }
        excludeCloseMinutes?.let { require(it >= 0) { "excludeCloseMinutes must be >= 0" } }
    }
}

enum class TradingSession { REGULAR, PRE_MARKET, POST_MARKET, ALL }

/**
 * 종목 그룹 (확장).
 *
 * @property market    KOSPI / KOSDAQ / KONEX / ALL
 * @property index     KOSPI200 · KOSDAQ150 · ETF 등 사전 정의 그룹
 * @property sectors   섹터 필터 (예: ["반도체", "이차전지"])
 * @property minMarketCap 최소 시가총액 (원, 옵션)
 * @property customCodes  커스텀 종목 코드 리스트
 */
data class SymbolGroup(
    val market: Market = Market.ALL,
    val index: SymbolIndex? = null,
    val sectors: List<String> = emptyList(),
    val minMarketCap: BigDecimal? = null,
    val customCodes: List<String> = emptyList(),
) {
    init {
        minMarketCap?.let { require(it.signum() > 0) { "minMarketCap must be positive" } }
    }
}

enum class Market { KOSPI, KOSDAQ, KONEX, ALL }

enum class SymbolIndex { KOSPI200, KOSDAQ150, KOSPI100, KRX300, ETF }
