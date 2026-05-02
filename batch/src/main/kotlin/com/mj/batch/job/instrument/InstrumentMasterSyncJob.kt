package com.mj.batch.job.instrument

import com.mj.batch.infrastructure.persistence.market.toDomain
import com.mj.domain.common.Market
import com.mj.domain.market.instrument.port.InstrumentRepository
import com.mj.domain.market.instrument.port.KisInstrumentMasterException
import com.mj.domain.market.instrument.port.KisInstrumentMasterPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 종목 마스터 일일 동기화 잡 (ADR 0037 §2-3).
 *
 * cron: 매일 06:00 KST (장 시작 전, 영업일 외도 실행 — 정확성 우선)
 * profile: dev·prd 에서 활성. mock 에서는 비활성.
 *
 * 흐름:
 * 1. KisInstrumentMasterPort.downloadAll(market) — KOSPI·KOSDAQ·KONEX
 * 2. InstrumentRaw → Instrument 변환
 * 3. InstrumentRepository.upsertAll (code 기준 ON CONFLICT)
 * 4. 신규 다운로드에 없는 종목 → markDelistedIfNotIn (delistedAt=today, active=false)
 * 5. Kafka instrument.updated.v1 publish (TODO: post-T3 — consumer RedisInstrumentSearchAdapter 연동)
 *
 * 실패 처리:
 * - 시장별 독립 실행: 한 시장 실패 시 나머지 시장은 계속 진행
 * - 로그 기록 후 재시도는 다음 cron에 위임 (마지막 성공 데이터 유지)
 */
@Component
@Profile("dev | prd")
class InstrumentMasterSyncJob(
    private val kisPort: KisInstrumentMasterPort,
    private val instrumentRepository: InstrumentRepository,
    @Value("\${magicjar.instrument.sync.enabled:true}") private val syncEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(InstrumentMasterSyncJob::class.java)

    /** 동기화 대상 시장. ETF·ETN은 KOSPI/KOSDAQ mst에 포함. */
    private val targetMarkets = listOf(Market.KOSPI, Market.KOSDAQ, Market.KONEX)

    /**
     * 매일 06:00 KST 실행.
     * cron zone: Asia/Seoul
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    fun syncAll() {
        if (!syncEnabled) {
            log.info("[instrument-sync] 비활성 (magicjar.instrument.sync.enabled=false)")
            return
        }

        log.info("[instrument-sync] 종목 마스터 동기화 시작")
        var totalSuccess = 0
        var totalFailed = 0

        for (market in targetMarkets) {
            try {
                val count = syncMarket(market)
                log.info("[instrument-sync] {} 완료: {} 건", market, count)
                totalSuccess++
            } catch (ex: KisInstrumentMasterException) {
                log.error("[instrument-sync] {} 실패 (KIS 오류): {}", market, ex.message)
                totalFailed++
            } catch (ex: Exception) {
                log.error("[instrument-sync] {} 실패 (예상치 못한 오류): {}", market, ex.message, ex)
                totalFailed++
            }
        }

        log.info("[instrument-sync] 종목 마스터 동기화 완료 — 성공={} 실패={}", totalSuccess, totalFailed)
    }

    /**
     * 단일 시장 동기화.
     * @return upsert된 종목 수
     */
    fun syncMarket(market: Market): Int {
        log.info("[instrument-sync] {} 다운로드 시작", market)
        val rawList = kisPort.downloadAll(market)

        if (rawList.isEmpty()) {
            log.warn("[instrument-sync] {} 응답 없음 — skip", market)
            return 0
        }

        val instruments = rawList.map { it.toDomain() }
        log.info("[instrument-sync] {} 파싱 완료: {} 건", market, instruments.size)

        instrumentRepository.upsertAll(instruments)

        val activeCodes = instruments.map { it.code }.toSet()
        val delistedCount = instrumentRepository.markDelistedIfNotIn(market, activeCodes, LocalDate.now())
        if (delistedCount > 0) {
            log.info("[instrument-sync] {} 상장폐지 처리: {} 건", market, delistedCount)
        }

        return instruments.size
    }
}
