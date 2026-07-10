package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.config.MinutePoolBackfillProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMinutePoolCandidateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 분봉 수집 풀 후보 산출 단일 경로 (2026-07-10 · market-data · minute-pool).
 *
 * union 순서(우선순위 = 풀 clamp 시 앞선 소스가 살아남음):
 *   1. yml seed 종목 (ops 지정 유동성 baseline — 항상 포함)
 *   2. ACTIVE/autopilot 전략 명시 종목 (인트라데이 데이터가 가장 필요한 종목 · [ActiveStrategyCodeProvider])
 *   3. 거래대금 상위 N (최신 1d 봉 close×volume — [ConsumerMinutePoolCandidateJpaRepository.findTopTurnoverCodes])
 *   4. 발굴 스코어카드 후보 (최신 snapshot TOP N)
 *
 * dedupe(순서 보존) 후 [MinutePoolBackfillProperties.normalizedPoolSize] 상한 clamp.
 * 각 동적 소스는 개별 try-catch 로 격리 — 한 소스 실패가 전체 풀 산출을 막지 않는다(WARN + 부분 진행).
 *
 * KIS 콜 0 (DB·전략 조회만). 자본 영향 0.
 */
@Component
class MinutePoolProvider(
    private val candidateRepository: ConsumerMinutePoolCandidateJpaRepository,
    private val activeStrategyCodeProvider: ActiveStrategyCodeProvider,
    private val properties: MinutePoolBackfillProperties,
) {
    private val log = LoggerFactory.getLogger(MinutePoolProvider::class.java)

    private companion object {
        val CODE_REGEX = Regex("^\\d{6}$")
    }

    data class PoolBreakdown(
        val seed: Int,
        val strategy: Int,
        val turnover: Int,
        val scorecard: Int,
        val unionBeforeClamp: Int,
        val finalPool: List<String>,
    )

    /** 후보 풀 종목코드(순서 보존·dedupe·clamp). 관측용 breakdown 은 [resolveBreakdown]. */
    fun resolvePool(): List<String> = resolveBreakdown().finalPool

    fun resolveBreakdown(): PoolBreakdown {
        val ordered = LinkedHashSet<String>()

        val seed = properties.seedCodes.filter { CODE_REGEX.matches(it) }
        ordered.addAll(seed)

        val strategy =
            if (properties.includeActiveStrategyCodes) {
                runCatching { activeStrategyCodeProvider.activeCodes() }
                    .onFailure { e -> log.warn("minute-pool: ACTIVE 전략 종목 조회 실패 — skip: err={}", e.message) }
                    .getOrDefault(emptySet())
                    .filter { CODE_REGEX.matches(it) }
            } else {
                emptyList()
            }
        ordered.addAll(strategy)

        val turnover =
            runCatching { candidateRepository.findTopTurnoverCodes(properties.turnoverTopN.coerceAtLeast(0)) }
                .onFailure { e -> log.warn("minute-pool: 거래대금 상위 조회 실패 — skip: err={}", e.message) }
                .getOrDefault(emptyList())
                .filter { CODE_REGEX.matches(it) }
        ordered.addAll(turnover)

        val scorecard =
            runCatching { candidateRepository.findLatestScorecardCandidateCodes(properties.scorecardTopN.coerceAtLeast(0)) }
                .onFailure { e -> log.warn("minute-pool: 발굴 스코어카드 후보 조회 실패 — skip: err={}", e.message) }
                .getOrDefault(emptyList())
                .filter { CODE_REGEX.matches(it) }
        ordered.addAll(scorecard)

        val unionBeforeClamp = ordered.size
        val finalPool = ordered.toList().take(properties.normalizedPoolSize())

        log.info(
            "minute-pool 후보 산출: seed={} strategy={} turnover={} scorecard={} union={} → pool={}(cap={})",
            seed.size,
            strategy.size,
            turnover.size,
            scorecard.size,
            unionBeforeClamp,
            finalPool.size,
            properties.normalizedPoolSize(),
        )

        return PoolBreakdown(
            seed = seed.size,
            strategy = strategy.size,
            turnover = turnover.size,
            scorecard = scorecard.size,
            unionBeforeClamp = unionBeforeClamp,
            finalPool = finalPool,
        )
    }
}
