package com.mj.api.application.screener

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.infrastructure.persistence.screener.ScreenMatchJpa
import com.mj.api.infrastructure.persistence.screener.ScreenMatchJpaRepository
import com.mj.api.infrastructure.persistence.screener.ScreenRunJpa
import com.mj.api.infrastructure.persistence.screener.ScreenRunJpaRepository
import com.mj.domain.common.Interval
import com.mj.domain.market.instrument.port.InstrumentRepository
import com.mj.domain.market.port.CandleRepository
import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import com.mj.domain.strategy.screener.ScreenRequest
import com.mj.domain.strategy.screener.ScreenResult
import com.mj.domain.strategy.screener.ScreenerEngine
import com.mj.domain.strategy.screener.Universe
import com.mj.domain.strategy.screener.UniverseResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Screener 실행 application service (ADR 0040 T10).
 *
 * 흐름:
 *  1. screen_run 행 생성 (RUNNING)
 *  2. UniverseResolver → 종목 코드 목록
 *  3. 종목별 캔들 200봉 로딩 (D1, asOfDate 기준 이하)
 *  4. ScreenerEngine.screen() — 순수 도메인 평가
 *  5. screen_run 결과 업데이트 (COMPLETED)
 *  6. screen_match 행 일괄 저장
 *
 * @param candleRepository  캔들 저장소
 * @param instrumentRepository 종목 마스터 (name 조회용)
 * @param universeResolver  Universe → code list
 * @param screenerEngine    순수 도메인 엔진
 */
@Service
class ScreenerExecutor(
    private val candleRepository: CandleRepository,
    private val instrumentRepository: InstrumentRepository,
    private val universeResolver: UniverseResolver,
    private val evaluator: StrategyDslEvaluator,
    private val screenRunRepo: ScreenRunJpaRepository,
    private val screenMatchRepo: ScreenMatchJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ScreenerExecutor::class.java)
    private val screenerEngine: ScreenerEngine get() = ScreenerEngine(evaluator)

    companion object {
        /** 기본 lookback 봉 수 */
        const val DEFAULT_LOOKBACK = 200

        private val KST = ZoneId.of("Asia/Seoul")
    }

    /**
     * 스크리닝 실행.
     *
     * @param request      스크린 요청
     * @param strategyYaml DSL YAML 원문 (이력 저장용)
     * @param userId       실행 사용자 (null = 시스템 실행)
     * @return 생성된 screen_run의 runId
     */
    @Transactional
    fun execute(
        request: ScreenRequest,
        strategyYaml: String = request.dsl.metadata.name,
        userId: Long? = null,
    ): String {
        val runId = UUID.randomUUID().toString()
        val universeType = universeTypeName(request.universe)
        val universeDetail = universeDetailJson(request.universe)

        // 1. screen_run 생성 (RUNNING)
        val screenRun =
            ScreenRunJpa(
                runId = runId,
                strategyYaml = strategyYaml,
                universeType = universeType,
                universeDetail = universeDetail,
                asOfDate = request.asOfDate,
                resultJson = null,
                totalScanned = 0,
                matchCount = 0,
                skipCount = 0,
                status = "RUNNING",
                errorMessage = null,
                userId = userId,
                createdAt = Instant.now(),
                finishedAt = null,
            )
        screenRunRepo.save(screenRun)

        runCatching {
            doRun(screenRun, request)
        }.onFailure { ex ->
            log.error("screener run=$runId FAILED", ex)
            screenRun.status = "FAILED"
            screenRun.errorMessage = ex.message?.take(512)
            screenRun.finishedAt = Instant.now()
            screenRunRepo.save(screenRun)
        }

        return runId
    }

    private fun doRun(
        screenRun: ScreenRunJpa,
        request: ScreenRequest,
    ) {
        // 2. 종목 코드 목록
        val codes = universeResolver.resolve(request.universe)
        log.info("screener run=${screenRun.runId} universe=${screenRun.universeType} codes=${codes.size}")

        // 3. 종목명 맵 (name bycode)
        val nameByCode: Map<String, String> =
            instrumentRepository
                .findAll()
                .filter { it.code in codes }
                .associate { it.code to it.name }

        // 4. 캔들 로딩 (asOfDate 기준 D1, lookback 200봉)
        val asOfDate = request.asOfDate
        val toInstant = asOfDate.plusDays(1).atStartOfDay(KST).toInstant()
        val fromInstant =
            asOfDate
                .minusDays((DEFAULT_LOOKBACK * 1.5).toLong())
                .atStartOfDay(KST)
                .toInstant()

        val candlesByCode =
            codes.associateWith { code ->
                candleRepository
                    .find(code, Interval.D1, fromInstant, toInstant)
                    .takeLast(DEFAULT_LOOKBACK)
            }

        // 5. ScreenerEngine 실행
        val screenResult: ScreenResult =
            screenerEngine.screen(request, candlesByCode, nameByCode)

        // 6. screen_run 업데이트
        screenRun.totalScanned = screenResult.totalScanned
        screenRun.matchCount = screenResult.matchCount
        screenRun.skipCount = screenResult.skipCount
        screenRun.resultJson =
            objectMapper.writeValueAsString(
                mapOf(
                    "skippedCodes" to screenResult.skippedCodes,
                    "executedAt" to screenResult.executedAt.toString(),
                ),
            )
        screenRun.status = "COMPLETED"
        screenRun.finishedAt = Instant.now()
        screenRunRepo.save(screenRun)

        // 7. screen_match 저장
        val matchJpas =
            screenResult.matches.map { m ->
                ScreenMatchJpa(
                    screenRun = screenRun,
                    code = m.code,
                    name = m.name,
                    entryReason = objectMapper.writeValueAsString(m.entryReasons),
                    matchedAt = m.matchedAt,
                )
            }
        screenMatchRepo.saveAll(matchJpas)

        log.info(
            "screener run=${screenRun.runId} COMPLETED " +
                "scanned=${screenResult.totalScanned} matched=${screenResult.matchCount} " +
                "skipped=${screenResult.skipCount}",
        )
    }

    /**
     * screen_run 상태 조회.
     */
    @Transactional(readOnly = true)
    fun getStatus(runId: String): ScreenRunStatus? =
        screenRunRepo.findByRunId(runId)?.let { run ->
            ScreenRunStatus(
                runId = run.runId,
                status = run.status,
                universeType = run.universeType,
                asOfDate = run.asOfDate,
                totalScanned = run.totalScanned,
                matchCount = run.matchCount,
                skipCount = run.skipCount,
                errorMessage = run.errorMessage,
                createdAt = run.createdAt,
                finishedAt = run.finishedAt,
            )
        }

    private fun universeTypeName(universe: Universe): String =
        when (universe) {
            is Universe.Kospi200 -> "KOSPI200"
            is Universe.Kosdaq150 -> "KOSDAQ150"
            is Universe.Etf -> "ETF"
            is Universe.Etn -> "ETN"
            is Universe.AllListed -> "ALL_LISTED"
            is Universe.Custom -> "CUSTOM"
            is Universe.SymbolGroup -> "SYMBOL_GROUP"
        }

    private fun universeDetailJson(universe: Universe): String? =
        when (universe) {
            is Universe.Custom ->
                objectMapper.writeValueAsString(mapOf("codes" to universe.codes))
            is Universe.SymbolGroup ->
                objectMapper.writeValueAsString(
                    mapOf(
                        "markets" to universe.markets?.map { it.name },
                        "types" to universe.types?.map { it.name },
                        "sectors" to universe.sectors,
                        "activeOnly" to universe.activeOnly,
                    ),
                )
            else -> null
        }
}

/** screen_run 상태 DTO */
data class ScreenRunStatus(
    val runId: String,
    val status: String,
    val universeType: String,
    val asOfDate: LocalDate,
    val totalScanned: Int,
    val matchCount: Int,
    val skipCount: Int,
    val errorMessage: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)
