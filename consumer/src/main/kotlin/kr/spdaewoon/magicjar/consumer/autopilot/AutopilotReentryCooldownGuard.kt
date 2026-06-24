package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * magicjar-autopilot **재진입 쿨다운** 가드 (follow-up I — 2026-06-22, owner=AUTOPILOT 격리).
 *
 * ## 문제 (오늘 실측 — churn 이 알파를 비용으로 흘려보낸다)
 * owner=AUTOPILOT 11왕복(회전 매수 261만/매도 232만 ≈ 소액 526k 의 3.5배). 같은 code 를 buy→TP/exit→**분 뒤
 * 재매수** 반복(006340 13:45·14:09 등)이 매 왕복마다 거래비용(매도세 0.18% + 수수료)을 누적해, gross 알파 +7,950 중
 * **5,222(66%)를 잠식**(net +2,728). [AutopilotCodeBuyDedupeGuard](C, #1100)는 *같은 cycle 동시* 중복만 막아
 * **시간차 재진입(churn)은 못 막는다** — 본 가드가 그 직교 갭을 닫는다.
 *
 * ## 해결 — 시간 축 + 가격 축 (ADR 0129 레버 3 — 가격 축 추가)
 * Redis 키([RedisKeyRegistry.Autopilot.reentryCooldown])에 청산 시각 **+ 청산가**를 `ts:price` 포맷으로 동반 기록한다.
 * 1. **[recordLiquidation]** — owner=AUTOPILOT 가 한 code 를 **전량 청산(SELL 체결로 quantity→0)** 할 때, 그 시점의
 *    `currentMillis:exitPrice` 를 TTL=쿨다운 윈도우로 기록한다. 키 존재 = "최근 청산 → 쿨다운 중", 값의 price = 직전 청산가.
 * 2. **[evaluate]** — owner=AUTOPILOT BUY 가 들어오면 그 code 의 쿨다운 키를 본다.
 *    - **시간 축(기존)**: 키가 살아있으면 윈도우 내 재매수 → suppress(churn 억제).
 *    - **가격 축(ADR 0129 레버 3, 신규)**: 시간 축이 풀린 뒤라도(키가 살아있는 동안) currentPrice 가 직전 청산가
 *      `× (1 + reentryPriceGuardPct/100)` 보다 비싸면 추격으로 보고 suppress. 시간 축과 직교(둘 중 하나라도 걸리면 차단).
 *    - 둘 다 통과(키 없음·윈도우 경과)면 Proceed(정당 추세 재진입 허용).
 *
 * 본래 시간 축만 있었고(follow-up I), 시간 쿨다운이 풀린 뒤 *더 비싼 가격* 재추격(2026-06-23 006340 +11%)이 직교 갭으로
 * 남았다 — 가격 축이 그 갭을 닫는다. 윈도우 경과 시 키가 소멸하므로 가격 축도 세션 내 직전 청산 기준으로만 적용된다.
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
     * 종목당 1일 왕복 cap 활성 여부 (누적 축, 2026-06-24) — enabled + cap 양수일 때만 동작. 0 이하면 통째 no-op
     * (byte 동일·롤백 경로). 시간 축([cooldownActive])·가격 축([reentryPriceGuardPct])과 독립으로 켜고 끌 수 있다.
     */
    private val dayCapActive: Boolean
        get() = properties.enabled && properties.maxReentriesPerCodePerDay > 0

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
        exitPrice: BigDecimal,
    ) {
        // 시간/가격 축(cooldown)·누적 축(dayCap) 중 하나라도 활성이어야 기록할 의미가 있다(둘 다 off 면 no-op).
        if (!cooldownActive && !dayCapActive) return
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

        // 누적 축(2026-06-24) — 왕복(전량 청산) 1회 완료 → 종목당 1일 카운터 INCR(KST EOD TTL). 시간/가격 축과 독립.
        recordDayRoundTrip(userId, code, strategyId)

        if (!cooldownActive) return

        val key = RedisKeyRegistry.Autopilot.reentryCooldown(userId, code)
        try {
            // ADR 0129 레버 3 — 값 포맷 `ts:price`. price = 직전 청산가(가격 축 판정 기준). exitPrice<=0 면 가격 축은
            //   evaluate 에서 자연 무효(parse 후 양수 확인)이나 ts 는 항상 기록(시간 축 보존).
            redisTemplate.opsForValue().set(key, "${System.currentTimeMillis()}:${exitPrice.toPlainString()}", cooldownWindow)
            log.info(
                "autopilot 재진입 쿨다운 기록(전량 청산): userId={} code={} strategyId={} exitPrice={} windowMin={}",
                userId,
                code,
                strategyId,
                exitPrice.toPlainString(),
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
        currentPrice: BigDecimal,
    ): Decision {
        // 누적 축(2026-06-24) — 종목당 1일 왕복 cap 이 먼저. 오늘 이 code 의 autopilot 왕복 횟수가 cap 에 도달했으면
        //   시간/가격 축과 무관하게 suppress(누적 비용 출혈 bound). 시간/가격 축보다 우선 — 가장 강한 누적 게이트.
        evaluateDayCap(userId, code, strategyId)?.let { return it }

        // 시간 축·가격 축은 직교. 둘 중 하나라도 활성이면 키를 읽어야 한다(시간 축 cooldownActive·가격 축 pct>0).
        val priceGuardActive = properties.reentryPriceGuardPct > BigDecimal.ZERO
        if (!cooldownActive && !priceGuardActive) return Decision.Proceed

        val key = RedisKeyRegistry.Autopilot.reentryCooldown(userId, code)
        val rawValue =
            try {
                redisTemplate.opsForValue().get(key)
            } catch (e: Exception) {
                // fail-open — Redis 결함으로 정상 신호를 막지 않는다(자본 안전은 별 게이트가 보장).
                log.warn(
                    "autopilot 재진입 쿨다운 조회 실패 — fail-open(진행): userId={} code={} err={}",
                    userId,
                    code,
                    e.message,
                )
                return Decision.Proceed
            }

        // 키 없음 = 윈도우 경과(또는 미청산) — 시간 축·가격 축 모두 무효 → 정당 재진입 허용.
        if (rawValue == null) return Decision.Proceed

        // ── 시간 축(기존, follow-up I) ── cooldownActive 인 동안 키가 살아있음 = 윈도우 내 재매수 → suppress(churn 억제).
        if (cooldownActive) {
            log.info(
                "autopilot BUY 재진입 쿨다운 suppress(churn 억제 — 최근 청산 code): userId={} code={} strategyId={} windowMin={}",
                userId,
                code,
                strategyId,
                properties.reentryCooldownMinutes,
            )
            return Decision.Suppress(
                "AUTOPILOT_REENTRY_COOLDOWN:liquidated $code within ${properties.reentryCooldownMinutes}m — churn 억제",
            )
        }

        // ── 가격 축(ADR 0129 레버 3, 신규) ── 시간 축 비활성(cooldownActive=false)인데 키가 살아있는 경우에만 도달.
        //   (정상 운영에선 시간 축이 먼저 걸리므로 본 분기는 reentryCooldownMinutes<=0 + pct>0 구성에서 의미. 그래도
        //    가격 축을 독립적으로 평가해 직전 청산가 대비 추격을 차단한다.)
        return evaluatePriceAxis(userId, code, strategyId, currentPrice, rawValue)
    }

    /**
     * 가격 축 판정 (ADR 0129 레버 3) — 직전 청산가 대비 추격 매수 차단. [evaluate] 내부에서 시간 축 통과 후 호출.
     *
     * - `reentryPriceGuardPct <= 0` → 비활성(즉시 롤백 경로) → Proceed.
     * - currentPrice<=0·값 파싱 실패·price<=0(legacy ts-only) → fail-open Proceed.
     * - `currentPrice > recordedExitPrice × (1 + pct/100)` → Suppress(추격 차단). 그 외 Proceed("더 싸게·같은 가격" 허용).
     */
    private fun evaluatePriceAxis(
        userId: String,
        code: String,
        strategyId: Long,
        currentPrice: BigDecimal,
        rawValue: String,
    ): Decision {
        val pct = properties.reentryPriceGuardPct
        if (pct <= BigDecimal.ZERO) return Decision.Proceed
        if (currentPrice <= BigDecimal.ZERO) return Decision.Proceed

        // `ts:price` 파싱 — price 토큰(마지막 ':' 뒤). legacy ts-only·파싱 실패·price<=0 → 가격 축 무효(fail-open Proceed).
        val recordedExitPrice =
            rawValue
                .substringAfterLast(':', "")
                .trim()
                .toBigDecimalOrNull()
                ?.takeIf { it > BigDecimal.ZERO }
                ?: return Decision.Proceed

        val threshold =
            recordedExitPrice.multiply(BigDecimal.ONE.add(pct.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
        return if (currentPrice > threshold) {
            log.info(
                "autopilot BUY 추격 가격 가드 suppress(직전 청산가 +{}% 초과 추격): userId={} code={} strategyId={} currentPrice={} exitPrice={} threshold={}",
                pct.toPlainString(),
                userId,
                code,
                strategyId,
                currentPrice.toPlainString(),
                recordedExitPrice.toPlainString(),
                threshold.toPlainString(),
            )
            Decision.Suppress(
                "AUTOPILOT_REENTRY_PRICE_GUARD:$code chase ${currentPrice.toPlainString()} > exit ${recordedExitPrice.toPlainString()} +${pct.toPlainString()}%",
            )
        } else {
            Decision.Proceed
        }
    }

    /**
     * 누적 축 (2026-06-24) — 종목당 1일 왕복(전량 청산) 카운터 INCR. 첫 카운트일 때만 KST EOD 까지 TTL 을 건다
     * (날짜 경계에 키 자연 소멸 → 익일 리셋). [evaluate] 의 [evaluateDayCap] 가 이 카운트를 읽어 cap 도달 시 suppress.
     *
     * 어떤 실패도 graceful(체결 권위 무손상 — 카운터는 부가 비용 신호이지 체결 자체가 아니다). dayCap 비활성이면 호출
     * 시점에 이미 걸러지나(recordLiquidation 의 `cooldownActive && dayCapActive` 게이트), 직접 호출 대비 한 번 더 가드한다.
     */
    private fun recordDayRoundTrip(
        userId: String,
        code: String,
        strategyId: Long,
    ) {
        if (!dayCapActive) return
        val kstDate = kstToday()
        val key = RedisKeyRegistry.Autopilot.reentryDayCount(userId, code, kstDate)
        try {
            val count = redisTemplate.opsForValue().increment(key) ?: return
            // 첫 카운트(1)일 때만 TTL 설정 — 이후 INCR 은 TTL 을 건드리지 않아(set 아닌 increment) 날짜 경계까지 유지된다.
            if (count == 1L) {
                redisTemplate.expire(key, secondsToKstEod())
            }
            log.info(
                "autopilot 종목 1일 왕복 카운트(전량 청산): userId={} code={} strategyId={} count={} cap={} date={}",
                userId,
                code,
                strategyId,
                count,
                properties.maxReentriesPerCodePerDay,
                kstDate,
            )
        } catch (e: Exception) {
            log.warn(
                "autopilot 1일 왕복 카운트 기록 실패 — skip(체결 권위 무손상): userId={} code={} err={}",
                userId,
                code,
                e.message,
            )
        }
    }

    /**
     * 누적 축 판정 (2026-06-24) — 오늘 이 code 의 autopilot 왕복 횟수가 cap 이상이면 [Decision.Suppress]. 그 외 null
     * (다음 축으로 진행). dayCap 비활성·카운트 미달·Redis 실패(fail-open)면 null 을 반환해 churn 게이트가 정상 매매를 막지 않는다.
     */
    private fun evaluateDayCap(
        userId: String,
        code: String,
        strategyId: Long,
    ): Decision? {
        if (!dayCapActive) return null
        val cap = properties.maxReentriesPerCodePerDay
        val kstDate = kstToday()
        val key = RedisKeyRegistry.Autopilot.reentryDayCount(userId, code, kstDate)
        val count =
            try {
                redisTemplate.opsForValue().get(key)?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                // fail-open — Redis 결함으로 정상 신호를 막지 않는다(누적 cap 은 비용 최적화이지 자본 안전 게이트가 아니다).
                log.warn(
                    "autopilot 1일 왕복 카운트 조회 실패 — fail-open(진행): userId={} code={} err={}",
                    userId,
                    code,
                    e.message,
                )
                return null
            }
        if (count < cap) return null

        log.info(
            "autopilot BUY 1일 왕복 cap suppress(종목 누적 churn 차단): userId={} code={} strategyId={} count={} cap={} date={}",
            userId,
            code,
            strategyId,
            count,
            cap,
            kstDate,
        )
        return Decision.Suppress(
            "AUTOPILOT_REENTRY_DAY_CAP:$code roundtrips $count >= cap $cap (KST $kstDate) — 누적 churn 차단",
        )
    }

    /** 현재 KST 날짜(yyyy-MM-dd) — 카운터 키의 날짜 슬롯(날짜 경계 자연 리셋). */
    private fun kstToday(): String = ZonedDateTime.now(KST_ZONE).format(KST_DATE_FMT)

    /** 현재 시각부터 KST 자정(익일 00:00)까지 남은 시간 — 카운터 TTL(날짜 경계에 키 소멸). 최소 1분 보장(경계 직전 0초 회피). */
    private fun secondsToKstEod(): Duration {
        val now = ZonedDateTime.now(KST_ZONE)
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(KST_ZONE)
        val remaining = Duration.between(now, nextMidnight)
        return if (remaining.toMinutes() < 1) Duration.ofMinutes(1) else remaining
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

    companion object {
        /** 누적 축 카운터 날짜 슬롯·TTL 계산 기준 — KST(한국 거래일 경계). */
        private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val KST_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
