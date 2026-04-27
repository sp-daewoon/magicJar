package com.mj.api.controller

import com.mj.api.application.journal.JournalService
import com.mj.domain.common.Mode
import com.mj.domain.journal.ChartSnapshot
import com.mj.domain.journal.SnapshotSource
import com.mj.domain.journal.TradeJournalEntry
import com.mj.domain.journal.port.TradeJournalFilter
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

/**
 * 매매일지 + 차트 스크린샷 엔드포인트.
 *
 * GET  /api/journal                         — 페이징 조회 (월·전략별 필터)
 * GET  /api/journal/{tradeId}               — 단건 상세
 * POST /api/journal/{tradeId}/snapshots     — 차트 캡처 PNG 업로드
 * GET  /api/journal/{tradeId}/snapshots     — 스냅샷 목록
 */
@RestController
@RequestMapping("/api/journal")
class JournalController(
    private val journalService: JournalService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) strategyId: String?,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) mode: String?,
        @RequestParam(defaultValue = "100") limit: Int,
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

    @GetMapping("/{tradeId}")
    fun get(
        @PathVariable tradeId: UUID,
    ): ResponseEntity<TradeJournalEntry> {
        val entry =
            journalService.get(tradeId)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(entry)
    }

    @PostMapping("/{tradeId}/snapshots")
    fun uploadSnapshot(
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

    @GetMapping("/{tradeId}/snapshots")
    fun listSnapshots(
        @PathVariable tradeId: UUID,
    ): ResponseEntity<List<ChartSnapshot>> = ResponseEntity.ok(journalService.listSnapshots(tradeId))
}

data class SnapshotUploadRequest(
    val imageBase64: String,
    val contentType: String? = "image/png",
    val width: Int? = null,
    val height: Int? = null,
)
