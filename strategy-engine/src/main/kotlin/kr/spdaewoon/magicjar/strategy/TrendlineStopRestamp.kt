package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.strategy.entry.KrxTickSize
import java.math.BigDecimal

/**
 * 추세선 손절 봉 경계 재박제 순수 함수 — ADR 0140 §3-4 (선-기반 동적 SL · 추세매매 Phase 2 R2b).
 *
 * ## 왜 strategy-engine 순수 함수인가 (헥사고날 LOCK · [TradePlanEvaluator]/[MinHoldGuard] 패턴 정합)
 * 봉 경계마다 추세선 선형보간 가격으로 [kr.spdaewoon.magicjar.domain.strategy.TradePlanStop.slPrice] 를 갱신할지,
 * 갱신한다면 어느 값으로 할지의 **판정 로직**(호가단위 내림 + 안전 가드)은 외부 I/O 0 인 순수 결정론이어야 상향/하향/
 * 가드/부재 각 경로를 단위 테스트로 엄밀히 증명할 수 있다(메모리 `feedback_verification_strict` — "단위테스트≠라이브").
 * 따라서 선 조회(port)·DB write·Kafka 는 consumer([PlanExecutor.restampTrendlineStops])가, 재박제 산식은 본 순수 객체가 책임진다.
 *
 * ## 재박제 알고리즘 (ADR 0140 §3-4 보수안 LOCK — tick 보간 확장 금지)
 * 1. 선 가격(`linePrice`)을 KRX 호가단위로 **내림**([KrxTickSize.floorToTick] 단일 출처 — [TradePlanEvaluator] 정합).
 * 2. 후보 손절가(candidate)를 현재 박제값(`currentSlPrice`)과 비교해 안전 가드로 채택 여부 결정.
 *
 * ## 안전 가드 (ADR 0140 §3-4 · 태스크 §4-① 보수 default — KDoc 근거 명시)
 * ADR/spec 에 상향/하향 명시가 없으므로 **보수 default = "선 가격을 추종하되, 이미 체결가 위로 올라간 손절을
 * 아래로 내리는 것은 금지(손실 확대 방향 금지)"** 를 채택한다:
 * - **상향(candidate ≥ current)**: 항상 채택 — 손절을 위로 조여 리스크를 줄이는 방향(우상향 추세선 추종 = 트레일링 유사).
 * - **하향(candidate < current)** 두 갈래:
 *   - `current ≥ entryPrice`(손절이 이미 손익분기/이익 구간으로 상승 잠금됨) → **거부·유지**([Decision.Keep]).
 *     잠근 이익 보호를 되돌리지 않는다(손실 확대 방향 금지 — 트레일링 래칫과 동일 정신).
 *   - `current < entryPrice`(손절이 아직 진입가 아래 = 손실 구간) → 채택. 사용자가 그은 선을 그대로 추종
 *     (선이 하락하면 손절도 하락 — 사용자 지정 지지선의 충실한 반영). 진입가 상향 잠금 이후엔 위 가드가 되돌림을 차단.
 * - **최초 박제(current==null)**: candidate 채택(첫 선 가격 고정).
 *
 * ## 부재/무효 (ADR 0140 §3-6 · §11-2 silent 금지 · 태스크 §4-②)
 * `linePrice==null`(선 부재/범위 밖·조회 실패) 또는 후보가 ≤ 0(무효)면 **마지막 박제값 유지**([Decision.Keep])하고
 * caller 가 WARN 로그로 가시화한다. 선이 사라져도 마지막 유효 손절가로 보호가 이어진다(자본 안전 방향).
 *
 * ## 롱온리 정합 (헌법 §13 · 태스크 §4-③)
 * 손절가는 롱 포지션의 하한(SELL 트리거)이며 항상 양수(> 0)로 유지된다. 재박제는 slPrice(양수 하한)만 다루고
 * side/청산 로직은 변경하지 않는다([TradePlanEvaluator] 의 `price <= sl` 판정 재사용).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 평가 (ADR 0023 §2-4 정합)
 */
object TrendlineStopRestamp {
    /**
     * 봉 경계 재박제 결정.
     *
     * - [NewPrice]: [slPrice] 로 재박제(상향 또는 손실구간 추종 하향).
     * - [Keep]: 박제값 유지(변화 없음). [reason] = 유지 사유(관측·로그용).
     */
    sealed interface Decision {
        data class NewPrice(
            val slPrice: BigDecimal,
        ) : Decision

        data class Keep(
            val reason: KeepReason,
        ) : Decision
    }

    /** 유지 사유 — 관측(ADR 0140 §6 관측 매트릭스)·로그 식별자. */
    enum class KeepReason {
        /** 선 부재/범위 밖·조회 실패(linePrice==null) — 마지막 박제값 유지 + caller WARN. */
        LINE_UNAVAILABLE,

        /** 호가단위 내림 결과 ≤ 0(무효 선 가격) — 유지. */
        INVALID_LINE_PRICE,

        /** 하향 재박제인데 현재 손절이 이미 진입가 이상(이익 잠금) — 되돌림 금지(손실 확대 방향 금지). */
        LOCKED_ABOVE_ENTRY,

        /** 후보가 현재 박제값과 동일(호가단위 내림 후 무변) — write 절약(유지). */
        NO_CHANGE,
    }

    /**
     * 추세선 손절 재박제 판정 (순수).
     *
     * @param entryPrice 이 lot 진입가(> 0) — 이익 잠금(하향 금지) 경계.
     * @param currentSlPrice 현재 박제된 손절가(원, > 0). null = 최초 박제(아직 선 가격 미고정).
     * @param linePrice 이번 봉 경계 시점의 추세선 선형보간 가격(원). null = 선 부재/범위 밖/조회 실패.
     * @return [Decision.NewPrice] 재박제 값 또는 [Decision.Keep] 유지 사유.
     */
    fun decide(
        entryPrice: BigDecimal,
        currentSlPrice: BigDecimal?,
        linePrice: BigDecimal?,
    ): Decision {
        // ② 선 부재/범위 밖/조회 실패 — 마지막 박제값 유지(caller WARN).
        val line = linePrice ?: return Decision.Keep(KeepReason.LINE_UNAVAILABLE)

        // 호가단위 내림([TradePlanEvaluator.stopPriceOf] 정합 — slPrice 는 tick 위에 있어야 KIS 정상 접수).
        val candidate = KrxTickSize.floorToTick(line)
        if (candidate <= BigDecimal.ZERO) return Decision.Keep(KeepReason.INVALID_LINE_PRICE)

        // 최초 박제 — 첫 선 가격 고정.
        val current = currentSlPrice ?: return Decision.NewPrice(candidate)

        val cmp = candidate.compareTo(current)
        if (cmp == 0) return Decision.Keep(KeepReason.NO_CHANGE) // 무변 — write 절약.
        if (cmp > 0) return Decision.NewPrice(candidate) // 상향 — 항상 채택(리스크 축소·트레일링 유사).

        // 하향(candidate < current) — 이익 잠금 구간이면 되돌림 금지, 손실 구간이면 선 추종.
        return if (current >= entryPrice) {
            Decision.Keep(KeepReason.LOCKED_ABOVE_ENTRY) // 손실 확대 방향 금지(잠근 이익 보존).
        } else {
            Decision.NewPrice(candidate) // 진입가 아래 = 손실 구간 — 사용자 지정 선을 충실히 추종.
        }
    }
}
