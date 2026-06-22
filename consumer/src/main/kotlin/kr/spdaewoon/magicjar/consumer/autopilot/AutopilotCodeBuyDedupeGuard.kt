package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * magicjar-autopilot **종목 충돌 dedup** 가드 (개선 C — 2026-06-22, owner=AUTOPILOT 격리).
 *
 * ## 문제 (오늘 실측)
 * owner=AUTOPILOT N전략(100~105)이 같은 발굴 후보를 공유 → **같은 code 에 여러 전략이 동시 BUY 신호**.
 * 예: 006340 momentum(100) BUY 체결 + volume-spike(104) BUY 같은 ts REJECTED. 슬롯 1개를 여러 전략이
 * 다투고 나머지는 버려져 **종목 분산 효과가 0**. 기존 [kr.spdaewoon.magicjar.consumer.signal.SignalDedupeGuard]
 * 는 strategyId+code 단위(같은 전략 중복만 차단) — 전략 간 같은 code 중복은 못 막는다.
 *
 * ## 해결 (code 단위 dedup → 종목 분산)
 * owner=AUTOPILOT BUY 가 같은 code 에 중복 진입하지 않게 한다. N전략이 **서로 다른 종목**으로 퍼진다.
 *
 * 1. **같은 cycle cross-strategy dedup (1차)** — Redis SETNX, key=`autopilot:buy:code:user:{userId}:code:{code}`
 *    (strategyId 제외 = code 당 1 BUY/cycle). 같은 boundary 에 여러 autopilot 전략이 같은 code BUY 를 내면
 *    **첫 1건만 lock 획득→진행**, 나머지는 suppress. boundary closing ts 가 deterministic 이라 동시 평가가
 *    좁은 window 에 몰리므로 짧은 TTL([LOCK_TTL])로 한 cycle 1 슬롯을 강제한다.
 *
 * 2. **autopilot 보유 code 중복 suppress (2차)** — owner=AUTOPILOT 가 이미 그 code 를 보유(quantity>0)하면
 *    추가 autopilot BUY 를 suppress([StrategyPositionJpaRepository.findAutopilotHeldCodesByUser] — owner-scoped
 *    native join 이라 USER 보유는 구조적 제외). 같은 종목 재적층 방지 → 미보유 종목 우선이 곧 분산.
 *
 * ## 격리 LOCK (zero-regression)
 * - 호출자([kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener.applyAutopilotBuyGuard])가
 *   **owner=AUTOPILOT BUY 확정 후에만** 호출한다 → USER/SYSTEM 전략·SELL·청산은 본 가드를 절대 타지 않는다.
 * - 사용자는 같은 종목 여러 전략 허용될 수 있으므로 USER 무영향이 본질(spec).
 * - Redis 결함 시 **fail-open**(true=진행) — dedup 은 분산 최적화이지 자본 안전 게이트가 아니다(사이징/풀 한도는
 *   [AutopilotBuyGuard] 가 별도 보장). dedup 결함으로 정상 신호를 막지 않는다.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 wiring (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotCodeBuyDedupeGuard(
    private val redisTemplate: StringRedisTemplate,
    private val strategyPositionRepository: StrategyPositionJpaRepository,
) {
    private val log = LoggerFactory.getLogger(AutopilotCodeBuyDedupeGuard::class.java)

    companion object {
        /**
         * code lock TTL — 단일 평가 cycle 의 동시 신호 폭(같은 boundary 에 몰리는 N전략 BUY)을 덮을 만큼 길고,
         * 다음 boundary cycle 전에 만료될 만큼 짧게(한 cycle = 1 슬롯). 최단 평가 interval 도 분 단위라 20초면
         * 동일 boundary 동시성은 충분히 덮고 다음 cycle 의 정상 진입은 막지 않는다.
         */
        private val LOCK_TTL = Duration.ofSeconds(20)
    }

    /**
     * owner=AUTOPILOT BUY 가 이 code 에 진입해도 되는지 dedup 판정. **호출자는 owner=AUTOPILOT BUY 확정 후에만 호출**.
     *
     * @return [Decision.Proceed] — 이 code 첫 진입(lock 획득) · [Decision.Suppress] — 같은 code 중복(타 전략 cycle
     *   선점 또는 autopilot 이미 보유). 호출자는 Suppress 면 intent 미발행(dedup — Reject 와 구분되는 정상 분산).
     */
    fun evaluate(
        userId: String,
        code: String,
        strategyId: Long,
    ): Decision {
        // ── 2차: autopilot 이미 보유 code → 재적층 회피 (owner-scoped, USER 보유 구조적 제외) ──────────────
        //   먼저 검사: 보유 중이면 lock 을 잡을 필요도 없이 suppress(미보유 종목으로 분산 유도).
        val alreadyHeld =
            try {
                strategyPositionRepository.findAutopilotHeldCodesByUser(userId).contains(code)
            } catch (e: Exception) {
                // 조회 실패 시 보유 미상 → dedup 은 fail-open(분산 최적화일 뿐, 안전 게이트 아님). lock 단계로 진행.
                log.warn(
                    "autopilot dedup 보유조회 실패 — fail-open(lock 단계 진행): userId={} code={} err={}",
                    userId,
                    code,
                    e.message,
                )
                false
            }
        if (alreadyHeld) {
            log.info(
                "autopilot BUY dedup suppress(이미 보유 code — 분산 유도): userId={} code={} strategyId={}",
                userId,
                code,
                strategyId,
            )
            return Decision.Suppress("AUTOPILOT_CODE_ALREADY_HELD:owner=AUTOPILOT already holds $code")
        }

        // ── 1차: 같은 cycle cross-strategy code lock (SETNX) — 첫 1건만 진행 ──────────────────────────────
        val key = RedisKeyRegistry.Autopilot.buyCodeLock(userId, code)
        val acquired =
            try {
                redisTemplate.opsForValue().setIfAbsent(key, strategyId.toString(), LOCK_TTL) ?: false
            } catch (e: Exception) {
                // Redis 결함 시 fail-open — dedup 결함으로 정상 신호를 막지 않는다(자본 안전은 별 게이트가 보장).
                log.warn(
                    "autopilot dedup lock Redis 실패 — fail-open(진행): userId={} code={} err={}",
                    userId,
                    code,
                    e.message,
                )
                true
            }
        return if (acquired) {
            Decision.Proceed
        } else {
            log.info(
                "autopilot BUY dedup suppress(같은 cycle 타 전략 선점 — 분산): userId={} code={} strategyId={}",
                userId,
                code,
                strategyId,
            )
            Decision.Suppress("AUTOPILOT_CODE_CYCLE_DEDUP:another autopilot strategy already buying $code this cycle")
        }
    }

    /**
     * dedup 판정 결과(sealed) — 매수 경로가 소비.
     */
    sealed interface Decision {
        /** 이 code 첫 진입(lock 획득·미보유) — 기존 매수 경로 그대로 진행. */
        data object Proceed : Decision

        /** 같은 code 중복(cycle 선점 또는 보유) — intent 미발행(분산 dedup). [reason] = audit 사유. */
        data class Suppress(
            val reason: String,
        ) : Decision
    }
}
