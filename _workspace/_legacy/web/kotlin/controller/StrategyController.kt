package kr.spdaewoon.magicjar.web.controller

import kr.spdaewoon.magicjar.web.config.AiEnabledFlag
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 전략 목록 및 백테스트 폼. 엔진 호출은 REST `/api/backtest` 로 이뤄지며,
 * 실제 엔진 구현 전까지는 501 배너를 통해 안내한다.
 */
@Controller
class StrategyController(
    private val aiEnabledFlag: AiEnabledFlag,
) {
    @GetMapping("/strategies")
    fun list(model: Model): String {
        model.addAttribute("title", "전략")
        model.addAttribute("aiEnabled", aiEnabledFlag.enabled)
        model.addAttribute("serverTimeKst", ZonedDateTime.now(ZoneId.of("Asia/Seoul")))
        model.addAttribute(
            "strategies",
            listOf(
                StrategyRow("rsi-reversal-demo", "RSI 역추세 (데모)", "rsi14 < 30 매수 / rsi14 > 70 매도"),
                StrategyRow("ma-cross-demo", "이평선 골든크로스 (데모)", "sma5 > sma20 교차 시 매수"),
            ),
        )
        return "strategies/list"
    }

    @GetMapping("/strategies/{id}/backtest")
    fun backtestForm(
        @PathVariable id: String,
        model: Model,
    ): String {
        model.addAttribute("title", "백테스트 · $id")
        model.addAttribute("aiEnabled", aiEnabledFlag.enabled)
        model.addAttribute("serverTimeKst", ZonedDateTime.now(ZoneId.of("Asia/Seoul")))
        model.addAttribute("strategyId", id)
        return "strategies/backtest"
    }

    data class StrategyRow(
        val id: String,
        val name: String,
        val description: String,
    )
}
