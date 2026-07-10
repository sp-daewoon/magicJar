package kr.spdaewoon.magicjar.consumer.signal

import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 진입 가드 ② — **손절 후 재진입 쿨다운** (2026-07-08 즉효 3종, 최고 관리자 결재).
 *
 * ## 근거 (2026-07-08)
 * "손절 후 같은 종목 재진입 → 재손절" 반복(014910·037070·046940·013120)이 손실을 누적했다. 본 게이트는 같은
 * (userId, code)에서 **손절성(실현 손실) 전량 청산**이 체결된 뒤 N분(default 60, yml) 동안 같은 code 의 신규 **BUY
 * 시그널 발행을 skip** 한다. SELL/청산/손절/익절은 **절대 무영향**(보유분 회수는 항상 보장 — 롱온리).
 *
 * ## 손절 판정 소스 (조사 후 선택)
 * 가장 신뢰성 있는 출처는 **실현 손실 전량 청산 체결**이다. strategy_position 원장([ConsumerExecutionStrategyPositionAdapter.applySell])이
 * SELL 체결마다 `realizedDelta = (fillPrice − avgPrice) × sellQty` 를 정확히 산출하므로, 그 delta 가 음수(손실)이고
 * 전량 청산(nextQty==0)이면 손절성 청산으로 본다. 손절 MARKET·손실 DSL SELL·손실 익절 미달 청산 모두 포괄한다
 * (auto-sl OrderTrigger 만 보면 DSL/수동 손실 청산을 놓친다 — 실현 손실이 상위 개념). 호출부([recordStopLossExit])는
 * 체결 어댑터의 SELL 전량청산+손실 분기에서만 호출한다.
 *
 * ## 재기동 생존 — Redis 1키 (신규 키 1개 — 공통 가드 예산 내)
 * 쿨다운을 [RedisKeyRegistry.Strategy.stopLossReentryCooldown] 키에 TTL=윈도우로 기록한다. Redis 영속이라 consumer
 * 재기동 후에도 윈도우가 유지된다(재부팅 직후 즉시 재진입 방지 — DB 폴링 아닌 평가 시점 1회 조회). 인메모리는 재기동에
 * 소실돼 재부팅 churn 을 못 막으므로 Redis 를 택했다.
 *
 * ## fail-open — Redis 결함 시 skip 안 함
 * [isInCooldown] 은 Redis 조회 실패 시 false(진행), [recordStopLossExit] 는 실패 시 graceful skip. 쿨다운은 churn/재손절
 * 억제 최적화이지 자본 안전 게이트가 아니다(손절 자체는 tick watcher·시그널이 보장) — 결함이 정상 매매·청산을 막지 않게 한다.
 *
 * ## skip 로그 dedupe (스팸 방지)
 * 같은 code 반복 skip 은 로그를 1회로 억제(윈도우 만료·다음 청산 시 재로그). 행동(skip 여부)은 그대로.
 *
 * ## 토글 (rollback)
 * [enabled]=false 또는 [cooldownMinutes] ≤ 0 → no-op = byte 동일(zero-regression).
 *
 * // AI 호출 0 — 룰 기반 진입 가드 (ADR 0023 §2-4 정합)
 */
@Component
class StopLossCooldownGate(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${magicjar.strategy.entry-guard.cooldown.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.strategy.entry-guard.cooldown.minutes:60}")
    private val cooldownMinutes: Long = 60,
) {
    private val log = LoggerFactory.getLogger(StopLossCooldownGate::class.java)

    /** 최근 skip 로그한 (userId:code) — 반복 skip 로그 dedupe. */
    private val lastLoggedSkip = ConcurrentHashMap.newKeySet<String>()

    private val active: Boolean
        get() = enabled && cooldownMinutes > 0L

    /**
     * 손절성(실현 손실) 전량 청산 기록 — **호출자는 SELL + 전량청산(nextQty==0) + realizedDelta<0 확정 후 호출**.
     *
     * 쿨다운 키에 TTL=[cooldownMinutes] 로 기록하면 이후 그 분 동안 같은 (userId, code)의 BUY 가 [isInCooldown] 에서
     * true → 발행 skip 된다. 비활성·Redis 실패면 graceful(체결 권위 흐름 무손상 — 쿨다운은 부가 신호이지 체결 자체가 아니다).
     */
    fun recordStopLossExit(
        userId: String,
        code: String,
    ) {
        if (!active) return
        val key = RedisKeyRegistry.Strategy.stopLossReentryCooldown(userId, code)
        try {
            redisTemplate.opsForValue().set(key, System.currentTimeMillis().toString(), Duration.ofMinutes(cooldownMinutes))
            lastLoggedSkip.remove("$userId:$code") // 새 청산 → dedupe reset(다음 skip 재로그 허용).
            log.info(
                "StopLossCooldownGate 손절 청산 쿨다운 기록: userId={} code={} windowMin={}",
                userId,
                code,
                cooldownMinutes,
            )
        } catch (e: Exception) {
            log.warn(
                "StopLossCooldownGate 쿨다운 기록 실패 — skip(체결 권위 무손상): userId={} code={} err={}",
                userId,
                code,
                e.message,
            )
        }
    }

    /**
     * 같은 (userId, code)가 손절 쿨다운 윈도우 안이라 신규 BUY 발행을 skip 해야 하는가.
     *
     * @return true = 쿨다운 중(BUY skip) / false = 쿨다운 없음·만료·비활성·Redis 실패(진행).
     */
    fun isInCooldown(
        userId: String,
        code: String,
    ): Boolean {
        if (!active) return false
        val key = RedisKeyRegistry.Strategy.stopLossReentryCooldown(userId, code)
        val inCooldown =
            try {
                redisTemplate.hasKey(key)
            } catch (e: Exception) {
                // fail-open — Redis 결함으로 정상 매매를 막지 않는다(쿨다운은 재손절 억제 최적화).
                log.warn("StopLossCooldownGate 쿨다운 조회 실패 — fail-open(진행): userId={} code={} err={}", userId, code, e.message)
                false
            }
        if (inCooldown) {
            logSkipDeduped(userId, code)
        }
        return inCooldown
    }

    /** 같은 (userId, code) 반복 skip 은 로그 1회로 억제(새 청산 기록 시 reset → 다음 사이클 재로그). */
    private fun logSkipDeduped(
        userId: String,
        code: String,
    ) {
        if (!lastLoggedSkip.add("$userId:$code")) return
        log.info(
            "StopLossCooldownGate BUY skip — 손절 후 재진입 쿨다운 중(SELL/청산 무영향): userId={} code={} windowMin={}",
            userId,
            code,
            cooldownMinutes,
        )
    }
}
