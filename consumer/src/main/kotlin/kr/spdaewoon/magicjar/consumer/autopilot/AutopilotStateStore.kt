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
 * `autopilot_state`(V91) 읽기·쓰기 단일 진입점 (spec 2026-06-22 §2-0-A·§2-0-B · Task 3).
 *
 * ## 책임 (SRP)
 * - [load] — account-wide 단일 행(id=1)을 도메인 [AutopilotState] 로 읽는다. 부재·실패 시
 *   [MagicjarAutopilotProperties.toDefaultState] fallback(부트 default — DRY 단일 출처).
 * - [accumulateRealizedLoss] — owner=AUTOPILOT 실현 손실을 `cum_realized_loss`·`daily_realized_loss` 에
 *   누적(양수 = 손실). 날짜(KST) 바뀌면 daily reset(`last_reset_date`). 이게 kill switch 발동의 토대다.
 * - [activateKill] / [load 후 verdict] — kill_active 영속.
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
                lastResetDate = today,
                updatedAt = now,
            )
        val saved = repository.save(next)
        log.info(
            "autopilot 손실 누적: realizedLoss={} cumRealizedLoss={} dailyRealizedLoss={} dailyReset={} lastResetDate={}",
            realizedLoss.toPlainString(),
            saved.cumRealizedLoss.toPlainString(),
            saved.dailyRealizedLoss.toPlainString(),
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
        )

    private companion object {
        val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
