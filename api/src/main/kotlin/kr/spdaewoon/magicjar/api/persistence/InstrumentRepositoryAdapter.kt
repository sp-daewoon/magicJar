package kr.spdaewoon.magicjar.api.persistence

import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentSearchResult
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.instrument.port.InstrumentRepository
import kotlinx.datetime.toKotlinLocalDate
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * InstrumentRepository 도메인 포트 구현 (api 모듈).
 * PR-11b 신규: InstrumentJpaEntity ↔ Instrument 도메인 변환.
 */
@Component
class InstrumentRepositoryAdapter(
    private val instrumentJpaRepository: InstrumentJpaRepository,
) : InstrumentRepository {
    private val log = LoggerFactory.getLogger(InstrumentRepositoryAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findByCode(code: String): Instrument? =
        instrumentJpaRepository.findById(code).orElse(null)?.toDomain()

    @Transactional(readOnly = true)
    override fun findActiveByMarket(market: InstrumentMarket): List<Instrument> =
        instrumentJpaRepository.findByActiveTrueAndMarket(market.name).map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findByActiveTrue(): List<Instrument> =
        instrumentJpaRepository.findByActiveTrue().map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findActiveByCodeIn(codes: Collection<String>): List<Instrument> =
        instrumentJpaRepository.findByActiveTrueAndCodeIn(codes).map { it.toDomain() }

    @Transactional
    override fun markDelisted(
        code: String,
        delistedAt: LocalDate,
    ) {
        instrumentJpaRepository.markDelisted(code, delistedAt)
        log.info("[instrument] markDelisted: code={} delistedAt={}", code, delistedAt)
    }

    @Transactional(readOnly = true)
    override fun searchByPgTrgm(
        query: String,
        limit: Int,
        market: InstrumentMarket?,
    ): List<InstrumentSearchResult> {
        val pageable = PageRequest.of(0, limit)
        val entities = instrumentJpaRepository.searchByNameOrCode(query, market?.name, pageable)
        return entities.mapIndexed { idx, e ->
            // score: 앞쪽 결과일수록 높은 점수 (1.0 ~ 0.1 범위)
            val score = maxOf(1.0 - idx * 0.05, 0.1)
            InstrumentSearchResult(
                code = e.code,
                name = e.name,
                market = runCatching { InstrumentMarket.valueOf(e.market) }.getOrElse { InstrumentMarket.KOSPI },
                sector = e.sector,
                score = score,
            )
        }
    }

    @Transactional
    override fun upsert(instrument: Instrument): Instrument {
        val existing = instrumentJpaRepository.findById(instrument.code).orElse(null)
        val entity =
            if (existing != null) {
                // UPDATE: name·market·sector·nameNorm·theme·active 갱신
                InstrumentJpaEntity(
                    code = instrument.code,
                    isin = instrument.isin,
                    name = instrument.name,
                    nameEn = instrument.nameEn,
                    market = instrument.market.name,
                    type = instrument.type.name,
                    sector = instrument.sector,
                    nameNorm = normalizeNameForIndex(instrument.name),
                    listedAt = instrument.listedAt?.toJavaLocalDate(),
                    delistedAt = instrument.delistedAt?.toJavaLocalDate(),
                    active = instrument.active,
                    createdAt = existing.createdAt,
                    updatedAt = Instant.now(),
                )
            } else {
                InstrumentJpaEntity(
                    code = instrument.code,
                    isin = instrument.isin,
                    name = instrument.name,
                    nameEn = instrument.nameEn,
                    market = instrument.market.name,
                    type = instrument.type.name,
                    sector = instrument.sector,
                    nameNorm = normalizeNameForIndex(instrument.name),
                    listedAt = instrument.listedAt?.toJavaLocalDate(),
                    delistedAt = instrument.delistedAt?.toJavaLocalDate(),
                    active = instrument.active,
                )
            }
        return instrumentJpaRepository.save(entity).toDomain()
    }

    @Transactional
    override fun upsertAll(instruments: List<Instrument>): Int {
        if (instruments.isEmpty()) return 0
        var count = 0
        for (instrument in instruments) {
            upsert(instrument)
            count++
        }
        log.debug("[instrument] upsertAll: count={}", count)
        return count
    }

    // ── private helpers ─────────────────────────────────────────────────

    /** 종목명 정규화 — 공백·점 제거 + lowercase. design §6-5. */
    private fun normalizeNameForIndex(name: String): String =
        name.replace(Regex("[\\s.·()]"), "").lowercase()

    private fun InstrumentJpaEntity.toDomain(): Instrument =
        Instrument(
            code = code,
            isin = isin,
            name = name,
            nameEn = nameEn,
            market = runCatching { InstrumentMarket.valueOf(market) }.getOrElse { InstrumentMarket.KOSPI },
            type = runCatching { InstrumentType.valueOf(type) }.getOrElse { InstrumentType.STOCK },
            sector = sector,
            listedAt = listedAt?.toKotlinLocalDate(),
            delistedAt = delistedAt?.toKotlinLocalDate(),
            active = active,
        )

    private fun kotlinx.datetime.LocalDate.toJavaLocalDate(): LocalDate =
        LocalDate.of(year, monthNumber, dayOfMonth)
}
