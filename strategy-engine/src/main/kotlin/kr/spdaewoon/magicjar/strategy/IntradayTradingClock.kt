package kr.spdaewoon.magicjar.strategy

import java.time.LocalTime

/**
 * 초단타 매매 시간 상수 단일 출처 (KST) — ADR 0132 §D1-3 (라이브 워커 ↔ 백테스트 parity LOCK).
 *
 * ## 왜 단일 출처인가 (drift 0)
 * EOD 청산(15:15)·late-entry 차단(14:30)·개장 blackout(09:00~09:15)은 라이브에서 [AutopilotEodLiquidationWorker]·
 * `SignalGeneratedKafkaListener` open-blackout 게이트(모두 ENV `@Value` 로 주입·default 동일)에 분산돼 있다. 백테스트가
 * 이 값을 별도 하드코딩하면 라이브와 어긋나(ADR 0132 §3 기둥1 parity 붕괴) — 따라서 **라이브 워커의 default 값과
 * 동일한 상수**를 본 객체가 보유하고, 라이브 워커는 자신의 `@Value` fallback 으로 본 상수를 참조한다(ENV 가 런타임
 * 권위 · 본 상수가 코드 default 단일 출처). 백테스트는 ENV 가 없는 순수 시뮬이므로 본 default 를 그대로 쓴다.
 *
 * > ENV override 시 라이브는 ENV 값으로 동작하고 백테스트는 default 로 동작할 수 있다(백테스트는 과거 시뮬이라
 * > 미래 ENV tweak 을 따르지 않는 게 정직 — R2b 엔진이 필요 시 ENV 를 시뮬에 주입하는 hook 을 둘 수 있다).
 *
 * // AI 호출 0 — 순수 시간 상수 (ADR 0023 §2-4 정합)
 */
object IntradayTradingClock {
    /** EOD 강제 청산 cutoff (KST) — 라이브 `magicjar.autotrading.eod-liquidation.cutoff` default 15:15. */
    val EOD_LIQUIDATE_KST: LocalTime = LocalTime.of(15, 15)

    /** late-entry 차단 cutoff (KST) — 라이브 `magicjar.autotrading.eod-liquidation.late-entry-cutoff` default 14:30. */
    val LATE_ENTRY_CUTOFF_KST: LocalTime = LocalTime.of(14, 30)

    /** 개장 blackout 시작 (KST, 포함) — 라이브 `magicjar.autotrading.open-blackout.start` default 09:00. */
    val OPEN_BLACKOUT_FROM: LocalTime = LocalTime.of(9, 0)

    /** 개장 blackout 종료 (KST, 미포함) — 라이브 `magicjar.autotrading.open-blackout.end` default 09:15. */
    val OPEN_BLACKOUT_TO: LocalTime = LocalTime.of(9, 15)

    /**
     * 이 봉 ts(KST)에서 owner=AUTOPILOT BUY 가 개장 blackout 으로 억제되는가 — 윈도우 `[from, to)`.
     *
     * 라이브 `SignalGeneratedKafkaListener.isWithinOpenBlackout` 과 동일 판정(start<=t<end). SELL/SL/TP 는 영향 0.
     */
    fun isOpenBlackout(
        barTimeKst: LocalTime,
        from: LocalTime = OPEN_BLACKOUT_FROM,
        to: LocalTime = OPEN_BLACKOUT_TO,
    ): Boolean {
        if (!from.isBefore(to)) return false // start>=end → 막지 않음(보수적 · 라이브 정합).
        return !barTimeKst.isBefore(from) && barTimeKst.isBefore(to)
    }

    /**
     * 이 봉 ts(KST)가 late-entry cutoff 이상이라 신규 BUY 가 차단되는가 (allow_overnight=false 전략 한정 판정은 caller).
     *
     * 라이브 `SignalGeneratedKafkaListener.isAfterLateEntryCutoff` 와 동일(t >= cutoff).
     */
    fun isLateEntryBlocked(
        barTimeKst: LocalTime,
        cutoff: LocalTime = LATE_ENTRY_CUTOFF_KST,
    ): Boolean = !barTimeKst.isBefore(cutoff)

    /**
     * allow_overnight=false 이고 봉 ts(KST) >= EOD cutoff → EOD 강제 청산 신호.
     *
     * 라이브 `AutopilotEodLiquidationWorker` 와 동일(allow_overnight=false AND t>=cutoff). allow_overnight=true 면 false.
     */
    fun isEodLiquidate(
        allowOvernight: Boolean,
        barTimeKst: LocalTime,
        cutoff: LocalTime = EOD_LIQUIDATE_KST,
    ): Boolean = !allowOvernight && !barTimeKst.isBefore(cutoff)
}
