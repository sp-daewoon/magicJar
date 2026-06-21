package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * magicjar-autopilot 자율 루프 안전 하니스 **시드/부트 default 단일 출처**
 * (`@ConfigurationProperties(prefix = "magicjar.autopilot")` · spec 2026-06-22 §2-0-A · Task 3 DRY).
 *
 * ## DRY 단일화 (Task 1 노트 해소 — 진보 페르소나)
 * autopilot 한도(pool/per-trade/max-positions/daily-loss/cum-loss)의 default 가 그동안 세 곳에 흩어져 있었다:
 *  1. [AutopilotState] companion (`DEFAULT_*`) — 도메인 데이터 클래스 default.
 *  2. `V91__autopilot_state.sql` 컬럼 DEFAULT — DB 시드.
 *  3. `application.yml` `magicjar.autopilot.*` placeholder default.
 *
 * **런타임 권위는 `autopilot_state` 행**(DB)이다. 그러나 그 행이 아직 시드되지 않았거나(부트 1회차) 조회
 * 실패 시 사용할 **부트 default 의 단일 코드 출처**가 본 클래스다. yml `magicjar.autopilot.*` → 본 클래스로
 * 바인딩되고, ENV(`MAGICJAR_AUTOPILOT_*`)는 yml placeholder 를 통해 흘러든다(application.yml 정합).
 *
 * Flyway 컬럼 DEFAULT 는 DB 차원 안전망으로 **유지**하되(SQL 주석에 "본 properties 가 권위" 명시), 코드 경로의
 * default 는 본 클래스 하나로 수렴한다([AutopilotState] companion 도 본 클래스 [toDefaultState] 가 우선).
 *
 * ## 게이트 (spec §3-bis Tier A — 폐기 핵심)
 * [enabled] default **false** — 미설정 시 autopilot 전 기능 비활성 = v3.14.0 byte-동일(zero-regression).
 * `true` 일 때만 [AutopilotBuyGuard]·[AutopilotKillSwitch]·[AutopilotLossAccumulator] 가 실제 경로에 개입한다.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 config (ADR 0023 §2-4 정합)
 */
@ConfigurationProperties(prefix = "magicjar.autopilot")
data class MagicjarAutopilotProperties(
    /** spec §3-bis Tier A 게이트 — false(default)면 autopilot 전 경로 비활성(zero-regression). */
    val enabled: Boolean = false,
    /** spec §2-0-A — 루프 전용 자본 풀 상한 = 계좌 순자산 %. ENV `MAGICJAR_AUTOPILOT_POOL_PCT`. */
    val poolPct: BigDecimal = AutopilotState.DEFAULT_POOL_PCT,
    /** spec §2-0-A — 건당 진입 캡 = 풀 %. ENV `MAGICJAR_AUTOPILOT_PER_TRADE_PCT`. */
    val perTradePct: BigDecimal = AutopilotState.DEFAULT_PER_TRADE_PCT,
    /** spec §2-0-A — 동시 보유 종목수 상한. ENV `MAGICJAR_AUTOPILOT_MAX_POSITIONS`. */
    val maxPositions: Int = AutopilotState.DEFAULT_MAX_POSITIONS,
    /** spec §2-0-A — 일일 손실 한도 = 풀 % → 당일 halt. ENV `MAGICJAR_AUTOPILOT_DAILY_LOSS_PCT`. */
    val dailyLossPct: BigDecimal = AutopilotState.DEFAULT_DAILY_LOSS_PCT,
    /** spec §2-0-A — 누적 손실 한도 = 풀 % → kill switch. ENV `MAGICJAR_AUTOPILOT_CUM_LOSS_PCT`. */
    val cumLossPct: BigDecimal = AutopilotState.DEFAULT_CUM_LOSS_PCT,
    /**
     * spec §2-0-B 하드 플로어 — kill 해제(deactivate) 영구 거부 임계 = **풀 상한의 %** (default 100 = 풀 전소).
     *
     * `lifetimeRealizedLoss >= poolCeiling × lifetimeFloorPct/100` 이면 [AutopilotControlListener] 의 RESUME 이
     * 코드로 거부된다(무한 reset 출혈 방어 — 사용자 재가만 해제). 풀 상한 = 순자산 × poolPct/100 이므로
     * default 100 → 순자산의 poolPct%(=5%) 전액 소진 시 영구 kill. ENV `MAGICJAR_AUTOPILOT_LIFETIME_FLOOR_PCT`.
     */
    val lifetimeFloorPct: BigDecimal = DEFAULT_LIFETIME_FLOOR_PCT,
    /**
     * autopilot account-wide 단일 계좌 userId — 제어 평면(status·resume floor 검사)이 순자산·전략수 조회에 쓴다.
     * autopilot 은 account-wide 단일 행(id=1)이며 canary 는 단일 사용자다. ENV `MAGICJAR_AUTOPILOT_ACCOUNT_USER_ID`.
     */
    val accountUserId: String = DEFAULT_ACCOUNT_USER_ID,
) {
    /**
     * 부트 default [AutopilotState] — `autopilot_state` 행이 부재·조회 실패 시 사용할 한도 config(손실 0·kill off).
     * DB 행이 있으면 그 행이 권위이고, 본 메서드는 시드/fallback 전용이다.
     */
    fun toDefaultState(): AutopilotState =
        AutopilotState(
            killActive = false,
            poolPct = poolPct,
            perTradePct = perTradePct,
            maxPositions = maxPositions,
            dailyLossPct = dailyLossPct,
            cumLossPct = cumLossPct,
        )

    companion object {
        /** 하드 플로어 default = 풀 상한의 100%(풀 전소 = 순자산 poolPct% 전액 소진). */
        val DEFAULT_LIFETIME_FLOOR_PCT: BigDecimal = BigDecimal("100")

        /** account-wide 단일 계좌 userId default(canary 단일 사용자). */
        const val DEFAULT_ACCOUNT_USER_ID: String = "1"
    }
}
