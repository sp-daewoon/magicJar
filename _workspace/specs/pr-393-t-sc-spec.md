# PR-393 spec — T-SC Screener spec/plan (ADR 0071 — W3 다섯 번째 트랙)

> **권위 ADR**: [`_workspace/arch/adr/0071-t-sc-screener-spec.md`](../arch/adr/0071-t-sc-screener-spec.md)
> **사이클**: v3.6.3 patch (W3 마무리 — T-O·T-RECON·T-NOTIFY 마감 후 종목 발굴 자동화)
> **작성**: 2026-05-14 architect (사용자 위임 자율 최종권한 — 2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율")

---

## §1. T-SC 묶음 사유 (잠재 종목 발굴 단일 의제)

### 1-1. 사이클 위치

- v3.6.0 (2026-05-13 cut): **T-O 종착 노드 마감** — ADR 0068 A-1~H-1 채택, 5 PR(#371·#372·#373·#374·#375), 3중 게이트 자본 보호 LOCK
- v3.6.2 (2026-05-13 cut): **T-RECON + T-NOTIFY 묶음 마감** — ADR 0070 5 Phase 직렬, 자본 보호 prereq 완비
- **v3.6.3 (본 사이클)**: T-SC (Screener) — "전략으로 종목 찾기" UX 핵심 흐름 합류
- v3.6.4 (다음 사이클): T-J + T-PERF — 매매일지·일별 통계
- v3.7.0 minor cut: W3 완전 마감 직후 W4 (AI 옵션) 진입

### 1-2. 사용자 비전 정합 흐름 LOCK

```
[Strategy Builder /strategy 페이지]
   ↓ 사용자가 RSI<30 BUY 등 DSL 작성·저장
   ↓ "이 전략으로 종목 찾기" 버튼 클릭
[ScreenerResultModal mount + useScreenerEvaluate mutation]
   ↓ POST /api/screener/evaluate
[ScreenerService (api)]
   ↓ Redis 분산락 + 캐시 lookup
   ↓ MISS → ScreenerEngine.screen(query)
[ScreenerEngine (consumer)]
   ↓ InstrumentLookupPort.findActive(filter) — instrument 테이블 (T-IM1 baseline)
   ↓ universe ~3000 종목 × strategy-eval-pool (timeout 500ms · fixed 4)
   ↓ DSL evaluator (ADR 0047 sandbox 정합 — reuse from T-SIG)
   ↓ ScreenerRanker (multi-criteria 4 기준 fixed weights)
   ↓ skip 매트릭스 누적 (캔들 미수집 · 부족 · timeout · error)
[ScreenerResult 반환]
   ↓ Redis cache 30m put
   ↓ 옵션: env MAGICJAR_SCREENER_PERSIST=true → screening_run + screening_match INSERT
[REST body 즉시 응답 ~5초 내]
   ↓ ScreenerResultModal에 결과 + 랭킹 + skip 매트릭스 표시
[사용자 일괄 체크 + "Watchlist 등록" 클릭]
   ↓ POST /api/watchlist (단건 multi 호출 또는 bulk endpoint)
[Watchlist row INSERT + Kafka watchlist.changed.v1 publish]
[→ T-W2 WatchlistIngestionWorker 자동 시세 수집 시작]
```

흐름 단절 회복 — **Strategy Builder → ? → Watchlist** 사이의 핵심 합류점.

### 1-3. fitness 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression` 정합)

| 검증 | 결과 |
|---|---|
| 자동매매 본체에 필요? | ★★★ — "전략으로 종목 찾기" UX 핵심 + universe 자동 발굴 |
| v1/v2 잔재? | 0 — DSL 평가 엔진 reuse + universe 자동 평가 + 랭킹은 v3 신규 의제 |
| AI 호출? | 0 — 룰 기반 DSL 평가 + multi-criteria 수학 랭킹, sub-agent 산출물 grep 검증 의무 |
| KIS endpoint 호출? | **0 LOCK** — universe + candle 모두 영속 reuse (T-IM1 + T-W2 baseline) |
| mock 합성? | 0 — 영속 데이터 부재 시 universe size=0 또는 "캔들 미수집" 표시 |

---

## §2. Phase 분리 LOCK

### 2-1. 직렬 디스패치 매트릭스

**원칙**: Phase 1 산출물(domain port + consumer engine)이 Phase 2 REST 의존 + Phase 2 결과가 Phase 3 frontend 의존 — 직렬 LOCK.

| 순서 | Phase | sub-agent | 영역 | 의존 |
|---|---|---|---|---|
| 1 | **T-SC Phase 1** | **strategy-engine** | domain + consumer | ADR 0047 sandbox + T-DSL StrategyEvaluator + T-IM1 instrument 영속 |
| 2 | **T-SC Phase 2** | **backend-core** | api (REST + Redis cache + 옵션 Flyway V28) | Phase 1 머지 후 |
| 3 | **T-SC Phase 3** | **frontend** | Strategy Builder 합류 + ScreenerResultModal | Phase 2 머지 후 |

**병렬 가능 검토** (사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합): Phase 1·2 add-only 영역 분리 → 병렬 가능 검토했으나 backend-core REST는 domain port 의존(Phase 1 산출물) → 직렬 LOCK이 정합. Phase 3은 Phase 2 REST endpoint 명세 의존 → 직렬 LOCK.

**ObjectProvider graceful fallback** (헌법 §6 + ADR 0066·0070 패턴): Phase 2·3 미합류 시 Phase 1 단독 부팅 가능. Phase 1 consumer screening engine은 ScreenerEngine bean만 등록 (호출자 부재). 부팅 의무 정합.

### 2-2. T-SC Phase 1 산출물 매트릭스 (strategy-engine)

| 영역 | 파일 | 책임 |
|---|---|---|
| **domain** | `domain/.../screener/ScreenerQuery.kt` | 평가 요청 모델 (userId, strategyId, filters, limit) |
| **domain** | `domain/.../screener/ScreenerResult.kt` | 결과 + 랭킹 + skip 매트릭스 (universeSize·evaluatedCount·matchedCount·skippedCount·matches·skippedReasons·elapsedMs·cacheHit·cacheKey) |
| **domain** | `domain/.../screener/ScreenerMatch.kt` | 매칭 종목 + 랭킹 점수 + score components (instrumentCode·instrumentName·rank·score·scoreComponents·reason) |
| **domain** | `domain/.../screener/ScreenerFilter.kt` | universe 필터 (markets, types, active, marketCapMin, avgVolumeMin) |
| **domain** | `domain/.../screener/ScreenerStatus.kt` | enum (RUNNING·COMPLETED·ERROR) |
| **domain** | `domain/.../screener/SkipReason.kt` | sealed class (CandleMissing·CandleInsufficient(need:Int, have:Int)·EvalTimeout·EvalError(message:String)) |
| **domain** | `domain/.../screener/Universe.kt` | universe 표현 (instrument list + filter hash) |
| **domain port** | `domain/.../screener/port/ScreenerEngine.kt` | `fun screen(query: ScreenerQuery): ScreenerResult` (헌법 §6 abstract LOCK) |
| **domain port** | `domain/.../screener/port/InstrumentLookupPort.kt` | `fun findActive(filter: ScreenerFilter): List<Instrument>` (T-IM1 baseline reuse 또는 신규) |
| **domain port** | `domain/.../screener/port/ScreenerCachePort.kt` | get·put·delete + 분산락 SETNX·release |
| **domain port** | `domain/.../screener/port/ScreenerRankerPort.kt` | `fun rank(matches: List<ScreenerRawMatch>): List<ScreenerMatch>` (E-1 multi-criteria) |
| **consumer** | `consumer/.../screener/ScreenerEngineImpl.kt` | universe iterate + strategy-eval-pool 비동기 평가 + skip 매트릭스 누적 + ranker 호출 |
| **consumer** | `consumer/.../screener/ScreenerRankerImpl.kt` | E-1 multi-criteria 4 기준 (signal 0.40 + volume 0.30 + atr 0.20 + cap 0.10) — fixed weights |
| **consumer** | `consumer/.../screener/ScreenerStrategyEvalAdapter.kt` | T-DSL StrategyEvaluator + strategy-eval-pool reuse + timeout 500ms (universe screening 한정) |
| **test** | `consumer/src/test/.../ScreenerEngineImplTest.kt` | 5 케이스 (정상·universe empty·timeout 일부·error 일부·캔들 미수집 skip) |
| **test** | `consumer/src/test/.../ScreenerRankerImplTest.kt` | 4 케이스 (4 기준 가중치·동점 처리·empty matches·outlier penalty) |
| **test** | `consumer/src/test/.../ScreenerStrategyEvalAdapterTest.kt` | 3 케이스 (timeout 500ms · CPU budget · auto-disable 트리거 ADR 0047) |

**Phase 1 단위 테스트 ~12건 GREEN 의무** (ADR 0068·0070 패턴 — 트랙 자본 보호 정합 cover).

### 2-3. T-SC Phase 2 산출물 매트릭스 (backend-core)

| 영역 | 파일 | 책임 |
|---|---|---|
| **api/infrastructure/cache** | `api/.../infrastructure/cache/RedisScreenerCacheAdapter.kt` | ScreenerCachePort 구현 — Jackson serialize + TTL 30m + SETNX 분산락 |
| **api/infrastructure/instrument** | `api/.../infrastructure/instrument/InstrumentLookupAdapter.kt` | T-IM1 baseline reuse — InstrumentRepository 합류 (active=true + market·type 필터) |
| **api/persistence (옵션)** | `api/db/migration/V28__screening_run.sql` | screening_run + screening_match 테이블 IF NOT EXISTS (D-2 옵션) — 슬롯 예약 |
| **api/persistence (옵션)** | `api/.../persistence/screener/Screening{Run,Match}{JpaEntity,JpaRepository,Adapter}.kt` | env toggle ON 시 영속 어댑터 (헌법 §6 default fun 함정 회피 — JpaRepository interface 단독) |
| **api/service** | `api/.../service/screener/ScreenerService.kt` | 분산락 + 캐시 lookup + ScreenerEngine 호출 + 옵션 영속 |
| **api/controller** | `api/.../controller/screener/ScreenerController.kt` | 3 REST endpoint Swagger 4단 |
| **api/dto** | `api/.../dto/screener/ScreenerEvaluateRequest.kt` + `ScreenerResultDto.kt` + `ScreenerMatchDto.kt` + `ScreenerFilterDto.kt` + `ScreeningRunDto.kt` + `ScreeningRunDetailDto.kt` | request·response DTO Swagger @Schema 한국어 설명 + example 값 |
| **test** | `api/src/test/.../ScreenerServiceTest.kt` | 4 케이스 (캐시 HIT·MISS+inline eval·분산락 충돌·옵션 영속 ON) |
| **test** | `api/src/test/.../ScreenerControllerTest.kt` | 5 케이스 (POST evaluate 200·404 strategy 부재·409 lock·GET runs list·GET runs detail) |
| **test** | `api/src/test/.../RedisScreenerCacheAdapterTest.kt` | 3 케이스 (put·get HIT·TTL expire) |

**Phase 2 단위 테스트 ~12건 GREEN 의무**.

**옵션 영속 LOCK**: Flyway V28 SQL 파일은 Phase 2 머지 시 합류 (IF NOT EXISTS 강제 LOCK). JPA Entity·Adapter 코드는 toggle OFF default 부팅 — backend-core 자율 결정 (1차 cycle scope LOCK은 cache-only).

### 2-4. T-SC Phase 3 산출물 매트릭스 (frontend)

| 영역 | 파일 | 책임 |
|---|---|---|
| **pages** | `frontend/src/pages/StrategyBuilderPage.tsx` | "이 전략으로 종목 찾기" 버튼 추가 + 모달 mount state |
| **features/screener** | `frontend/src/features/screener/components/ScreenerResultModal.tsx` | 모달 + 결과 표시 + 닫기·일괄 등록 액션 |
| **features/screener** | `frontend/src/features/screener/components/ScreenerMatchTable.tsx` | 랭킹 table (rank·name·score·reason) — sortable column |
| **features/screener** | `frontend/src/features/screener/components/ScreenerScoreBreakdown.tsx` | 점수 구성 tooltip (signal/volume/atr/cap 4 bar — recharts 또는 단순 progress bar) |
| **features/screener** | `frontend/src/features/screener/components/ScreenerSkipReasonsBadge.tsx` | skip 매트릭스 badge (캔들 미수집 N건 + tooltip) |
| **features/screener** | `frontend/src/features/screener/hooks/useScreenerEvaluate.ts` | React Query mutation `POST /api/screener/evaluate` + cache 5분 invalidate |
| **features/screener** | `frontend/src/features/screener/hooks/useBulkAddWatchlist.ts` | 일괄 Watchlist 등록 — 기존 useAddWatchlist hook multi 호출 (또는 backend bulk endpoint 합류 시 단일 호출) |
| **features/screener** | `frontend/src/features/screener/api/screenerApi.ts` | axios endpoint + 타입 정의 (DTO 1:1 매핑) |
| **test** | `frontend/src/features/screener/__tests__/ScreenerResultModal.test.tsx` | 4 케이스 (loading state · matches render · skip badge · watchlist 일괄 등록) |
| **test** | `frontend/src/features/screener/__tests__/ScreenerMatchTable.test.tsx` | 3 케이스 (rank 정렬 · score 정렬 · empty list) |

**Phase 3 단위 테스트 ~7건 GREEN 의무**.

---

## §3. DB Flyway V28 매트릭스 (D-2 옵션 영속 사전 예약)

ADR 0071 §3 정밀화. Phase 2 LOCK은 cache-only 단독 — V28 SQL 파일은 슬롯 예약 + JPA 코드는 env toggle (backend-core 자율).

### 3-1. V28 — screening_run + screening_match (옵션 영속, default 비활성)

```sql
-- V28__screening_run.sql
-- T-SC Phase 2 옵션 영속 (ADR 0071 §3 D-2)
-- 기본 비활성(MAGICJAR_SCREENER_PERSIST=false) — env toggle 시 ON

CREATE TABLE IF NOT EXISTS screening_run (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    strategy_id BIGINT NOT NULL,
    universe_filter_hash VARCHAR(64) NOT NULL,
    dsl_hash VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    universe_size INT NOT NULL DEFAULT 0,
    evaluated_count INT NOT NULL DEFAULT 0,
    matched_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,        -- RUNNING · COMPLETED · ERROR
    last_error TEXT,
    trigger_source VARCHAR(16) NOT NULL -- API · CRON · MANUAL
);
CREATE INDEX IF NOT EXISTS ix_screening_run_user_started ON screening_run (user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_screening_run_strategy_started ON screening_run (strategy_id, started_at DESC);

CREATE TABLE IF NOT EXISTS screening_match (
    id BIGSERIAL PRIMARY KEY,
    screening_run_id BIGINT NOT NULL REFERENCES screening_run(id) ON DELETE CASCADE,
    instrument_code VARCHAR(16) NOT NULL,
    rank INT NOT NULL,
    score NUMERIC(10, 6) NOT NULL,
    score_components JSONB NOT NULL,  -- {"signal": 0.45, "volume": 0.20, "atr": 0.18, "marketCap": 0.08}
    reason TEXT
);
CREATE INDEX IF NOT EXISTS ix_screening_match_run_rank ON screening_match (screening_run_id, rank);
CREATE INDEX IF NOT EXISTS ix_screening_match_code ON screening_match (instrument_code);
```

**IF NOT EXISTS 강제 LOCK** (헌법 §6 + ADR 0068·0070 hotfix 회피 패턴).

**Phase 2 자율 결정 매트릭스**:

| 결정 | 권장 |
|---|---|
| V28 SQL 파일 본 사이클 합류 여부 | **합류 권장** — IF NOT EXISTS 강제로 toggle OFF 영향 0 + 미래 toggle ON 시 hotfix 회피 |
| JPA Entity·Adapter 코드 본 사이클 합류 여부 | **합류 권장** — env toggle 체크만 코드 + cache-only path 단독 부팅 가능 |
| GET endpoint 영속 OFF 시 응답 | **404 Not Found** 권장 (사용자 메모리 `feedback_no_mock_data` 정합 — "데이터 없음" 표시) |

---

## §4. Kafka 토픽 매트릭스 (신규 0 LOCK)

ADR 0071 §4 정합. 본 사이클 publish·listen 신규 토픽 0.

| 항목 | 채택 |
|---|---|
| 신규 publish | 0 LOCK |
| 신규 listen | 0 LOCK |
| 후속 backlog 합류 시 | `screener.completed.v1` (비동기 모드 — universe 5000+ 도달 시) |
| `instrument.updated.v1` listen | 본 사이클 0 — Redis universe 캐시 (`screener:universe:{filterHash}`) 무효는 13시각 cron sync에서 instrument 변경 시 합류 가능 (post-MVP backlog) |

---

## §5. Redis 키 매트릭스

ADR 0071 §5 정합. Phase 2 backend-core 책임.

| Redis 키 | TTL | 용도 | sub-agent |
|---|---|---|---|
| `screener:cache:{dslHash}:{universeFilterHash}` | 30m | screening 결과 캐시 — A-1 동기 inline 평가 idempotency. JSON serialize (Jackson) | backend-core (Phase 2) |
| `screener:universe:{filterHash}` | 24h | instrument 테이블 필터 캐시 — 13시각 cron sync 후 무효 | backend-core (Phase 2) |
| `screener:lock:{userId}:{strategyId}` | 1m | 동시 평가 방지 (분산락 SETNX). 정상 종료 시 즉시 해제 + TTL fallback | backend-core (Phase 2) |

**`dslHash` 생성**: SHA-256(strategy.dsl + strategy.params + strategy.indicatorVersions) — DSL 변경 시 캐시 자동 무효.
**`universeFilterHash` 생성**: SHA-256(filter.markets + filter.types + filter.marketCapMin + filter.avgVolumeMin).

**분산락 패턴**:
```kotlin
val locked = redis.opsForValue().setIfAbsent(
    "screener:lock:$userId:$strategyId", "1", Duration.ofSeconds(60)
)
if (!locked) {
    val cached = redis.get("screener:cache:${dslHash}:${filterHash}")
    return cached?.let { Jackson.deserialize<ScreenerResult>(it).copy(cacheHit = true) }
        ?: throw ConcurrentScreeningException("동시 평가 중 — 잠시 후 재시도")
}
try {
    val cached = redis.get(...)
    if (cached != null) return Jackson.deserialize(...).copy(cacheHit = true)
    val result = screenerEngine.screen(query)
    redis.set("screener:cache:${dslHash}:${filterHash}", Jackson.serialize(result), Duration.ofMinutes(30))
    return result
} finally {
    redis.delete("screener:lock:$userId:$strategyId")
}
```

---

## §6. REST endpoint 매트릭스 (3 endpoint Swagger 4단 LOCK)

ADR 0071 §6 정밀화. **Swagger 4단 LOCK** (사용자 메모리 `feedback_swagger_pattern` 정합).

### 6-1. POST /api/screener/evaluate — 동기 inline 평가

```kotlin
@Tag(name = "Screener", description = "종목 발굴 (universe × DSL → 후보 + 랭킹) — ADR 0071")
@RestController
@RequestMapping("/api/screener")
class ScreenerController(
    private val screenerService: ScreenerService,
) {

    @Operation(
        summary = "전략으로 종목 찾기 (inline 평가)",
        description = """
            등록된 Strategy DSL을 universe(KOSPI·KOSDAQ active instrument)에 적용해 매수 후보 + 랭킹 반환.
            30분 내 동일 DSL + 동일 필터 재호출 시 캐시 반환 (idempotency).
            universe size ~3000 × DSL eval 500ms timeout = 최대 inline ~25s.
            동시 호출은 1회 분산락 + 1회 캐시 fetch 또는 409 Conflict.
        """
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "screening 결과 + 랭킹"),
        ApiResponse(responseCode = "404", description = "strategyId 또는 userId 미존재"),
        ApiResponse(responseCode = "409", description = "동시 평가 중 (분산락 충돌 + 캐시 부재)"),
        ApiResponse(responseCode = "422", description = "DSL 평가 엔진 sandbox auto-disable"),
        ApiResponse(responseCode = "500", description = "내부 오류"),
    ])
    @PostMapping("/evaluate")
    fun evaluate(@Valid @RequestBody request: ScreenerEvaluateRequest): ResponseEntity<ScreenerResultDto> = ...
}
```

**Request DTO** (Swagger @Schema 4단 LOCK):

```kotlin
@Schema(description = "screening 평가 요청")
data class ScreenerEvaluateRequest(
    @Schema(description = "사용자 ID (multi-tenant 격리)", example = "12345678")
    @field:NotNull val userId: Long,

    @Schema(description = "Strategy ID (저장된 DSL 참조)", example = "42")
    @field:NotNull val strategyId: Long,

    @Schema(description = "universe 필터 (옵션 — 기본 KOSPI+KOSDAQ active STOCK+ETF)")
    val filters: ScreenerFilterDto? = null,

    @Schema(description = "랭킹 최대 결과 수", example = "50")
    @field:Min(1) @field:Max(500) val limit: Int = 50,
)

@Schema(description = "universe 필터")
data class ScreenerFilterDto(
    @Schema(description = "시장 리스트", example = """["KOSPI", "KOSDAQ"]""")
    val markets: List<String>? = null,

    @Schema(description = "종목 타입 리스트", example = """["STOCK", "ETF"]""")
    val types: List<String>? = null,

    @Schema(description = "시총 하한 (원, 옵션)", example = "100000000000")
    val marketCapMin: Long? = null,

    @Schema(description = "5일 평균 거래대금 하한 (원, 옵션)", example = "1000000000")
    val avgVolumeMin: Long? = null,
)
```

**Response DTO**:

```kotlin
@Schema(description = "screening 결과 + 랭킹")
data class ScreenerResultDto(
    @Schema(description = "screening 캐시 키 (debug용)", example = "screener:cache:abc123:xyz789")
    val cacheKey: String,
    @Schema(description = "universe 사이즈", example = "2847") val universeSize: Int,
    @Schema(description = "평가 완료 수", example = "2814") val evaluatedCount: Int,
    @Schema(description = "매칭 결과 수 (BUY 시그널 종목)", example = "23") val matchedCount: Int,
    @Schema(description = "skip 사유별 카운트", example = """{"캔들 미수집": 12, "캔들 부족": 21}""")
    val skippedReasons: Map<String, Int>,
    @Schema(description = "랭킹된 매칭 결과 (limit 적용)") val matches: List<ScreenerMatchDto>,
    @Schema(description = "평가 elapsed (ms)", example = "4521") val elapsedMs: Long,
    @Schema(description = "캐시 HIT 여부", example = "false") val cacheHit: Boolean,
)

@Schema(description = "screening 매칭 종목")
data class ScreenerMatchDto(
    @Schema(description = "랭킹 (1-base)", example = "1") val rank: Int,
    @Schema(description = "종목 코드", example = "005930") val instrumentCode: String,
    @Schema(description = "종목명", example = "삼성전자") val instrumentName: String,
    @Schema(description = "랭킹 점수 (0~1 normalize)", example = "0.854") val score: BigDecimal,
    @Schema(description = "점수 구성", example = """{"signal":0.40,"volume":0.20,"atr":0.18,"marketCap":0.08}""")
    val scoreComponents: Map<String, BigDecimal>,
    @Schema(description = "BUY 시그널 reason (DSL evalResult.reason 정합)", example = "RSI(14) < 30 + MACD bullish cross")
    val reason: String,
)
```

### 6-2. GET /api/screener/runs (옵션 영속 ON 시)

```kotlin
@Operation(
    summary = "screening 실행 이력 조회 (옵션 영속 모드 한정)",
    description = "MAGICJAR_SCREENER_PERSIST=true 환경에서만 활성. screening_run 시계열 (페이징, 최신순)."
)
@ApiResponses(value = [
    ApiResponse(responseCode = "200", description = "screening_run 페이징"),
    ApiResponse(responseCode = "404", description = "영속 모드 OFF (default)"),
])
@GetMapping("/runs")
fun listRuns(
    @Parameter(description = "사용자 ID", example = "12345678") @RequestParam userId: Long,
    @Parameter(description = "페이지 (0-based)", example = "0") @RequestParam(defaultValue = "0") page: Int,
    @Parameter(description = "페이지당 row 수", example = "20") @RequestParam(defaultValue = "20") size: Int,
): Page<ScreeningRunDto> = ...
```

### 6-3. GET /api/screener/runs/{id} (옵션 영속 ON 시)

```kotlin
@Operation(
    summary = "screening 단건 상세 (옵션 영속 모드 한정)",
    description = "screening_run + screening_match 배열 상세 (랭킹 50건 default + skip 매트릭스)."
)
@GetMapping("/runs/{id}")
fun findDetail(
    @Parameter(description = "screening 실행 ID", example = "1001") @PathVariable id: Long,
    @Parameter(description = "사용자 ID (격리 검증)", example = "12345678") @RequestParam userId: Long,
): ScreeningRunDetailDto = ...
```

**userId mismatch 시 404 LOCK** (ADR 0010 multi-tenant 격리 정합).

---

## §7. STOMP 토픽 매트릭스 (신규 0 LOCK)

ADR 0071 §7 정합. 본 사이클 STOMP fanout 0. A-1 동기 inline 평가 채택 — REST body로 즉시 반환 + 캐시.

**후속 backlog 합류 시 (post-MVP)**:
- `/topic/screener.user.{userId}` 신규 — 비동기 모드 + 결과 push (universe 5000+ 도달 시)
- `/topic/screener.user.{userId}` event types — `screener.run.started` · `screener.run.completed` · `screener.run.failed`

---

## §8. UI 변경 매트릭스 (Phase 3 frontend)

ADR 0071 §8 정합. Strategy Builder 페이지 합류 + 모달 신규.

### 8-1. Strategy Builder 페이지 수정

```typescript
// frontend/src/pages/StrategyBuilderPage.tsx (수정)
import { ScreenerResultModal } from '@/features/screener/components/ScreenerResultModal';

export function StrategyBuilderPage() {
  const [screenerOpen, setScreenerOpen] = useState(false);
  const { strategy } = useSelectedStrategy();
  const userId = useCurrentUserId();

  return (
    <div>
      <StrategyBuilderForm strategy={strategy} />
      <div className="flex gap-2 mt-4">
        <Button onClick={handleSave}>전략 저장</Button>
        <Button
          variant="primary"
          disabled={!strategy?.id}
          onClick={() => setScreenerOpen(true)}
        >
          이 전략으로 종목 찾기
        </Button>
      </div>
      {screenerOpen && strategy?.id && (
        <ScreenerResultModal
          strategyId={strategy.id}
          userId={userId}
          onClose={() => setScreenerOpen(false)}
        />
      )}
    </div>
  );
}
```

### 8-2. ScreenerResultModal 구조

```typescript
// frontend/src/features/screener/components/ScreenerResultModal.tsx
export function ScreenerResultModal({ strategyId, userId, onClose }) {
  const { mutate, data, isPending, error } = useScreenerEvaluate();
  const [selectedCodes, setSelectedCodes] = useState<Set<string>>(new Set());
  const bulkAdd = useBulkAddWatchlist();

  useEffect(() => {
    mutate({ userId, strategyId, limit: 50 });
  }, [strategyId, userId]);

  return (
    <Modal isOpen onClose={onClose} size="lg">
      <ModalHeader>전략으로 종목 찾기</ModalHeader>
      <ModalBody>
        {isPending && <Spinner />}
        {error && <ErrorBadge error={error} />}
        {data && (
          <>
            <ScreenerSummary
              universeSize={data.universeSize}
              evaluatedCount={data.evaluatedCount}
              matchedCount={data.matchedCount}
              elapsedMs={data.elapsedMs}
              cacheHit={data.cacheHit}
            />
            <ScreenerSkipReasonsBadge reasons={data.skippedReasons} />
            <ScreenerMatchTable
              matches={data.matches}
              selectedCodes={selectedCodes}
              onSelect={setSelectedCodes}
            />
          </>
        )}
      </ModalBody>
      <ModalFooter>
        <Button onClick={onClose}>닫기</Button>
        <Button
          variant="primary"
          disabled={selectedCodes.size === 0 || bulkAdd.isPending}
          onClick={() => bulkAdd.mutate({ userId, codes: Array.from(selectedCodes) })}
        >
          Watchlist에 {selectedCodes.size}개 등록
        </Button>
      </ModalFooter>
    </Modal>
  );
}
```

---

## §9. AI 호출 0 검증 매트릭스 (개발 룰 5 + 사용자 메모리 정합)

| 검증 단계 | 의무 |
|---|---|
| Phase 1 PR 머지 전 | `grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" consumer/screener domain/screener` → 0 lines 의무 |
| Phase 2 PR 머지 전 | `grep -rn "...같은 패턴..." api/.../screener` → 0 lines 의무 |
| Phase 3 PR 머지 전 | `grep -rn "anthropic\|openai\|gpt" frontend/src/features/screener` → 0 lines 의무 |
| dogfooding cycle | sub-agent 산출물 행렬에 LLM provider 합류 패턴 0 확인 |

**예외 후속**: W4 진입 시 AiCommentProvider 합류 — ScreenerResultModal에 "이 결과 해석해줘" 버튼 추가 (post-MVP backlog §N). 본 사이클 LOCK은 AI 0.

---

## §10. universe source 매트릭스 (KIS-first raw 정합)

ADR 0071 §2-2 + §10 정합. 본 사이클 KIS endpoint 신규 호출 0 LOCK.

| source | T-SC 사용 | 책임 ADR |
|---|---|---|
| `instrument` 테이블 (PostgreSQL) | ✓ universe lookup 단일 출처 | ADR 0017 (T-IM1 baseline) |
| `candle` 테이블 (PostgreSQL) | ✓ 지표 lookback (DSL evaluator 입력) | ADR 0019 (T-W2 baseline) + ADR 0029 (당일 진행 중) |
| Redis quote cache | △ 옵션 — DSL이 현재가 의존 시 (RSI 등은 candle만) | ADR 0014 + ADR 0029 |
| KIS REST endpoint 신규 호출 | **✗ LOCK** | (본 사이클 발동 0) |

**필터 매트릭스 1차 LOCK**:

| 필터 | default | 사용자 토글 |
|---|---|---|
| `market IN ('KOSPI', 'KOSDAQ')` | ON | OFF 옵션 |
| `active = true AND delistedAt IS NULL` | ON LOCK | - |
| `type IN ('STOCK', 'ETF')` | ON | OFF 옵션 |
| `marketCap >= N` (옵션) | OFF | ON + threshold |
| `avgVolume5d * close >= M` (옵션) | OFF | ON + threshold |

---

## §11. 검증 시나리오 (E2E + dogfooding 의무)

ADR 0071 §10 정합. dogfooding cycle로 사용자 메모리 `feedback_verification_strict` 정합 (할루시네이션 금지 — 로그·DB·Redis 실증).

### 11-1. backend 검증 (Phase 1·2 머지 후)

| # | 시나리오 | 명령 | 기대 |
|---|---|---|---|
| 1 | universe 정상 + DSL 평가 | `curl -X POST localhost:8080/api/screener/evaluate -d '{"userId":1,"strategyId":1,"limit":50}'` | `200 OK` + matchedCount ≥ 1 + cacheHit=false + elapsedMs < 30000 |
| 2 | 캐시 HIT | 1번 반복 호출 | cacheHit=true + elapsedMs < 100 |
| 3 | 동시 호출 분산락 | 1번 동시 2회 호출 (xargs -P 2) | 1회 inline + 1회 cache fetch (cacheHit=true) 또는 1회 409 |
| 4 | candle 결손 graceful skip | universe 신규 상장 종목 (candle 0건) 포함 | `skippedReasons["캔들 미수집"] >= 1` |
| 5 | DSL 평가 timeout | strategy-eval-pool timeout 500ms 트리거 strategy | `skippedReasons["DSL 평가 timeout"] >= 1` + auto-disable 트리거 |
| 6 | 옵션 영속 ON | `MAGICJAR_SCREENER_PERSIST=true ./gradlew :api:bootRun` 후 1번 호출 + `SELECT * FROM screening_run` | row 1건 + status=COMPLETED + matched_count = response.matchedCount |
| 7 | userId 격리 | `userId=1` POST 후 `userId=2` GET /runs/{id} | `404 Not Found` |
| 8 | AI 호출 0 grep | §9 grep 명령 | 0 lines |

### 11-2. frontend 검증 (Phase 3 머지 후)

| # | 시나리오 | 절차 | 기대 |
|---|---|---|---|
| 9 | Strategy Builder 합류 UX | `/strategy` 페이지 → 전략 선택 → "이 전략으로 종목 찾기" 클릭 | ScreenerResultModal 표시 + 결과 + skip 매트릭스 badge |
| 10 | 일괄 Watchlist 등록 | 모달에서 5개 체크 → "Watchlist에 5개 등록" 클릭 | watchlist 5건 INSERT + WatchlistIngestionWorker 자동 시세 수집 시작 (T-W2 baseline) |
| 11 | 모달 닫기 + 재호출 캐시 | 닫기 → 다시 열기 | cacheHit=true (Redis 30m TTL) |

### 11-3. dogfooding 의무 (사용자 메모리 `feedback_verification_strict` 정합)

PR 머지·gradle check GREEN만으로 "완료" 종결 금지. 실증 evidence 의무:
- **로그**: `docker logs magicjar-api | grep "screener:cache"` HIT/MISS log
- **DB**: `SELECT count(*) FROM screening_run WHERE user_id=1` (영속 ON 시)
- **Redis**: `redis-cli KEYS "screener:cache:*"` 키 존재
- **STOMP**: 본 사이클 0 (검증 skip)
- **화면**: 브라우저 `/strategy` → 버튼 → 모달 결과 표시 스크린샷

---

## §12. 한계 + backlog 후보

ADR 0071 §11 정합. 본 사이클 1차 MVP scope 한계 — backlog 의제 7건 등록.

### 12-1. 본 사이클 1차 MVP scope 한계

| 한계 | 사유 | backlog 의제 (§N) |
|---|---|---|
| universe 5000+ 비동기 모드 | A-1 동기 inline 채택 | §52 — `screener.completed.v1` Kafka publish + STOMP fanout |
| `/screener` 단독 페이지 | H-1 Strategy Builder 합류 1차 | §53 — 단독 페이지 (전략 비선택 진입) |
| 사용자 custom 랭킹 가중치 | E-1 fixed weights 1차 | §54 — DSL meta 또는 env override |
| DailyScreenerJob cron (마스터 §3-1 [3]) | 1차는 inline only | §55 — 다음 영업일 09:00 cron + 후보 Slack push |
| AiCommentProvider 합류 | AI 호출 0 LOCK 본 사이클 | W4 진입 시 §56 |
| Watchlist bulk POST endpoint | 1차는 단건 multi 호출 | §57 — backend bulk endpoint 정합 |
| screening_match retention 정책 | 영속 ON 시 무한 누적 | §58 — 30일 cron 정리 |

### 12-2. 후속 ADR 후보

- **ADR 0072 (가칭)** — DailyScreenerJob cron 표준
- **ADR 0073 (가칭)** — 사용자 custom 랭킹 가중치 + DSL meta 합류

---

## §13. Rollback 시나리오

ADR 0071 §12 정합.

| 시나리오 | rollback 절차 |
|---|---|
| Phase 1 머지 후 DSL 평가 timeout 빈발 | strategy.status=DISABLED 자동 처리 (ADR 0047 baseline) + sub-agent feedback로 timeout 상향 검토 |
| Phase 2 머지 후 Redis 캐시 메모리 폭주 | TTL 30m → 10m 또는 LRU 정책 강화 |
| Phase 3 머지 후 Strategy Builder UX 회귀 | "이 전략으로 종목 찾기" 버튼 hidden flag (env `MAGICJAR_SCREENER_UI_ENABLED=false`) — backend는 정상 가동 |
| 옵션 영속 V28 머지 후 DDL 충돌 | IF NOT EXISTS 강제 LOCK으로 hotfix 회피 |
| universe instrument 부재 환경 | universe size=0 + "instrument 미수집" 표시 (mock 합성 금지) |

---

## §14. KIS-first raw 인용 매트릭스 (헌법 §3 검증)

**본 사이클 KIS endpoint 신규 호출 0 LOCK** — raw 인용 의무 발동 0.

| baseline ADR | cross-ref | 영역 |
|---|---|---|
| ADR 0017 (Instrument Master 1급 시민) | universe instrument 표 source | T-IM1 baseline reuse |
| ADR 0019 (Watchlist 캔들 정책) | candle 영속 베이스라인 | T-W2 baseline reuse |
| ADR 0037 (KIS Rate Limit) | 본 사이클 호출 0이므로 영향 0 | - |
| ADR 0047 (DSL Sandbox) | strategy-eval-pool reuse 단일 출처 | T-DSL baseline reuse |

**예외 발생 시 의무 발동 절차**: cold-start fetch 합류 결정 시(post-MVP backlog) — raw URL fetch + KDoc inline + 헌법 §3 5단계 절차 정독. 본 ADR 0071 cycle은 G-1 graceful skip 채택 → 회피.

---

## §15. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-14 | PR-393 spec 신규 발행 (ADR 0071 정밀화 매트릭스). 3 Phase 직렬 디스패치(strategy-engine domain+consumer → backend-core api+Redis+옵션 Flyway V28 → frontend Strategy Builder 합류+ScreenerResultModal). Phase 1 단위 테스트 ~12건 + Phase 2 ~12건 + Phase 3 ~7건 GREEN 의무. universe = instrument 영속 reuse + candle = 영속 reuse, KIS endpoint 호출 0 LOCK. DSL 평가 = strategy-eval-pool reuse + timeout 500ms (universe screening 한정). 결과 = Redis cache 30m + 옵션 영속 V28(toggle env). 랭킹 = 4 기준 multi-criteria fixed weights. REST 3 endpoint Swagger 4단. STOMP 0. Kafka 신규 0. 사용자 비전 정합 = Strategy Builder "이 전략으로 종목 찾기" 흐름 단절 회복. fitness = ★★★ + v1/v2 잔재 0 + AI 호출 0 + mock 합성 0. v3.6.3 patch 사이클. (architect) |
