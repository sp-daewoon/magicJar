package kr.spdaewoon.magicjar.web.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.core.domain.BacktestRequest
import kr.spdaewoon.magicjar.core.domain.BacktestResult
import kr.spdaewoon.magicjar.web.backtest.BacktestService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/backtest")
@Tag(name = "Backtest", description = "전략 백테스트 실행 API (strategy 모듈 조립 전까지 501)")
class BacktestApi(
    private val backtestService: BacktestService,
) {
    @Operation(summary = "백테스트 실행")
    @PostMapping
    fun run(
        @RequestBody request: BacktestRequest,
    ): BacktestResult = backtestService.run(request)
}
