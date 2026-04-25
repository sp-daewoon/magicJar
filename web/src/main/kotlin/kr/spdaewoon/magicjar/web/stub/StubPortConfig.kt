package kr.spdaewoon.magicjar.web.stub

import kr.spdaewoon.magicjar.core.domain.Candle
import kr.spdaewoon.magicjar.core.domain.Instrument
import kr.spdaewoon.magicjar.core.domain.Interval
import kr.spdaewoon.magicjar.core.domain.Market
import kr.spdaewoon.magicjar.core.domain.Mode
import kr.spdaewoon.magicjar.core.domain.NewsItem
import kr.spdaewoon.magicjar.core.domain.Side
import kr.spdaewoon.magicjar.core.domain.TradeJournalEntry
import kr.spdaewoon.magicjar.core.port.CandleRepository
import kr.spdaewoon.magicjar.core.port.InstrumentCatalog
import kr.spdaewoon.magicjar.core.port.NewsRepository
import kr.spdaewoon.magicjar.core.port.TradeJournalFilter
import kr.spdaewoon.magicjar.core.port.TradeJournalRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 다른 어댑터 모듈(market-data, strategy)이 실제 구현을 등록하지 않았을 때
 * web 페이지가 최소한의 UI 미리보기를 렌더링할 수 있도록 제공하는 스텁 빈 묶음.
 *
 * `@ConditionalOnMissingBean` 을 사용해 실제 구현이 있으면 자동으로 우회된다.
 * 따라서 "기본 프로파일에서는 실제 Repository 사용, 없을 때만 Stub" 원칙이 자동 충족된다.
 */
@Configuration
class StubPortConfig {
    @Bean
    @ConditionalOnMissingBean(InstrumentCatalog::class)
    fun stubInstrumentCatalog(): InstrumentCatalog = StubInstrumentCatalog()

    @Bean
    @ConditionalOnMissingBean(CandleRepository::class)
    fun stubCandleRepository(): CandleRepository = StubCandleRepository()

    @Bean
    @ConditionalOnMissingBean(NewsRepository::class)
    fun stubNewsRepository(): NewsRepository = StubNewsRepository()

    @Bean
    @ConditionalOnMissingBean(TradeJournalRepository::class)
    fun stubTradeJournalRepository(): TradeJournalRepository = StubTradeJournalRepository()
}

internal class StubInstrumentCatalog : InstrumentCatalog {
    private val fixtures =
        listOf(
            Instrument("005930", "삼성전자", Market.KOSPI),
            Instrument("000660", "SK하이닉스", Market.KOSPI),
            Instrument("035420", "NAVER", Market.KOSPI),
            Instrument("035720", "카카오", Market.KOSPI),
            Instrument("293490", "카카오게임즈", Market.KOSDAQ),
        )

    override fun findAll(): List<Instrument> = fixtures

    override fun findByCode(code: String): Instrument? = fixtures.firstOrNull { it.code == code }

    override fun upsert(instruments: List<Instrument>) {
        // no-op
    }
}

internal class StubCandleRepository : CandleRepository {
    override fun save(candles: List<Candle>) {
        // no-op
    }

    override fun find(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<Candle> {
        // 최근 30영업일 일봉 목업. 실제 시장 데이터가 아니므로 지표 계산 용도가 아닌 UI 프리뷰 전용.
        val base = BigDecimal("72000")
        return (0 until 30).map { i ->
            val t = to.minus((29 - i).toLong(), ChronoUnit.DAYS)
            val delta = BigDecimal(((i % 7) - 3) * 250)
            val open = base.add(delta)
            val close = open.add(BigDecimal(((i * 37) % 9) - 4).multiply(BigDecimal("100")))
            val high = open.max(close).add(BigDecimal("300"))
            val low = open.min(close).subtract(BigDecimal("300"))
            Candle(
                code = code,
                interval = interval,
                openTime = t,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 1_000_000L + i * 12_345L,
                tradeCount = 8_000L + i * 10L,
            )
        }
    }
}

internal class StubNewsRepository : NewsRepository {
    override fun save(items: List<NewsItem>) {
        // no-op
    }

    override fun search(
        code: String?,
        from: Instant,
        to: Instant,
    ): List<NewsItem> = emptyList()
}

internal class StubTradeJournalRepository : TradeJournalRepository {
    override fun save(entry: TradeJournalEntry) {
        // no-op
    }

    override fun find(id: UUID): TradeJournalEntry? = null

    override fun search(filter: TradeJournalFilter): List<TradeJournalEntry> {
        if (filter.offset > 0) return emptyList()
        val now = Instant.now()
        return listOf(
            TradeJournalEntry(
                id = UUID.randomUUID(),
                tradeId = UUID.randomUUID(),
                mode = Mode.BACKTEST,
                code = "005930",
                name = "삼성전자",
                side = Side.BUY,
                entryAt = now.minus(2, ChronoUnit.DAYS),
                entryPrice = BigDecimal("71500"),
                entryQty = 10,
                exitAt = now.minus(1, ChronoUnit.DAYS),
                exitPrice = BigDecimal("72800"),
                exitQty = 10,
                pnl = BigDecimal("13000"),
                pnlRate = BigDecimal("0.0182"),
                strategyId = "rsi-reversal-demo",
                entrySnapshot = mapOf("rsi14" to BigDecimal("27.5"), "close" to BigDecimal("71500")),
                exitSnapshot = mapOf("rsi14" to BigDecimal("52.0"), "close" to BigDecimal("72800")),
                entryReason = "rsi14 < 30 진입 (데모 스텁)",
                exitReason = "rsi14 > 50 청산 (데모 스텁)",
                aiExplanation = null,
                aiReview = null,
                tags = listOf("stub", "preview"),
                userNote = null,
                createdAt = now.minus(2, ChronoUnit.DAYS),
                updatedAt = now.minus(1, ChronoUnit.DAYS),
            ),
        ).take(filter.limit.coerceAtLeast(0))
    }
}
