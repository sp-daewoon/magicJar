package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PostConstruct
import kr.spdaewoon.magicjar.consumer.reconciliation.SymbolDetailMinuteGapFillService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.time.ZoneId

/**
 * ACTIVE 전략 종목 인트라데이(1m→3m/5m) 캔들 개장 직후 조기 warmup.
 *
 * ## 왜 필요한가 (follow-up J — 개장 zone starve 근본)
 * 인트라데이 1m→합산봉(3m·5m) **과거 시리즈** backfill 경로([SymbolDetailMinuteGapFillService.submitIfNeeded]
 * → [kr.spdaewoon.magicjar.domain.market.port.KisOneMinuteCandleRangePort.fetchOneMinuteCandlesBetween] 09:00→now
 * → saveAll → aggregateBackfillRunner)는 다음 3 경로에서만 트리거됐다:
 *   1. 종목 상세 화면 ENTER ([kr.spdaewoon.magicjar.consumer.kafka.SymbolDetailCommandKafkaListener])
 *   2. KIS WS OPSP0008 거부 fallback ([KisWsRejectFallbackWiring])
 *   3. 무tick watchdog(120s) fallback ([kr.spdaewoon.magicjar.consumer.reconciliation.NoTickMinuteFallbackService])
 *
 * **그 어느 것도 ACTIVE 전략 활성화 시점에 발사되지 않는다.** 정상적으로 WS tick을 받는 전략 종목은
 * 진행 중 1m 진행봉만 앞으로 채워질 뿐, 09:00~현재의 **과거 1m/3m/5m bar 가 DB에 없다**. 따라서 FVG/오더블록
 * lookback zone이 개장에 형성되지 못하고, 누군가 상세 화면을 열거나(2026-06-19 ~13:40) WS가 거부/무tick될 때까지
 * 미형성으로 남았다. 그 결과 102 fvg·103 ob 첫 신호가 오후로 밀려 슬롯 경합에서 starve (#1101 진단).
 *
 * ## 조치 (개장 시점 준비 — 전략 종목 한정 on-demand)
 * 개장 직후(09:01 KST cron) + 부팅 시 세션 중이면 즉시, ACTIVE 전략 종목 each 에 대해
 * [SymbolDetailMinuteGapFillService.submitIfNeeded]를 호출한다. **이미 검증된 on-demand 경로를 그대로 재사용**하며
 * 전략-활성화 타이밍으로만 구동한다(detail-screen 진입 대신). gap-fill 서비스 내부 60초 dedupe·in-flight 가드가
 * 중복 KIS 호출을 막고, range fetch 는 [kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority.BACKFILL]
 * 버킷(최저 우선순위)을 써 매매 경로(USER_PATH·REALTIME·INGESTION)를 침범하지 않는다.
 *
 * ## ADR 0119 정합 (proactive 백필 폐지 정신과 충돌 없음)
 * ADR 0119 가 폐지한 것은 **전종목 cold-start 1m storm**(managed universe 2,517종 × 126일)이다. 본 warmup 은
 * **ACTIVE 전략 종목 한정**(보통 한 자릿수~십 단위) × **당일 09:00~now 1구간**만 backfill 하므로 on-demand 범주이며,
 * SymbolDetail ENTER gap-fill 과 동일한 KIS 부하 프로파일이다(전종목 proactive 가 아님). 캔들 정책 정합.
 *
 * ## rate limit 영향
 * 종목당 당일 1구간(09:00~now, 보통 1~2 페이지) × BACKFILL 버킷. 부팅 직후 1회 + 매일 09:01 1회. 전략 N종목이면
 * 1회 burst N콜이나 gap-fill executor(2 thread)가 직렬화하고 BACKFILL 버킷이 throttle 한다. 매매 경로 0 침범.
 *
 * AI 호출 0 — 룰 5 정합.
 */
@Component
class ActiveStrategyIntradayWarmupRunner(
    private val activeStrategyCodeProvider: ActiveStrategyCodeProvider,
    private val minuteGapFillService: SymbolDetailMinuteGapFillService,
    @Value("\${magicjar.realtime-candle.intraday-warmup.enabled:true}")
    private val enabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(ActiveStrategyIntradayWarmupRunner::class.java)
    private val kstZone = ZoneId.of("Asia/Seoul")

    // 정규장 개장 = 09:00 KST. warmup 가능 윈도우는 개장~마감(15:30) — 부팅 catch-up 판정용.
    // (08:00 NXT-통합 개장 대신 정규장 09:00 기준 — FVG/OB zone 은 정규장 시리즈가 권위)
    private val sessionOpen: LocalTime = LocalTime.of(9, 0)
    private val sessionClose: LocalTime = LocalTime.of(15, 30)

    /**
     * 부팅 catch-up — 부팅 시각이 정규장 세션(09:00~15:30) 중이면 즉시 ACTIVE 전략 종목 warmup.
     *
     * consumer 재기동·배포가 장중에 일어나면 09:01 cron 을 놓치므로 부팅 시점에 한 번 보강한다.
     * gap-fill 내부 dedupe 가 09:01 cron 과의 중복을 흡수한다.
     */
    @PostConstruct
    fun onStartup() {
        if (!enabled) return
        val nowKst = LocalTime.now(kstZone)
        if (isInSession(nowKst)) {
            log.info("부팅 시 정규장 세션 감지 ({}KST) — ACTIVE 전략 종목 인트라데이 캔들 catch-up warmup", nowKst)
            warmupActiveStrategies("boot-catchup")
        } else {
            log.debug("부팅 시 정규장 세션 밖 ({}KST) — 인트라데이 warmup 생략 (09:01 cron 대기)", nowKst)
        }
    }

    /**
     * 09:01 KST cron — 개장 직후 ACTIVE 전략 종목 인트라데이 캔들 backfill.
     *
     * 09:00 개장 직후 1분(09:00 봉 1개)을 KIS 가 노출할 여유를 주고자 09:01 에 발사한다.
     * 이후 09:00~09:01 + 진행분은 WS tick(TickToMinuteAggregator)이 실시간 마감하고,
     * 본 warmup 은 "개장 직후 첫 zone 형성을 위한 과거 시리즈 토대"를 만든다.
     */
    @Scheduled(cron = "\${magicjar.realtime-candle.intraday-warmup.cron:0 1 9 * * MON-FRI}", zone = "Asia/Seoul")
    fun onMarketOpen() {
        if (!enabled) return
        log.info("정규장 개장 직후 — ACTIVE 전략 종목 인트라데이 캔들 warmup 트리거")
        warmupActiveStrategies("market-open")
    }

    private fun warmupActiveStrategies(reason: String) {
        val codes =
            runCatching { activeStrategyCodeProvider.activeCodes() }
                .onFailure { e -> log.warn("ACTIVE 전략 종목 조회 실패 — warmup 생략: err={}", e.message) }
                .getOrDefault(emptySet())
        if (codes.isEmpty()) {
            log.info("ACTIVE 전략 종목 없음 — 인트라데이 warmup 대상 0 (reason={})", reason)
            return
        }
        var submitted = 0
        codes.forEach { code ->
            runCatching {
                // 이미 검증된 on-demand 경로 재사용 — 09:00~now 1m range fetch + 3m/5m 합산 재생성.
                // 내부 60초 dedupe·in-flight 가드 + BACKFILL 버킷 throttle 로 매매 경로 침범 0.
                minuteGapFillService.submitIfNeeded(code = code, reason = "intraday-warmup-$reason")
                submitted++
            }.onFailure { e ->
                log.warn("인트라데이 warmup gap-fill 큐잉 실패: code={} err={}", code, e.message)
            }
        }
        log.info(
            "ACTIVE 전략 종목 인트라데이 캔들 warmup 큐잉 완료: codes={} submitted={} reason={} (BACKFILL 버킷·매매 경로 침범 0)",
            codes.size,
            submitted,
            reason,
        )
    }

    private fun isInSession(now: LocalTime): Boolean = !now.isBefore(sessionOpen) && now.isBefore(sessionClose)
}
