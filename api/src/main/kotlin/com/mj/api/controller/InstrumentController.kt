package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentMasterSyncApiService
import com.mj.api.application.instrument.InstrumentService
import com.mj.api.application.market.CandleCacheService
import com.mj.api.application.market.CandleQueryService
import com.mj.api.application.market.InstrumentDetailService
import com.mj.api.application.market.InvestorTrendService
import com.mj.api.application.market.OrderbookService
import com.mj.api.application.market.RecentTradesService
import com.mj.api.controller.dto.CandleResponse
import com.mj.api.controller.dto.InstrumentDetailResponse
import com.mj.api.controller.dto.InstrumentMasterSyncRequest
import com.mj.api.controller.dto.InstrumentMasterSyncResponse
import com.mj.api.controller.dto.InstrumentMasterSyncStatusResponse
import com.mj.api.controller.dto.InstrumentSearchResponse
import com.mj.api.controller.dto.InvestorTrendResponse
import com.mj.api.controller.dto.OrderbookResponse
import com.mj.api.controller.dto.RecentTradeResponse
import com.mj.api.controller.dto.toDetailResponse
import com.mj.api.controller.dto.toResponse
import com.mj.api.controller.dto.toSearchResponse
import com.mj.domain.market.instrument.Instrument
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

@Tag(
    name = "Instrument",
    description = """
        KRX 상장 종목 카탈로그.

        Flyway V0~V4가 시드한 16개 종목(주요 KOSPI 우량주 + 대표 KOSDAQ)을 노출한다.
        실제 운영 시 KIS OpenAPI 종목마스터로부터 일 1회 동기화하는 잡이 추가될 예정(Phase F).
    """,
)
@RestController
@RequestMapping("/api/instruments")
class InstrumentController(
    private val instrumentService: InstrumentService,
    private val candleQueryService: CandleQueryService,
    private val candleCacheService: CandleCacheService,
    private val masterSyncService: InstrumentMasterSyncApiService,
    private val instrumentDetailService: InstrumentDetailService,
    private val orderbookService: OrderbookService,
    private val recentTradesService: RecentTradesService,
    private val investorTrendService: InvestorTrendService,
) {
    private val kst = ZoneId.of("Asia/Seoul")

    @Operation(summary = "종목 전체 목록", description = "현재 등록된 모든 Instrument를 코드 오름차순으로 반환.")
    @GetMapping
    fun list(): ResponseEntity<List<Instrument>> = ResponseEntity.ok(instrumentService.list())

    @Operation(
        summary = "종목 검색",
        description = """
            종목 코드(prefix) 또는 종목명(substring) 으로 검색한다 (ADR 0037 §2-4).

            - 코드 검색: '005930', '005', '0059' 등 prefix 매칭
            - 이름 검색: '삼성', '전자', 'Samsung' 등 부분 일치
            - 빈 쿼리는 빈 배열 반환 (전체 목록은 GET /api/instruments 사용)
            - limit 기본값 20, 최대 100
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "검색 결과 (0건이어도 200)"),
            ApiResponse(responseCode = "400", description = "limit 파라미터 범위 초과 (>100)"),
        ],
    )
    @GetMapping("/search")
    fun search(
        @Parameter(description = "검색어 — 종목코드 prefix 또는 종목명 substring", example = "삼성")
        @RequestParam(defaultValue = "") q: String,
        @Parameter(description = "최대 결과 수 (기본 20, 최대 100)", example = "20")
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<List<InstrumentSearchResponse>> {
        if (limit > 100) return ResponseEntity.badRequest().build()
        val results = instrumentService.search(q.trim(), limit)
        return ResponseEntity.ok(results.map { it.toSearchResponse() })
    }

    @Operation(
        summary = "종목 단건 조회",
        description = "KRX 6자리 코드로 단건 조회. 코드는 자동으로 대문자 정규화된다(예: a005930 → 005930).",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "해당 코드의 종목 없음"),
        ],
    )
    @GetMapping("/{code}")
    fun get(
        @Parameter(description = "KRX 종목 코드 6자리", example = "005930")
        @PathVariable code: String,
    ): ResponseEntity<Instrument> {
        val instrument =
            instrumentService.get(code.uppercase())
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(instrument)
    }

    @Operation(
        summary = "종목 상세 메타 조회",
        description = """
            KIS inquire-stock-info + DART 재무제표를 병합한 종목 상세 정보를 반환한다 (T20-T5).

            - 시가총액·상장주식수·PER·PBR·EPS·BPS·배당률: KIS inquire-stock-info (CTPF1002R)
            - 매출액·영업이익·당기순이익: DART fnlttSinglAcntAll (연간 연결재무제표)
            - 외국인·기관 비율: 현재 미구현 (R2 예정)
            - Redis TTL 6시간 캐시. 캐시 hit 시 외부 API 호출 없음.

            **KIS/DART 키 미설정 시**: code·name만 채워진 200 응답 (각 필드 null).
            사용자 화면에서 null인 필드는 '—' 또는 빈 칸으로 처리.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (필드 일부 null 가능)"),
            ApiResponse(responseCode = "404", description = "해당 코드의 종목 없음"),
        ],
    )
    @GetMapping("/{code}/detail")
    fun detail(
        @Parameter(description = "KRX 종목 코드 6자리", example = "005930")
        @PathVariable code: String,
    ): ResponseEntity<InstrumentDetailResponse> {
        val normalized = code.uppercase()
        val instrument =
            instrumentService.get(normalized)
                ?: return ResponseEntity.notFound().build()
        val result = instrumentDetailService.getDetail(normalized, instrument.name)
        return ResponseEntity.ok(result.detail.toDetailResponse(warnings = result.warnings.takeIf { it.isNotEmpty() }))
    }

    @Operation(
        summary = "종목 캔들 조회",
        description = """
            LWC(Lightweight Charts) 호환 OHLCV 배열을 반환한다.

            **분봉 합성 정책 v2 (ADR 0048)**:
            - interval=D1/DAY → 일봉 DB 직접 조회
            - interval=M1/1m → 1분봉 DB 직접 조회
            - interval=M5/M15/M30/H1/5m/30m/60m/90m/120m 등 → M1 DB 조회 후 백엔드 합성
            - interval=W/MO/Y → D1 DB 조회 후 주봉/월봉/연봉 합성

            from·to 미지정 시 봉 단위별 기본 lookback 자동 적용.
            응답 time 필드: 일봉='YYYY-MM-DD'(KST), 분봉=epoch second(Long).

            **infinite scroll 모드 (T19-T4)**:
            - `before` 파라미터를 지정하면 해당 시각 이전 `limit`개 캔들을 반환 (차트 좌측 스크롤 시 사용).
            - `before`와 `from/to`는 상호 배타적. `before` 지정 시 `from/to` 무시.
            - `limit` 기본값 200, 최대 1000. 초과 시 400 반환.

            005930(삼성전자) 일봉은 Flyway V7로 90 영업일 시드, M1 분봉은 V8로 1 영업일 시드.
            실제 운영에서는 batch 모듈의 DailyCandleBackfillJob·MinuteCandleBackfillJob이 KIS API로 채운다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (빈 배열도 200)"),
            ApiResponse(responseCode = "400", description = "interval·from/to·limit 파라미터 오류"),
            ApiResponse(responseCode = "404", description = "해당 코드의 종목 없음"),
        ],
    )
    @GetMapping("/{code}/candles")
    fun candles(
        @Parameter(description = "KRX 종목 코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(
            description =
                "봉 단위. D1/DAY(일봉), M1/1m(1분봉), M5/5m(5분봉), M30/30m(30분봉), H1/60m(60분봉), 90m, 120m, W(주봉), MO(월봉), Y(연봉) 등. " +
                    "M5·M30·H1 등은 M1 DB 조회 후 백엔드 합성 응답.",
            example = "DAY",
        )
        @RequestParam(defaultValue = "DAY") interval: String,
        @Parameter(description = "조회 시작 시각 (ISO-8601, 미지정 시 봉 단위별 기본 lookback)", example = "2026-01-01T00:00:00Z")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "조회 종료 시각 (ISO-8601, 미지정 시 현재 시각)", example = "2026-04-30T23:59:59Z")
        @RequestParam(required = false) to: String?,
        @Parameter(description = "infinite scroll: 이 시각 이전 캔들 반환 (YYYY-MM-DD 또는 ISO-8601 또는 epoch second)", example = "2026-01-01")
        @RequestParam(required = false) before: String?,
        @Parameter(description = "infinite scroll: 최대 반환 건수 (기본 200, 최대 1000). 응답 헤더 X-Has-More=true 시 추가 페이지 존재.", example = "200")
        @RequestParam(defaultValue = "200") limit: Int,
    ): ResponseEntity<List<CandleResponse>> {
        val normalized = code.uppercase()
        instrumentService.get(normalized) ?: return ResponseEntity.notFound().build()

        // limit 검증
        if (limit <= 0 || limit > CandleQueryService.MAX_LIMIT) return ResponseEntity.badRequest().build()

        // interval 검증 — v2: D1·M1 또는 합성 N분봉 모두 허용
        val intervalIsValid = runCatching { validateInterval(interval) }.isSuccess
        if (!intervalIsValid) return ResponseEntity.badRequest().build()

        // before 모드: infinite scroll
        if (!before.isNullOrBlank()) {
            val beforeInstant = parseBeforeInstant(before) ?: return ResponseEntity.badRequest().build()
            val candles =
                runCatching {
                    candleQueryService.queryBeforeByString(normalized, interval, beforeInstant, limit)
                }.getOrElse { return ResponseEntity.badRequest().build() }
            // before 모드: 반환 봉 수가 limit와 같으면 이전 페이지 존재 가능 → hasMore=true
            val hasMore = candles.size >= limit
            return buildCandleResponse(candles, hasMore)
        }

        val fromInstant =
            if (from.isNullOrBlank()) {
                null
            } else {
                parseInstantOrNull(from) ?: return ResponseEntity.badRequest().build()
            }
        val toInstant =
            if (to.isNullOrBlank()) {
                null
            } else {
                parseInstantOrNull(to) ?: return ResponseEntity.badRequest().build()
            }

        // v2: queryByString이 D1/M1/합성 interval 모두 처리 (limit 적용 + hasMore 판단)
        val result =
            runCatching {
                candleQueryService.queryByString(normalized, interval, fromInstant, toInstant, limit)
            }.getOrElse { return ResponseEntity.badRequest().build() }
        return buildCandleResponse(result.candles, result.hasMore)
    }

    /**
     * 캔들 응답 빌드 — 공통 헤더 포함.
     *
     * 응답 헤더:
     * - `X-Has-More`: 다음 페이지 존재 여부 (true/false)
     * - `X-Total-Count`: 이번 응답에 포함된 봉 수
     */
    private fun buildCandleResponse(
        candles: List<com.mj.domain.common.Candle>,
        hasMore: Boolean,
    ): ResponseEntity<List<CandleResponse>> {
        val headers =
            HttpHeaders().apply {
                set("X-Has-More", hasMore.toString())
                set("X-Total-Count", candles.size.toString())
                set("Access-Control-Expose-Headers", "X-Has-More, X-Total-Count")
            }
        return ResponseEntity
            .ok()
            .headers(headers)
            .body(candles.map { it.toResponse() })
    }

    /**
     * interval 문자열 유효성 검사 (v2).
     * 허용: D1·DAY·M1·1m + M<n>·<n>m·H<n>·<n>h (N분봉) + W·MO·Y (일봉 합성).
     */
    private fun validateInterval(interval: String) {
        val upper = interval.trim().uppercase()
        val d1Aliases = setOf("D1", "DAY", "D", "1D")
        val m1Aliases = setOf("M1", "1M", "1MIN", "MIN1")
        val weeklyAliases = setOf("W", "WK", "MO", "MONTH", "Y", "YEAR")

        when {
            upper in d1Aliases -> return
            upper in m1Aliases -> return
            upper in weeklyAliases -> return
            else -> {
                val minutes =
                    com.mj.api.application.market.CandleAggregator
                        .parseToMinutesOrNull(interval)
                        ?: throw IllegalArgumentException("Unsupported interval: $interval")
                require(minutes > 0) { "interval must be positive: $interval" }
                require(minutes <= 1440) { "interval must be <= 1440 min: $interval" }
            }
        }
    }

    @Operation(
        summary = "종목 마스터파일 수동 수집 트리거",
        description = """
            KIS OpenAPI로부터 종목 마스터파일을 즉시 수집한다 (T18a-T4).

            - markets: 수집 대상 시장 목록. 미지정 시 KOSPI·KOSDAQ·KONEX 기본 대상.
            - 비동기 처리 — runId로 상태 폴링 (GET /api/instruments/master/sync/{runId}).
            - batch-app(port 8081)에 위임 실행. batch-app 미가용 시 BATCH_APP_UNAVAILABLE 상태 반환.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "수집 시작 (runId 반환)"),
        ],
    )
    @PostMapping("/master/sync", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun triggerMasterSync(
        @RequestBody(required = false) req: InstrumentMasterSyncRequest?,
    ): ResponseEntity<InstrumentMasterSyncResponse> {
        val result = masterSyncService.triggerSync(req?.markets)
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(InstrumentMasterSyncResponse(runId = result.runId, status = result.status, scheduledAt = result.scheduledAt.toString()))
    }

    @Operation(
        summary = "종목 마스터파일 수집 상태 조회",
        description = "triggerMasterSync에서 받은 runId로 수집 진행 상태를 조회한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "상태 조회 성공"),
            ApiResponse(responseCode = "404", description = "해당 runId 없음 또는 batch-app 미가용"),
        ],
    )
    @GetMapping("/master/sync/{runId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun masterSyncStatus(
        @Parameter(description = "triggerMasterSync 응답에서 받은 runId", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @PathVariable runId: String,
    ): ResponseEntity<InstrumentMasterSyncStatusResponse> {
        val status = masterSyncService.syncStatus(runId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            InstrumentMasterSyncStatusResponse(
                runId = status["runId"] as? String ?: runId,
                status = status["status"] as? String ?: "UNKNOWN",
                scheduledAt = status["scheduledAt"]?.toString(),
                completedAt = status["completedAt"]?.toString(),
                errorMessage = status["errorMessage"]?.toString(),
            ),
        )
    }

    // ── 호가창 ────────────────────────────────────────────────────────────────

    @Operation(
        summary = "호가창 조회",
        description = """
            KIS inquire-asking-price-exp-ccn (FHKST01010200) 기반 호가창.

            - 매도 10단 + 매수 10단 호가·잔량·잔량증감
            - 총 매도/매수 잔량 + 현재가 + 예상체결가
            - Redis 캐시 TTL: 5초 (실시간성 중요)
            - KIS 키 미설정 시 404 반환
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "해당 코드의 종목 없음 또는 KIS 키 미설정"),
        ],
    )
    @GetMapping("/{code}/orderbook")
    fun orderbook(
        @Parameter(description = "KRX 종목 코드 6자리", example = "005930")
        @PathVariable code: String,
    ): ResponseEntity<OrderbookResponse> {
        val normalized = code.uppercase()
        instrumentService.get(normalized) ?: return ResponseEntity.notFound().build()
        val result = orderbookService.getOrderbook(normalized) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result.toResponse())
    }

    // ── 실시간 체결 list ───────────────────────────────────────────────────────

    @Operation(
        summary = "최근 체결 리스트 조회",
        description = """
            KIS inquire-ccnl (FHKST01010300) 기반 최근 체결 리스트.

            - KIS 응답 최대 30건 (장 마감 후 당일 마지막 체결 기준)
            - 체결 시각은 HHmmss 형식 (예: "155959")
            - Redis 캐시 TTL: 10초
            - KIS 키 미설정 시 빈 배열 반환 (200)
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (빈 배열도 200)"),
            ApiResponse(responseCode = "400", description = "limit 파라미터 범위 초과"),
            ApiResponse(responseCode = "404", description = "해당 코드의 종목 없음"),
        ],
    )
    @GetMapping("/{code}/trades")
    fun trades(
        @Parameter(description = "KRX 종목 코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(description = "반환 건수 (기본 30, 최대 30)", example = "30")
        @RequestParam(defaultValue = "30") limit: Int,
    ): ResponseEntity<List<RecentTradeResponse>> {
        if (limit < 1 || limit > 30) return ResponseEntity.badRequest().build()
        val normalized = code.uppercase()
        instrumentService.get(normalized) ?: return ResponseEntity.notFound().build()
        val result = recentTradesService.getRecentTrades(normalized, limit)
        return ResponseEntity.ok(result.map { it.toResponse() })
    }

    // ── 외국인·기관 동향 ──────────────────────────────────────────────────────

    @Operation(
        summary = "외국인·기관·개인 동향 조회",
        description = """
            KIS inquire-investor (FHKST01010900) 기반 일자별 투자자 동향.

            - KIS 응답 최대 30 영업일 (최신 날짜부터 역순)
            - 개인·외국인·기관 순매수 수량·금액(백만원) + 매수/매도 수량 포함
            - Redis 캐시 TTL: 5분 (일별 데이터)
            - KIS 키 미설정 시 빈 배열 반환 (200)
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (빈 배열도 200)"),
            ApiResponse(responseCode = "400", description = "days 파라미터 범위 초과"),
            ApiResponse(responseCode = "404", description = "해당 코드의 종목 없음"),
        ],
    )
    @GetMapping("/{code}/investors")
    fun investors(
        @Parameter(description = "KRX 종목 코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(description = "조회 영업일 수 (기본 20, 최대 30)", example = "20")
        @RequestParam(defaultValue = "20") days: Int,
    ): ResponseEntity<List<InvestorTrendResponse>> {
        if (days < 1 || days > 30) return ResponseEntity.badRequest().build()
        val normalized = code.uppercase()
        instrumentService.get(normalized) ?: return ResponseEntity.notFound().build()
        val result = investorTrendService.getInvestorTrend(normalized, days)
        return ResponseEntity.ok(result.map { it.toResponse() })
    }

    private fun parseInstantOrNull(raw: String): Instant? =
        try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }

    /**
     * before 파라미터 파싱 — 3가지 형식 지원:
     * 1. YYYY-MM-DD → 해당 날짜 00:00:00 KST
     * 2. ISO-8601 Instant (e.g. 2026-01-01T00:00:00Z)
     * 3. epoch second (숫자)
     */
    private fun parseBeforeInstant(raw: String): Instant? {
        // epoch second
        raw.toLongOrNull()?.let { return Instant.ofEpochSecond(it) }
        // YYYY-MM-DD
        runCatching {
            LocalDate.parse(raw).atStartOfDay(kst).toInstant()
        }.getOrNull()?.let { return it }
        // ISO-8601
        return parseInstantOrNull(raw)
    }
}
