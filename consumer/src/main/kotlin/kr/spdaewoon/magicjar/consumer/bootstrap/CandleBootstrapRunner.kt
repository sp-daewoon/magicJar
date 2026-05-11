package kr.spdaewoon.magicjar.consumer.bootstrap

import kr.spdaewoon.magicjar.consumer.ingestion.ColdStartExecutor
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * 부팅 시 candle 공백·부족 종목 KIS cold-start 자동 보충 (2026-05-11 — KIS-only 정책).
 *
 * 목적:
 *   - Yahoo 삭제 또는 신규 배포 후 DB에 1d candle이 없는 watchlist 종목 자동 감지
 *   - 1m candle이 부족한 종목(MIN_1M_CANDLE_THRESHOLD 미만) cold-start 트리거
 *   - ColdStartExecutor.submit(code)로 비동기 KIS backfill 트리거
 *
 * 실행 순서: PositionBootstrapRunner 이후 실행 (@Order(10)).
 * 빠른 부팅을 위해 submit만 호출 (비동기 — cold-start 완료를 기다리지 않음).
 *
 * 판단 기준:
 *   - 1d candle count == 0 → cold-start 트리거 (전체 interval 재수집)
 *   - 1m candle count < MIN_1M_CANDLE_THRESHOLD (=350) → cold-start 트리거
 *     (정규장 1일 ≈ 390건 기준, 350건 미만이면 당일 분봉 미완료 판단)
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
@Order(10)
class CandleBootstrapRunner(
    private val watchlistJpaRepository: ConsumerWatchlistJpaRepository,
    private val candleRepository: CandleRepository,
    private val coldStartExecutor: ColdStartExecutor,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(CandleBootstrapRunner::class.java)

    companion object {
        /**
         * 1m candle 최소 임계치.
         * 정규장 1일 ≈ 390건 기준, 350건 미만이면 당일 분봉 미수집 판단 → 재트리거.
         * (KIS FHKST03010230 페이지네이션 보강 후 전수 수집 보장 — 2026-05-11 dogfooding 결함 수정)
         */
        private const val MIN_1M_CANDLE_THRESHOLD = 350L
    }

    override fun run(args: ApplicationArguments) {
        log.info("CandleBootstrapRunner 시작 — 1d/1m candle 공백·부족 종목 감지")
        val watchlistEntries = watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc()
        if (watchlistEntries.isEmpty()) {
            log.info("CandleBootstrapRunner — watchlist 종목 없음, 건너뜀")
            return
        }

        var triggerCount = 0
        for (entry in watchlistEntries) {
            val code = entry.code
            val count1d = candleRepository.countByCodeAndInterval(code, CandleInterval.ONE_DAY)
            val count1m = candleRepository.countByCodeAndInterval(code, CandleInterval.ONE_MIN)

            val needsColdStart =
                when {
                    count1d == 0L -> {
                        log.info("CandleBootstrapRunner — 1d candle 없음, cold-start 트리거: code={}", code)
                        true
                    }
                    count1m < MIN_1M_CANDLE_THRESHOLD -> {
                        log.info(
                            "CandleBootstrapRunner — 1m candle 부족 (count={} < threshold={}), cold-start 트리거: code={}",
                            count1m,
                            MIN_1M_CANDLE_THRESHOLD,
                            code,
                        )
                        true
                    }
                    else -> {
                        log.debug(
                            "CandleBootstrapRunner — candle 충분: code={} 1d={} 1m={}",
                            code,
                            count1d,
                            count1m,
                        )
                        false
                    }
                }

            if (needsColdStart) {
                coldStartExecutor.submit(code)
                triggerCount++
                // Rate limit 보호: 종목 간 100ms 간격 (각 종목 ~4 call × 30일 × 50ms = ~6s 예상)
                Thread.sleep(100)
            }
        }
        log.info(
            "CandleBootstrapRunner 완료 — cold-start 트리거 {}건 (total watchlist={}건)",
            triggerCount,
            watchlistEntries.size,
        )
    }
}
