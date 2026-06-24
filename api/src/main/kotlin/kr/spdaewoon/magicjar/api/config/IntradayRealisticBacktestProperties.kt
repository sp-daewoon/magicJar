package kr.spdaewoon.magicjar.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 초단타 정합 백테스트(INTRADAY_REALISTIC) 게이트 properties — ADR 0132 §2-B·§4-A R2b (3 ENV).
 *
 * 신규 모드는 **opt-in**(게이트 default OFF)이라 미설정 시 백테스트는 LEGACY 그대로(byte-동일·zero-regression).
 * orchestrator가 검증 후에만 [enabled]를 켠다(롤백 = 게이트 false 복귀 → 신규 경로 전부 no-op).
 *
 * Override 패턴 (env override 1:1 · application.yml `magicjar.backtest.intraday-realistic`):
 *   ```
 *   magicjar:
 *     backtest:
 *       intraday-realistic:
 *         enabled: false            # MAGICJAR_BACKTEST_INTRADAY_REALISTIC_ENABLED
 *         backfill-enabled: false   # MAGICJAR_BACKTEST_INTRADAY_REALISTIC_BACKFILL_ENABLED
 *         max-lookback-days: 30     # MAGICJAR_BACKTEST_INTRADAY_REALISTIC_MAX_LOOKBACK_DAYS
 *   ```
 *
 * 진보 페르소나 결정 메모 (ADR 0087):
 *   - 💡 3 ENV를 단일 `@ConfigurationProperties` 값 객체로 묶어 엔진 생성자 의존을 1개로 응집(DRY · 보일러플레이트 0).
 *   - ✨ [resolveBackfillEnabled] 확장 fun으로 "ENV 게이트 ∧ 요청 플래그" AND 규칙을 단일 함수화 — 엔진이 직접 boolean 곱 안 함.
 */
@ConfigurationProperties(prefix = "magicjar.backtest.intraday-realistic")
data class IntradayRealisticBacktestProperties(
    /**
     * INTRADAY_REALISTIC 모드 마스터 게이트. default **false**(opt-in · zero-regression LOCK).
     *
     * false면 요청 `executionMode=INTRADAY_REALISTIC`이어도 엔진은 LEGACY로 강등 시뮬한다(자본경로 보호 — orchestrator 결재 전 신규 경로 차단).
     */
    val enabled: Boolean = false,
    /**
     * 분봉 on-demand backfill on/off. default **false**(라이브 자본경로 보호 — KIS 호출 폭증 차단).
     *
     * false면 [BacktestRequest.backfillIfMissing]가 true여도 fetch 0(DB 기존 봉만 평가 → 부족 시 INSUFFICIENT_DATA).
     * 실제 backfill은 본 게이트 ∧ 요청 플래그 ∧ 포트 존재(R2a 머지) 셋 모두 충족 시에만 발화한다.
     */
    val backfillEnabled: Boolean = false,
    /**
     * KIS 1m lookback bound (거래일). default **30**(약 150 call/종목 — spec §3 KIS 1년 보관 한계 내 보수적 클램프).
     *
     * 요청 from이 (to − maxLookbackDays)보다 과거면 그 경계로 clamp(거짓 결과 차단 — lookbackClamped 플래그).
     * 상한은 KIS 1년 ∧ ADR 0105 retention(포트 구현체 책임).
     */
    val maxLookbackDays: Int = 30,
) {
    /**
     * 실제 backfill 발화 여부 — ENV 게이트([backfillEnabled]) ∧ 요청 플래그([requestBackfillIfMissing]) AND.
     *
     * 둘 중 하나라도 false면 fetch 0(DB 기존 봉만). 포트 구현체(R2a) 부재 시는 엔진이 추가로 short-circuit(포트 null guard).
     */
    fun resolveBackfillEnabled(requestBackfillIfMissing: Boolean): Boolean = backfillEnabled && requestBackfillIfMissing

    /** [maxLookbackDays] sanity — 1 이상으로 정규화(0/음수 방어). 상한 클램프는 포트(R2a) 책임. */
    fun normalizedMaxLookbackDays(): Int = maxLookbackDays.coerceAtLeast(1)
}
