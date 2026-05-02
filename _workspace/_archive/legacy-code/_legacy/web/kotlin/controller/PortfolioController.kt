package kr.spdaewoon.magicjar.web.controller

import kr.spdaewoon.magicjar.web.config.AiEnabledFlag
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 포트폴리오 페이지. 포지션 조회는 core.port.BrokerGateway (market-data 구현) 가 담당하지만,
 * 현재는 해당 구현이 없으므로 빈 목록 + 안내 배너를 노출한다.
 */
@Controller
class PortfolioController(
    private val aiEnabledFlag: AiEnabledFlag,
) {
    @GetMapping("/portfolio")
    fun show(model: Model): String {
        model.addAttribute("title", "포트폴리오")
        model.addAttribute("aiEnabled", aiEnabledFlag.enabled)
        model.addAttribute("serverTimeKst", ZonedDateTime.now(ZoneId.of("Asia/Seoul")))
        model.addAttribute("positions", emptyList<Any>())
        return "portfolio"
    }
}
