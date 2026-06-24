package kr.spdaewoon.magicjar.api.scheduler

import kr.spdaewoon.magicjar.api.persistence.MarketCalendarJpaRepository
import kr.spdaewoon.magicjar.api.service.autopilot.AutopilotUniverseRefreshService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * magicjar-autopilot **일일 universe 자동 재생성 워커** — ADR 0131.
 *
 * 매 영업일 **08:35 KST**(scorecard snapshot 08:30 직후 · 개장 09:00 전), 발굴 결과를 deduped TOP N 으로 합성해
 * owner=AUTOPILOT 평가 전략 codes 를 갱신한다([AutopilotUniverseRefreshService.refresh]).
 *
 * ## 순서 의존 LOCK (ADR 0131 §2-2)
 *   scorecard snapshot 08:30 → **universe refresh 08:35**(5분 여유) → 개장 09:00 → 인트라데이 warmup 09:01.
 *   08:35 갱신 → 09:01 warmup 사이 26분 여유로 신규 종목 WS 구독·캔들 warmup 이 준비된다(신규 배선 0 —
 *   `ActiveStrategyCodeProvider`·`ActiveStrategyIntradayWarmupRunner` 가 갱신 codes 를 자동 read).
 *
 * ## 게이트 (4단)
 *   1. ENV `magicjar.autopilot.universe-refresh.enabled`(default **false**) — off → worker no-op(고정 universe 유지).
 *      서비스도 동일 게이트로 DISABLED 반환(이중 가드). 게이트 off = v3.14.0 거동 byte-동일(zero-regression).
 *   2. cron `zone=Asia/Seoul` — SpEL placeholder ENV 외부화(default `0 35 8 * * *`).
 *   3. 영업일 가드 — market_calendar DB row + 주말 fallback ([DiscoveryScorecardScheduler.isBusinessDay] 1:1 산식).
 *   4. 1일 1회 멱등 — KST 거래일 [lastRefreshedDate] AtomicReference(중복 UPDATE·SystemEvent 억제).
 *
 * ## 자본 안전
 *   codes 갱신만 — 강제 청산 0(hold-until-exit). fail-safe(snapshot 0건·빈 TOP N) 시 서비스가 codes 갱신 skip.
 *   KIS 호출 0 · AI 0.
 *
 * ## cron ENV 외부화 함정 (Spring @Scheduled 제약 — DiscoveryScorecardScheduler 동일)
 *   @Scheduled(cron=...)는 SpEL placeholder 만 받는다 → `\${magicjar.autopilot.universe-refresh.cron:0 35 8 * * *}`.
 */
@Component
class AutopilotUniverseRefreshWorker(
    private val refreshService: AutopilotUniverseRefreshService,
    private val marketCalendarJpaRepository: MarketCalendarJpaRepository,
    @Value("\${magicjar.autopilot.universe-refresh.enabled:false}")
    private val enabled: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(AutopilotUniverseRefreshWorker::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /** 당일 1회 멱등 가드 — 마지막 refresh KST 거래일. 같은 거래일 재발사 0(중복 UPDATE·중복 SystemEvent 방지). */
    private val lastRefreshedDate = AtomicReference<LocalDate?>(null)

    /**
     * 장전 universe 재생성 — default 08:35 KST 매 영업일.
     * ENV `MAGICJAR_AUTOPILOT_UNIVERSE_REFRESH_CRON`(property `magicjar.autopilot.universe-refresh.cron`).
     */
    @Scheduled(cron = "\${magicjar.autopilot.universe-refresh.cron:0 35 8 * * *}", zone = "Asia/Seoul")
    fun refreshTick() {
        if (!enabled) {
            log.debug("autopilot universe refresh skip — 게이트 OFF")
            return
        }

        val today = LocalDate.now(kst)
        if (!isBusinessDay(today)) {
            log.info("autopilot universe refresh skip — KRX 비거래일 ({})", today)
            return
        }
        // 당일 이미 발사 → 멱등 skip(부팅·수동 재트리거 중복 흡수).
        if (lastRefreshedDate.get() == today) {
            log.debug("autopilot universe refresh skip — 당일 이미 발사: {}", today)
            return
        }

        // 멱등 마킹을 발사 전 set — 발사 중 겹침 0(서비스 트랜잭션이 2차 방어, 멱등 산식이라 재실행도 동일 결과).
        lastRefreshedDate.set(today)
        try {
            val result = refreshService.refresh(today)
            log.info(
                "autopilot universe refresh 발사: status={} universe={} strategies={} dropped={} held-lots={}",
                result.status,
                result.universeSize,
                result.strategiesUpdated,
                result.droppedCodes.size,
                result.heldLotsRemaining.size,
            )
        } catch (e: Exception) {
            // 실패 시 당일 마킹 해제 → 다음 부팅·수동 트리거 재시도 가능(멱등 산식이라 재실행 무해).
            lastRefreshedDate.set(null)
            log.warn("autopilot universe refresh 실패(당일 마킹 해제·재시도 허용): date={} err={}", today, e.message, e)
        }
    }

    /**
     * 영업일 판정 — market_calendar DB row 우선, 미등록 시 주말 가드 fallback.
     * ([DiscoveryScorecardScheduler.isBusinessDay] 산식 1:1 — ADR 0104 거래일 게이트.)
     */
    private fun isBusinessDay(date: LocalDate): Boolean {
        val entry = marketCalendarJpaRepository.findById(date).orElse(null)
        if (entry != null) return entry.isBusinessDay
        val dow = date.dayOfWeek
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
    }
}
