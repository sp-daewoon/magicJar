package com.mj.api.controller

import com.mj.api.application.backtest.BacktestService
import com.mj.api.application.backtest.BacktestStatusResponse
import com.mj.api.application.backtest.BacktestTriggerRequest
import com.mj.api.application.backtest.BacktestTriggerResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 백테스트 트리거 및 상태 조회 엔드포인트.
 *
 * POST /api/backtest         — 백테스트 잡 트리거 → runId 응답
 * GET  /api/backtest/{runId} — 상태 + 결과 조회 (QUEUED/RUNNING/COMPLETED/FAILED)
 */
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val backtestService: BacktestService,
) {
    @PostMapping
    fun trigger(
        @Valid @RequestBody req: BacktestTriggerRequest,
    ): ResponseEntity<BacktestTriggerResponse> {
        val result = backtestService.trigger(req)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result)
    }

    @GetMapping("/{runId}")
    fun status(
        @PathVariable runId: String,
    ): ResponseEntity<BacktestStatusResponse> {
        val result =
            backtestService.getStatus(runId)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }
}
