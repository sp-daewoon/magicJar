package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.strategy.EvaluatorAutoDisableCallback
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Auto-pause 가드 — 연속 BLOCKED N회 시 strategy 신규 BUY 차단(BUY_BLOCKED).
 *
 * 임계 도달 시 [EvaluatorAutoDisableCallback.onAutoDisable] 호출. RISK_VIOLATION은 전체 평가 중지가 아니라
 * BUY_BLOCKED 상태로 전환되어 SELL/익절/손절 평가는 계속 유지한다.
 *
 * spec 인용: ADR 0067 §7-1 AutoPauseGuard + §7-2 단계 5 + F-1 보호 처리.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class AutoPauseGuard(
    private val riskEventRepositoryProvider: ObjectProvider<RiskEventRepository>,
    private val autoDisableCallback: EvaluatorAutoDisableCallback,
) {
    private val log = LoggerFactory.getLogger(AutoPauseGuard::class.java)

    companion object {
        /** BLOCKED 누적 검사 window (분) — ADR 0067 §2-6 threshold 산출 사유 정합. */
        const val DEFAULT_WINDOW_MINUTES = 10L

        /**
         * 자동정지 임계에서 제외하는 **무해 가드 rule** set (F9 — 2026-06-12 최고 관리자 결재).
         *
         * 진보 페르소나 (ADR 0087):
         *   - 💡 [문제] threshold(10) 도달 자동정지는 "자본 위험 신호의 연속 누적"을 가정한다. 그런데 기존
         *     count 는 **verdict=BLOCKED 전수**라, flat 전략이 매분 발생시키는 정상 가드(보유 0 SELL 차단 등)가
         *     10분이면 임계를 채워 모든 상태형 exit 전략을 자동정지시키는 구조 결함이었다(10:34 전략 85·14:22 전략 86).
         *   - 🔄 [리팩토링] 무해 rule 을 SQL 카운트 단계에서 제외 — 임계 산식 자체는 무손상(threshold·전환 메커니즘
         *     변경 0). `NotificationDispatcher.SLACK_MUTED_RISK_RULES` 의 상수 set 패턴과 정합(한 곳만 보강).
         *   - ✨ [모던 패턴] enum.name 문자열 set — DB 에 저장된 rule 문자열과 직접 매칭(adapter 변환 0).
         *
         * 제외 분류 근거:
         *   - [RiskRule.SELL_WITHOUT_POSITION]     : 보유 0 의 SELL 차단 = 정상 가드 (long-only-trading.md §4 권위).
         *   - [RiskRule.MAX_CONCURRENT_POSITIONS]  : 보유 중 중복 매수 차단 = 정상 거동(이미 진입한 자본 보호 X).
         *   - [RiskRule.SELL_QUANTITY_UNAVAILABLE] : 익절/손절 선주문 예약 중 SELL 가용 0 = 정상 거동.
         *   - [RiskRule.AUTOPILOT_REENTRY_COOLDOWN]: autopilot 청산 직후 churn(과회전) 자기억제 = 정상 거동 (ADR 0129 레버5-B).
         *   - [RiskRule.AUTOPILOT_CODE_DEDUP]      : autopilot 같은 code 동시 중복 진입 자기억제 = 정상 분산 거동 (ADR 0129 레버5-B).
         *
         * **유지(임계 카운트)**: DAILY_LOSS_LIMIT·POSITION_LIMIT·TRADE_FREQUENCY_MINUTE/DAY·KILL_SWITCH_ON·
         * INVALID_SIGNAL_PRICE·RISK_PLAN_REQUIRED·INVALID_ORDER_INTENT·KIS_EXECUTION_SYNC_STALE·USER_DISABLED
         * — 실제 자본 위험·예산 초과 시도·데이터/운영 무결성 신호.
         *
         * **레버5-B 회귀 fix (ADR 0129 · 2026-06-23 첫 손실일 복기)**:
         *   진보 페르소나 (ADR 0087):
         *     - 💡 [문제] follow-up I(#1109)·개선 C(#1100)가 도입한 자기억제 가드
         *       (`AUTOPILOT_REENTRY_COOLDOWN`·`AUTOPILOT_CODE_DEDUP`)가 BLOCKED 카운트에 기여하면, autopilot 이
         *       *스스로 churn·중복을 억제하는 정상 거동*이 임계를 채워 전략을 죽인다(F9 와 동형 회귀 —
         *       2026-06-23 개장 12분 만에 6전략 중 5개 auto BUY_BLOCKED). 기존 F9 fix 는 옛 무해룰만 제외하고
         *       신규 autopilot 자기억제 룰을 빠뜨렸다.
         *     - 🔄 [리팩토링] 자기억제 룰을 benign set 에 add-only 로 합류 — 임계 산식 무손상(threshold·전환
         *       메커니즘 변경 0). 자기억제는 본질적으로 자본 위험이 아니므로 owner 무관 전역 제외(USER/SYSTEM 엔
         *       애초 미발생하는 autopilot 전용 룰이라 격리 안전).
         *
         * **ADR 0129 진입 가드 3룰 합류 (ADR 0130 R-aux · 2026-06-23 14:00 라이브 evidence)**:
         *   진보 페르소나 (ADR 0087):
         *     - 💡 [문제] ADR 0129 가 추가한 진입 자기억제 가드 3룰
         *       (`AUTOPILOT_LATE_ENTRY_BLOCKED`·`AUTOPILOT_OPEN_BLACKOUT`·`AUTOPILOT_REENTRY_PRICE_GUARD`)이
         *       benign set 에 빠져 있어 BLOCKED 카운트에 기여 → 임계 도달 → 전략 자동정지(레버5-B 와 동형 회귀).
         *       2026-06-23 14:00 재활성 시 LATE_ENTRY 가 auto-pause 를 실제 발동시킨 것이 라이브 확인됐다.
         *       위 자기억제 룰(COOLDOWN·CODE_DEDUP) 합류 때와 같은 누락(신규 autopilot 전용 룰을 빠뜨림)이 반복됐다.
         *     - 🔄 [리팩토링] 진입 가드 3룰을 benign set 에 add-only 로 합류 — 임계 산식 무손상(threshold·전환
         *       메커니즘 변경 0). "늦은 진입 차단·개장 blackout·재진입 추격가 차단"은 *autopilot 이 스스로 나쁜
         *       진입을 거르는 정상 거동*이지 자본 위험 신호가 아니므로 owner 무관 전역 제외(USER/SYSTEM 엔 애초
         *       미발생하는 autopilot 전용 룰이라 격리 안전).
         */
        val BENIGN_AUTO_PAUSE_EXCLUDED_RULES: Set<String> =
            setOf(
                RiskRule.SELL_WITHOUT_POSITION,
                RiskRule.MAX_CONCURRENT_POSITIONS,
                RiskRule.SELL_QUANTITY_UNAVAILABLE,
                RiskRule.AUTOPILOT_REENTRY_COOLDOWN,
                RiskRule.AUTOPILOT_CODE_DEDUP,
                RiskRule.AUTOPILOT_LATE_ENTRY_BLOCKED,
                RiskRule.AUTOPILOT_OPEN_BLACKOUT,
                RiskRule.AUTOPILOT_REENTRY_PRICE_GUARD,
            ).map { it.name }.toSet()

        /**
         * owner=AUTOPILOT 전용 자동정지 제외 set (ADR 0129 레버5-C — 공유풀 과민 차단).
         *
         * 진보 페르소나 (ADR 0087):
         *   - 💡 [문제] magicjar-autopilot 은 N전략(100~105)이 **계좌 전체 1개 공유풀**(메모리
         *     `feedback_position_limit_account_wide`)을 나눠 쓴다. 이 구조에서 "풀이 참(full)"은 위험이 아니라
         *     **설계대로의 정상 상태**다. 6전략이 동시에 [RiskRule.POSITION_LIMIT] BLOCKED 를 내는 것은 정상인데,
         *     그것으로 하루 전체 전략을 비활성화하는 것은 과민하다(2026-06-23 좀비 포지션 #507 → 풀 잠금 →
         *     POSITION_LIMIT 20건 12분 폭주 → 5/6 죽음의 증폭요인).
         *   - 🔄 [리팩토링] benign set 에 POSITION_LIMIT 을 전역 추가하면 **USER 전략에선 실제 자본 위험 신호를
         *     놓친다**(격리 위반). 따라서 owner=AUTOPILOT 일 때만 적용하는 별도 set 을 신설하고
         *     [checkAndPauseIfNeeded] 에서 owner 분기. USER/SYSTEM 은 [BENIGN_AUTO_PAUSE_EXCLUDED_RULES] 그대로
         *     (owner 격리 LOCK — v3.14.0 거동 byte-동일).
         *   - ✨ [모던 패턴] `BENIGN ∪ {POSITION_LIMIT}` 합집합 — benign set 변경이 자동 전파(DRY).
         *     [RiskRule.MAX_CONCURRENT_POSITIONS] 은 이미 benign 에 포함(공유풀 full=정상 정합).
         *
         * **실위험 룰은 AUTOPILOT 도 여전히 카운트**: DAILY_LOSS_LIMIT·TRADE_FREQUENCY_MINUTE/DAY·KILL_SWITCH_ON
         * 등은 owner 무관 카운트 유지 → autopilot 도 진짜 손실·과회전·kill 누적엔 자동정지된다.
         */
        val AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES: Set<String> =
            BENIGN_AUTO_PAUSE_EXCLUDED_RULES + RiskRule.POSITION_LIMIT.name
    }

    /**
     * strategy 연속 BLOCKED 임계 검사 + 도달 시 신규 BUY 차단 callback 호출.
     *
     * @param ownerIsAutopilot owner=AUTOPILOT 전략이면 true — 공유풀 과민 차단(ADR 0129 레버5-C)을 위해
     *   [AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES](POSITION_LIMIT 추가 제외)로 카운트한다. false(USER/SYSTEM)면
     *   기존 [BENIGN_AUTO_PAUSE_EXCLUDED_RULES] 그대로(owner 격리 LOCK · v3.14.0 거동 보존). default false 로
     *   기존 호출부·테스트 byte-호환 유지.
     * @return true — AUTO_PAUSE 발동 · false — 미발동
     */
    fun checkAndPauseIfNeeded(
        strategyId: Long,
        autoPauseThreshold: Int,
        ownerIsAutopilot: Boolean = false,
    ): Boolean {
        val repo = riskEventRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.debug(
                "AutoPauseGuard RiskEventRepository 미합류 — skip (strategyId={})",
                strategyId,
            )
            return false
        }

        // owner 분기 — autopilot 공유풀은 'full(POSITION_LIMIT)'이 정상이므로 추가 제외(레버5-C). USER 는 현행 격리.
        val excludedRules =
            if (ownerIsAutopilot) AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES else BENIGN_AUTO_PAUSE_EXCLUDED_RULES

        val recentBlocked =
            try {
                // F9(무해 가드) + ADR 0129 레버5(B 자기억제 · C autopilot 공유풀) rule 제외:
                //   정상 거동이 임계에 기여하지 않게 한다(임계 산식 무손상 — SQL 카운트 단계 모수만 정정).
                repo.countRecentBlockedByStrategy(
                    strategyId,
                    DEFAULT_WINDOW_MINUTES,
                    excludedRules,
                )
            } catch (e: Exception) {
                log.warn(
                    "AutoPauseGuard countRecentBlockedByStrategy 실패 (skip): strategyId={} err={}",
                    strategyId,
                    e.message,
                )
                return false
            }

        if (recentBlocked >= autoPauseThreshold) {
            log.warn(
                "AutoPauseGuard threshold 도달 — 신규 BUY 차단 발동: strategyId={} recentBlocked={} threshold={} ownerIsAutopilot={}",
                strategyId,
                recentBlocked,
                autoPauseThreshold,
                ownerIsAutopilot,
            )
            try {
                // ADR 0067 F-1 정합 — risk 차단 누적은 사용자 화면에서 구분 가능한 사유로 남긴다.
                autoDisableCallback.onAutoDisable(strategyId, StrategyDisabledReason.RISK_VIOLATION)
            } catch (e: Exception) {
                log.error(
                    "AutoPauseGuard onAutoDisable 호출 실패: strategyId={} err={}",
                    strategyId,
                    e.message,
                )
            }
            return true
        }
        return false
    }
}
