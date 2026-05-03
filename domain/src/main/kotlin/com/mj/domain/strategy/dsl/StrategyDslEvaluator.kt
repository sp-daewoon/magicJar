package com.mj.domain.strategy.dsl

import com.mj.domain.common.Candle
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.IndicatorResult
import com.mj.domain.strategy.indicator.IndicatorSpec
import com.mj.domain.strategy.pattern.PatternMatch
import com.mj.domain.strategy.pattern.PatternRecognizer
import com.mj.domain.strategy.pattern.catalog.PatternCatalog
import java.math.BigDecimal

/**
 * Strategy DSL 평가기 (순수 도메인).
 *
 * 입력: [StrategyDsl] + 캔들 시계열 + 평가 위치(currentIndex)
 * 출력: [StrategyEvalResult] — entry / exit boolean + 사유 trace
 *
 * 흐름:
 *  1. 선언된 indicators를 [IndicatorEngine]으로 계산 (alias 캐시)
 *  2. 선언된 patterns + 조건에서 참조한 패턴을 [PatternRecognizer]로 인식
 *  3. entry/exit 그룹을 재귀 평가
 *  4. CROSS_ABOVE/BELOW는 currentIndex와 currentIndex-1 비교로 교차 판정
 *
 * 의존성은 생성자 주입 — IndicatorEngine은 ADR 0039 port 추상화.
 *
 * 멀티 timeframe (조건별 IndicatorRef.timeframe)은 R1 본 구현에서는
 * `tf-resampler` 부재로 caller가 같은 TF candles만 넘긴다고 가정. 향후 R2에서
 * MarketDataPort 도입 시 timeframe 별 candles 재조회.
 */
class StrategyDslEvaluator(
    private val indicatorEngine: IndicatorEngine,
) {
    /**
     * 평가.
     *
     * @param dsl          전략
     * @param candles      시간 오름차순 캔들 (전체 백테스트 구간)
     * @param currentIndex 평가 위치 (보통 candles.lastIndex)
     */
    fun evaluate(
        dsl: StrategyDsl,
        candles: List<Candle>,
        currentIndex: Int = candles.lastIndex,
    ): StrategyEvalResult {
        require(candles.isNotEmpty()) { "candles must not be empty" }
        require(currentIndex in candles.indices) {
            "currentIndex $currentIndex out of bounds [0, ${candles.lastIndex}]"
        }

        // 1. 지표 사전 계산 (alias → 결과 시계열)
        val indicatorResults: Map<String, IndicatorResult> =
            dsl.strategy.indicators.associate { ref ->
                val spec = IndicatorSpec(name = ref.id.lowercase(), params = ref.params)
                ref.key().lowercase() to indicatorEngine.calculate(spec, candles)
            }

        // 2. 패턴 사전 인식 — 선언된 patterns 또는 패턴 조건에서 참조한 코드
        val referencedPatternCodes = collectReferencedPatternCodes(dsl)
        val patternMatches: List<PatternMatch> =
            if (referencedPatternCodes.isEmpty()) {
                emptyList()
            } else {
                // currentIndex 봉만 본다 (lookback 1 — currentIndex 위치에서 끝나는 패턴)
                val out = mutableListOf<PatternMatch>()
                for (code in referencedPatternCodes) {
                    val pattern = PatternCatalog.findByCode(code) ?: continue
                    val m = PatternRecognizer.match(pattern, candles, currentIndex)
                    if (m != null) out += m
                }
                out
            }

        val ctx =
            EvalContext(
                candles = candles,
                currentIndex = currentIndex,
                indicatorResults = indicatorResults,
                patternMatches = patternMatches,
            )

        val entryTrace = mutableListOf<EvalReason>()
        val exitTrace = mutableListOf<EvalReason>()
        val entry = evalGroup(dsl.strategy.entry, ctx, entryTrace)
        val exit = evalGroup(dsl.strategy.exit, ctx, exitTrace)

        return StrategyEvalResult(
            entry = entry,
            exit = exit,
            entryReasons = entryTrace.toList(),
            exitReasons = exitTrace.toList(),
            evaluatedAt = candles[currentIndex].openTime,
        )
    }

    // ----------------------------------------------------------------
    // 내부 평가 로직
    // ----------------------------------------------------------------

    private fun evalGroup(
        group: LogicalGroup,
        ctx: EvalContext,
        trace: MutableList<EvalReason>,
    ): Boolean {
        val nodeResults = group.nodes.map { node -> evalNode(node, ctx, trace) }
        return when (group.logic) {
            LogicalOp.AND -> nodeResults.all { it }
            LogicalOp.OR -> nodeResults.any { it }
        }
    }

    private fun evalNode(
        node: DslNode,
        ctx: EvalContext,
        trace: MutableList<EvalReason>,
    ): Boolean =
        when (node) {
            is LogicalGroup -> evalGroup(node, ctx, trace)
            is Condition -> evalCondition(node, ctx, trace)
            is PatternCondition -> evalPatternCondition(node, ctx, trace)
        }

    private fun evalCondition(
        cond: Condition,
        ctx: EvalContext,
        trace: MutableList<EvalReason>,
    ): Boolean {
        return when (cond.operator) {
            ConditionOperator.GREATER_THAN -> {
                val l = resolveOperand(cond.lhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val r = resolveOperand(cond.rhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val ok = l > r
                trace += EvalReason.fromCondition(cond, l, r, ok, ctx.currentIndex)
                ok
            }
            ConditionOperator.LESS_THAN -> {
                val l = resolveOperand(cond.lhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val r = resolveOperand(cond.rhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val ok = l < r
                trace += EvalReason.fromCondition(cond, l, r, ok, ctx.currentIndex)
                ok
            }
            ConditionOperator.GREATER_EQUAL -> {
                val l = resolveOperand(cond.lhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val r = resolveOperand(cond.rhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val ok = l >= r
                trace += EvalReason.fromCondition(cond, l, r, ok, ctx.currentIndex)
                ok
            }
            ConditionOperator.LESS_EQUAL -> {
                val l = resolveOperand(cond.lhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val r = resolveOperand(cond.rhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val ok = l <= r
                trace += EvalReason.fromCondition(cond, l, r, ok, ctx.currentIndex)
                ok
            }
            ConditionOperator.EQUALS -> {
                val l = resolveOperand(cond.lhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val r = resolveOperand(cond.rhs, ctx, ctx.currentIndex) ?: return traceMiss(cond, trace)
                val ok = l.compareTo(r) == 0
                trace += EvalReason.fromCondition(cond, l, r, ok, ctx.currentIndex)
                ok
            }
            ConditionOperator.CROSS_ABOVE -> evalCross(cond, ctx, trace, above = true)
            ConditionOperator.CROSS_BELOW -> evalCross(cond, ctx, trace, above = false)
        }
    }

    /**
     * Cross above: prev L <= prev R AND curr L > curr R.
     * Cross below: prev L >= prev R AND curr L < curr R.
     */
    private fun evalCross(
        cond: Condition,
        ctx: EvalContext,
        trace: MutableList<EvalReason>,
        above: Boolean,
    ): Boolean {
        if (ctx.currentIndex < 1) {
            trace += EvalReason.fromMiss(cond, "currentIndex < 1, cross 평가 불가")
            return false
        }
        val prevL = resolveOperand(cond.lhs, ctx, ctx.currentIndex - 1)
        val prevR = resolveOperand(cond.rhs, ctx, ctx.currentIndex - 1)
        val currL = resolveOperand(cond.lhs, ctx, ctx.currentIndex)
        val currR = resolveOperand(cond.rhs, ctx, ctx.currentIndex)
        if (prevL == null || prevR == null || currL == null || currR == null) {
            return traceMiss(cond, trace)
        }
        val ok =
            if (above) {
                prevL <= prevR && currL > currR
            } else {
                prevL >= prevR && currL < currR
            }
        trace += EvalReason.fromCross(cond, prevL, prevR, currL, currR, ok, ctx.currentIndex)
        return ok
    }

    private fun evalPatternCondition(
        cond: PatternCondition,
        ctx: EvalContext,
        trace: MutableList<EvalReason>,
    ): Boolean {
        val matched = ctx.patternMatches.any { it.pattern.code == cond.code.lowercase() && it.endIndex == ctx.currentIndex }
        val ok = matched == cond.expected
        trace +=
            EvalReason(
                kind = "pattern",
                description = "pattern '${cond.code}' expected=${cond.expected} matched=$matched",
                satisfied = ok,
            )
        return ok
    }

    private fun traceMiss(
        cond: Condition,
        trace: MutableList<EvalReason>,
    ): Boolean {
        trace += EvalReason.fromMiss(cond, "data missing — null operand at currentIndex")
        return false
    }

    private fun resolveOperand(
        op: Operand,
        ctx: EvalContext,
        atIndex: Int,
    ): BigDecimal? {
        if (atIndex < 0 || atIndex >= ctx.candles.size) return null
        return when (op) {
            is ConstantOperand -> op.value
            is PriceOperand -> {
                val targetIdx = atIndex - op.offset
                if (targetIdx < 0) {
                    null
                } else {
                    val c = ctx.candles[targetIdx]
                    when (op.field) {
                        PriceField.OPEN -> c.open
                        PriceField.HIGH -> c.high
                        PriceField.LOW -> c.low
                        PriceField.CLOSE -> c.close
                        PriceField.VOLUME -> BigDecimal(c.volume)
                    }
                }
            }
            is IndicatorOperand -> {
                val targetIdx = atIndex - op.offset
                if (targetIdx < 0) return null
                val result = ctx.indicatorResults[op.alias.lowercase()] ?: return null
                val targetTime = ctx.candles[targetIdx].openTime
                val point = result.series.firstOrNull { it.time == targetTime } ?: return null
                val key = op.outputKey ?: result.spec.name
                point.values[key.lowercase()] ?: point.values[result.spec.name] ?: point.values.values.firstOrNull()
            }
        }
    }

    private fun collectReferencedPatternCodes(dsl: StrategyDsl): Set<String> {
        val out = mutableSetOf<String>()
        out += dsl.strategy.patterns.map { it.code.lowercase() }
        collectPatternCodesInGroup(dsl.strategy.entry, out)
        collectPatternCodesInGroup(dsl.strategy.exit, out)
        return out
    }

    private fun collectPatternCodesInGroup(
        group: LogicalGroup,
        out: MutableSet<String>,
    ) {
        for (node in group.nodes) {
            when (node) {
                is LogicalGroup -> collectPatternCodesInGroup(node, out)
                is PatternCondition -> out += node.code.lowercase()
                is Condition -> Unit
            }
        }
    }

    private data class EvalContext(
        val candles: List<Candle>,
        val currentIndex: Int,
        val indicatorResults: Map<String, IndicatorResult>,
        val patternMatches: List<PatternMatch>,
    )
}

/**
 * 평가 결과.
 *
 * @property entry         entry 그룹 평가 결과
 * @property exit          exit 그룹 평가 결과
 * @property entryReasons  entry 평가 trace (디버그·UI용)
 * @property exitReasons   exit 평가 trace
 * @property evaluatedAt   평가 기준 봉의 openTime
 */
data class StrategyEvalResult(
    val entry: Boolean,
    val exit: Boolean,
    val entryReasons: List<EvalReason>,
    val exitReasons: List<EvalReason>,
    val evaluatedAt: java.time.Instant,
)

/**
 * 단일 조건/그룹 평가 흔적.
 */
data class EvalReason(
    val kind: String, // "compare", "cross_above", "cross_below", "pattern", "miss"
    val description: String,
    val satisfied: Boolean,
) {
    companion object {
        fun fromCondition(
            cond: Condition,
            lhs: BigDecimal,
            rhs: BigDecimal,
            ok: Boolean,
            atIndex: Int,
        ): EvalReason =
            EvalReason(
                kind = "compare",
                description = "[idx=$atIndex] ${operandLabel(cond.lhs)}=$lhs ${cond.operator.symbol} ${operandLabel(cond.rhs)}=$rhs",
                satisfied = ok,
            )

        fun fromCross(
            cond: Condition,
            prevL: BigDecimal,
            prevR: BigDecimal,
            currL: BigDecimal,
            currR: BigDecimal,
            ok: Boolean,
            atIndex: Int,
        ): EvalReason =
            EvalReason(
                kind = "cross",
                description =
                    "[idx=$atIndex] ${cond.operator.symbol}: " +
                        "prev(${operandLabel(cond.lhs)}=$prevL, ${operandLabel(cond.rhs)}=$prevR) " +
                        "curr(${operandLabel(cond.lhs)}=$currL, ${operandLabel(cond.rhs)}=$currR)",
                satisfied = ok,
            )

        fun fromMiss(
            cond: Condition,
            reason: String,
        ): EvalReason =
            EvalReason(
                kind = "miss",
                description = "${operandLabel(cond.lhs)} ${cond.operator.symbol} ${operandLabel(cond.rhs)}: $reason",
                satisfied = false,
            )

        private fun operandLabel(op: Operand): String =
            when (op) {
                is IndicatorOperand -> if (op.outputKey != null) "${op.alias}.${op.outputKey}" else op.alias
                is PriceOperand -> op.field.name.lowercase() + (if (op.offset > 0) "[-${op.offset}]" else "")
                is ConstantOperand -> op.value.toPlainString()
            }
    }
}
