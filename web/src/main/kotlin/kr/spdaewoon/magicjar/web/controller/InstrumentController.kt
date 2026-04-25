package kr.spdaewoon.magicjar.web.controller

import kr.spdaewoon.magicjar.core.domain.Interval
import kr.spdaewoon.magicjar.core.port.CandleRepository
import kr.spdaewoon.magicjar.core.port.InstrumentCatalog
import kr.spdaewoon.magicjar.core.port.NewsRepository
import kr.spdaewoon.magicjar.web.config.AiEnabledFlag
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 종목 상세 페이지. 캔들차트·뉴스·감성 스파크라인 자리.
 *
 * 실시간 시세는 core.event 에 RealtimeQuoteEvent 가 추가될 때까지 3초 HTMX 폴링으로 대체.
 * (템플릿에서 hx-get hx-trigger="every 3s")
 */
@Controller
class InstrumentController(
    private val instrumentCatalog: InstrumentCatalog,
    private val candleRepository: CandleRepository,
    private val newsRepository: NewsRepository,
    private val aiEnabledFlag: AiEnabledFlag,
) {
    @GetMapping("/instruments/{code}")
    fun show(
        @PathVariable code: String,
        model: Model,
    ): String {
        val instrument =
            instrumentCatalog.findByCode(code)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다: $code")

        val to = Instant.now()
        val from = to.minus(60, ChronoUnit.DAYS)
        val candles = candleRepository.find(code, Interval.D1, from, to)
        val news = newsRepository.search(code, from, to).take(10)

        model.addAttribute("title", "${instrument.name} (${instrument.code})")
        model.addAttribute("aiEnabled", aiEnabledFlag.enabled)
        model.addAttribute("serverTimeKst", ZonedDateTime.now(ZoneId.of("Asia/Seoul")))
        model.addAttribute("instrument", instrument)
        model.addAttribute("candles", candles)
        model.addAttribute("news", news)
        return "instrument"
    }
}
