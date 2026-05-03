package com.mj.api.controller

import com.mj.api.application.backtest.BacktestDetailQueryService
import com.mj.api.application.backtest.BacktestService
import com.mj.api.application.backtest.BacktestStatusResponse
import com.mj.api.application.backtest.BacktestTriggerRequest
import com.mj.api.application.backtest.BacktestTriggerResponse
import com.mj.api.application.backtest.EquityCurveResponse
import com.mj.api.application.backtest.TradesResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(
    name = "Backtest",
    description = """
        백테스트 잡 트리거 및 상태 조회.

        흐름: 클라이언트가 `POST /api/backtest`로 전략·종목·기간을 보내면 서버가 `runId`를 즉시 응답하고
        batch-app의 `DailyCandleBackfillJob → BacktestRunJob` 체인을 비동기 실행한다.
        클라이언트는 `GET /api/backtest/{runId}`를 폴링해 진행 상태와 최종 요약(JSON)을 받는다.

        결과는 `magicjar.backtest.result.v1` Kafka 토픽으로도 발행되며,
        프론트엔드는 STOMP 토픽 `/topic/backtest.result.<runId>`로 실시간 구독 가능하다.
    """,
)
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val backtestService: BacktestService,
    private val detailQueryService: BacktestDetailQueryService,
) {
    @Operation(
        summary = "백테스트 잡 트리거",
        description = """
            전략·종목·기간을 받아 백테스트 잡을 큐에 넣는다. 즉시 `runId`를 반환하며 실제 실행은 batch-app이 비동기 처리.

            - `strategyId`: 미리 등록된 전략 식별자 (예: `sma-cross-5-20`).
            - `symbol`: KRX 6자리 종목 코드 (예: 삼성전자 `005930`).
            - `from`/`to`: ISO date `yyyy-MM-dd`. `to`는 `from` 이후여야 한다(서버 검증).

            응답 `status`는 항상 `QUEUED`로 시작하며 폴링으로 `RUNNING → COMPLETED|FAILED` 추적.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "잡 큐 등록 성공 (runId 반환)"),
            ApiResponse(responseCode = "400", description = "유효성 검증 실패 (날짜 형식·필수값 등)"),
        ],
    )
    @PostMapping
    fun trigger(
        @Valid @RequestBody req: BacktestTriggerRequest,
    ): ResponseEntity<BacktestTriggerResponse> {
        val result = backtestService.trigger(req)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result)
    }

    @Operation(
        summary = "백테스트 실행 상태/결과 조회",
        description = """
            `runId`로 잡 진행 상태를 폴링한다. `summaryJson`은 COMPLETED 시점에 채워지며
            누적 수익률·승률·MDD·체결 횟수 등이 포함된다(전략별 스키마 상이).

            FAILED 시 `errorMessage`가 채워진다 (KIS 응답 오류·캔들 부족·DSL 검증 실패 등).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "해당 runId의 백테스트 기록 없음"),
        ],
    )
    @GetMapping("/{runId}")
    fun status(
        @Parameter(description = "trigger 응답에서 받은 runId (UUID 또는 클라이언트 부여 식별자)", example = "8f6c1c10-9d0e-4b2c-a5b6-1234567890ab")
        @PathVariable runId: String,
    ): ResponseEntity<BacktestStatusResponse> {
        val result =
            backtestService.getStatus(runId)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @Operation(
        summary = "백테스트 자산곡선 조회",
        description = """
            특정 백테스트 실행의 봉 단위 자산곡선(equity curve)을 시간 오름차순으로 반환한다.

            - 백테스트 미완료(QUEUED/RUNNING) 또는 trades 미저장 시 빈 리스트 응답.
            - mark-to-market: equity = cash + positionValue. positionValue = 보유 주식 수 × 봉 종가.
            - track9 R1 부터 영속화.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (빈 리스트 가능)"),
            ApiResponse(responseCode = "404", description = "해당 runId의 백테스트 기록 없음"),
        ],
    )
    @GetMapping("/{runId}/equity-curve")
    fun equityCurve(
        @Parameter(description = "백테스트 실행 ID", example = "8f6c1c10-9d0e-4b2c-a5b6-1234567890ab")
        @PathVariable runId: String,
    ): ResponseEntity<EquityCurveResponse> {
        val result =
            detailQueryService.getEquityCurve(runId)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @Operation(
        summary = "백테스트 거래 목록 조회",
        description = """
            특정 백테스트 실행의 라운드트립 거래(entry → exit 한 쌍) 목록을 sequence 오름차순으로 반환한다.

            - 비용(수수료·거래세·슬리피지) 차감 후 순손익(`pnl`)이 핵심 지표.
            - `pnlPercent`는 진입 명목금액 대비 비율 (예: 0.0234 = 2.34%).
            - `exitReason`: SIGNAL_EXIT(신호 종료) / STOP_LOSS / TAKE_PROFIT / END_OF_DATA.
            - track9 R1 부터 영속화.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (빈 리스트 가능)"),
            ApiResponse(responseCode = "404", description = "해당 runId의 백테스트 기록 없음"),
        ],
    )
    @GetMapping("/{runId}/trades")
    fun trades(
        @Parameter(description = "백테스트 실행 ID", example = "8f6c1c10-9d0e-4b2c-a5b6-1234567890ab")
        @PathVariable runId: String,
    ): ResponseEntity<TradesResponse> {
        val result =
            detailQueryService.getTrades(runId)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }
}
