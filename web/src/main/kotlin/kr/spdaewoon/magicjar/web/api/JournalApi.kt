package kr.spdaewoon.magicjar.web.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.core.domain.Mode
import kr.spdaewoon.magicjar.core.domain.Side
import kr.spdaewoon.magicjar.core.domain.TradeJournalEntry
import kr.spdaewoon.magicjar.core.port.TradeJournalFilter
import kr.spdaewoon.magicjar.core.port.TradeJournalRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/journal")
@Tag(name = "Journal", description = "매매일지 조회/검색 API (읽기 전용, ADR 0007)")
class JournalApi(
    private val journalRepository: TradeJournalRepository,
) {
    @Operation(summary = "매매일지 검색")
    @GetMapping
    fun search(
        @RequestParam(required = false) mode: Mode?,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) side: Side?,
        @RequestParam(required = false) strategyId: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): List<TradeJournalEntry> =
        journalRepository.search(
            TradeJournalFilter(
                mode = mode,
                code = code,
                side = side,
                strategyId = strategyId,
                tag = tag,
                from = from,
                to = to,
                limit = limit.coerceIn(1, 500),
                offset = offset.coerceAtLeast(0),
            ),
        )

    @Operation(summary = "매매일지 단건 조회")
    @GetMapping("/{id}")
    fun find(
        @PathVariable id: String,
    ): TradeJournalEntry {
        val uuid =
            runCatching { UUID.fromString(id) }.getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 id")
            }
        return journalRepository.find(uuid)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "매매일지 항목을 찾을 수 없습니다")
    }
}
