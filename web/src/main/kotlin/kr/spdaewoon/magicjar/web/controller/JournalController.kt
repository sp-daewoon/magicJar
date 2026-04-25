package kr.spdaewoon.magicjar.web.controller

import kr.spdaewoon.magicjar.core.domain.Mode
import kr.spdaewoon.magicjar.core.domain.Side
import kr.spdaewoon.magicjar.core.port.TradeJournalFilter
import kr.spdaewoon.magicjar.core.port.TradeJournalRepository
import kr.spdaewoon.magicjar.web.config.AiEnabledFlag
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 매매일지(`/journal`, `/journal/{id}`, `/journal/reports/monthly`).
 * 사용자 핵심 요구이며 ADR 0007 에 근거한다.
 */
@Controller
class JournalController(
    private val journalRepository: TradeJournalRepository,
    private val aiEnabledFlag: AiEnabledFlag,
) {
    private val kstZone: ZoneId = ZoneId.of("Asia/Seoul")

    @GetMapping("/journal")
    fun list(
        @RequestParam(required = false) mode: String?,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) side: String?,
        @RequestParam(required = false) strategyId: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        model: Model,
    ): String {
        val filter =
            TradeJournalFilter(
                mode = mode?.ifBlank { null }?.let { runCatching { Mode.valueOf(it) }.getOrNull() },
                code = code?.ifBlank { null },
                side = side?.ifBlank { null }?.let { runCatching { Side.valueOf(it) }.getOrNull() },
                strategyId = strategyId?.ifBlank { null },
                tag = tag?.ifBlank { null },
                from = parseLocalDate(from)?.atStartOfDay(kstZone)?.toInstant(),
                to = parseLocalDate(to)?.plusDays(1)?.atStartOfDay(kstZone)?.toInstant(),
                limit = limit.coerceIn(1, 500),
                offset = offset.coerceAtLeast(0),
            )
        val entries = journalRepository.search(filter)
        model.addAttribute("title", "매매일지")
        model.addAttribute("aiEnabled", aiEnabledFlag.enabled)
        model.addAttribute("serverTimeKst", ZonedDateTime.now(kstZone))
        model.addAttribute("entries", entries)
        model.addAttribute("filter", filter)
        model.addAttribute("modes", Mode.values())
        model.addAttribute("sides", Side.values())
        return "journal/list"
    }

    @GetMapping("/journal/{id}")
    fun detail(
        @PathVariable id: String,
        model: Model,
    ): String {
        val uuid =
            runCatching { UUID.fromString(id) }.getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 id 형식")
            }
        val entry =
            journalRepository.find(uuid)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "매매일지 항목을 찾을 수 없습니다")
        model.addAttribute("title", "매매일지 상세 · ${entry.code}")
        model.addAttribute("aiEnabled", aiEnabledFlag.enabled)
        model.addAttribute("serverTimeKst", ZonedDateTime.now(kstZone))
        model.addAttribute("entry", entry)
        return "journal/detail"
    }

    @GetMapping("/journal/reports/monthly")
    fun monthlyReport(
        @RequestParam(required = false) ym: String?,
        model: Model,
    ): String {
        val target =
            runCatching { YearMonth.parse(ym ?: YearMonth.now(kstZone).toString()) }
                .getOrElse { YearMonth.now(kstZone) }
        val start = target.atDay(1).atStartOfDay(kstZone).toInstant()
        val end =
            target
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(kstZone)
                .toInstant()
        val entries =
            journalRepository.search(
                TradeJournalFilter(from = start, to = end, limit = 500),
            )

        val realized = entries.filter { it.exitAt != null }
        val wins = realized.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val losses = realized.size - wins
        val pnlSum = realized.mapNotNull { it.pnl }.fold(BigDecimal.ZERO, BigDecimal::add)
        val strategyDistribution = realized.groupingBy { it.strategyId }.eachCount()

        model.addAttribute("title", "매매일지 리포트 · $target")
        model.addAttribute("aiEnabled", aiEnabledFlag.enabled)
        model.addAttribute("serverTimeKst", ZonedDateTime.now(kstZone))
        model.addAttribute("target", target.toString())
        model.addAttribute("totalTrades", realized.size)
        model.addAttribute("wins", wins)
        model.addAttribute("losses", losses)
        model.addAttribute("pnl", pnlSum)
        model.addAttribute("strategies", strategyDistribution)
        return "journal/report"
    }

    private fun parseLocalDate(value: String?): LocalDate? =
        value?.ifBlank { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun Instant.toKstString(): String = this.atZone(kstZone).toLocalDateTime().toString()
}
