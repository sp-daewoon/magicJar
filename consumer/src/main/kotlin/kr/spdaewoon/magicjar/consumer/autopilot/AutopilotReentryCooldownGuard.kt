package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * magicjar-autopilot **재진입 쿨다운** 가드 (follow-up I — 2026-06-22, owner=AUTOPILOT 격리).
 *
 * ## 문제 (오늘 실측 — churn 이 알파를 비용으로 흘려보낸다)
 * owner=AUTOPILOT 11왕복(회전 매수 261만/매도 232만 ≈ 소액 526k 의 3.5배). 같은 code 를 buy→TP/exit→**분 뒤
 * 재매수** 반복(006340 13:45·14:09 등)이 매 왕복마다 거래비용(매도세 0.18% + 수수료)을 누적해, gross 알파 +7,950 중
 * **5,222(66%)를 잠식**(net +2,728). [AutopilotCodeBuyDedupeGuard](C, #1100)는 *같은 cycle 동시* 중복만 막아
 * **시간차 재진입(churn)은 못 막는다** — 본 가드가 그 직교 갭을 닫는다.
 *
 * ## 해결 (청산 ts 기록 → 쿨다운 윈도우 내 같은 code 재매수 suppress)
 * 1. **[recordLiquidation]** — owner=AUTOPILOT 가 한 code 를 **전량 청산(SELL 체결로 quantity→0)** 할 때, 그 시점을
 *    Redis 키([RedisKeyRegistry.Autopilot.reentryCooldown])에 TTL=쿨다운 윈도우로 기록한다. 키 존재 자체가 "최근
 *    청산했다 → 쿨다운 중"의 신호이며 TTL 만료로 자연 해제된다(별도 sweep 불필요).
 * 2. **[evaluate]** — owner=AUTOPILOT BUY 가 들어오면 그 code 의 쿨다운 키가 살아있는지 본다. 살아있으면 suppress
 *    (윈도우 내 재매수 차단 = churn 억제). 없으면 Proceed(윈도우 경과 = 정당 추세 재진입 허용).
 *
 * ## 격리 LOCK (zero-regression)
 * - [recordLiquidation] 은 호출자([kr.spdaewoon.magicjar.consumer.persistence.ConsumerExecutionStrategyPositionAdapter])가
 *   **owner=AUTOPILOT + 전량 청산(nextQty==0) 확정 후에만** 호출한다 → USER/SYSTEM·부분매도·BUY 체결은 기록 0.
 * - [evaluate] 는 호출자([kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener.applyAutopilotBuyGuard])가
 *   **owner=AUTOPILOT BUY 확정 후에만** 호출한다 → USER/SYSTEM 전략·SELL·청산은 본 가드를 절대 타지 않는다.
 * - **SELL/청산 무영향** — 본 가드는 BUY suppress 만 한다. 보유분 청산은 항상 보장(롱온리·ADR 헌법 §13).
 * - **fail-open** — Redis 결함 시 [evaluate]=Proceed(진행) · [recordLiquidation]=graceful skip. 쿨다운은 churn 비용
 *   최적화이지 자본 안전 게이트가 아니다(사이징/풀 한도는 [AutopilotBuyGuard], 종목 분산은 dedup 이 별도 보장).
 *   쿨다운 결함이 정상 매매·청산을 막지 않게 한다.
 * - [enabled]=false 또는 [reentryCooldownMinutes] ≤ 0 → no-op = byte 동일(zero-regression).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 wiring (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotReentryCooldownGuard(
    private val redisTemplate: StringRedisTemplate,
    private val properties: MagicjarAutopilotProperties,
    // owner=AUTOPILOT 판정 단일 출처 — [AutopilotLossAccumulator] 와 동일 repository(DRY). 청산 hook 의 owner 격리 게이트를
    //   가드 내부에 내장해 호출자(체결 어댑터)는 "SELL + 전량 청산" 조건만 보고 위임하면 된다(어댑터 격리 부담 0).
    private val strategyRepository: ConsumerStrategyJpaRepository,
) {
    private val log = LoggerFactory.getLogger(AutopilotReentryCooldownGuard::class.java)

    /** 쿨다운 활성 여부 — enabled + 윈도우 양수일 때만 게이트가 실제로 동작(둘 중 하나라도 off 면 통째 no-op). */
    private val cooldownActive: Boolean
        get() = properties.enabled && properties.reentryCooldownMinutes > 0L

    private val cooldownWindow: Duration
        get() = Duration.ofMinutes(properties.reentryCooldownMinutes)

    /**
     * 전량 청산 ts 기록 — **호출자는 SELL + nextQty==0(전량 청산) 확정 후 호출**. owner=AUTOPILOT 격리는 가드 내부 판정.
     *
     * owner 판정([strategyRepository] — [AutopilotLossAccumulator] 와 동일 출처)을 가드 안에 내장해 호출자(체결 어댑터)는
     * owner 식별 부담이 0이다. owner≠AUTOPILOT(사용자 수동·후성)이면 기록 0(격리 LOCK). 비활성(enabled=false·윈도우≤0)·
     * strategyId=0(MANUAL sentinel)이면 no-op. 쿨다운 키에 청산 시각(epoch-millis)을 TTL=윈도우로 기록하면 이후
     * [reentryCooldownMinutes] 분 동안 같은 code 의 autopilot BUY 가 [evaluate] 에서 suppress 된다. 어떤 실패도 graceful
     * (체결 권위 흐름 무손상 — 쿨다운 기록은 부가 신호이지 체결 자체가 아니다).
     */
    fun recordLiquidation(
        userId: String,
        code: String,
        strategyId: Long,
    ) {
        if (!cooldownActive) return
        if (strategyId == 0L) return

        val isAutopilot =
            try {
                strategyRepository.countAutopilotStrategyById(strategyId) > 0
            } catch (e: Exception) {
                // owner 미상 → 비대상으로 보고 기록 skip(graceful — 체결 권위 무손상, USER 오기록 회피).
                log.warn("autopilot 쿨다운 owner 조회 실패 — skip(체결 권위 무손상): strategyId={} err={}", strategyId, e.message)
                return
            }
        if (!isAutopilot) return

        val key = RedisKeyRegistry.Autopilot.reentryCooldown(userId, code)
        try {
            redisTemplate.opsForValue().set(key, System.currentTimeMillis().toString(), cooldownWindow)
            log.info(
                "autopilot 재진입 쿨다운 기록(전량 청산): userId={} code={} strategyId={} windowMin={}",
                userId,
                code,
                strategyId,
                properties.reentryCooldownMinutes,
            )
        } catch (e: Exception) {
            // graceful — 쿨다운 기록 실패가 체결 권위 흐름을 막지 않는다(부가 신호일 뿐).
            log.warn(
                "autopilot 재진입 쿨다운 기록 실패 — skip(체결 권위 무손상): userId={} code={} err={}",
                userId,
                code,
                e.message,
            )
        }
    }

    /**
     * owner=AUTOPILOT BUY 가 이 code 에 재진입해도 되는지 쿨다운 판정. **호출자는 owner=AUTOPILOT BUY 확정 후에만 호출**.
     *
     * @return [Decision.Proceed] — 쿨다운 비활성·만료(윈도우 경과 = 정당 재진입) · [Decision.Suppress] — 쿨다운 윈도우 내
     *   재매수(churn 억제). 호출자는 Suppress 면 intent 미발행(churn 차단 — Reject 와 구분되는 비용 회수 분기).
     */
    fun evaluate(
        userId: String,
        code: String,
        strategyId: Long,
    ): Decision {
        if (!cooldownActive) return Decision.Proceed

        val key = RedisKeyRegistry.Autopilot.reentryCooldown(userId, code)
        val onCooldown =
            try {
                redisTemplate.hasKey(key)
            } catch (e: Exception) {
                // fail-open — Redis 결함으로 정상 신호를 막지 않는다(자본 안전은 별 게이트가 보장).
                log.warn(
                    "autopilot 재진입 쿨다운 조회 실패 — fail-open(진행): userId={} code={} err={}",
                    userId,
                    code,
                    e.message,
                )
                false
            }

        return if (onCooldown) {
            log.info(
                "autopilot BUY 재진입 쿨다운 suppress(churn 억제 — 최근 청산 code): userId={} code={} strategyId={} windowMin={}",
                userId,
                code,
                strategyId,
                properties.reentryCooldownMinutes,
            )
            Decision.Suppress(
                "AUTOPILOT_REENTRY_COOLDOWN:liquidated $code within ${properties.reentryCooldownMinutes}m — churn 억제",
            )
        } else {
            Decision.Proceed
        }
    }

    /**
     * 쿨다운 판정 결과(sealed) — 매수 경로가 소비.
     */
    sealed interface Decision {
        /** 쿨다운 비활성·만료 — 기존 매수 경로 그대로 진행(정당 재진입 허용). */
        data object Proceed : Decision

        /** 쿨다운 윈도우 내 재매수 — intent 미발행(churn 억제). [reason] = audit 사유. */
        data class Suppress(
            val reason: String,
        ) : Decision
    }
}
