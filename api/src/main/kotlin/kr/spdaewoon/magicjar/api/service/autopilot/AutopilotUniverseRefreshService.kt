package kr.spdaewoon.magicjar.api.service.autopilot

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * magicjar-autopilot **일일 universe 자동 재생성 서비스** — ADR 0131.
 *
 * ## 책임 (옵션 B — 전 전략 동일 deduped TOP N · ADR 0131 §2-1)
 * 매 영업일 장전, `discovery_scorecard` 의 오늘 snapshot(전 활성 프리셋 후보)을 합집합해
 *   1. `instrument_code` 별 **max(score)** dedup (같은 종목이 여러 프리셋 TOP 에 등장하면 가장 강한 신호 1회 반영)
 *   2. **score DESC · instrument_code ASC** 정렬(결정성 LOCK)
 *   3. **TOP N**(default 16 · 상한 40 = ADR 0124 WS slot 예산) take
 * 한 뒤, owner=AUTOPILOT 평가 전략(ACTIVE·BUY_BLOCKED) `codes` 를 **단일 트랜잭션**으로 동일 codes JSON 갱신한다.
 *
 * ## 자본 안전 (ADR 0131 §2-3 · §2-4 · §2-5 LOCK)
 * - **codes = BUY 평가 대상**일 뿐 청산 대상이 아니다. universe 에서 빠지는 종목 보유 lot 은 **hold-until-exit**
 *   (강제 청산 0 — plan·안전망 L2/L3 가 계획대로 청산). 본 서비스는 강제 청산을 **절대** 발행하지 않는다.
 * - **owner 격리(zero-regression)**: WHERE owner='AUTOPILOT' AND status IN ('ACTIVE','BUY_BLOCKED') — USER/SYSTEM touch 0.
 * - **fail-safe**: enabled=false → DISABLED no-op · snapshot 0건 → NO_SNAPSHOT skip · 빈 TOP N → EMPTY_TOPN skip
 *   (빈 universe 로 덮어써서 autopilot 매수 대상 0 이 되는 사고 차단 — 기존 universe 유지).
 * - **가시화**: 결과·회전 종목·보유 lot 잔존을 SystemEvent(INFO) + fail-safe(WARN) 로 노출(헌법 §11-2 silent 0).
 *
 * ## 호출자
 * [AutopilotUniverseRefreshWorker] 가 08:35 KST(snapshot 직후·개장 전) 거래일·1일1회 멱등 게이트를 통과한 뒤 호출한다.
 * 게이트 enabled=false 시 본 서비스가 DISABLED no-op 반환(이중 가드 — worker 도 게이트 off no-op).
 *
 * KIS 호출 0 · candle DB read 0(scorecard 영속값 read) · AI 0.
 */
@Service
class AutopilotUniverseRefreshService(
    private val discoveryScorecardJpaRepository: DiscoveryScorecardJpaRepository,
    private val strategyJpaRepository: StrategyJpaRepository,
    private val strategyPositionReadJpaRepository: StrategyPositionReadJpaRepository,
    private val objectMapper: ObjectMapper,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    @Value("\${magicjar.autopilot.universe-refresh.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${magicjar.autopilot.universe-refresh.size:16}")
    private val sizeRaw: Int = DEFAULT_SIZE,
) {
    private val log = LoggerFactory.getLogger(AutopilotUniverseRefreshService::class.java)

    companion object {
        const val DEFAULT_SIZE: Int = 16
        const val MIN_SIZE: Int = 1
        const val MAX_SIZE: Int = 40 // ADR 0124 WS slot 예산(매매 전용 40 slot) 정합.
        private const val ACTOR = "AutopilotUniverseRefreshService"
    }

    /**
     * 오늘 snapshot 으로 owner=AUTOPILOT universe 를 재생성한다.
     *
     * @param snapshotDate scorecard snapshot_date(= 오늘 KST). worker 가 거래일 게이트 통과 후 전달.
     * @return [RefreshResult] — status·universeSize·strategiesUpdated·droppedCodes·heldLotsRemaining.
     */
    @Transactional
    fun refresh(snapshotDate: LocalDate): RefreshResult {
        if (!enabled) {
            log.debug("autopilot universe refresh skip — 게이트 OFF (magicjar.autopilot.universe-refresh.enabled=false)")
            return RefreshResult.disabled()
        }

        val size = sizeRaw.coerceIn(MIN_SIZE, MAX_SIZE)

        // step0: 오늘 snapshot 전 프리셋 후보 read (from=to=today · presetKey=null 전체).
        val rows = discoveryScorecardJpaRepository.findRowsInRange(snapshotDate, snapshotDate, null)
        if (rows.isEmpty()) {
            log.warn(
                "autopilot universe refresh skip — discovery_scorecard {} snapshot 0건(기존 universe 유지·fail-safe)",
                snapshotDate,
            )
            emit(
                SystemEventSeverity.WARN,
                "UNIVERSE_REFRESH_NO_SNAPSHOT",
                """{"snapshotDate":"$snapshotDate","reason":"no scorecard snapshot rows"}""",
            )
            return RefreshResult.noSnapshot()
        }

        // step1~3: dedup(max score) → 정렬(score DESC, code ASC) → TOP N.
        val topCodes = synthesizeUniverse(rows, size)
        if (topCodes.isEmpty()) {
            log.warn("autopilot universe refresh skip — TOP N 산출 빈 결과(기존 universe 유지·fail-safe): rows={}", rows.size)
            emit(
                SystemEventSeverity.WARN,
                "UNIVERSE_REFRESH_EMPTY_TOPN",
                """{"snapshotDate":"$snapshotDate","scorecardRows":${rows.size},"reason":"empty top-n"}""",
            )
            return RefreshResult.emptyTopN()
        }

        // 직전 universe(회전 집계용) — UPDATE 전에 read.
        val previousUniverse = readPreviousUniverse()

        // owner=AUTOPILOT codes 일괄 UPDATE (단일 트랜잭션 · USER/SYSTEM touch 0).
        val codesJson = objectMapper.writeValueAsString(topCodes)
        val updated = strategyJpaRepository.updateAutopilotUniverse(codesJson)

        // 회전·보유 lot 잔존 집계(hold-until-exit 가시화) — codes 만 변경, 강제 청산 0.
        val dropped = (previousUniverse - topCodes.toSet()).sorted()
        val heldCodes = strategyPositionReadJpaRepository.findAutopilotHeldCodes().toSet()
        val heldRemaining = dropped.filter { it in heldCodes }.sorted()

        log.info(
            "autopilot universe refresh 완료 — date={} universe={}종목 strategies={} dropped={} held-lots-remaining={}",
            snapshotDate,
            topCodes.size,
            updated,
            dropped.size,
            heldRemaining.size,
        )
        emit(
            SystemEventSeverity.INFO,
            "UNIVERSE_REFRESH_UPDATED",
            """{"snapshotDate":"$snapshotDate","universeSize":${topCodes.size},"strategiesUpdated":$updated,""" +
                """"dropped":${dropped.size},"heldLotsRemaining":${heldRemaining.size},""" +
                """"heldCodes":${objectMapper.writeValueAsString(heldRemaining)}}""",
        )

        return RefreshResult(
            status = RefreshStatus.UPDATED,
            universeSize = topCodes.size,
            strategiesUpdated = updated,
            droppedCodes = dropped,
            heldLotsRemaining = heldRemaining,
        )
    }

    /**
     * deduped TOP N 합성 (결정성 LOCK — ADR 0131 §6-1).
     * group by instrument_code → max(score) → sort score DESC, code ASC → take(size).
     */
    private fun synthesizeUniverse(
        rows: List<kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaEntity>,
        size: Int,
    ): List<String> =
        rows
            .groupBy { it.instrumentCode }
            .mapValues { (_, group) -> group.maxOf { it.score } }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, java.math.BigDecimal>> { it.value }.thenBy { it.key })
            .take(size)
            .map { it.key }

    /** 직전 owner=AUTOPILOT universe 합집합 — codes JSONB(raw) 파싱(회전 집계용). 파싱 실패 row 는 skip(보수적). */
    private fun readPreviousUniverse(): Set<String> =
        strategyJpaRepository
            .findAutopilotUniverseCodesRaw()
            .asSequence()
            .filterNotNull()
            .flatMap { raw ->
                runCatching { objectMapper.readValue(raw, Array<String>::class.java).asSequence() }
                    .getOrElse {
                        log.debug("autopilot 직전 universe codes 파싱 실패(skip): raw={}", raw)
                        emptySequence()
                    }
            }.toSet()

    private fun emit(
        severity: SystemEventSeverity,
        action: String,
        detailJson: String,
    ) {
        val logger = systemEventLoggerProvider.getIfAvailable() ?: return
        runCatching {
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.OTHER,
                    severity = severity,
                    actor = ACTOR,
                    action = action,
                    userId = null,
                    detailJson = detailJson,
                ),
            )
        }.onFailure { e -> log.error("autopilot universe refresh SystemEvent emit 실패: action={} err={}", action, e.message) }
    }

    /** refresh 결과 — worker 로그·관측·테스트 단언 대상. */
    data class RefreshResult(
        val status: RefreshStatus,
        val universeSize: Int = 0,
        val strategiesUpdated: Int = 0,
        val droppedCodes: List<String> = emptyList(),
        val heldLotsRemaining: List<String> = emptyList(),
    ) {
        companion object {
            fun disabled() = RefreshResult(RefreshStatus.DISABLED)

            fun noSnapshot() = RefreshResult(RefreshStatus.NO_SNAPSHOT)

            fun emptyTopN() = RefreshResult(RefreshStatus.EMPTY_TOPN)
        }
    }

    enum class RefreshStatus {
        /** 게이트 OFF — no-op(고정 universe 유지). */
        DISABLED,

        /** 오늘 snapshot 0건 — codes 갱신 skip(기존 universe 유지·fail-safe). */
        NO_SNAPSHOT,

        /** TOP N 산출 빈 결과 — codes 갱신 skip(기존 universe 유지·fail-safe). */
        EMPTY_TOPN,

        /** codes 갱신 완료. */
        UPDATED,
    }
}
