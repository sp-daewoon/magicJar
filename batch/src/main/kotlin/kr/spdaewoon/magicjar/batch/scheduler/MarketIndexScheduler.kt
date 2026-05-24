package kr.spdaewoon.magicjar.batch.scheduler

import kr.spdaewoon.magicjar.batch.job.market.MarketIndexDailyCloseJob
import kr.spdaewoon.magicjar.batch.job.market.MarketIndexSyncJob
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T-MARKET cron scheduler (ADR 0100 §2-4 LOCK).
 *
 * ### cron 매트릭스
 *   - 1분 cron (09:00~15:30 KST · MON-FRI): MarketIndexSyncJob.run("minute")
 *     · 영업일 + 정규장 시간 게이트 (간단 stub — MarketSession FSM 본격 통합은 post-MVP)
 *   - 16:30 KST (MON-FRI): MarketIndexDailyCloseJob.runDomestic("16:30")
 *   - 06:00 KST (TUE-SAT · 미국 정규장 마감 후): MarketIndexDailyCloseJob.runOverseas("06:00")
 *
 * ### 영업일 게이트
 * - cron `zone=Asia/Seoul` MON-FRI 1차 게이트
 * - 공휴일·임시 휴장은 KIS adapter 응답 부재로 자연 흡수 (gate.shouldSkip 또는 null 응답)
 * - 본격 KrxMarketSessionResolver 통합은 batch ↔ api 모듈 경계 정합 후 별 사이클
 *
 * ### sync-at-boot 패턴
 * - `magicjar.market.index.sync.boot=true` 시 부팅 직후 1회 (dev smoke 용도)
 */
@Component
@EnableScheduling
class MarketIndexScheduler(
    private val marketIndexSyncJob: MarketIndexSyncJob,
    private val marketIndexDailyCloseJob: MarketIndexDailyCloseJob,
    @Value("\${magicjar.market.index.sync.boot:false}")
    private val syncAtBoot: Boolean,
) {
    private val log = LoggerFactory.getLogger(MarketIndexScheduler::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 1분 cron — 09:00~15:30 KST MON-FRI 매분 발사.
     *
     * cron expression 분해 (KST):
     *   "0 * 9-15 * * MON-FRI"
     *   · 분초: every minute 0초 발사
     *   · 시  : 9·10·11·12·13·14·15 시 (15시는 30분 cutoff 별도 in-Job check)
     *   · 요일: 평일만
     */
    @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    fun syncEveryMinute() {
        val now = LocalDateTime.now(kst)
        // 15:30 KST 이후는 정규장 외 → skip (15:00~15:30 한정)
        if (now.hour == 15 && now.minute > 30) {
            return
        }
        try {
            marketIndexSyncJob.run("minute")
        } catch (e: Exception) {
            log.warn("[MarketIndexScheduler] minute 발사 실패 (다음 분 정상 재시도) err={}", e.message)
        }
    }

    /**
     * 16:30 KST 국내 일봉 backfill — 정규장 마감 + 30분 buffer.
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Seoul")
    fun dailyDomesticClose() {
        try {
            marketIndexDailyCloseJob.runDomestic("16:30")
        } catch (e: Exception) {
            log.warn("[MarketIndexScheduler] domestic daily 발사 실패 err={}", e.message)
        }
    }

    /**
     * 06:00 KST 해외 일봉 backfill — TUE-SAT (월요일 아침 = 미국 일요일, 휴장).
     *
     * KIS 응답 부재 시 graceful skip (Job 내부 처리).
     */
    @Scheduled(cron = "0 0 6 * * TUE-SAT", zone = "Asia/Seoul")
    fun dailyOverseasClose() {
        try {
            marketIndexDailyCloseJob.runOverseas("06:00")
        } catch (e: Exception) {
            log.warn("[MarketIndexScheduler] overseas daily 발사 실패 err={}", e.message)
        }
    }
}
