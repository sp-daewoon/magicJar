package com.mj.api.application.backtest

import com.mj.api.infrastructure.persistence.backtest.BacktestRunRepository
import com.mj.api.infrastructure.persistence.backtest.toDto
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID

/**
 * 백테스트 트리거 서비스.
 *
 * Phase E1 정책: api-app → batch-app HTTP POST /jobs/backtest 로 잡 트리거.
 * batch-app이 비가용일 경우(로컬 단독 실행 등) in-process 직접 실행으로 폴백.
 *
 * 결과 상태는 BacktestRun JPA 테이블에서 조회한다.
 */
@Service
class BacktestService(
    private val runRepository: BacktestRunRepository,
    @Value("\${magicjar.batch.base-url:http://localhost:8081}") private val batchBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(BacktestService::class.java)
    private val restClient = RestClient.create()

    fun trigger(req: BacktestTriggerRequest): BacktestTriggerResponse {
        val runId = UUID.randomUUID().toString()
        val run =
            com.mj.api.infrastructure.persistence.backtest.BacktestRunJpa(
                runId = runId,
                strategyId = req.strategyId,
                symbol = req.symbol,
                fromDate = req.from,
                toDate = req.to,
                status = BacktestRunStatus.QUEUED.name,
                startedAt = null,
                finishedAt = null,
                summaryJson = null,
                errorMessage = null,
                createdAt = Instant.now(),
            )
        runRepository.save(run)

        // batch-app에 비동기 트리거 시도 (실패 허용 — 로컬 단독 실행 시 batch-app 미가용)
        try {
            restClient
                .post()
                .uri("$batchBaseUrl/jobs/backtest")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "runId" to runId,
                        "strategyId" to req.strategyId,
                        "symbol" to req.symbol,
                        "from" to req.from,
                        "to" to req.to,
                    ),
                ).retrieve()
                .toBodilessEntity()
            log.info("Backtest run=$runId dispatched to batch-app")
        } catch (e: Exception) {
            log.warn("batch-app 미가용 — run=$runId 는 QUEUED 상태로 유지. 원인: ${e.message}")
        }

        return BacktestTriggerResponse(
            runId = runId,
            status = BacktestRunStatus.QUEUED,
            message = "백테스트 요청이 접수됐습니다.",
        )
    }

    fun getStatus(runId: String): BacktestStatusResponse? = runRepository.findByRunId(runId)?.toDto()
}
