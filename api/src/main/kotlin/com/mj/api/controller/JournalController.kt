package com.mj.api.controller

import com.mj.api.application.journal.JournalService
import com.mj.domain.common.Mode
import com.mj.domain.journal.ChartSnapshot
import com.mj.domain.journal.SnapshotSource
import com.mj.domain.journal.TradeJournalEntry
import com.mj.domain.journal.port.TradeJournalFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(
    name = "TradeJournal",
    description = """
        매매일지(Trading Journal) 조회 및 차트 스크린샷 관리.

        모든 매매 발생(백테스트·모의·실전) 시 진입/청산 근거·지표 스냅샷·AI 복기 설명·사용자 코멘트를
        영속화한다. 스냅샷 PNG는 MinIO(S3)에 저장하고 메타만 DB에 둔다.

        | 엔드포인트 | 용도 |
        |---|---|
        | `GET /api/journal` | 페이징 조회 (전략·종목·모드 필터) |
        | `GET /api/journal/{tradeId}` | 단건 상세 |
        | `POST /api/journal/{tradeId}/snapshots` | 차트 캡처 base64 PNG 업로드 |
        | `GET /api/journal/{tradeId}/snapshots` | 스냅샷 목록 |
    """,
)
@RestController
@RequestMapping("/api/journal")
class JournalController(
    private val journalService: JournalService,
) {
    @Operation(
        summary = "매매일지 페이징 조회",
        description = """
            매매일지 엔트리를 최신순으로 조회한다. `limit`은 1~500 범위로 클램핑된다.

            필터는 모두 옵션이며 AND 결합:
            - `strategyId`: 전략 식별자 정확 일치
            - `code`: 종목 코드 정확 일치
            - `mode`: `BACKTEST` | `PAPER` | `REAL` 중 하나 (대소문자 구분)
        """,
    )
    @GetMapping
    fun list(
        @Parameter(description = "전략 식별자 (옵션)", example = "sma-cross-5-20")
        @RequestParam(required = false) strategyId: String?,
        @Parameter(description = "KRX 종목 코드 (옵션)", example = "005930")
        @RequestParam(required = false) code: String?,
        @Parameter(description = "매매 모드 (옵션). BACKTEST/PAPER/REAL", example = "BACKTEST")
        @RequestParam(required = false) mode: String?,
        @Parameter(description = "페이지 크기 (1~500, 기본 100)", example = "100")
        @RequestParam(defaultValue = "100") limit: Int,
        @Parameter(description = "오프셋 (기본 0)", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<List<TradeJournalEntry>> {
        val filter =
            TradeJournalFilter(
                strategyId = strategyId,
                code = code,
                mode = mode?.let { runCatching { Mode.valueOf(it) }.getOrNull() },
                limit = limit.coerceIn(1, 500),
                offset = offset.coerceAtLeast(0),
            )
        return ResponseEntity.ok(journalService.list(filter))
    }

    @Operation(summary = "매매일지 단건 상세", description = "tradeId(UUID)로 매매일지 1건을 가져온다. 존재하지 않으면 404.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "해당 tradeId의 매매 기록 없음"),
        ],
    )
    @GetMapping("/{tradeId}")
    fun get(
        @Parameter(description = "매매 식별자 (UUID)", example = "0a3f7c64-2e9d-4b1c-a777-1234abcd5678")
        @PathVariable tradeId: UUID,
    ): ResponseEntity<TradeJournalEntry> {
        val entry =
            journalService.get(tradeId)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(entry)
    }

    @Operation(
        summary = "차트 스냅샷 업로드 (클라이언트 LWC 캡처)",
        description = """
            프론트엔드(LWC)에서 캡처한 PNG를 base64로 받아 MinIO `magicjar-screenshots` 버킷에 저장하고
            메타(URL·width/height·source) 1건을 `chart_snapshot` 테이블에 INSERT한다.

            서버 측 자동 캡처는 pyworker(Playwright)가 별도로 처리하며 source=`PYWORKER_HEADLESS`로 구분.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "업로드 성공 — ChartSnapshot 메타 반환"),
            ApiResponse(responseCode = "404", description = "해당 tradeId의 매매 기록 없음"),
            ApiResponse(responseCode = "413", description = "이미지 용량 초과 (서버 한도 초과 시)"),
        ],
    )
    @PostMapping("/{tradeId}/snapshots")
    fun uploadSnapshot(
        @Parameter(description = "스냅샷이 귀속될 매매 식별자")
        @PathVariable tradeId: UUID,
        @RequestBody req: SnapshotUploadRequest,
    ): ResponseEntity<ChartSnapshot> {
        val snapshot =
            journalService.saveSnapshot(
                tradeId = tradeId,
                imageBase64 = req.imageBase64,
                contentType = req.contentType ?: "image/png",
                width = req.width ?: 0,
                height = req.height ?: 0,
                source = SnapshotSource.CLIENT_LWC,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(snapshot)
    }

    @Operation(summary = "차트 스냅샷 목록", description = "특정 매매에 귀속된 스냅샷 메타 목록을 시간순으로 반환.")
    @GetMapping("/{tradeId}/snapshots")
    fun listSnapshots(
        @Parameter(description = "매매 식별자")
        @PathVariable tradeId: UUID,
    ): ResponseEntity<List<ChartSnapshot>> = ResponseEntity.ok(journalService.listSnapshots(tradeId))
}

@Schema(description = "차트 스냅샷 업로드 요청. PNG를 base64로 인코딩해 전송.")
data class SnapshotUploadRequest(
    @field:Schema(description = "base64 인코딩 PNG 본문 (data URI 프리픽스 없이)", required = true)
    val imageBase64: String,
    @field:Schema(description = "MIME 타입 (기본 image/png)", example = "image/png")
    val contentType: String? = "image/png",
    @field:Schema(description = "이미지 너비 (px)", example = "1280")
    val width: Int? = null,
    @field:Schema(description = "이미지 높이 (px)", example = "720")
    val height: Int? = null,
)
