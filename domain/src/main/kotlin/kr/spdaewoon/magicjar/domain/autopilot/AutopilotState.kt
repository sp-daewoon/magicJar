package kr.spdaewoon.magicjar.domain.autopilot

import java.math.BigDecimal
import java.time.LocalDate

/**
 * magicjar-autopilot 자율 루프 안전 하니스의 **account-wide 단일 권위 상태** —
 * `autopilot_state` 테이블(V91, id=1) 한 행에 대응하는 **순수 도메인 데이터 클래스**.
 *
 * 이 클래스는 **순수 도메인** — Spring·JPA·Jackson 어노테이션 import 절대 금지(헥사고날 정합).
 * 영속 매핑은 어댑터 모듈(api/consumer/persistence-shared) 책임이며, 본 클래스는 컬럼을 평탄하게 미러링한다.
 *
 * ## 폐기 철학(spec §3-bis Tier A)
 * autopilot 전용 패키지(`kr.spdaewoon.magicjar.domain.autopilot`)에 격리된다.
 * `MAGICJAR_AUTOPILOT_ENABLED=false`(default)면 어떤 코드도 이 상태를 읽지 않는다 → v3.14.0 zero-regression.
 *
 * ## 안전 한도(spec §2-0-A — 전부 보수적 default, % 단위)
 * 모든 한도는 `RiskGatewayEvaluator`/`RiskBudgetCalculator` 계열 코드 경로에서 강제되며,
 * 기존 계좌 전체 공유 풀 위에 **더 좁게** 겹쳐 적용된다(둘 중 보수적인 값이 이김).
 *
 * @property killActive         kill switch(spec §2-0-B) — true면 owner=AUTOPILOT REAL 경로 freeze.
 * @property poolPct            루프 전용 자본 풀 상한 = 계좌 순자산 %.
 * @property perTradePct        건당 진입 캡 = 풀 %.
 * @property maxPositions       루프 동시 보유 종목수 상한.
 * @property dailyLossPct       일일 손실 한도 = 풀 % → 도달 시 당일 halt.
 * @property cumLossPct         누적 손실 한도 = 풀 % → 도달 시 kill switch 자동 ON.
 * @property cumRealizedLoss    루프 누적 실현 손실(양수 = 손실 크기). kill 해제(deactivate) 시 0 으로 리셋 가능.
 * @property dailyRealizedLoss  당일 실현 손실(양수 = 손실 크기). [lastResetDate] 기준 일일 리셋.
 * @property lastResetDate      [dailyRealizedLoss] 마지막 리셋 일자.
 * @property lifetimeRealizedLoss 하드 플로어(spec §2-0-B) — **영구 누적** 실현 손실(양수). kill-reset 에도 리셋
 *                              안 됨. 풀상한 × lifetimeFloorPct/100 도달 시 kill 해제(deactivate)가 코드로 거부된다
 *                              (무한 reset 출혈 방어 — 사용자 재가만 해제). V92 추가.
 */
data class AutopilotState(
    val killActive: Boolean = false,
    val poolPct: BigDecimal = DEFAULT_POOL_PCT,
    val perTradePct: BigDecimal = DEFAULT_PER_TRADE_PCT,
    val maxPositions: Int = DEFAULT_MAX_POSITIONS,
    val dailyLossPct: BigDecimal = DEFAULT_DAILY_LOSS_PCT,
    val cumLossPct: BigDecimal = DEFAULT_CUM_LOSS_PCT,
    val cumRealizedLoss: BigDecimal = BigDecimal.ZERO,
    val dailyRealizedLoss: BigDecimal = BigDecimal.ZERO,
    val lastResetDate: LocalDate? = null,
    val lifetimeRealizedLoss: BigDecimal = BigDecimal.ZERO,
) {
    /** kill switch 활성 여부 — 게이트 가독성용 명시 getter(spec §2-0-B). */
    val isKillActive: Boolean
        get() = killActive

    companion object {
        /** spec §2-0-A — 루프 전용 자본 풀 = 계좌 순자산의 5%. ENV `MAGICJAR_AUTOPILOT_POOL_PCT`. */
        val DEFAULT_POOL_PCT: BigDecimal = BigDecimal("5")

        /** spec §2-0-A — 건당 진입 캡 = 풀의 20%. ENV `MAGICJAR_AUTOPILOT_PER_TRADE_PCT`. */
        val DEFAULT_PER_TRADE_PCT: BigDecimal = BigDecimal("20")

        /** spec §2-0-A — 동시 보유 종목수 3. ENV `MAGICJAR_AUTOPILOT_MAX_POSITIONS`. */
        const val DEFAULT_MAX_POSITIONS: Int = 3

        /** spec §2-0-A — 일일 손실 한도 = 풀의 30% → 당일 halt. ENV `MAGICJAR_AUTOPILOT_DAILY_LOSS_PCT`. */
        val DEFAULT_DAILY_LOSS_PCT: BigDecimal = BigDecimal("30")

        /** spec §2-0-A — 누적 손실 한도 = 풀의 50% → kill switch. ENV `MAGICJAR_AUTOPILOT_CUM_LOSS_PCT`. */
        val DEFAULT_CUM_LOSS_PCT: BigDecimal = BigDecimal("50")
    }
}
