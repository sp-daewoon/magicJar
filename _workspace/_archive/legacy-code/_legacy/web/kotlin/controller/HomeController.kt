package kr.spdaewoon.magicjar.web.controller

import kr.spdaewoon.magicjar.core.port.InstrumentCatalog
import kr.spdaewoon.magicjar.core.port.TradeJournalFilter
import kr.spdaewoon.magicjar.core.port.TradeJournalRepository
import kr.spdaewoon.magicjar.web.config.AiEnabledFlag
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 홈 대시보드. 3개 카드 위젯(시장 개요, 관심 종목, 최근 매매 3건)을 서버사이드 렌더링.
 * 실시간 부분(WS)은 별도 스크립트가 담당. 여기서는 초기 스냅샷만 제공.
 */
@Controller
class HomeController(
    private val instrumentCatalog: InstrumentCatalog,
    private val journalRepository: TradeJournalRepository,
    private val aiEnabledFlag: AiEnabledFlag,
) {
    @GetMapping("/")
    fun home(model: Model): String {
        model.addAttribute("title", "홈")
        model.addAttribute("aiEnabled", aiEnabledFlag.enabled)
        model.addAttribute("serverTimeKst", ZonedDateTime.now(ZoneId.of("Asia/Seoul")))
        model.addAttribute("watchlist", instrumentCatalog.findAll().take(5))
        model.addAttribute(
            "recentTrades",
            journalRepository.search(TradeJournalFilter(limit = 3)),
        )
        return "home"
    }
}
