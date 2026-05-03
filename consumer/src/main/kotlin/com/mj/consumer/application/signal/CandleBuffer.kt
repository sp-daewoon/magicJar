package com.mj.consumer.application.signal

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.event.TickEvent
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 종목별 1분봉 candle buffer (R1, 메모리 단순 구현).
 *
 * tick이 들어올 때마다 분 버킷에 누적하고, [snapshot]은 누적된 봉 시계열을 반환한다.
 * 마지막 봉(현재 분)은 진행 중이므로 OHLCV가 변동될 수 있다 — Signal Engine은 결정 시
 * "마지막 봉 = 현재 진행 봉" 으로 받아들인다 (실시간 평가).
 *
 * R2 개선 후보:
 *   - Redis 또는 DB로 영속화 → 컨테이너 재시작 후 평가 가능
 *   - 멀티 timeframe 동기 buffer (W/MO/Y 집계 포함)
 *   - LRU eviction (현재는 최대 보관 봉 수 cap만)
 *
 * 결정론:
 *   - 같은 tick 시퀀스 → 같은 candle 시계열.
 *   - 분 버킷 키는 `event.at.truncatedTo(MINUTES)` — 결정론적.
 *
 * 동시성:
 *   - ConcurrentHashMap + 종목별 lock (간단). 본격 멀티 listener thread에서도 안전.
 */
class CandleBuffer(
    /** 종목별 보관할 최대 봉 수 (FIFO eviction). default 500봉 = 약 8.3시간 1분봉. */
    private val maxBars: Int = 500,
) {
    private val log = LoggerFactory.getLogger(CandleBuffer::class.java)

    /** symbol → CandleSeries */
    private val series = ConcurrentHashMap<String, CandleSeries>()

    /**
     * tick 누적.
     *
     * @return 같은 분 내 누적 시 false, 새 분으로 넘어가 직전 봉이 닫혔으면 true.
     */
    fun ingest(tick: TickEvent): Boolean {
        val s = series.getOrPut(tick.code) { CandleSeries(tick.code, maxBars) }
        return s.ingest(tick)
    }

    /**
     * 종목의 누적 봉 시계열 snapshot (오름차순 시간).
     *
     * 빈 시계열 가능 (아직 tick 0건).
     */
    fun snapshot(symbol: String): List<Candle> = series[symbol]?.snapshot() ?: emptyList()

    /**
     * 누적된 종목 코드 목록.
     */
    fun trackedSymbols(): Set<String> = series.keys.toSet()

    /**
     * 종목 시계열 reset (테스트용).
     */
    fun clear(symbol: String) {
        series.remove(symbol)
    }

    fun clearAll() {
        series.clear()
    }
}

/**
 * 단일 종목의 캔들 시계열 + 진행 중 분 버킷.
 *
 * 동기화: 종목 단위 lock (`synchronized(this)`) — 평가 thread와 ingest thread가 충돌 X.
 */
internal class CandleSeries(
    private val code: String,
    private val maxBars: Int,
) {
    /** 닫힌 봉 (시간 오름차순). FIFO eviction. */
    private val closed: ArrayDeque<Candle> = ArrayDeque()

    /** 현재 진행 중인 분의 버킷. tick이 들어올 때 갱신. */
    private var current: Bucket? = null

    @Synchronized
    fun ingest(tick: TickEvent): Boolean {
        val minuteKey = tick.at.truncatedTo(ChronoUnit.MINUTES)
        val cur = current
        if (cur == null) {
            // 첫 tick — 새 봉 시작.
            current = Bucket.start(code, minuteKey, tick)
            return false
        }
        if (cur.openTime == minuteKey) {
            // 같은 분 → 누적.
            cur.add(tick.price, tick.volume)
            return false
        }
        if (minuteKey.isBefore(cur.openTime)) {
            // out-of-order tick — 옛 분으로 회귀. 단순 정책: 무시 (R2에서 보완).
            return false
        }
        // 새 분으로 넘어감 — 직전 봉을 닫는다.
        val finalized = cur.toCandle()
        closed.addLast(finalized)
        while (closed.size > maxBars) closed.removeFirst()
        current = Bucket.start(code, minuteKey, tick)
        return true
    }

    @Synchronized
    fun snapshot(): List<Candle> {
        val out = ArrayList<Candle>(closed.size + 1)
        out.addAll(closed)
        current?.let { out.add(it.toCandle()) } // 진행 중 봉도 포함 — 실시간 평가용.
        return out
    }
}

/**
 * 분 버킷 — OHLCV 누적용.
 */
internal class Bucket private constructor(
    val code: String,
    val openTime: Instant,
) {
    private var open: BigDecimal = BigDecimal.ZERO
    private var high: BigDecimal = BigDecimal.ZERO
    private var low: BigDecimal = BigDecimal(Long.MAX_VALUE)
    private var close: BigDecimal = BigDecimal.ZERO
    private var volume: Long = 0L
    private var tradeCount: Long = 0L

    fun add(
        price: BigDecimal,
        vol: Long,
    ) {
        if (tradeCount == 0L) open = price
        if (price > high) high = price
        if (price < low) low = price
        close = price
        volume += vol
        tradeCount++
    }

    fun toCandle(): Candle =
        Candle(
            code = code,
            interval = Interval.M1,
            openTime = openTime,
            open = open,
            high = high,
            low = if (low == BigDecimal(Long.MAX_VALUE)) close else low,
            close = close,
            volume = volume,
            tradeCount = tradeCount,
        )

    companion object {
        fun start(
            code: String,
            openTime: Instant,
            tick: TickEvent,
        ): Bucket =
            Bucket(code, openTime).apply {
                add(tick.price, tick.volume)
            }
    }
}
