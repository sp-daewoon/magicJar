package kr.spdaewoon.magicjar.domain.strategy

import java.math.BigDecimal

/**
 * [TradePlan] ↔ 평탄 Map 변환 단일 책임 codec — ADR 0130 L2 (계획 기반 진입 영속).
 *
 * ## 의도 ([EntryPolicySerde] 패턴 정합 — 단일 진실원)
 * `trade_plan` JSONB 는 **consumer 쓰기 경로**(SignalGeneratedKafkaListener 진입 박제·R1, PlanExecutor 청산 갱신·R2)와
 * **api/consumer 읽기 경로**(UI "예상 계획" 패널·R3, PlanExecutor 평가·R2)에서 양쪽 모두 직렬화/역직렬화가 필요하다.
 * 두 모듈이 동일 파싱 로직을 각자 복제하면 schema drift(한쪽만 필드 추가) 회귀 risk(ADR 0128 scale_in 매핑 누락 교훈)가
 * 생긴다. 본 codec 을 도메인에 두어 **단일 진실원**으로 캡슐화한다.
 *
 * ## 순수성 (LOCK · EntryPolicySerde 와 동일)
 * - Jackson·Spring import 0 — 도메인은 ObjectMapper 에 의존하지 않는다(헥사고날 정합).
 * - caller(어댑터)가 자신의 ObjectMapper 로 JSON String ↔ `Map<String, Any?>` 변환을 담당하고,
 *   본 codec 은 Map ↔ [TradePlan] 도메인 변환만 책임진다(책임 경계 명확).
 * - 역직렬화 실패(타입 불일치·범위 위반 init)는 caller 가 runCatching 으로 흡수(safe* 패턴 정합).
 *
 * ## BigDecimal 양측 파싱 (LOCK)
 * Jackson 이 JSONB 숫자를 number(Double/Int) 또는 string 으로 줄 수 있으므로 [bigDecimalOrNull]이 양측을 허용한다.
 * 직렬화 시 가격·비율은 `toPlainString()` 으로 문자열화해 정밀도 손실(double 부동소수)을 막는다(EntryPolicySerde 정합).
 */
object TradePlanSerde {
    // ── top-level keys ──
    const val KEY_PLAN_VERSION = "planVersion"
    const val KEY_ENTRY = "entry"
    const val KEY_TARGET = "target"
    const val KEY_STOP = "stop"
    const val KEY_TRAILING = "trailing"
    const val KEY_HOLD = "hold"
    const val KEY_EXIT_CONDITIONS = "exitConditions"
    const val KEY_CONTINGENCY = "contingency"
    const val KEY_STATUS = "status"
    const val KEY_EXIT_REASON = "exitReason"

    // ── entry keys ──
    const val KEY_ENTRY_STRATEGY_ID = "strategyId"
    const val KEY_ENTRY_THESIS = "thesis"
    const val KEY_ENTRY_PRICE = "entryPrice"
    const val KEY_ENTRY_BAR = "bar"
    const val KEY_ENTRY_OPENED_AT = "openedAt"

    // ── target keys ──
    const val KEY_TP_PCT = "tpPct"
    const val KEY_TP_PRICE = "tpPrice"

    // ── stop keys ──
    const val KEY_STOP_TYPE = "type"
    const val KEY_SL_PCT = "slPct"
    const val KEY_SL_PRICE = "slPrice"

    // ── trailing keys ──
    const val KEY_TRAIL_ENABLED = "enabled"
    const val KEY_TRAIL_ACTIVATE_PCT = "activatePct"
    const val KEY_TRAIL_PCT = "trailPct"

    // ── hold keys ──
    const val KEY_MIN_HOLD_BARS = "minHoldBars"
    const val KEY_MAX_HOLD_BARS = "maxHoldBars"

    // ── exitCondition keys ──
    const val KEY_EXIT_RULE = "rule"
    const val KEY_EXIT_PARAMS = "params"

    // ── contingency keys ──
    const val KEY_VI_ACTION = "viAction"
    const val KEY_EOD = "eod"

    /**
     * [TradePlan] → 직렬화용 Map. null 하위 객체·필드는 키 자체를 생략(부분 표현). 가격·비율은 `toPlainString()`.
     * caller 가 이 Map 을 ObjectMapper 로 JSON String 화해 `trade_plan` 컬럼에 write.
     */
    fun toMap(plan: TradePlan): Map<String, Any> =
        buildMap {
            put(KEY_PLAN_VERSION, plan.planVersion)
            put(KEY_ENTRY, entryToMap(plan.entry))
            plan.target?.let { put(KEY_TARGET, targetToMap(it)) }
            plan.stop?.let { put(KEY_STOP, stopToMap(it)) }
            plan.trailing?.let { put(KEY_TRAILING, trailingToMap(it)) }
            plan.hold?.let { put(KEY_HOLD, holdToMap(it)) }
            if (plan.exitConditions.isNotEmpty()) put(KEY_EXIT_CONDITIONS, plan.exitConditions.map { exitConditionToMap(it) })
            plan.contingency?.let { put(KEY_CONTINGENCY, contingencyToMap(it)) }
            put(KEY_STATUS, plan.status.name)
            plan.exitReason?.let { put(KEY_EXIT_REASON, it) }
        }

    /**
     * Map → [TradePlan]. 빈 Map·null(미기록=USER/SYSTEM·게이트 off)이면 null 반환(현행 동작).
     * entry(필수) 부재 또는 파싱 불가면 null(계획 무효 — caller 가 현행 경로 유지).
     * 개별 하위 객체 누락은 그 필드 null([TradePlan] default).
     */
    fun fromMap(map: Map<String, Any?>?): TradePlan? {
        if (map.isNullOrEmpty()) return null
        val entryMap = asMap(map[KEY_ENTRY]) ?: return null
        val entry = entryFromMap(entryMap) ?: return null
        return TradePlan(
            planVersion = intOrNull(map[KEY_PLAN_VERSION]) ?: TradePlan.CURRENT_PLAN_VERSION,
            entry = entry,
            target = asMap(map[KEY_TARGET])?.let { targetFromMap(it) },
            stop = asMap(map[KEY_STOP])?.let { stopFromMap(it) },
            trailing = asMap(map[KEY_TRAILING])?.let { trailingFromMap(it) },
            hold = asMap(map[KEY_HOLD])?.let { holdFromMap(it) },
            exitConditions = asList(map[KEY_EXIT_CONDITIONS]).mapNotNull { asMap(it)?.let { m -> exitConditionFromMap(m) } },
            contingency = asMap(map[KEY_CONTINGENCY])?.let { contingencyFromMap(it) },
            status = statusOrDefault(map[KEY_STATUS]),
            exitReason = stringOrNull(map[KEY_EXIT_REASON]),
        )
    }

    // ── entry ──

    private fun entryToMap(entry: TradePlanEntry): Map<String, Any> =
        buildMap {
            put(KEY_ENTRY_STRATEGY_ID, entry.strategyId)
            entry.thesis?.let { put(KEY_ENTRY_THESIS, it) }
            put(KEY_ENTRY_PRICE, entry.entryPrice.toPlainString())
            entry.bar?.let { put(KEY_ENTRY_BAR, it) }
            entry.openedAt?.let { put(KEY_ENTRY_OPENED_AT, it) }
        }

    private fun entryFromMap(map: Map<String, Any?>): TradePlanEntry? {
        val strategyId = longOrNull(map[KEY_ENTRY_STRATEGY_ID]) ?: return null
        val entryPrice = bigDecimalOrNull(map[KEY_ENTRY_PRICE]) ?: return null
        if (entryPrice <= BigDecimal.ZERO) return null
        return TradePlanEntry(
            strategyId = strategyId,
            thesis = stringOrNull(map[KEY_ENTRY_THESIS]),
            entryPrice = entryPrice,
            bar = stringOrNull(map[KEY_ENTRY_BAR]),
            openedAt = stringOrNull(map[KEY_ENTRY_OPENED_AT]),
        )
    }

    // ── target ──

    private fun targetToMap(target: TradePlanTarget): Map<String, Any> =
        buildMap {
            target.tpPct?.let { put(KEY_TP_PCT, it.toPlainString()) }
            target.tpPrice?.let { put(KEY_TP_PRICE, it.toPlainString()) }
        }

    private fun targetFromMap(map: Map<String, Any?>): TradePlanTarget =
        TradePlanTarget(
            tpPct = bigDecimalOrNull(map[KEY_TP_PCT]),
            tpPrice = bigDecimalOrNull(map[KEY_TP_PRICE]),
        )

    // ── stop ──

    private fun stopToMap(stop: TradePlanStop): Map<String, Any> =
        buildMap {
            put(KEY_STOP_TYPE, stop.type.name)
            stop.slPct?.let { put(KEY_SL_PCT, it.toPlainString()) }
            stop.slPrice?.let { put(KEY_SL_PRICE, it.toPlainString()) }
        }

    private fun stopFromMap(map: Map<String, Any?>): TradePlanStop =
        TradePlanStop(
            type = stopTypeOrDefault(map[KEY_STOP_TYPE]),
            slPct = bigDecimalOrNull(map[KEY_SL_PCT]),
            slPrice = bigDecimalOrNull(map[KEY_SL_PRICE]),
        )

    // ── trailing ──

    private fun trailingToMap(trailing: TradePlanTrailing): Map<String, Any> =
        buildMap {
            put(KEY_TRAIL_ENABLED, trailing.enabled)
            trailing.activatePct?.let { put(KEY_TRAIL_ACTIVATE_PCT, it.toPlainString()) }
            trailing.trailPct?.let { put(KEY_TRAIL_PCT, it.toPlainString()) }
        }

    private fun trailingFromMap(map: Map<String, Any?>): TradePlanTrailing =
        TradePlanTrailing(
            enabled = boolOrDefault(map[KEY_TRAIL_ENABLED], false),
            activatePct = bigDecimalOrNull(map[KEY_TRAIL_ACTIVATE_PCT]),
            trailPct = bigDecimalOrNull(map[KEY_TRAIL_PCT]),
        )

    // ── hold ──

    private fun holdToMap(hold: TradePlanHold): Map<String, Any> =
        buildMap {
            hold.minHoldBars?.let { put(KEY_MIN_HOLD_BARS, it) }
            hold.maxHoldBars?.let { put(KEY_MAX_HOLD_BARS, it) }
        }

    private fun holdFromMap(map: Map<String, Any?>): TradePlanHold =
        TradePlanHold(
            minHoldBars = intOrNull(map[KEY_MIN_HOLD_BARS]),
            maxHoldBars = intOrNull(map[KEY_MAX_HOLD_BARS]),
        )

    // ── exitCondition ──

    private fun exitConditionToMap(cond: TradePlanExitCondition): Map<String, Any> =
        buildMap {
            put(KEY_EXIT_RULE, cond.rule)
            if (cond.params.isNotEmpty()) put(KEY_EXIT_PARAMS, cond.params)
        }

    private fun exitConditionFromMap(map: Map<String, Any?>): TradePlanExitCondition? {
        val rule = stringOrNull(map[KEY_EXIT_RULE]) ?: return null
        if (rule.isBlank()) return null
        @Suppress("UNCHECKED_CAST")
        val params = (map[KEY_EXIT_PARAMS] as? Map<String, Any?>) ?: emptyMap()
        return TradePlanExitCondition(rule = rule, params = params)
    }

    // ── contingency ──

    private fun contingencyToMap(c: TradePlanContingency): Map<String, Any> =
        buildMap {
            c.viAction?.let { put(KEY_VI_ACTION, it) }
            c.eod?.let { put(KEY_EOD, it) }
        }

    private fun contingencyFromMap(map: Map<String, Any?>): TradePlanContingency =
        TradePlanContingency(
            viAction = stringOrNull(map[KEY_VI_ACTION]),
            eod = stringOrNull(map[KEY_EOD]),
        )

    // ── primitive parsers (EntryPolicySerde 패턴 정합) ──

    private fun statusOrDefault(raw: Any?): TradePlanStatus =
        raw?.toString()?.let { name -> TradePlanStatus.values().firstOrNull { it.name == name } } ?: TradePlanStatus.OPEN

    private fun stopTypeOrDefault(raw: Any?): TradePlanStopType =
        raw?.toString()?.let { name -> TradePlanStopType.values().firstOrNull { it.name == name } } ?: TradePlanStopType.FIXED

    @Suppress("UNCHECKED_CAST")
    private fun asMap(raw: Any?): Map<String, Any?>? = (raw as? Map<String, Any?>)?.takeIf { it.isNotEmpty() }

    private fun asList(raw: Any?): List<Any?> = (raw as? List<Any?>) ?: emptyList()

    private fun stringOrNull(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotBlank() }

    private fun boolOrDefault(
        raw: Any?,
        default: Boolean,
    ): Boolean =
        when (raw) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull() ?: default
            else -> default
        }

    private fun bigDecimalOrNull(raw: Any?): BigDecimal? =
        when (raw) {
            null -> null
            is BigDecimal -> raw
            is Number -> BigDecimal(raw.toString())
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
            else -> null
        }

    private fun intOrNull(raw: Any?): Int? =
        when (raw) {
            null -> null
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }

    private fun longOrNull(raw: Any?): Long? =
        when (raw) {
            null -> null
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
}
