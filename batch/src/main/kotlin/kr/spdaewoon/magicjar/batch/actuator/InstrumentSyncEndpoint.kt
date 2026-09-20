package kr.spdaewoon.magicjar.batch.actuator

import kr.spdaewoon.magicjar.batch.job.instrument.InstrumentSyncJob
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.stereotype.Component

/**
 * 종목 마스터 sync 수동 트리거 actuator endpoint (ADR 0143 clean-reset 재온보딩).
 *
 * 왜 필요한가:
 *   정규 sync는 [kr.spdaewoon.magicjar.batch.scheduler.InstrumentMasterSyncScheduler]가 MON-FRI cron +
 *   영업일 게이트(runIfBusinessDay)로만 실행한다. 휴장일/주말엔 skip되어 clean-reset 후 대·중형 재수집을 못 한다.
 *   본 endpoint는 [InstrumentSyncJob.run]을 **직접** 호출 — job 자체는 영업일 체크가 없고(스케줄러에만 있음),
 *   데이터 출처가 KIS 종목마스터 zip 파일 다운로드라 주말에도 상시 응답한다.
 *
 * 안전:
 *   - job 내부 Redis 동시 실행 락(instrument:sync:running)으로 중복 실행 방지 — 기존 로직 재사용.
 *   - 대·중형 필터(ADR 0143)는 job에 이미 배선 — 본 endpoint는 트리거만.
 *   - actuator write operation이라 management port(8081)에서만 접근. 외부 노출 아님.
 *
 * 호출: POST http://localhost:8081/actuator/instrumentSync
 */
@Component
@Endpoint(id = "instrumentSync")
class InstrumentSyncEndpoint(
    private val instrumentSyncJob: InstrumentSyncJob,
) {
    private val log = LoggerFactory.getLogger(InstrumentSyncEndpoint::class.java)

    @WriteOperation
    fun trigger(): Map<String, Any?> {
        log.info("[instrument-sync] actuator 수동 트리거 — 영업일 게이트 우회 (ADR 0143 재온보딩)")
        val result = instrumentSyncJob.run("manual-actuator")
        return mapOf(
            "triggered" to true,
            "fetched" to result.fetched,
            "inserted" to result.inserted,
            "updated" to result.updated,
            "delisted" to result.delisted,
            "failed" to result.failed,
        )
    }
}
