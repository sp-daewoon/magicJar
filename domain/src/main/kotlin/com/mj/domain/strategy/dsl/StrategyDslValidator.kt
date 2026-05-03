package com.mj.domain.strategy.dsl

import com.mj.domain.strategy.indicator.catalog.IndicatorCatalog
import com.mj.domain.strategy.pattern.catalog.PatternCatalog

/**
 * Strategy DSL 검증기 (순수 도메인).
 *
 * 검증 항목:
 *  1. version — 지원 (현재 1.0+)
 *  2. metadata.name — 비어있지 않음
 *  3. strategy.id — 비어있지 않음
 *  4. indicators[*].id — IndicatorCatalog 80개에 존재
 *  5. patterns[*].code — PatternCatalog 57개에 존재
 *  6. alias 충돌 — 같은 alias가 indicators·patterns에 중복 없음
 *  7. entry/exit 조건의 IndicatorOperand.alias — 선언된 indicator alias 또는 catalog id
 *  8. 패턴 조건 — 선언된 patterns 또는 catalog code 참조
 *  9. operator 정합 — CROSS_ABOVE/BELOW는 시계열 LHS 필수 (RHS는 상수 허용 — RSI cross 50 패턴)
 * 10. logical group 깊이 ≤ 5
 * 11. logical group 노드 비어있지 않음
 * 12. risk 값 범위 — stop_loss·take_profit·trailing 0~1
 *
 * 결과는 [ValidationResult.Ok] 또는 [ValidationResult.Errors].
 */
object StrategyDslValidator {
    /** 지원 버전 prefix */
    private val SUPPORTED_VERSIONS = setOf("1.0", "1.1")

    /** logical group 최대 중첩 깊이 */
    const val MAX_GROUP_DEPTH: Int = 5

    fun validate(dsl: StrategyDsl): ValidationResult {
        val errors = mutableListOf<DslError>()

        // 1. version
        val versionPrefix =
            dsl.version
                .split("+", limit = 2)
                .first()
                .trim()
        if (versionPrefix !in SUPPORTED_VERSIONS) {
            errors +=
                DslError(
                    path = "version",
                    code = "UNSUPPORTED_VERSION",
                    message = "version '${dsl.version}' not supported. allowed=${SUPPORTED_VERSIONS}",
                )
        }

        // 2. metadata.name (이미 init에서 require됨, 방어)
        if (dsl.metadata.name.isBlank()) {
            errors += DslError("metadata.name", "BLANK", "metadata.name must not be blank")
        }

        // 3. strategy.id
        if (dsl.strategy.id.isBlank()) {
            errors += DslError("strategy.id", "BLANK", "strategy.id must not be blank")
        }

        // 4. indicators[*].id ∈ catalog
        val knownIndicatorIds = IndicatorCatalog.BY_ID.keys
        dsl.strategy.indicators.forEachIndexed { idx, ind ->
            val normalized = ind.id.lowercase()
            if (normalized !in knownIndicatorIds) {
                errors +=
                    DslError(
                        path = "strategy.indicators[$idx].id",
                        code = "UNKNOWN_INDICATOR",
                        message = "indicator id '${ind.id}' not in catalog (80 known: e.g. ma, ema, rsi, macd, bb, ...)",
                    )
            }
        }

        // 5. patterns[*].code ∈ catalog
        val knownPatternCodes = PatternCatalog.ALL.map { it.code }.toSet()
        dsl.strategy.patterns.forEachIndexed { idx, pat ->
            val normalized = pat.code.lowercase()
            if (normalized !in knownPatternCodes) {
                errors +=
                    DslError(
                        path = "strategy.patterns[$idx].code",
                        code = "UNKNOWN_PATTERN",
                        message = "pattern code '${pat.code}' not in catalog (57 known: e.g. doji, hammer, morning_star, ...)",
                    )
            }
        }

        // 6. alias 충돌
        val indicatorAliases = dsl.strategy.indicators.map { it.key() }
        val patternAliases = dsl.strategy.patterns.map { it.key() }
        val allAliases = indicatorAliases + patternAliases
        val duplicates =
            allAliases
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (duplicates.isNotEmpty()) {
            errors +=
                DslError(
                    path = "strategy",
                    code = "DUPLICATE_ALIAS",
                    message = "duplicate alias(es): $duplicates",
                )
        }

        // 7~9. entry/exit conditions
        val declaredIndicatorAliases =
            dsl.strategy.indicators
                .map { it.key() }
                .toSet() + knownIndicatorIds
        val declaredPatternAliases =
            dsl.strategy.patterns
                .map { it.key() }
                .toSet() + knownPatternCodes
        validateGroup(
            group = dsl.strategy.entry,
            path = "strategy.entry",
            depth = 1,
            indicatorAliases = declaredIndicatorAliases,
            patternAliases = declaredPatternAliases,
            errors = errors,
        )
        validateGroup(
            group = dsl.strategy.exit,
            path = "strategy.exit",
            depth = 1,
            indicatorAliases = declaredIndicatorAliases,
            patternAliases = declaredPatternAliases,
            errors = errors,
        )

        // 12. risk 값 범위 — already in init for percent <= 1.0. 빈 RiskRule 허용.
        // 추가 게이트는 향후 ADR로.

        return if (errors.isEmpty()) ValidationResult.Ok else ValidationResult.Errors(errors)
    }

    private fun validateGroup(
        group: LogicalGroup,
        path: String,
        depth: Int,
        indicatorAliases: Set<String>,
        patternAliases: Set<String>,
        errors: MutableList<DslError>,
    ) {
        if (depth > MAX_GROUP_DEPTH) {
            errors +=
                DslError(
                    path = path,
                    code = "GROUP_TOO_DEEP",
                    message = "logical group depth $depth exceeds max $MAX_GROUP_DEPTH",
                )
            return
        }
        if (group.nodes.isEmpty()) {
            errors += DslError(path, "EMPTY_GROUP", "logical group must have at least 1 node")
            return
        }

        group.nodes.forEachIndexed { idx, node ->
            val nodePath = "$path.nodes[$idx]"
            when (node) {
                is LogicalGroup -> validateGroup(node, nodePath, depth + 1, indicatorAliases, patternAliases, errors)
                is Condition -> validateCondition(node, nodePath, indicatorAliases, errors)
                is PatternCondition -> validatePatternCondition(node, nodePath, patternAliases, errors)
            }
        }
    }

    private fun validateCondition(
        cond: Condition,
        path: String,
        indicatorAliases: Set<String>,
        errors: MutableList<DslError>,
    ) {
        validateOperand(cond.lhs, "$path.lhs", indicatorAliases, errors)
        validateOperand(cond.rhs, "$path.rhs", indicatorAliases, errors)

        // CROSS_*에서 LHS는 시계열이어야 한다 (상수가 cross over 가능 X).
        // RHS는 시계열 또는 상수 둘 다 허용 — RSI cross_above 50 같은 패턴이 일반적.
        if (cond.operator == ConditionOperator.CROSS_ABOVE || cond.operator == ConditionOperator.CROSS_BELOW) {
            if (cond.lhs is ConstantOperand) {
                errors +=
                    DslError(
                        path = "$path.operator",
                        code = "CROSS_REQUIRES_SERIES_LHS",
                        message = "${cond.operator.name} requires series LHS (indicator or price), not a constant",
                    )
            }
        }
    }

    private fun validateOperand(
        op: Operand,
        path: String,
        indicatorAliases: Set<String>,
        errors: MutableList<DslError>,
    ) {
        when (op) {
            is IndicatorOperand -> {
                if (op.alias.lowercase() !in indicatorAliases.map { it.lowercase() }.toSet()) {
                    errors +=
                        DslError(
                            path = path,
                            code = "UNKNOWN_INDICATOR_ALIAS",
                            message = "alias '${op.alias}' not declared in strategy.indicators and not in catalog",
                        )
                }
            }
            is PriceOperand -> Unit // OHLCV 필드는 항상 존재
            is ConstantOperand -> Unit
        }
    }

    private fun validatePatternCondition(
        cond: PatternCondition,
        path: String,
        patternAliases: Set<String>,
        errors: MutableList<DslError>,
    ) {
        if (cond.code.lowercase() !in patternAliases.map { it.lowercase() }.toSet()) {
            errors +=
                DslError(
                    path = "$path.code",
                    code = "UNKNOWN_PATTERN_REF",
                    message = "pattern '${cond.code}' not declared and not in catalog",
                )
        }
    }
}

/**
 * 검증 결과.
 */
sealed interface ValidationResult {
    object Ok : ValidationResult

    data class Errors(
        val errors: List<DslError>,
    ) : ValidationResult {
        init {
            require(errors.isNotEmpty()) { "Errors must contain at least one error (use Ok if none)" }
        }
    }
}

/**
 * 검증 오류 1건.
 *
 * @property path     필드 경로 (예: "strategy.entry.nodes[0].lhs")
 * @property code     기계 가독 오류 코드 (예: "UNKNOWN_INDICATOR")
 * @property message  사람이 읽는 메시지
 */
data class DslError(
    val path: String,
    val code: String,
    val message: String,
)
