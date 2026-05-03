package com.mj.api.infrastructure.dsl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.mj.domain.common.Interval
import com.mj.domain.strategy.dsl.Condition
import com.mj.domain.strategy.dsl.ConditionOperator
import com.mj.domain.strategy.dsl.ConstantOperand
import com.mj.domain.strategy.dsl.DslNode
import com.mj.domain.strategy.dsl.IndicatorOperand
import com.mj.domain.strategy.dsl.IndicatorRef
import com.mj.domain.strategy.dsl.LogicalGroup
import com.mj.domain.strategy.dsl.LogicalOp
import com.mj.domain.strategy.dsl.Market
import com.mj.domain.strategy.dsl.Operand
import com.mj.domain.strategy.dsl.PatternCondition
import com.mj.domain.strategy.dsl.PatternRef
import com.mj.domain.strategy.dsl.PositionSizing
import com.mj.domain.strategy.dsl.PriceField
import com.mj.domain.strategy.dsl.PriceOperand
import com.mj.domain.strategy.dsl.RiskRule
import com.mj.domain.strategy.dsl.StopLoss
import com.mj.domain.strategy.dsl.StrategyBody
import com.mj.domain.strategy.dsl.StrategyDsl
import com.mj.domain.strategy.dsl.StrategyMetadata
import com.mj.domain.strategy.dsl.SymbolGroup
import com.mj.domain.strategy.dsl.SymbolIndex
import com.mj.domain.strategy.dsl.TakeProfit
import com.mj.domain.strategy.dsl.TimeFilter
import com.mj.domain.strategy.dsl.TradingSession
import com.mj.domain.strategy.dsl.TrailingStop
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Strategy DSL yaml ⇄ 도메인 모델 변환기 (ADR 0040).
 *
 * import: KIS 표준 yaml + magicJar 확장 yaml 모두 수용
 * export: 도메인 모델 → magicJar 확장 yaml (KIS 호환 핵심 + 확장 키워드)
 *
 * Jackson YAML 사용 — 중간 표현은 Map<String, Any?> raw structure를 거친다.
 * 이는 yaml의 동적 union(Operand의 indicator|pattern|price|constant) 표현을
 * Kotlin sealed interface로 명시 분기하기 위함.
 *
 * 호환성 정책 (ADR 0040 §2-3):
 *  - KIS 표준 키워드(name·universe·entry·exit·conditions·indicator·params·op·lhs·rhs·value) 모두 수용
 *  - magicJar 확장(metadata·indicators·patterns·tf·time·risk·sizing) optional
 *  - export 시 magicJar 확장 키워드는 그대로 yaml로 — KIS GUI는 무시 가능 (호환 superset)
 */
@Component
class StrategyDslParser {
    /** 캐시 가능. ObjectMapper는 thread-safe. */
    private val yamlMapper: ObjectMapper =
        ObjectMapper(
            YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES),
        ).registerModule(KotlinModule.Builder().build())

    /**
     * yaml string → 도메인 모델.
     *
     * @throws DslParseException yaml 파싱 실패 또는 필수 필드 누락
     */
    fun parse(yaml: String): StrategyDsl {
        if (yaml.isBlank()) throw DslParseException("yaml content is blank")
        val raw: Map<String, Any?> =
            try {
                yamlMapper.readValue(yaml)
            } catch (e: Exception) {
                throw DslParseException("invalid yaml: ${e.message}", e)
            }
        return buildDsl(raw)
    }

    /**
     * 도메인 모델 → yaml string.
     */
    fun serialize(dsl: StrategyDsl): String {
        val map = dslToMap(dsl)
        return yamlMapper.writeValueAsString(map)
    }

    // ======================================================================
    // import (yaml raw map → domain)
    // ======================================================================

    private fun buildDsl(raw: Map<String, Any?>): StrategyDsl {
        // version: default "1.0"
        val version = (raw["version"] as? String)?.takeIf { it.isNotBlank() } ?: "1.0"

        // metadata: 명시 또는 KIS 표준의 name 단독에서 합성
        val metadataRaw = raw["metadata"] as? Map<String, Any?>
        val metadata =
            if (metadataRaw != null) {
                StrategyMetadata(
                    name =
                        (metadataRaw["name"] as? String)
                            ?: throw DslParseException("metadata.name required"),
                    description = metadataRaw["description"] as? String,
                    author = metadataRaw["author"] as? String,
                    tags = (metadataRaw["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                )
            } else {
                // KIS 호환: top-level name이 메타이름
                val name = (raw["name"] as? String) ?: "untitled"
                StrategyMetadata(name = name)
            }

        // strategy: 명시 또는 KIS 호환의 top-level entry/exit/indicators 합성
        val strategyRaw = raw["strategy"] as? Map<String, Any?>
        val body =
            if (strategyRaw != null) {
                buildStrategyBody(strategyRaw, defaultId = metadata.name)
            } else {
                // KIS 표준 — top-level entry/exit
                buildStrategyBody(raw, defaultId = (raw["name"] as? String) ?: "untitled")
            }

        // risk: 명시 또는 top-level 단축 키 (stop_loss·take_profit·trailing_stop·sizing)
        val riskRaw = raw["risk"] as? Map<String, Any?>
        val risk =
            if (riskRaw != null) {
                buildRisk(riskRaw)
            } else {
                buildRiskFromShortcuts(raw)
            }

        // time (확장)
        val time = (raw["time"] as? Map<String, Any?>)?.let { buildTimeFilter(it) }

        // universe (KIS 호환 — 문자열 또는 map / magicJar 확장)
        val universe =
            when (val u = raw["universe"]) {
                is String -> SymbolGroup(index = parseSymbolIndex(u))
                is Map<*, *> -> buildUniverse(@Suppress("UNCHECKED_CAST") (u as Map<String, Any?>))
                else -> null
            }

        return StrategyDsl(
            version = version,
            metadata = metadata,
            strategy = body,
            risk = risk,
            time = time,
            universe = universe,
        )
    }

    private fun buildStrategyBody(
        raw: Map<String, Any?>,
        defaultId: String,
    ): StrategyBody {
        val id = (raw["id"] as? String) ?: defaultId
        val category = raw["category"] as? String

        val indicatorsRaw = (raw["indicators"] as? List<*>) ?: emptyList<Any?>()
        val indicators =
            indicatorsRaw.filterIsInstance<Map<String, Any?>>().map { buildIndicatorRef(it) }

        val patternsRaw = (raw["patterns"] as? List<*>) ?: emptyList<Any?>()
        val patterns =
            patternsRaw.mapNotNull { p ->
                when (p) {
                    is String -> PatternRef(code = p)
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val m = p as Map<String, Any?>
                        val code =
                            (m["code"] as? String) ?: (m["id"] as? String)
                                ?: return@mapNotNull null
                        PatternRef(code = code, alias = m["alias"] as? String)
                    }
                    else -> null
                }
            }

        val entryRaw =
            raw["entry"] as? Map<String, Any?>
                ?: throw DslParseException("strategy.entry required")
        val exitRaw =
            raw["exit"] as? Map<String, Any?>
                ?: throw DslParseException("strategy.exit required")

        return StrategyBody(
            id = id,
            category = category,
            indicators = indicators,
            patterns = patterns,
            entry = buildLogicalGroup(entryRaw, "entry"),
            exit = buildLogicalGroup(exitRaw, "exit"),
        )
    }

    private fun buildIndicatorRef(raw: Map<String, Any?>): IndicatorRef {
        val id =
            (raw["id"] as? String)?.lowercase()
                ?: throw DslParseException("indicator.id required")
        val alias = raw["alias"] as? String

        @Suppress("UNCHECKED_CAST")
        val params =
            (raw["params"] as? Map<String, Any?>)?.filterValues { it != null } as Map<String, Any>?
                ?: emptyMap()
        val tf = (raw["tf"] as? String ?: raw["timeframe"] as? String)?.let { parseInterval(it) }
        return IndicatorRef(id = id, alias = alias, params = params, timeframe = tf)
    }

    private fun buildLogicalGroup(
        raw: Map<String, Any?>,
        path: String,
    ): LogicalGroup {
        val logic = parseLogicalOp(raw["logic"] as? String ?: "AND")

        // KIS 표준: conditions: [...]  | magicJar 확장: nodes: [...]
        @Suppress("UNCHECKED_CAST")
        val nodesRaw =
            (raw["nodes"] as? List<Any?>)
                ?: (raw["conditions"] as? List<Any?>)
                ?: throw DslParseException("$path requires 'conditions' or 'nodes' list")

        val nodes =
            nodesRaw.mapIndexedNotNull { idx, n ->
                when (n) {
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        buildDslNode(n as Map<String, Any?>, "$path.nodes[$idx]")
                    }
                    else -> throw DslParseException("$path.nodes[$idx]: expected map, got ${n?.javaClass?.simpleName}")
                }
            }
        if (nodes.isEmpty()) throw DslParseException("$path: no valid nodes")
        return LogicalGroup(logic, nodes)
    }

    private fun buildDslNode(
        raw: Map<String, Any?>,
        path: String,
    ): DslNode {
        // 내부 그룹 인지 체크 (logic + (conditions|nodes))
        if (raw.containsKey("logic") && (raw.containsKey("conditions") || raw.containsKey("nodes"))) {
            return buildLogicalGroup(raw, path)
        }

        // 패턴 조건 (확장)
        val patternCode = raw["pattern"] as? String
        if (patternCode != null) {
            val expected = (raw["expected"] as? Boolean) ?: true
            val tf = (raw["tf"] as? String)?.let { parseInterval(it) }
            return PatternCondition(code = patternCode.lowercase(), expected = expected, timeframe = tf)
        }

        // 일반 조건: lhs / op|operator / rhs|compare_to
        val lhsRaw =
            raw["lhs"] ?: raw["indicator"]?.let { mapOf("indicator" to it, "params" to raw["params"]) }
                ?: throw DslParseException("$path.lhs required")

        @Suppress("UNCHECKED_CAST")
        val lhs = parseOperand(lhsRaw, "$path.lhs")
        val opStr =
            (raw["op"] as? String) ?: (raw["operator"] as? String)
                ?: throw DslParseException("$path.op required")
        val operator =
            ConditionOperator.fromKeyword(opStr)
                ?: throw DslParseException("$path.op: unknown operator '$opStr'")
        val rhsRaw =
            raw["rhs"] ?: raw["compare_to"] ?: raw["value"]?.let { mapOf("value" to it) }
                ?: throw DslParseException("$path.rhs required")
        val rhs = parseOperand(rhsRaw, "$path.rhs")
        val tf = (raw["tf"] as? String)?.let { parseInterval(it) }
        return Condition(lhs = lhs, operator = operator, rhs = rhs, timeframe = tf)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseOperand(
        raw: Any?,
        path: String,
    ): Operand =
        when (raw) {
            null -> throw DslParseException("$path: null operand")
            is Number -> ConstantOperand(BigDecimal(raw.toString()))
            is String -> {
                // "RSI(14)" 같은 함수형 표현은 R1 미지원 — 구조적 yaml만.
                // OHLCV 키워드 + alias 이름 자동 분기.
                val lower = raw.lowercase()
                when (lower) {
                    "open", "high", "low", "close", "volume" ->
                        PriceOperand(field = PriceField.valueOf(lower.uppercase()))
                    else -> IndicatorOperand(alias = raw)
                }
            }
            is Map<*, *> -> {
                val m = raw as Map<String, Any?>
                when {
                    m.containsKey("value") -> {
                        val v =
                            m["value"]
                                ?: throw DslParseException("$path.value: null")
                        ConstantOperand(BigDecimal(v.toString()))
                    }
                    m.containsKey("indicator") -> {
                        val name =
                            (m["indicator"] as? String)?.lowercase()
                                ?: throw DslParseException("$path.indicator must be string")
                        // alias 또는 id 어느 쪽이든 — 평가 시 indicators 정의에서 alias로 lookup
                        IndicatorOperand(
                            alias = name,
                            outputKey = m["output"] as? String ?: m["key"] as? String,
                            offset = (m["offset"] as? Number)?.toInt() ?: 0,
                        )
                    }
                    m.containsKey("alias") -> {
                        val alias =
                            (m["alias"] as? String)
                                ?: throw DslParseException("$path.alias must be string")
                        IndicatorOperand(
                            alias = alias,
                            outputKey = m["output"] as? String ?: m["key"] as? String,
                            offset = (m["offset"] as? Number)?.toInt() ?: 0,
                        )
                    }
                    m.containsKey("price") -> {
                        val p =
                            (m["price"] as? String)?.uppercase()
                                ?: throw DslParseException("$path.price must be string")
                        PriceOperand(
                            field =
                                runCatching { PriceField.valueOf(p) }
                                    .getOrElse { throw DslParseException("$path.price: unknown '$p'") },
                            offset = (m["offset"] as? Number)?.toInt() ?: 0,
                        )
                    }
                    else -> throw DslParseException("$path: unrecognized operand keys ${m.keys}")
                }
            }
            else -> throw DslParseException("$path: cannot parse operand of type ${raw.javaClass.simpleName}")
        }

    private fun parseLogicalOp(s: String): LogicalOp =
        when (s.uppercase().trim()) {
            "AND" -> LogicalOp.AND
            "OR" -> LogicalOp.OR
            else -> throw DslParseException("unknown logical op '$s'")
        }

    private fun parseInterval(s: String): Interval =
        when (s.uppercase().trim()) {
            "D1", "DAY", "DAILY" -> Interval.D1
            // M5/M15/M30/H1 등 합성 interval은 영속 enum에 없음 (ADR 0048).
            // DSL에서 타임프레임 지정 시 M1·D1 기준으로 저장하고, 실행 시 CandleAggregator가 합성.
            // 합성 타임프레임(M5 등)을 지정한 경우 M1으로 폴백 (런타임 합성은 전략 실행 엔진이 담당).
            else -> Interval.M1
        }

    private fun parseSymbolIndex(s: String): SymbolIndex? =
        when (s.uppercase().trim()) {
            "KOSPI200" -> SymbolIndex.KOSPI200
            "KOSDAQ150" -> SymbolIndex.KOSDAQ150
            "KOSPI100" -> SymbolIndex.KOSPI100
            "KRX300" -> SymbolIndex.KRX300
            "ETF" -> SymbolIndex.ETF
            else -> null
        }

    private fun buildRisk(raw: Map<String, Any?>): RiskRule {
        @Suppress("UNCHECKED_CAST")
        val sl = (raw["stop_loss"] as? Map<String, Any?>)?.let { buildStopLoss(it) }

        @Suppress("UNCHECKED_CAST")
        val tp = (raw["take_profit"] as? Map<String, Any?>)?.let { buildTakeProfit(it) }

        @Suppress("UNCHECKED_CAST")
        val ts = (raw["trailing_stop"] as? Map<String, Any?>)?.let { buildTrailingStop(it) }

        @Suppress("UNCHECKED_CAST")
        val sz = (raw["sizing"] as? Map<String, Any?>)?.let { buildPositionSizing(it) }
        return RiskRule(stopLoss = sl, takeProfit = tp, trailingStop = ts, positionSizing = sz)
    }

    private fun buildRiskFromShortcuts(raw: Map<String, Any?>): RiskRule? {
        val pieces = mutableListOf<Any>()
        // top-level fixedStopLoss / trailingStop는 magicJar 확장 단축
        val fsl = raw["fixed_stop_loss"] as? Number
        val tr = raw["trailing_stop"] as? Number
        if (fsl == null && tr == null) return null
        return RiskRule(
            stopLoss = fsl?.let { StopLoss(percent = BigDecimal(it.toString())) },
            trailingStop = tr?.let { TrailingStop(percent = BigDecimal(it.toString())) },
        )
    }

    private fun buildStopLoss(m: Map<String, Any?>): StopLoss =
        StopLoss(
            enabled = (m["enabled"] as? Boolean) ?: true,
            percent = readBigDecimal(m, "percent", "stop_loss.percent"),
        )

    private fun buildTakeProfit(m: Map<String, Any?>): TakeProfit =
        TakeProfit(
            enabled = (m["enabled"] as? Boolean) ?: true,
            percent = readBigDecimal(m, "percent", "take_profit.percent"),
        )

    private fun buildTrailingStop(m: Map<String, Any?>): TrailingStop =
        TrailingStop(
            enabled = (m["enabled"] as? Boolean) ?: true,
            percent = readBigDecimal(m, "percent", "trailing_stop.percent"),
        )

    private fun buildPositionSizing(m: Map<String, Any?>): PositionSizing {
        val type = (m["type"] as? String)?.uppercase() ?: throw DslParseException("sizing.type required")
        return when (type) {
            "KELLY" ->
                PositionSizing.Kelly(
                    fraction =
                        (m["fraction"] as? Number)?.let { BigDecimal(it.toString()) }
                            ?: BigDecimal("0.25"),
                )
            "FIXED_FRACTIONAL" ->
                PositionSizing.FixedFractional(
                    fraction = readBigDecimal(m, "fraction", "sizing.fraction"),
                )
            "FIXED_AMOUNT" ->
                PositionSizing.FixedAmount(
                    amount = readBigDecimal(m, "amount", "sizing.amount"),
                )
            "FIXED_LOT" ->
                PositionSizing.FixedLot(
                    lot =
                        (m["lot"] as? Number)?.toInt()
                            ?: throw DslParseException("sizing.lot required"),
                )
            else -> throw DslParseException("unknown sizing.type '$type'")
        }
    }

    private fun buildTimeFilter(m: Map<String, Any?>): TimeFilter {
        val session =
            (m["session"] as? String)?.uppercase()?.let {
                runCatching { TradingSession.valueOf(it) }.getOrElse {
                    throw DslParseException("time.session: unknown '$it'")
                }
            } ?: TradingSession.REGULAR

        @Suppress("UNCHECKED_CAST")
        val window =
            (m["windowKst"] as? List<*>)?.filterIsInstance<String>()
                ?: (m["window_kst"] as? List<*>)?.filterIsInstance<String>()
        val (windowStart, windowEnd) =
            if (window != null && window.size == 2) {
                LocalTime.parse(window[0]) to LocalTime.parse(window[1])
            } else {
                null to null
            }
        val excludeOpen =
            (m["excludeOpenMinutes"] as? Number)?.toInt()
                ?: (m["exclude_open_minutes"] as? Number)?.toInt()
        val excludeClose =
            (m["excludeCloseMinutes"] as? Number)?.toInt()
                ?: (m["exclude_close_minutes"] as? Number)?.toInt()
        return TimeFilter(
            session = session,
            windowStart = windowStart,
            windowEnd = windowEnd,
            excludeOpenMinutes = excludeOpen,
            excludeCloseMinutes = excludeClose,
        )
    }

    private fun buildUniverse(m: Map<String, Any?>): SymbolGroup {
        val market =
            (m["market"] as? String)?.uppercase()?.let {
                runCatching { Market.valueOf(it) }.getOrElse { Market.ALL }
            } ?: Market.ALL
        val index = (m["index"] as? String)?.let { parseSymbolIndex(it) }

        @Suppress("UNCHECKED_CAST")
        val filterMap = m["filter"] as? Map<String, Any?> ?: emptyMap()
        val sectors =
            (filterMap["sector"] as? List<*>)?.filterIsInstance<String>()
                ?: emptyList()
        val minMarketCap =
            (filterMap["minMarketCap"] as? Number)?.let { BigDecimal(it.toString()) }
                ?: (filterMap["min_market_cap"] as? Number)?.let { BigDecimal(it.toString()) }
        val custom = (m["custom"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return SymbolGroup(
            market = market,
            index = index,
            sectors = sectors,
            minMarketCap = minMarketCap,
            customCodes = custom,
        )
    }

    private fun readBigDecimal(
        m: Map<String, Any?>,
        key: String,
        path: String,
    ): BigDecimal {
        val v = m[key] ?: throw DslParseException("$path required")
        return BigDecimal(v.toString())
    }

    // ======================================================================
    // export (domain → yaml map)
    // ======================================================================

    private fun dslToMap(dsl: StrategyDsl): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["version"] = dsl.version
        out["metadata"] = metadataToMap(dsl.metadata)
        out["strategy"] = strategyBodyToMap(dsl.strategy)
        dsl.risk?.let { out["risk"] = riskToMap(it) }
        dsl.time?.let { out["time"] = timeToMap(it) }
        dsl.universe?.let { out["universe"] = universeToMap(it) }
        return out
    }

    private fun metadataToMap(m: StrategyMetadata): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["name"] = m.name
        m.description?.let { out["description"] = it }
        m.author?.let { out["author"] = it }
        if (m.tags.isNotEmpty()) out["tags"] = m.tags
        return out
    }

    private fun strategyBodyToMap(b: StrategyBody): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["id"] = b.id
        b.category?.let { out["category"] = it }
        if (b.indicators.isNotEmpty()) out["indicators"] = b.indicators.map { indicatorRefToMap(it) }
        if (b.patterns.isNotEmpty()) out["patterns"] = b.patterns.map { patternRefToMap(it) }
        out["entry"] = groupToMap(b.entry)
        out["exit"] = groupToMap(b.exit)
        return out
    }

    private fun indicatorRefToMap(ref: IndicatorRef): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["id"] = ref.id
        ref.alias?.let { out["alias"] = it }
        if (ref.params.isNotEmpty()) out["params"] = ref.params
        ref.timeframe?.let { out["tf"] = it.name }
        return out
    }

    private fun patternRefToMap(ref: PatternRef): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["code"] = ref.code
        ref.alias?.let { out["alias"] = it }
        return out
    }

    private fun groupToMap(g: LogicalGroup): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["logic"] = g.logic.name
        out["conditions"] = g.nodes.map { nodeToMap(it) }
        return out
    }

    private fun nodeToMap(node: DslNode): Map<String, Any?> =
        when (node) {
            is LogicalGroup -> groupToMap(node)
            is Condition -> conditionToMap(node)
            is PatternCondition -> patternConditionToMap(node)
        }

    private fun conditionToMap(c: Condition): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["lhs"] = operandToMap(c.lhs)
        out["op"] = c.operator.name.lowercase()
        out["rhs"] = operandToMap(c.rhs)
        c.timeframe?.let { out["tf"] = it.name }
        return out
    }

    private fun patternConditionToMap(p: PatternCondition): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["pattern"] = p.code
        out["expected"] = p.expected
        p.timeframe?.let { out["tf"] = it.name }
        return out
    }

    private fun operandToMap(op: Operand): Any =
        when (op) {
            is ConstantOperand -> mapOf("value" to op.value)
            is PriceOperand -> {
                val out = linkedMapOf<String, Any?>()
                out["price"] = op.field.name.lowercase()
                if (op.offset != 0) out["offset"] = op.offset
                out
            }
            is IndicatorOperand -> {
                val out = linkedMapOf<String, Any?>()
                out["alias"] = op.alias
                op.outputKey?.let { out["output"] = it }
                if (op.offset != 0) out["offset"] = op.offset
                out
            }
        }

    private fun riskToMap(r: RiskRule): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        r.stopLoss?.let {
            out["stop_loss"] = linkedMapOf("enabled" to it.enabled, "percent" to it.percent)
        }
        r.takeProfit?.let {
            out["take_profit"] = linkedMapOf("enabled" to it.enabled, "percent" to it.percent)
        }
        r.trailingStop?.let {
            out["trailing_stop"] = linkedMapOf("enabled" to it.enabled, "percent" to it.percent)
        }
        r.positionSizing?.let { ps ->
            val sz = linkedMapOf<String, Any?>()
            when (ps) {
                is PositionSizing.Kelly -> {
                    sz["type"] = "KELLY"
                    sz["fraction"] = ps.fraction
                }
                is PositionSizing.FixedFractional -> {
                    sz["type"] = "FIXED_FRACTIONAL"
                    sz["fraction"] = ps.fraction
                }
                is PositionSizing.FixedAmount -> {
                    sz["type"] = "FIXED_AMOUNT"
                    sz["amount"] = ps.amount
                }
                is PositionSizing.FixedLot -> {
                    sz["type"] = "FIXED_LOT"
                    sz["lot"] = ps.lot
                }
            }
            out["sizing"] = sz
        }
        return out
    }

    private fun timeToMap(t: TimeFilter): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["session"] = t.session.name
        if (t.windowStart != null && t.windowEnd != null) {
            out["windowKst"] = listOf(t.windowStart.toString(), t.windowEnd.toString())
        }
        t.excludeOpenMinutes?.let { out["excludeOpenMinutes"] = it }
        t.excludeCloseMinutes?.let { out["excludeCloseMinutes"] = it }
        return out
    }

    private fun universeToMap(u: SymbolGroup): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["market"] = u.market.name
        u.index?.let { out["index"] = it.name }
        val filter = linkedMapOf<String, Any?>()
        if (u.sectors.isNotEmpty()) filter["sector"] = u.sectors
        u.minMarketCap?.let { filter["minMarketCap"] = it }
        if (filter.isNotEmpty()) out["filter"] = filter
        if (u.customCodes.isNotEmpty()) out["custom"] = u.customCodes
        return out
    }
}

/**
 * yaml 파싱 실패 예외.
 */
class DslParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
