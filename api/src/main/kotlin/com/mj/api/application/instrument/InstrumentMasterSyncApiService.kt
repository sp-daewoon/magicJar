package com.mj.api.application.instrument

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.OffsetDateTime

/**
 * 종목 마스터파일 수동 수집 서비스 (api 모듈 측).
 *
 * api-app → batch-app HTTP POST /jobs/instrument/sync 위임.
 * batch-app이 미가용(로컬 standalone 실행)인 경우 graceful warn 처리.
 *
 * T18a-T4.
 */
@Service
class InstrumentMasterSyncApiService(
    @Value("\${magicjar.batch.base-url:http://localhost:8081}") private val batchBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(InstrumentMasterSyncApiService::class.java)
    private val restClient = RestClient.create()

    /**
     * batch-app에 종목 마스터파일 수집 트리거. 비동기 시작(202 Accepted).
     *
     * @param markets 수집 대상 시장 목록 (null이면 batch 기본값 KOSPI·KOSDAQ·KONEX)
     * @return SyncTriggerResult
     */
    fun triggerSync(markets: List<String>?): SyncTriggerResult {
        log.info("[instrument-master-sync] trigger → batch-app markets={}", markets)
        return try {
            val response =
                restClient
                    .post()
                    .uri("$batchBaseUrl/jobs/instrument/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapOf("markets" to markets))
                    .retrieve()
                    .body(Map::class.java)

            val runId = response?.get("runId") as? String ?: "unknown"
            val status = response?.get("status") as? String ?: "STARTED"
            log.info("[instrument-master-sync] dispatched runId={}", runId)
            SyncTriggerResult(runId = runId, status = status, scheduledAt = OffsetDateTime.now())
        } catch (e: Exception) {
            log.warn("[instrument-master-sync] batch-app 미가용 — {}", e.message)
            SyncTriggerResult(runId = "unavailable", status = "BATCH_APP_UNAVAILABLE", scheduledAt = OffsetDateTime.now())
        }
    }

    /**
     * 실행 상태 조회.
     */
    fun syncStatus(runId: String): Map<*, *>? =
        try {
            restClient
                .get()
                .uri("$batchBaseUrl/jobs/instrument/sync/$runId")
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            log.warn("[instrument-master-sync] status query failed runId={} — {}", runId, e.message)
            null
        }
}

data class SyncTriggerResult(
    val runId: String,
    val status: String,
    val scheduledAt: OffsetDateTime,
)
