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
 * 부팅 시 candle 공백 종목 KIS cold-start 자동 보충 (2026-05-11 — KIS-only 정책).
 *
 * 목적:
 *   - Yahoo 삭제 또는 신규 배포 후 DB에 1d candle이 없는 watchlist 종목 자동 감지
 *   - ColdStartExecutor.submit(code)로 비동기 KIS backfill 트리거
 *
 * 실행 순서: PositionBootstrapRunner 이후 실행 (@Order(10)).
 * 빠른 부팅을 위해 submit만 호출 (비동기 — cold-start 완료를 기다리지 않음).
 *
 * 판단 기준: 해당 종목의 1d candle count == 0 → cold-start 트리거.
 * (1m·1wk·1mo·1y가 있어도 1d가 없으면 트리거)
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

    override fun run(args: ApplicationArguments) {
        log.info("CandleBootstrapRunner 시작 — 1d candle 공백 종목 감지")
        val watchlistEntries = watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc()
        if (watchlistEntries.isEmpty()) {
            log.info("CandleBootstrapRunner — watchlist 종목 없음, 건너뜀")
            return
        }

        var triggerCount = 0
        for (entry in watchlistEntries) {
            val code = entry.code
            val count1d = candleRepository.countByCodeAndInterval(code, CandleInterval.ONE_DAY)
            if (count1d == 0L) {
                log.info("CandleBootstrapRunner — 1d candle 없음, cold-start 트리거: code={}", code)
                coldStartExecutor.submit(code)
                triggerCount++
                // Rate limit 보호: 종목 간 100ms 간격 (각 종목 30 calls × 50ms = 1.5s 예상)
                Thread.sleep(100)
            } else {
                log.debug("CandleBootstrapRunner — 1d candle 이미 존재: code={} count={}", code, count1d)
            }
        }
        log.info(
            "CandleBootstrapRunner 완료 — cold-start 트리거 {}건 (total watchlist={}건)",
            triggerCount,
            watchlistEntries.size,
        )
    }
}
