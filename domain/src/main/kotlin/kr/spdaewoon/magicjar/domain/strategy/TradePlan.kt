package kr.spdaewoon.magicjar.domain.strategy

import java.math.BigDecimal

/**
 * 거래 계획(Planned Entry) — ADR 0130 L2 (계획 기반 진입 + lot 소유권 청산).
 *
 * ## 의도 (왜 거래 단위 계획인가)
 * ADR 0129 가 autopilot 매매 *행위*를 5 레버로 하드닝했지만, "이 거래 하나가 어떻게 들어가서 어떻게 나올지"를
 * **계획으로 박제하고 그 계획을 보장 실행**하는 구조는 아니었다(교차 청산·휩쏘 재발 통로). 본 도메인은 진입(BUY)
 * 성공 시점에 산 전략이 이 lot 의 thesis·목표(TP)·손절(SL)·트레일링·hold 범위·청산 조건·contingency 를 1회 박제하고
 * (`strategy_position.trade_plan` JSONB · V95), R2 [PlanExecutor]가 그 계획을 lot-scoped 로 보장 실행한다.
 *
 * ## 순수성 (헥사고날 LOCK · [ScaleInPlan] 패턴 정합)
 * - Spring·JPA·Jackson 어노테이션/import **절대 금지** — 도메인은 ObjectMapper 에 의존하지 않는다.
 * - 단 Jackson 이 별도 어노테이션 없이 직렬화할 수 있도록 **평탄한 데이터 클래스** 형태를 유지한다(JSONB 영속).
 *   Map↔도메인 codec 은 [TradePlanSerde]가 단일 책임으로 캡슐화하고(EntryPolicySerde 패턴), caller(어댑터)가
 *   자신의 ObjectMapper 로 JSON String ↔ Map 변환을 담당한다.
 * - 범위 검증은 각 하위 데이터 클래스 `init` 에서 수행 — 위반 시 예외(caller 가 runCatching 으로 흡수).
 *
 * ## 확장성 (ADR 0130 §2 L2 LOCK)
 * 새 청산 유형은 [exitConditions] 배열에 데이터를 추가하면 되고, 새 필드는 add-only(스키마 변경 0)다.
 *
 * ## 스키마 v1 (`trade_plan` JSONB 직렬화 예시 — ADR 0130 §2)
 * ```jsonc
 * {
 *   "planVersion": 1,
 *   "entry":   { "strategyId": 105, "thesis": "vwap reclaim + vol surge",
 *                "entryPrice": "12340", "bar": "1m", "openedAt": "2026-06-24T01:12:00Z" },
 *   "target":  { "tpPct": "3.0", "tpPrice": "12710" },
 *   "stop":    { "type": "FIXED", "slPct": "2.0", "slPrice": "12093" },
 *   "trailing":{ "enabled": true, "activatePct": "1.5", "trailPct": "1.0" },
 *   "hold":    { "minHoldBars": 3, "maxHoldBars": 30 },
 *   "exitConditions": [ { "rule": "vwap_cross_down", "params": { "src": "1m" } } ],
 *   "contingency": { "viAction": "EXIT_MARKET", "eod": "LIQUIDATE" },
 *   "status": "OPEN",
 *   "exitReason": null
 * }
 * ```
 *
 * @param planVersion 스키마 버전(현재 1). 미래 마이그레이션 분기 hook.
 * @param entry 진입 메타(필수) — 산 전략·thesis·진입가·봉·진입시각.
 * @param target 익절 목표(선택). null = TP 미설정(L3 안전망·DSL exit 에 의존).
 * @param stop 손절(선택). null = SL 미설정(L3 floor·dll 에 의존).
 * @param trailing 트레일링 스탑(선택). null 또는 enabled=false = 미적용.
 * @param hold 보유 봉 범위(선택). minHold = 잔익절 방어, maxHold = 강제 청산 상한.
 * @param exitConditions 데이터 기반 청산 조건 리스트(확장점, default 빈 리스트). 새 청산 유형의 add-only 추가 지점.
 * @param contingency L3 안전망(VI·EOD) 동작 override(선택). null = 현행 정책(ViActionPolicy·allow_overnight) 그대로.
 * @param status 계획 lifecycle 상태 (default [TradePlanStatus.OPEN]). R2 가 청산 발행 시 CLOSING, 체결 시 CLOSED.
 * @param exitReason 청산 사유(선택, status≠OPEN 시 기록). 예: TP|SL|TRAIL|MAX_HOLD|EXIT_COND|VI|EOD|MANUAL|DSL.
 */
data class TradePlan(
    val planVersion: Int = CURRENT_PLAN_VERSION,
    val entry: TradePlanEntry,
    val target: TradePlanTarget? = null,
    val stop: TradePlanStop? = null,
    val trailing: TradePlanTrailing? = null,
    val hold: TradePlanHold? = null,
    val exitConditions: List<TradePlanExitCondition> = emptyList(),
    val contingency: TradePlanContingency? = null,
    val status: TradePlanStatus = TradePlanStatus.OPEN,
    val exitReason: String? = null,
) {
    init {
        require(planVersion >= 1) { "planVersion must be >= 1: $planVersion" }
    }

    companion object {
        /** 현재 스키마 버전. 미래 v2 마이그레이션 시 분기 hook. */
        const val CURRENT_PLAN_VERSION: Int = 1
    }
}

/**
 * 진입 메타 — 이 lot 을 산 전략·근거·진입가·봉·진입시각 (ADR 0130 §2 entry).
 *
 * @param strategyId 이 lot 을 산 전략 id (lot 소유권 단일 출처 — L1 교차 청산 차단의 귀속 키).
 * @param thesis 진입 근거 한 줄(선택). UI·exitReason audit 가시화용. null = 미기록.
 * @param entryPrice 진입가(원, > 0) — TP/SL 가격 산정의 base.
 * @param bar 진입 봉 interval 코드(선택, 예 "1m"·"D1"). null = 미기록.
 * @param openedAt 진입(박제) 시각 ISO-8601 문자열(선택). 도메인 순수성 유지 위해 Instant 대신 String 보관(직렬화 호환).
 */
data class TradePlanEntry(
    val strategyId: Long,
    val thesis: String? = null,
    val entryPrice: BigDecimal,
    val bar: String? = null,
    val openedAt: String? = null,
) {
    init {
        require(entryPrice > BigDecimal.ZERO) { "entryPrice must be positive: $entryPrice" }
    }
}

/**
 * 익절 목표 — 평단(진입가) 대비 수익률 또는 절대 가격 (ADR 0130 §2 target).
 *
 * @param tpPct 익절 수익률%(양수, 선택). 예 3.0.
 * @param tpPrice 익절 절대 가격(원, 양수, 선택). PlanExecutor 가 평단 연동 재계산(R2) 또는 박제값 사용.
 *   tpPct·tpPrice 둘 다 null 이면 TP 미설정(상위 [TradePlan]에서 target=null 로 표현하는 게 정상이나 허용).
 */
data class TradePlanTarget(
    val tpPct: BigDecimal? = null,
    val tpPrice: BigDecimal? = null,
) {
    init {
        tpPct?.let { require(it > BigDecimal.ZERO) { "tpPct must be positive: $it" } }
        tpPrice?.let { require(it > BigDecimal.ZERO) { "tpPrice must be positive: $it" } }
    }
}

/**
 * 손절 — 고정(FIXED) 또는 ATR 기반 (ADR 0130 §2 stop).
 *
 * @param type 손절 유형 (default [TradePlanStopType.FIXED]).
 * @param slPct 손절 손실폭%(양수, 선택). 예 2.0.
 * @param slPrice 손절 절대 가격(원, 양수, 선택).
 */
data class TradePlanStop(
    val type: TradePlanStopType = TradePlanStopType.FIXED,
    val slPct: BigDecimal? = null,
    val slPrice: BigDecimal? = null,
) {
    init {
        slPct?.let { require(it > BigDecimal.ZERO) { "slPct must be positive: $it" } }
        slPrice?.let { require(it > BigDecimal.ZERO) { "slPrice must be positive: $it" } }
    }
}

/** 손절 유형 — 고정 비율/가격 또는 ATR(변동성) 기반 (ADR 0130 §2). */
enum class TradePlanStopType { FIXED, ATR }

/**
 * 트레일링 스탑 — 활성화 후 고점 대비 trailPct% 하락 시 청산 (ADR 0130 §2 trailing).
 *
 * @param enabled 트레일링 활성 여부 (default false = 미적용).
 * @param activatePct 트레일링 활성 발동 수익률%(양수, 선택). 이 수익률 도달 후부터 trailing 추적 시작.
 * @param trailPct 고점 대비 청산 트리거 하락폭%(양수, 선택).
 * @param highWatermark 진입 후 관측 최고가 영속(원, 양수, 선택 — add-only · 감사 §6 P2-4). PlanExecutor 가
 *   인메모리 고점을 주기 write 하고 재기동 시 rehydrate 한다(고점 리셋 → 트레일링 무장 해제 재발 방지). null = 미기록.
 */
data class TradePlanTrailing(
    val enabled: Boolean = false,
    val activatePct: BigDecimal? = null,
    val trailPct: BigDecimal? = null,
    val highWatermark: BigDecimal? = null,
) {
    init {
        activatePct?.let { require(it > BigDecimal.ZERO) { "activatePct must be positive: $it" } }
        trailPct?.let { require(it > BigDecimal.ZERO) { "trailPct must be positive: $it" } }
        highWatermark?.let { require(it > BigDecimal.ZERO) { "highWatermark must be positive: $it" } }
    }
}

/**
 * 보유 봉 범위 — 최소(잔익절 방어)·최대(강제 청산 상한) (ADR 0130 §2 hold).
 *
 * @param minHoldBars 최소 보유 봉 수(>= 0, 선택). 진입 후 N봉 동안 DSL 매도 무시(잔익절 방어 — [MinHoldGuard] lot 기준).
 * @param maxHoldBars 최대 보유 봉 수(>= 1, 선택). 초과 시 PlanExecutor 가 MAX_HOLD 청산(R2).
 *   둘 다 지정 시 maxHoldBars >= minHoldBars 여야 한다.
 */
data class TradePlanHold(
    val minHoldBars: Int? = null,
    val maxHoldBars: Int? = null,
) {
    init {
        minHoldBars?.let { require(it >= 0) { "minHoldBars must be >= 0: $it" } }
        maxHoldBars?.let { require(it >= 1) { "maxHoldBars must be >= 1: $it" } }
        if (minHoldBars != null && maxHoldBars != null) {
            require(maxHoldBars >= minHoldBars) {
                "maxHoldBars($maxHoldBars) must be >= minHoldBars($minHoldBars)"
            }
        }
    }
}

/**
 * 데이터 기반 청산 조건 — 확장점 (ADR 0130 §2 exitConditions LOCK).
 *
 * 새 청산 유형은 본 리스트에 데이터(rule + params)를 추가하면 되고, 스키마 변경 0이다. PlanExecutor(R2)가
 * rule 식별자로 평가기를 dispatch 한다(예 "vwap_cross_down").
 *
 * @param rule 청산 조건 식별자(비어 있지 않음). 예 "vwap_cross_down".
 * @param params 조건 파라미터 Map(선택, default 빈 Map). 예 { "src": "1m" }. 평탄 값만(직렬화 호환).
 */
data class TradePlanExitCondition(
    val rule: String,
    val params: Map<String, Any?> = emptyMap(),
) {
    init {
        require(rule.isNotBlank()) { "exitCondition rule must not be blank" }
    }
}

/**
 * L3 안전망(VI·EOD) 동작 override — PlanExecutor 가 아닌 L3 워처가 참조 (ADR 0130 §2 contingency · R3 wiring).
 *
 * null 이거나 개별 필드 null 이면 현행 정책(ViActionPolicy·allow_overnight 토글) 그대로(zero-regression).
 *
 * @param viAction VI(변동성 완화장치) 발동 시 동작(선택, 예 "EXIT_MARKET"). [PositionViAutoExitWatcher]가 참조(R3).
 * @param eod 장마감 전 동작(선택, 예 "LIQUIDATE"). [AutopilotEodLiquidationWorker]가 참조(R3).
 */
data class TradePlanContingency(
    val viAction: String? = null,
    val eod: String? = null,
)

/**
 * 거래 계획 lifecycle 상태 (ADR 0130 §2 status).
 *
 * - [OPEN]: 진입 박제 직후 ~ 청산 발행 전(계획 활성).
 * - [CLOSING]: PlanExecutor·DSL 가 청산 SELL 을 발행했고 체결 대기 중.
 * - [CLOSED]: 전량 청산 체결 완료(다음 진입 사이클에 새 plan 으로 교체·reset).
 */
enum class TradePlanStatus { OPEN, CLOSING, CLOSED }
