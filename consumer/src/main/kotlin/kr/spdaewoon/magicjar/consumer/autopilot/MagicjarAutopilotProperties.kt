package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
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
    /**
     * 재진입 churn 억제 (follow-up I, 2026-06-22) — owner=AUTOPILOT 가 한 code 를 **전량 청산(SELL→qty 0)** 한 뒤
     * 본 분만큼 같은 code 의 autopilot BUY 를 suppress 한다. ENV `MAGICJAR_AUTOPILOT_REENTRY_COOLDOWN_MINUTES`.
     *
     * ## 윈도우 근거 (default [DEFAULT_REENTRY_COOLDOWN_MINUTES]=15분)
     * 오늘 실측: owner=AUTOPILOT 11왕복(회전 매수 261만/매도 232만 ≈ 소액 526k 의 3.5배)이 gross 알파 +7,950 중
     * **5,222(66%)를 거래비용(매도세 0.18% + 수수료)으로 잠식**(net +2,728). 같은 code 를 buy→TP/exit→분 뒤 재매수
     * 반복(006340 13:45·14:09 등)이 churn 손실원. 단타 TF=1분봉 기준 15분(=15봉)이면 청산 직후 noise/whipsaw 재진입은
     * 막고, 추세가 실제로 재형성될 호흡은 보존한다(왕복 비용 ~0.21% > 즉시 재진입 기대수익). 0 이하면 쿨다운 비활성
     * (게이트 통째 skip = 기존 경로 byte 동일). [enabled]=false 면 autopilot BUY 자체가 안 생성되므로 본 값도 무의미.
     */
    val reentryCooldownMinutes: Long = DEFAULT_REENTRY_COOLDOWN_MINUTES,
    /**
     * 추격 가격 가드 (ADR 0129 레버 3) — owner=AUTOPILOT 가 같은 code 를 직전 청산가 대비 본 % 이상 **비싸게**
     * 재매수하려 하면 suppress 한다. ENV `MAGICJAR_AUTOPILOT_REENTRY_PRICE_GUARD_PCT`. default
     * [DEFAULT_REENTRY_PRICE_GUARD_PCT]=1.0%.
     *
     * ## 가격 축 (시간 축과 직교)
     * 시간 쿨다운([reentryCooldownMinutes])이 풀린 뒤라도, 같은 세션 직전 청산가보다 본 % 이상 높은 가격이면
     * 추격으로 보고 차단한다(2026-06-23 006340 11,070→12,340 +11% 추격 복기). "더 싸게·같은 가격에만 재진입" 이
     * 추세추종-비용 균형의 보수적 디폴트. 직전 청산가는 [AutopilotReentryCooldownGuard] 가 쿨다운 키에 `ts:price` 로
     * 동반 기록하므로 쿨다운 윈도우가 살아있는 동안에만 적용된다(윈도우 경과 시 키 자연 소멸 → 가격 축도 무효).
     *
     * **0 이하면 가격 축 비활성**(시간 축만 유지 — 즉시 롤백 경로). [enabled]=false 면 autopilot BUY 자체가 안
     * 생성되므로 본 값도 무의미.
     */
    val reentryPriceGuardPct: BigDecimal = DEFAULT_REENTRY_PRICE_GUARD_PCT,
    /**
     * 종목당 1일 왕복 cap (planned-entry churn 저감, 2026-06-24) — owner=AUTOPILOT 가 같은 code 를 하루에 본 횟수만큼
     * **전량 청산(왕복 완료)** 하면, 그날 같은 code 의 신규 BUY 재진입을 suppress 한다. ENV
     * `MAGICJAR_AUTOPILOT_MAX_REENTRIES_PER_CODE_PER_DAY`. default [DEFAULT_MAX_REENTRIES_PER_CODE_PER_DAY].
     *
     * ## 근거 (누적 축 — 시간/가격 축과 직교)
     * 쿨다운([reentryCooldownMinutes]=15분)은 청산 직후 즉시 재진입만 막아, 윈도우 경과 후 같은 code 를 하루 종일
     * 반복 왕복하는 누적 churn 은 못 묶는다. 실측 2026-06-23: 122640 한 종목이 ~8왕복, gross 26,016 중 거래비용
     * 12,084(46%)가 알파 잠식. 본 cap 은 한 종목의 **누적 왕복 횟수**를 묶어 비용 출혈을 종목 단위로 bound 한다.
     * default 3 = "한 종목 하루 최대 3왕복" — 정당한 추세 재진입 몇 회는 허용하되 8왕복 같은 churn 은 차단(보수적 큰 값).
     *
     * **0 이하면 cap 비활성**(누적 축 통째 skip = 기존 경로 byte 동일·롤백 경로). [enabled]=false 면 autopilot BUY 자체가
     * 안 생성되므로 본 값도 무의미.
     */
    val maxReentriesPerCodePerDay: Int = DEFAULT_MAX_REENTRIES_PER_CODE_PER_DAY,
) {
    /**
     * 실시간 BUY 신호 구동 평가 쿼리에 넘길 **owner 집합** (spec 2026-06-22 §2 Phase 1 wiring · DRY 단일 출처).
     *
     * - [enabled]=false → `["USER"]` → AUTOPILOT 평가 0 = v3.14.0 byte 동일(zero-regression).
     * - [enabled]=true  → `["USER","AUTOPILOT"]` → 루프 생성 전략도 BUY 구동 평가·시세 universe 진입.
     *
     * BUY 구동 3 호출자([ConsumerStrategyLookupJpaAdapter]·[kr.spdaewoon.magicjar.consumer.ingestion.ActiveStrategyCodeProvider]·
     * `ManagedCandleUniverseProvider`)가 동일 집합을 공유해 평가·universe가 어긋나지 않게 한다. **청산(SELL) 쿼리는
     * 이 게이트를 쓰지 않는다** — 청산은 보유분을 닫는 행위이므로 enabled 무관 항상 AUTOPILOT 포함(BUY/청산 비대칭).
     */
    fun buyEvalOwners(): List<String> = if (enabled) ENABLED_BUY_EVAL_OWNERS else DISABLED_BUY_EVAL_OWNERS

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

        /**
         * 재진입 쿨다운 default = 15분 (단타 1분봉 15봉 — churn 비용 회수 + 정당 추세 재형성 호흡 보존, follow-up I).
         * 너무 빡빡하면(예: 60분) 정당 추세추종 재진입까지 막으니 단타 TF 1분봉 기준 보수적 15분으로 둔다.
         */
        const val DEFAULT_REENTRY_COOLDOWN_MINUTES: Long = 15L

        /**
         * 추격 가격 가드 default = 1.0% (ADR 0129 레버 3). 직전 청산가 +1% 이상 비싸면 추격으로 차단.
         * 보수적 작은 값 — 정당한 추세 재진입의 자연 호흡(작은 등락)은 허용하되 명확한 추격(+1%↑)만 막는다.
         */
        val DEFAULT_REENTRY_PRICE_GUARD_PCT: BigDecimal = BigDecimal("1.0")

        /**
         * 종목당 1일 왕복 cap default = 3 (planned-entry churn 저감, 2026-06-24). 한 code 하루 3왕복 도달 후 재진입 차단.
         * 보수적 큰 값 — 정당한 추세 재진입 몇 회는 허용하되 실측 8왕복 같은 누적 churn 만 묶는다. 0 이하면 비활성.
         */
        const val DEFAULT_MAX_REENTRIES_PER_CODE_PER_DAY: Int = 3

        /**
         * BUY 구동 평가 owner 집합 — [StrategyOwner] enum name 과 동일 문자열(DB owner VARCHAR 값과 정합).
         * 불변 리스트 상수로 둬 호출마다 재할당 0([buyEvalOwners] 가 enabled 분기로 둘 중 하나 반환).
         */
        private val DISABLED_BUY_EVAL_OWNERS: List<String> = listOf(StrategyOwner.USER.name)
        private val ENABLED_BUY_EVAL_OWNERS: List<String> = listOf(StrategyOwner.USER.name, StrategyOwner.AUTOPILOT.name)
    }
}
