package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * `autopilot_state`(V91 + V92) 읽기·쓰기 단일 진입점 (spec 2026-06-22 §2-0-A·§2-0-B · Task 3·4).
 *
 * ## 책임 (SRP)
 * - [load] — account-wide 단일 행(id=1)을 도메인 [AutopilotState] 로 읽는다. 부재·실패 시
 *   [MagicjarAutopilotProperties.toDefaultState] fallback(부트 default — DRY 단일 출처).
 * - [accumulateRealizedLoss] — owner=AUTOPILOT 실현 손실을 `cum_realized_loss`·`daily_realized_loss` +
 *   `lifetime_realized_loss`(하드 플로어, V92)에 누적(양수 = 손실). 날짜(KST) 바뀌면 daily reset. kill 토대.
 * - [activateKill] — kill_active 영속(멱등).
 * - [deactivateKill] — kill 해제(Task 4) — 단, **하드 플로어**(lifetime_realized_loss ≥ poolCeiling ×
 *   lifetimeFloorPct/100) 초과 시 코드로 거부([DeactivateOutcome.FloorBreached]). correctedLimits 있으면 한도 갱신.
 *
 * ## 게이트 (zero-regression)
 * 본 store 는 [AutopilotBuyGuard]·[AutopilotKillSwitch]·[AutopilotLossAccumulator] 가
 * `enabled=true` 게이트 통과 후에만 호출한다. enabled=false 면 본 store 메서드는 호출조차 되지 않는다.
 *
 * 헌법 §6 — Repository 는 abstract fun 만, 매핑·날짜 reset 로직은 본 store 의 private helper.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 영속 (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotStateStore(
    private val repository: AutopilotStateJpaRepository,
    private val properties: MagicjarAutopilotProperties,
) {
    private val log = LoggerFactory.getLogger(AutopilotStateStore::class.java)

    /**
     * 단일 행을 도메인 [AutopilotState] 로 읽는다(읽기 전 KST 날짜 변경 시 daily reset 반영).
     *
     * 행 부재·조회 실패 시 [MagicjarAutopilotProperties.toDefaultState](부트 default) 반환 — 손실 0·kill off
     * 이므로 안전측(진입은 풀 한도 안에서 허용, kill 미발동). 시드(V91)가 있으면 정상적으로 그 행이 권위.
     */
    @Transactional(readOnly = true)
    fun load(): AutopilotState =
        try {
            repository
                .findById(AutopilotStateJpaEntity.SINGLETON_ID)
                .map { it.toDomain() }
                .orElseGet {
                    log.warn("autopilot_state 행 부재(id=1) — 부트 default fallback(손실 0·kill off). V91 시드 확인 필요.")
                    properties.toDefaultState()
                }
        } catch (e: Exception) {
            log.error("autopilot_state 조회 실패 — 부트 default fallback: err={}", e.message)
            properties.toDefaultState()
        }

    /**
     * owner=AUTOPILOT 실현 손실 누적 (양수 = 손실 크기 · spec §2-0-A 손실 추적).
     *
     * - `realizedLoss <= 0`(이익·무손실)이면 no-op(누적 대상 아님 — 손실만 카운트).
     * - KST 날짜가 `last_reset_date` 보다 진행됐으면 `daily_realized_loss` 를 먼저 0 으로 reset 후 누적
     *   (일일 halt 의 "당일" 경계). `cum_realized_loss` 는 reset 없이 영구 누적(누적 kill 기준).
     * - `lifetime_realized_loss`(V92 하드 플로어)도 cum 과 **함께** 영구 누적 — kill-reset 에도 보존된다.
     * - 행 부재 시 시드(default 한도)로 생성 후 누적.
     *
     * @param realizedLoss 실현 손실 크기(양수). 청산 PnL 이 음수일 때 그 절대값을 넘긴다([AutopilotLossAccumulator]).
     * @return 누적 반영 후 도메인 상태(caller 가 kill 판정에 사용 가능).
     */
    @Transactional
    fun accumulateRealizedLoss(realizedLoss: BigDecimal): AutopilotState {
        if (realizedLoss <= BigDecimal.ZERO) return load()

        val today = LocalDate.now(KST_ZONE)
        val now = Instant.now()
        val entity =
            repository
                .findById(AutopilotStateJpaEntity.SINGLETON_ID)
                .orElseGet { seedEntity(today, now) }

        // 날짜 진행 시 daily reset — 당일 halt 경계. cum 은 보존(영구 누적).
        val resetNeeded = today.isAfter(entity.lastResetDate)
        val dailyBase = if (resetNeeded) BigDecimal.ZERO else entity.dailyRealizedLoss

        val next =
            copyOf(
                entity,
                cumRealizedLoss = entity.cumRealizedLoss.add(realizedLoss),
                dailyRealizedLoss = dailyBase.add(realizedLoss),
                // 하드 플로어(V92) — cum 과 함께 영구 누적. kill-reset(deactivate)에도 절대 리셋 안 됨.
                lifetimeRealizedLoss = entity.lifetimeRealizedLoss.add(realizedLoss),
                lastResetDate = today,
                updatedAt = now,
            )
        val saved = repository.save(next)
        log.info(
            "autopilot 손실 누적: realizedLoss={} cumRealizedLoss={} dailyRealizedLoss={} lifetimeRealizedLoss={} dailyReset={} lastResetDate={}",
            realizedLoss.toPlainString(),
            saved.cumRealizedLoss.toPlainString(),
            saved.dailyRealizedLoss.toPlainString(),
            saved.lifetimeRealizedLoss.toPlainString(),
            resetNeeded,
            saved.lastResetDate,
        )
        return saved.toDomain()
    }

    /**
     * kill switch 영속 ON ([AutopilotKillSwitch] · spec §2-0-B). 이미 ON 이면 멱등(no-op).
     * @return kill 반영(또는 이미 ON) 후 상태.
     */
    @Transactional
    fun activateKill(): AutopilotState {
        val today = LocalDate.now(KST_ZONE)
        val now = Instant.now()
        val entity =
            repository
                .findById(AutopilotStateJpaEntity.SINGLETON_ID)
                .orElseGet { seedEntity(today, now) }
        if (entity.killActive) {
            log.info("autopilot kill 이미 ON — 멱등 skip")
            return entity.toDomain()
        }
        val saved = repository.save(copyOf(entity, killActive = true, updatedAt = now))
        log.warn("autopilot kill switch ON 영속(autopilot_state.kill_active=true)")
        return saved.toDomain()
    }

    /**
     * kill **해제**(spec §2-0-B kill-recovery · Task 4) — Claude 가 사후분석·보정 후 재개하는 학습 체크포인트.
     *
     * ## 하드 플로어 (코드 강제 — 무한 reset 출혈 방어)
     * `lifetime_realized_loss >= poolCeiling × lifetimeFloorPct/100`(=풀 전소 상당)이면 **해제를 거부**하고
     * [DeactivateOutcome.FloorBreached] 를 반환한다(kill_active 유지). 이건 코드가 강제하는 마지막 방벽 —
     * 프롬프트·LLM 판단으로 우회 불가(spec §1 "코드 강제 > 프롬프트"). 사용자 재가(수동 floor 리셋)만 해제.
     *
     * ## 멱등·correctedLimits
     * - kill 이 이미 off 면 멱등 [DeactivateOutcome.AlreadyInactive](correctedLimits 가 있으면 한도만 반영).
     * - [correctedLimits] 가 있으면 autopilot_state 한도(pool/per-trade/max/daily/cum)를 갱신(Claude 보정 재개).
     * - `cum_realized_loss`·`daily_realized_loss` 는 해제 시 0 으로 리셋(envelope 재시작) — 단 `lifetime` 은 보존.
     *
     * @param reason          해제 사유(저널 audit).
     * @param poolCeiling     풀 상한 금액(= 순자산 × poolPct/100). 호출자([AutopilotControlListener])가 net worth 로 산정.
     * @param correctedLimits 보정 한도(null = 한도 유지). 일부 필드만 채워도 됨(null 필드는 기존 값 유지).
     * @return [DeactivateOutcome] — Deactivated / AlreadyInactive / FloorBreached.
     */
    @Transactional
    fun deactivateKill(
        reason: String,
        poolCeiling: BigDecimal,
        correctedLimits: AutopilotLimitCorrection? = null,
    ): DeactivateOutcome {
        val now = Instant.now()
        val entity =
            repository
                .findById(AutopilotStateJpaEntity.SINGLETON_ID)
                .orElseGet { seedEntity(LocalDate.now(KST_ZONE), now) }

        // 하드 플로어 검사 — lifetime 손실이 풀 전소 임계 이상이면 영구 kill(해제 거부).
        val floor = floorThreshold(poolCeiling)
        if (entity.lifetimeRealizedLoss >= floor && floor > BigDecimal.ZERO) {
            log.error(
                "★ autopilot 하드 플로어 도달 — kill 해제 거부(영구 kill, 사용자 재가 필요): " +
                    "lifetimeRealizedLoss={} >= floor={} (poolCeiling={} × lifetimeFloorPct/100) reason={}",
                entity.lifetimeRealizedLoss.toPlainString(),
                floor.toPlainString(),
                poolCeiling.toPlainString(),
                reason,
            )
            return DeactivateOutcome.FloorBreached(entity.toDomain(), floor)
        }

        val limited = applyCorrection(entity, correctedLimits)
        if (!entity.killActive) {
            // 멱등 — 이미 해제. correctedLimits 가 있으면 한도만 반영(없으면 no change).
            val saved = if (correctedLimits != null) repository.save(copyOf(limited, updatedAt = now)) else entity
            log.info("autopilot kill 이미 해제 — 멱등(correctedLimits 반영={})", correctedLimits != null)
            return DeactivateOutcome.AlreadyInactive(saved.toDomain())
        }

        // 해제 — kill off + envelope(cum·daily) 리셋. lifetime 은 보존(하드 플로어 누적 유지).
        val saved =
            repository.save(
                copyOf(
                    limited,
                    killActive = false,
                    cumRealizedLoss = BigDecimal.ZERO,
                    dailyRealizedLoss = BigDecimal.ZERO,
                    updatedAt = now,
                ),
            )
        log.warn(
            "autopilot kill 해제(deactivate) — cum·daily envelope 리셋, lifetime={} 보존. reason={} correctedLimits={}",
            saved.lifetimeRealizedLoss.toPlainString(),
            reason,
            correctedLimits,
        )
        return DeactivateOutcome.Deactivated(saved.toDomain())
    }

    /** 하드 플로어 임계 금액 = poolCeiling × lifetimeFloorPct/100. poolCeiling ≤ 0(net worth 미상)이면 0(검사 skip — 호출자 fail-closed 책임). */
    private fun floorThreshold(poolCeiling: BigDecimal): BigDecimal {
        if (poolCeiling <= BigDecimal.ZERO) return BigDecimal.ZERO
        return poolCeiling
            .multiply(properties.lifetimeFloorPct)
            .divide(BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP)
    }

    /** correctedLimits 의 non-null 필드만 entity 한도에 override(부분 보정). null 입력이면 entity 그대로. */
    private fun applyCorrection(
        e: AutopilotStateJpaEntity,
        c: AutopilotLimitCorrection?,
    ): AutopilotStateJpaEntity {
        if (c == null) return e
        return AutopilotStateJpaEntity(
            id = e.id,
            killActive = e.killActive,
            poolPct = c.poolPct ?: e.poolPct,
            perTradePct = c.perTradePct ?: e.perTradePct,
            maxPositions = c.maxPositions ?: e.maxPositions,
            dailyLossPct = c.dailyLossPct ?: e.dailyLossPct,
            cumLossPct = c.cumLossPct ?: e.cumLossPct,
            cumRealizedLoss = e.cumRealizedLoss,
            dailyRealizedLoss = e.dailyRealizedLoss,
            lifetimeRealizedLoss = e.lifetimeRealizedLoss,
            lastResetDate = e.lastResetDate,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }

    /** 부트 default 한도로 단일 행 시드(행 부재 시 — V91 시드가 정상이면 도달하지 않음). */
    private fun seedEntity(
        today: LocalDate,
        now: Instant,
    ): AutopilotStateJpaEntity {
        val d = properties.toDefaultState()
        return AutopilotStateJpaEntity(
            id = AutopilotStateJpaEntity.SINGLETON_ID,
            killActive = false,
            poolPct = d.poolPct,
            perTradePct = d.perTradePct,
            maxPositions = d.maxPositions,
            dailyLossPct = d.dailyLossPct,
            cumLossPct = d.cumLossPct,
            cumRealizedLoss = BigDecimal.ZERO,
            dailyRealizedLoss = BigDecimal.ZERO,
            lifetimeRealizedLoss = BigDecimal.ZERO,
            lastResetDate = today,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 엔티티 부분 변경(불변 엔티티 복사 — 한도 config 보존, 변경 필드만 override). */
    private fun copyOf(
        e: AutopilotStateJpaEntity,
        killActive: Boolean = e.killActive,
        cumRealizedLoss: BigDecimal = e.cumRealizedLoss,
        dailyRealizedLoss: BigDecimal = e.dailyRealizedLoss,
        lifetimeRealizedLoss: BigDecimal = e.lifetimeRealizedLoss,
        lastResetDate: LocalDate = e.lastResetDate,
        updatedAt: Instant = e.updatedAt,
    ): AutopilotStateJpaEntity =
        AutopilotStateJpaEntity(
            id = e.id,
            killActive = killActive,
            poolPct = e.poolPct,
            perTradePct = e.perTradePct,
            maxPositions = e.maxPositions,
            dailyLossPct = e.dailyLossPct,
            cumLossPct = e.cumLossPct,
            cumRealizedLoss = cumRealizedLoss,
            dailyRealizedLoss = dailyRealizedLoss,
            lifetimeRealizedLoss = lifetimeRealizedLoss,
            lastResetDate = lastResetDate,
            createdAt = e.createdAt,
            updatedAt = updatedAt,
        )

    private fun AutopilotStateJpaEntity.toDomain(): AutopilotState =
        AutopilotState(
            killActive = killActive,
            poolPct = poolPct,
            perTradePct = perTradePct,
            maxPositions = maxPositions,
            dailyLossPct = dailyLossPct,
            cumLossPct = cumLossPct,
            cumRealizedLoss = cumRealizedLoss,
            dailyRealizedLoss = dailyRealizedLoss,
            lastResetDate = lastResetDate,
            lifetimeRealizedLoss = lifetimeRealizedLoss,
        )

    private companion object {
        val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

/**
 * kill 해제([AutopilotStateStore.deactivateKill]) 부분 보정 한도 — null 필드는 기존 값 유지(부분 patch).
 * Claude 가 사후분석 후 재개 시 더 보수적인 envelope 으로 좁히는 데 쓴다(spec §2-0-B kill-recovery).
 */
data class AutopilotLimitCorrection(
    val poolPct: BigDecimal? = null,
    val perTradePct: BigDecimal? = null,
    val maxPositions: Int? = null,
    val dailyLossPct: BigDecimal? = null,
    val cumLossPct: BigDecimal? = null,
)

/**
 * kill 해제 결과(sealed — caller `when` exhaustive). 하드 플로어 거부를 타입으로 분리해
 * 우회 경로를 컴파일에서 차단한다(진보 페르소나 — sealed 상태 표현).
 */
sealed interface DeactivateOutcome {
    /** 정상 해제(kill off + envelope 리셋). */
    data class Deactivated(
        val state: AutopilotState,
    ) : DeactivateOutcome

    /** 이미 해제 상태였음(멱등 — correctedLimits 만 반영했을 수 있음). */
    data class AlreadyInactive(
        val state: AutopilotState,
    ) : DeactivateOutcome

    /** 하드 플로어 초과 — 해제 거부(영구 kill 유지). 사용자 재가만 해제 가능. */
    data class FloorBreached(
        val state: AutopilotState,
        val floorThreshold: BigDecimal,
    ) : DeactivateOutcome
}
