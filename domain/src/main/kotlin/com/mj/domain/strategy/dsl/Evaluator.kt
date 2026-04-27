package com.mj.domain.strategy.dsl

import java.math.BigDecimal

/**
 * 파싱된 BoolNode/NumNode를 평가한다.
 *
 * NumNode 평가 시점에 함수 호출은 캔들 시리즈로 변환되어 currentIndex의 값을 가져온다.
 * 시리즈 결과 캐싱은 동일 노드 트리 내에서 수행한다(반복 백테스트는 호출자 캐시).
 *
 * 미정의(null) 시리즈 값을 만나면 `DslEvaluationException`을 던지지 않고 비교 결과를 false로 처리한다.
 * 산술 노드에서 만나면 평가 자체를 "정의되지 않음"으로 표시하고, 비교 단계에서 false 반환.
 */
internal class Evaluator {
    fun evaluate(
        node: BoolNode,
        ctx: BarEvaluationContext,
    ): Boolean {
        val cache = HashMap<String, BigDecimal?>()
        return evalBool(node, ctx, cache)
    }

    /** flat values 컨텍스트(코어 포트)용. 함수 호출/컬럼 참조는 변수 맵으로 룩업. */
    fun evaluateFlat(
        node: BoolNode,
        values: Map<String, BigDecimal>,
    ): Boolean = evalBoolFlat(node, values)

    private fun evalBool(
        node: BoolNode,
        ctx: BarEvaluationContext,
        cache: MutableMap<String, BigDecimal?>,
    ): Boolean =
        when (node) {
            is And -> evalBool(node.left, ctx, cache) && evalBool(node.right, ctx, cache)
            is Or -> evalBool(node.left, ctx, cache) || evalBool(node.right, ctx, cache)
            is Not -> !evalBool(node.inner, ctx, cache)
            is Compare -> {
                val l = evalNum(node.left, ctx, cache)
                val r = evalNum(node.right, ctx, cache)
                if (l == null || r == null) {
                    false // undefined indicator → condition false
                } else {
                    compare(l, node.op, r)
                }
            }
        }

    private fun evalNum(
        node: NumNode,
        ctx: BarEvaluationContext,
        cache: MutableMap<String, BigDecimal?>,
    ): BigDecimal? {
        return when (node) {
            is NumLiteral -> node.value
            is Neg -> evalNum(node.inner, ctx, cache)?.negate()
            is BinOp -> {
                val l = evalNum(node.left, ctx, cache) ?: return null
                val r = evalNum(node.right, ctx, cache) ?: return null
                applyBinOp(l, node.op, r)
            }
            is Call -> resolveCall(node, ctx, cache)
        }
    }

    private fun resolveCall(
        node: Call,
        ctx: BarEvaluationContext,
        cache: MutableMap<String, BigDecimal?>,
    ): BigDecimal? {
        val name = node.name
        if (node.args.isEmpty()) {
            // 컬럼 참조 또는 extras 룩업
            return when (name) {
                "OPEN" -> ctx.candles[ctx.currentIndex].open
                "HIGH" -> ctx.candles[ctx.currentIndex].high
                "LOW" -> ctx.candles[ctx.currentIndex].low
                "CLOSE" -> ctx.candles[ctx.currentIndex].close
                "VOLUME" -> BigDecimal(ctx.candles[ctx.currentIndex].volume)
                else -> {
                    val extra =
                        ctx.extras[name] ?: ctx.extras[name.lowercase()]
                            ?: throw DslEvaluationException("unknown identifier: $name")
                    extra
                }
            }
        }
        if (!IndicatorRegistry.isKnown(name)) {
            throw DslEvaluationException("unknown function: $name")
        }
        // 모든 인자는 정수 리터럴로 평가 가능해야 함
        val intArgs =
            node.args.map { argNode ->
                val v =
                    evalNum(argNode, ctx, cache)
                        ?: throw DslEvaluationException("function $name argument is undefined")
                v.toInt()
            }
        val key = name + "(" + intArgs.joinToString(",") + ")"
        if (cache.containsKey(key)) return cache[key]
        val series = IndicatorRegistry.compute(name, intArgs, ctx.candles)
        val value = series.getOrNull(ctx.currentIndex)
        cache[key] = value
        return value
    }

    // ---------- flat-values evaluator (core.port.StrategyEvaluator) ----------

    private fun evalBoolFlat(
        node: BoolNode,
        values: Map<String, BigDecimal>,
    ): Boolean {
        return when (node) {
            is And -> evalBoolFlat(node.left, values) && evalBoolFlat(node.right, values)
            is Or -> evalBoolFlat(node.left, values) || evalBoolFlat(node.right, values)
            is Not -> !evalBoolFlat(node.inner, values)
            is Compare -> {
                val l = evalNumFlat(node.left, values) ?: return false
                val r = evalNumFlat(node.right, values) ?: return false
                compare(l, node.op, r)
            }
        }
    }

    private fun evalNumFlat(
        node: NumNode,
        values: Map<String, BigDecimal>,
    ): BigDecimal? {
        return when (node) {
            is NumLiteral -> node.value
            is Neg -> evalNumFlat(node.inner, values)?.negate()
            is BinOp -> {
                val l = evalNumFlat(node.left, values) ?: return null
                val r = evalNumFlat(node.right, values) ?: return null
                applyBinOp(l, node.op, r)
            }
            is Call -> {
                val key =
                    if (node.args.isEmpty()) {
                        node.name
                    } else {
                        val intArgs = node.args.map { (evalNumFlat(it, values) ?: return null).toInt() }
                        node.name + "(" + intArgs.joinToString(",") + ")"
                    }
                values[key] ?: values[key.lowercase()]
            }
        }
    }

    // ---------- helpers ----------

    private fun compare(
        l: BigDecimal,
        op: TokenType,
        r: BigDecimal,
    ): Boolean {
        val cmp = l.compareTo(r)
        return when (op) {
            TokenType.LT -> cmp < 0
            TokenType.LTE -> cmp <= 0
            TokenType.GT -> cmp > 0
            TokenType.GTE -> cmp >= 0
            TokenType.EQ -> cmp == 0
            TokenType.NEQ -> cmp != 0
            else -> throw DslEvaluationException("not a comparison op: $op")
        }
    }

    private fun applyBinOp(
        l: BigDecimal,
        op: TokenType,
        r: BigDecimal,
    ): BigDecimal? =
        when (op) {
            TokenType.PLUS -> l.add(r)
            TokenType.MINUS -> l.subtract(r)
            TokenType.STAR -> l.multiply(r)
            TokenType.SLASH -> if (r.signum() == 0) null else l.divide(r, java.math.MathContext.DECIMAL64)
            else -> throw DslEvaluationException("not an arithmetic op: $op")
        }
}
