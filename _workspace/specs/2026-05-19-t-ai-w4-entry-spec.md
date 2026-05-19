# T-AI 트랙 진입 spec/plan — W4 AI 옵션 보조 본격 진입

> **발행일**: 2026-05-19 KST
> **발행자**: architect (opus 4.7 1M)
> **주체 사이클**: v3.7.0 minor LOCK (사용자 메모리 `feedback_release_policy_patch_first` — v3.7=W4 마감)
> **baseline ADR**: 0001(시스템 비전) · 0023(Signal Engine 룰 only) · **0026(AI 옵션 보조)** ★ · 0027(MCP Server 미래)
> **상태**: Proposed — 사용자 결정 의제 8건 위임 (architect 권장 풀 매트릭스 제시)
> **운영 코드 0 touch** (spec 사이클) — 본 PR은 사이클 분석 + ADR 발행 + spec 발행만
> **KIS endpoint 호출 0 LOCK** (T-AI 트랙 정의상 LLM 호출만 · KIS REST/WS 0)
> **AI 호출 0** — 본 spec 발행 단계는 실제 LLM 호출 0 (구현 Phase 1·2·3에서 실호출)
> **mock 합성 0** (`feedback_no_mock_data` 정합 — LLM 응답이 자연어 코멘트이므로 mock fallback은 `MockAnalysisAssistant`로 격리)

---

## §1. 컨텍스트 + baseline 매트릭스

### §1-A. 사용자 비전 정합 (ADR 0001)

> "AI = 사용자 트리거 한정 분석 코멘트. 매매 트리거 0."
>
> **시스템 = 룰 본체 + AI 보조** — 자동매매 의사결정은 100% 룰(시그널 엔진 ADR 0023). AI는 사용자가 명시적으로 클릭한 endpoint 응답에 한정.

본 사이클 W4 진입은 **W3 마감 evidence 7건**(T-O·T-RECON·T-NOTIFY·T-J·T-PERF·T-NEWS·T-ACCT) + **W3 보강 6 트랙 Accepted**(indicator-impl·T-MAN·T-CC Phase 1+2-A·페르소나 0085·0087) 후 진입. 사용자 메모리 `feedback_release_policy_patch_first` LOCK = v3.7=W4 마감.

### §1-B. baseline ADR 0026 7 § 정독 결과

| ADR 0026 § | 내용 | baseline 보존 | 본 spec 정정·확장 |
|------------|------|---------------|------------------|
| §2-1 AI provider 1차 | Anthropic 채택 + Gemini/OpenAI 옵션 | ✅ Anthropic LOCK | Spring AI 1.0.0-M3 ChatClient + spring-ai-anthropic-spring-boot-starter baseline LOCK (api 모듈 의존 이미 추가) |
| §2-2 호출 트리거 | 4 위치 (종목·차트·전략·시그널) + 매매 트리거 0 | ✅ LOCK | 카탈로그 5종 확장 (symbol·company·chart·indicator·journal — `_workspace/ai/prompt-catalog.md` baseline) |
| §2-3 Domain | `AiCommentRequest`·`AiCommentResponse`·`AiCommentProvider` | ✅ port 시그니처 baseline | sealed class `AnalysisRequest` + `AnalysisResult` 변경(prompt-catalog §입력·출력 스키마 정합) |
| §2-4 흐름 | Kafka `ai.comment.requested.v1` → consumer dispatcher → adapter → STOMP | 🟡 부분 | 본 spec 6 의제 = 의제 B 비동기 vs 동기 결정 |
| §2-5 비용 통제 | Redis cache 5분 + audit 테이블 `ai_comment_log` (V20) + 일일 한도 post-MVP | 🔴 정정 | **V20 충돌** — 현 baseline V41까지 발행 → V42 신규 `ai_analysis_log` LOCK |
| §2-6 실패 영향 | 본체 흐름 영향 0 + Circuit Breaker | ✅ LOCK | Resilience4j 30s open + 3회 retry 정합 |
| §2-7 prompt 표준 | `prompt_template` 테이블 (V20) post-W4 보강 | 🔴 정정 | post-MVP backlog (V20 충돌 + 본 사이클 비범위) |

### §1-C. baseline 코드 footprint (T-AI R1 잔존)

이미 발행된 baseline:
1. `api/src/main/kotlin/.../ai/dsl/NaturalLanguageDslTranslator.kt` — DSL 자연어 변환 helper (T-DSL · ai-engineer 영역) Spring AI ChatClient + mock fallback + safety regex baseline
2. `_workspace/ai/prompt-catalog.md` — 5 분석 카탈로그 (symbol·company·chart·indicator·journal) + 변수 치환 + 비용 통제 baseline
3. `application.yml` 73~76 — `magicjar.ai.{enabled, mock-mode, provider}` 3 prop
4. `build.gradle.kts` — `spring-ai-bom:1.0.0-M3` + api 모듈 `spring-ai-anthropic-spring-boot-starter` 의존

본 사이클 T-AI **본격 진입**은 baseline 위에 본격 분석 5 path 구현 (controller·service·adapter·prompt·persistence).

### §1-D. Spring AI 1.0.0-M3 ChatClient API raw 인용 (WebFetch 2026-05-19)

출처: https://docs.spring.io/spring-ai/reference/1.0/api/chatclient.html (1.0.0-M3 baseline 정합)

```java
// 표준 빌더 패턴 + chatResponse + 토큰 회수
ChatResponse chatResponse = chatClient.prompt()
    .user("Tell me a joke")
    .call()
    .chatResponse();

Usage usage = chatResponse.getMetadata().getUsage();
int inputTokens = usage.getPromptTokens();        // raw 인용 — 회수 정확치 가능
int outputTokens = usage.getGenerationTokens();
String modelName = chatResponse.getMetadata().getModel();
```

- ✅ 토큰 정확치 회수 가능 (prompt-catalog §비용 통제 "raw response metadata 파싱 post-MVP" 갱신 — 본 사이클부터 정확치 LOCK)
- ✅ 표준 빌더 패턴 (이미 baseline 적용 중)
- ✅ Anthropic provider qualified bean 매트릭스 LOCK

### §1-E. fitness gap 정합 (사용자 메모리 `feedback_v3_skepticism_no_regression`)

- ✅ "자동매매에 필요한가?" — AI는 사용자 명시적 분석 보조 + W4 비전 정합 (필수 아님 + 옵션)
- ✅ "v1/v2 잔재 아닌가?" — v3 baseline (Spring AI 1.0.0-M3) + 사용자 명시 비전 정합

---

## §2. T-AI 트랙 정의 (master §D.18 + tracks-roadmap T-AI baseline)

### §2-A. 5 분석 path (`_workspace/ai/prompt-catalog.md` baseline)

| 분석 타입 | endpoint | 입력 sealed | 출력 (AnalysisResult) | UI 위치 |
|-----------|----------|------------|---------------------|--------|
| SYMBOL_COMMENTARY | `POST /api/analysis/symbol/{code}/commentary` | `SymbolCommentary(code · contextSnapshot)` | text·confidence·model·promptVersion·tokensUsed·costEstimate·durationMs | `/symbol/:code` 종목 상세 "AI 종목 분석" 버튼 |
| COMPANY_ANALYSIS | `POST /api/analysis/symbol/{code}/company` | `CompanyAnalysis(code · financialsHint?)` | (동상) | `/symbol/:code` "기업 분석" 버튼 |
| CHART_INTERPRETATION | `POST /api/analysis/symbol/{code}/chart` | `ChartInterpretation(code · interval · candles · indicators)` | (동상) | `/symbol/:code` 차트 패널 "차트 해석" 버튼 |
| INDICATOR_MEANING | `POST /api/analysis/indicator/{id}/explain` | `IndicatorMeaning(indicatorId · params)` | (동상) | `/symbol/:code` 지표 패널 "지표 설명" 버튼 |
| JOURNAL_RETROSPECTIVE | `POST /api/analysis/journal/{tradeId}/retrospective` | `JournalRetrospective(tradeId · journalEntry)` | (동상) | `/journal` 페이지 "AI 복기" 버튼 |

### §2-B. Domain 모델 (domain 모듈)

```kotlin
package kr.spdaewoon.magicjar.domain.ai

// sealed class — 입력 강타입 + 컴파일 타임 안전성
sealed class AnalysisRequest {
    abstract val userId: Long
    abstract val requestId: UUID
    abstract val type: AnalysisType
    abstract val createdAt: Instant

    data class SymbolCommentary(
        override val userId: Long, override val requestId: UUID, override val createdAt: Instant,
        val code: String,                       // 6자리 종목코드
        val contextSnapshot: ContextSnapshot,   // last·change1d·change5d·patterns·recentNews
    ) : AnalysisRequest() { override val type = AnalysisType.SYMBOL_COMMENTARY }

    data class CompanyAnalysis(
        override val userId: Long, override val requestId: UUID, override val createdAt: Instant,
        val code: String, val financialsHint: FinancialsHint?,  // per·pbr·roe·marketCap
    ) : AnalysisRequest() { override val type = AnalysisType.COMPANY_ANALYSIS }

    data class ChartInterpretation(
        override val userId: Long, override val requestId: UUID, override val createdAt: Instant,
        val code: String, val interval: CandleInterval,
        val candles: List<Candle>,              // 최근 30개로 truncate
        val indicators: Map<String, IndicatorResult>,
    ) : AnalysisRequest() { override val type = AnalysisType.CHART_INTERPRETATION }

    data class IndicatorMeaning(
        override val userId: Long, override val requestId: UUID, override val createdAt: Instant,
        val indicatorId: String, val params: Map<String, String>,
    ) : AnalysisRequest() { override val type = AnalysisType.INDICATOR_MEANING }

    data class JournalRetrospective(
        override val userId: Long, override val requestId: UUID, override val createdAt: Instant,
        val tradeId: Long, val journalEntry: JournalEntry,
    ) : AnalysisRequest() { override val type = AnalysisType.JOURNAL_RETROSPECTIVE }
}

enum class AnalysisType { SYMBOL_COMMENTARY, COMPANY_ANALYSIS, CHART_INTERPRETATION, INDICATOR_MEANING, JOURNAL_RETROSPECTIVE }

data class AnalysisResult(
    val requestId: UUID, val type: AnalysisType,
    val text: String,                            // 자연어 코멘트 (한국어, 면책 포함)
    val confidence: ConfidenceLevel,             // LOW · MEDIUM · HIGH (응답 fallback or LLM self-report)
    val model: String,                           // "claude-3-5-sonnet-20241022" 등
    val promptVersion: String,                   // "v1"
    val tokensIn: Int, val tokensOut: Int,
    val costEstimateUsd: BigDecimal,             // model × tokens × rate
    val durationMs: Long,
    val cached: Boolean,                         // Redis cache hit 여부
    val completedAt: Instant,
)

// port (domain 순수)
interface AnalysisAssistant {
    fun analyze(request: AnalysisRequest): AnalysisResult
    val supportedTypes: Set<AnalysisType>
}
```

### §2-C. 모듈 경계 (ADR 0004 정합)

| 모듈 | 책임 | 신규 파일 매트릭스 |
|------|------|------------------|
| **domain** | `AnalysisRequest`·`AnalysisResult`·`AnalysisAssistant` port + `AnalysisType` enum + `ConfidenceLevel` enum + DTO 보조(`ContextSnapshot`·`FinancialsHint`·`JournalEntry`) — Spring 의존 0 | `domain/.../ai/AnalysisRequest.kt` · `AnalysisResult.kt` · `AnalysisAssistant.kt` (port) · `AnalysisType.kt` · `ConfidenceLevel.kt` · `ContextSnapshot.kt` · `FinancialsHint.kt` · `JournalEntry.kt` (~8 파일) |
| **api** | REST endpoint 5 + STOMP 1 + Service + Redis cache adapter + Spring AI ChatClient adapter + 비용 통제 logging + Resilience4j retry + audit_trail 5 path | `api/.../controller/AnalysisController.kt` · `api/.../service/AnalysisService.kt` · `api/.../service/AnalysisCacheAdapter.kt` · `api/.../service/AnthropicAnalysisAssistant.kt` (Spring AI ChatClient impl) · `api/.../service/MockAnalysisAssistant.kt` (mock fallback) · `api/.../service/AnalysisPromptLoader.kt` (template rendering) · `api/.../service/AnalysisAuditService.kt` · `api/.../persistence/AiAnalysisLogJpaEntity.kt`·`Repository.kt`·`Adapter.kt` · `api/.../config/AiCircuitBreakerConfig.kt` (Resilience4j) · `api/.../controller/AnalysisDto.kt` (request/response DTO 5건 Swagger 4단) · `api/src/main/resources/prompts/analysis/*.st` (6 프롬프트 — `_system_base.st` + 5 분석) (~14 파일) |
| **consumer** | **0 touch** (T-AI는 api 모듈 단독 — Kafka 비동기 의제 B-2 선택 시만 consumer 합류) | 0 (의제 B-1 채택 시) |
| **batch** | **0 touch** | 0 |
| **frontend** | `AiCard` 컴포넌트 + 5 트리거 버튼 + TanStack Query mutation + AI 활성 상태 표시 배지 + 면책 footer + 비용/토큰/모델 메타 표시 + 응답 markdown 렌더링 | `frontend/src/components/ai/AiCard.tsx` · `AiCommentTriggerButton.tsx` · `AiResponseRenderer.tsx` · `useAiAnalysis.ts` (5 hook) · `pages/SymbolPage.tsx` 격상 + `JournalPage.tsx` 격상 (~9 파일) |
| **indicator-impl**·**pattern-engine** | **0 touch** | 0 |

**자본 path 영향 0 LOCK** — chart-curator 페르소나 cross-review 불필요(자본 path 미접촉).

### §2-D. Flyway 신규 (V42)

```sql
-- V42__ai_analysis_log.sql
CREATE TABLE IF NOT EXISTS ai_analysis_log (
    id                BIGSERIAL    PRIMARY KEY,
    request_id        UUID         NOT NULL,
    user_id           BIGINT       NOT NULL,
    type              VARCHAR(32)  NOT NULL,       -- AnalysisType enum
    code              VARCHAR(20),                 -- nullable (INDICATOR_MEANING은 code 없음)
    provider          VARCHAR(32)  NOT NULL,       -- "anthropic" / "mock"
    model             VARCHAR(128) NOT NULL,
    prompt_version    VARCHAR(16)  NOT NULL DEFAULT 'v1',
    prompt_hash       VARCHAR(64)  NOT NULL,       -- Redis cache key SHA-256
    tokens_in         INTEGER      NOT NULL DEFAULT 0,
    tokens_out        INTEGER      NOT NULL DEFAULT 0,
    cost_estimate_usd NUMERIC(10,6) NOT NULL DEFAULT 0,
    duration_ms       INTEGER      NOT NULL DEFAULT 0,
    cached            BOOLEAN      NOT NULL DEFAULT false,
    status            VARCHAR(16)  NOT NULL,       -- SUCCESS · FAILED · TIMEOUT · DISABLED · MOCK
    confidence        VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    error_message     TEXT,
    response_text     TEXT,                        -- 자연어 응답 (감사·복기·post-MVP 학습 데이터)
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_analysis_log_user_created ON ai_analysis_log (user_id, created_at DESC);
CREATE INDEX idx_ai_analysis_log_type_created ON ai_analysis_log (type, created_at DESC);
CREATE INDEX idx_ai_analysis_log_request_id   ON ai_analysis_log (request_id);
```

- add-only LOCK (운영 baseline 변경 0)
- 멱등성 — `request_id` UNIQUE 안 함 (재시도 시 모든 시도 audit 의무)

### §2-E. Redis cache

| 키 | TTL | 값 | 용도 |
|----|-----|----|------|
| `ai:analysis:cache:{promptHash}` | 5분 | `AnalysisResult` (JSON) | 동일 입력 재호출 시 token 절약 |
| `ai:analysis:rate-limit:{userId}` | 1일 (ZSET sliding) | timestamp | 일일 사용자 토큰 한도(post-MVP backlog) |

### §2-F. STOMP 토픽

| 토픽 | 사용 시점 | payload |
|------|---------|---------|
| `/topic/ai.analysis.{requestId}` | 비동기 의제 B-2 채택 시만 (B-1 동기 호출 채택 시 STOMP 0 LOCK) | `AnalysisResult` JSON |

---

## §3. 사용자 결정 의제 매트릭스 (8건)

### 의제 A: 호출 동기성 (동기 vs 비동기)

- **A-1 동기 호출 (HTTP request-response)** ★ architect 권장
  - `POST /api/analysis/...` 요청 → 인스턴스 내부 ChatClient 호출 → 30s timeout → 직접 응답
  - 장점: 흐름 단순 + Kafka 토픽 신규 0 + consumer 모듈 0 touch + 사용자 대기 명확
  - 단점: api 모듈 thread block 30s · LLM 응답 30s 초과 시 frontend timeout 표시
- A-2 비동기 호출 (Kafka 사이클) — ADR 0026 §2-4 baseline 따름
  - `api → Kafka ai.analysis.requested.v1 → consumer AnalysisDispatcher → adapter → Kafka ai.analysis.responded.v1 → api STOMP`
  - 장점: thread block 0 + 사용자 화면 다른 작업 가능
  - 단점: 신규 Kafka 토픽 2 + STOMP 1 + consumer 신규 5 파일 + 영역 침범 확장 + 30s 이내 응답 시 over-engineering

**architect 권장 = A-1 동기 호출** — MVP fitness 정합 (`feedback_dev_priority_qa_batch` "단일 사이클 minor 정정에 매달리지 말 것") · 향후 비동기 진입 backlog (사용자 동시 다중 호출 본격화 시).

### 의제 B: Provider 매트릭스 (Anthropic 단독 vs 다중)

- **B-1 Anthropic Claude 단독** ★ architect 권장
  - ADR 0026 baseline 정합 + `application.yml MAGICJAR_AI_PROVIDER=anthropic` 단독 LOCK
  - 장점: 첫 사이클 dependency 최소 + Spring AI baseline 정합
- B-2 Anthropic + Gemini 2 provider
  - 장점: 무료 fallback (Gemini)
  - 단점: 2 SDK 의존 + qualifier 분기 + 토큰 회수 인터페이스 차이
- B-3 풀세트 (Anthropic + Gemini + OpenAI)
  - 단점: post-MVP 의제

**architect 권장 = B-1** — `feedback_release_policy_patch_first` 정합 (v3.7=W4 마감 LOCK) · 다중 provider는 v3.8 또는 post-MVP backlog.

### 의제 C: Mock fallback 정책

- **C-1 baseline 정합 LOCK** ★ architect 권장
  - `MAGICJAR_AI_ENABLED=false` default + 명시 활성 후 사용 + `MAGICJAR_AI_MOCK_MODE=auto` (키 없으면 mock)
  - `MockAnalysisAssistant`(api 모듈 거주) — 5 path 모두 "[MOCK] AI 응답 비활성. .env에서 MAGICJAR_AI_ENABLED=true + ANTHROPIC_API_KEY 설정 후 재기동하세요." 한국어 fallback
- C-2 mock 자동 비활성 + 키 없으면 503
  - 단점: 첫 dogfooding 사이클 사용자 혼란

**architect 권장 = C-1** — baseline 정합 + 첫 사용자 dogfooding 안전망 (`feedback_no_mock_data`는 데이터 합성 mock 금지 정합, 본 mock은 "AI 비활성 안내" graceful — 데이터 합성 0 LOCK).

### 의제 D: 비용 통제 (Redis cache TTL + 일일 한도)

- **D-1 Redis cache 5분 + 일일 한도 post-MVP** ★ architect 권장
  - ADR 0026 §2-5 baseline 정합 + `prompt_template` baseline 그대로
  - 일일 사용자 토큰 한도는 v3.7 후속 사이클 (사용자 dogfooding 비용 evidence 누적 후)
- D-2 Redis cache + 일일 한도 동시 진입 (V42 cost_estimate_usd ZSET sliding)
  - 단점: 사용자 dogfooding evidence 부재 시 임계치 임의 산정

**architect 권장 = D-1** — chart-curator 햇징 룰 §6 패턴 정합 ("임의 임계치 영구 차단") + v3.7 후속 patch.

### 의제 E: 프롬프트 버전 관리

- **E-1 SoT = `_workspace/ai/prompt-catalog.md` + `api/src/main/resources/prompts/analysis/*.st` 파일 단독** ★ architect 권장
  - prompt-catalog v1 + 6 `.st` 파일 (`_system_base.st` + 5 분석)
  - 변경 시 catalog 파일 변경 이력 row + version bump
  - `prompt_template` DB 테이블은 post-MVP backlog
- E-2 DB `prompt_template` 본 사이클 진입 (ADR 0026 §2-7 baseline)
  - 단점: V20 충돌 + 운영 부담 (사용자 dogfooding evidence 부재)

**architect 권장 = E-1** — 사용자 메모리 `feedback_dev_priority_qa_batch` 정합.

### 의제 F: T-NEWS 통합 (감성 분석 backlog §62)

- **F-1 backlog §62 잔류 (본 사이클 비범위)** ★ architect 권장
  - T-NEWS prompt input reuse (`news.collected.v1` listen + sentiment UPDATE) backlog §62
  - 본 spec의 SYMBOL_COMMENTARY는 `recentNews` 변수로 뉴스 단순 인용 OK (감성 분석 0)
- F-2 본 사이클 흡수
  - 단점: 본 사이클 scope 확장 + Kafka 토픽 listen 추가 + Phase 1 분기 추가

**architect 권장 = F-1** — `feedback_spec_plan_scope` "작은 묶음 변경" 정합.

### 의제 G: MCP server 합류 (T-MCP)

- **G-1 별도 트랙 (T-MCP, ADR 0027)** ★ architect 권장
  - T-AI 마감 후 T-MCP 진입 (ADR 0027 baseline) — magicJar MCP server expose
  - tools 5종: get_indicator · get_pattern · get_strategy · get_backtest · get_journal
- G-2 본 사이클 흡수
  - 단점: 본 spec scope 풀세트 확장 + 일정 ↑

**architect 권장 = G-1** — `tracks-roadmap §1` baseline (T-AI · T-MCP 별도 트랙 LOCK).

### 의제 H: Phase 분리

- **H-1 Phase 1 domain+api 백엔드 (~14 파일) + Phase 2 frontend (~9 파일) 직렬** ★ architect 권장
  - Phase 1: ai-engineer + backend-core 협업 단일 PR ~600 LOC (domain port 8 + api 14 = 22 파일)
  - Phase 2: frontend 단일 PR ~400 LOC (9 파일)
  - 직렬 LOCK — frontend는 backend endpoint 의존 (병렬 시 frontend mock 부담)
- H-2 Phase 1·2 병렬 (frontend mock으로 진행)
  - 단점: frontend mock 부담 + 머지 후 다시 정합 작업 (`feedback_no_mock_data` 미준수 risk)
- H-3 Phase 1 domain · Phase 2 api · Phase 3 frontend 3 PR 직렬
  - 단점: 1·2 분리 OEM 증가 (`feedback_dev_priority_qa_batch` 정합 X)

**architect 권장 = H-1** — `feedback_dev_priority_qa_batch` + 자본 path 영향 0 + 영역 분리 자연 정합.

---

## §4. Phase 분리 LOCK (의제 H-1 채택 시)

### Phase 1 — backend (ai-engineer + backend-core 협업 단일 PR)

**담당**: ai-engineer (lead) + backend-core (port·persistence 협력)
**branch**: `agent/ai-engineer/t-ai-phase1-backend`
**scope**: ~22 파일 ~600 LOC

| 영역 | 파일 |
|------|------|
| domain (8) | `domain/.../ai/{AnalysisRequest, AnalysisResult, AnalysisAssistant, AnalysisType, ConfidenceLevel, ContextSnapshot, FinancialsHint, JournalEntry}.kt` |
| api Controller (2) | `api/.../controller/{AnalysisController, AnalysisDto}.kt` |
| api Service (5) | `api/.../service/{AnalysisService, AnalysisCacheAdapter, AnthropicAnalysisAssistant, MockAnalysisAssistant, AnalysisPromptLoader, AnalysisAuditService}.kt` |
| api Persistence (3) | `api/.../persistence/AiAnalysisLog{JpaEntity, Repository, Adapter}.kt` |
| api Config (1) | `api/.../config/AiCircuitBreakerConfig.kt` |
| Flyway (1) | `api/src/main/resources/db/migration/V42__ai_analysis_log.sql` |
| Prompts (6) | `api/src/main/resources/prompts/analysis/{_system_base, symbol_commentary, company_analysis, chart_interpretation, indicator_meaning, journal_retrospective}.st` |
| Test (5+) | Service·Adapter unit tests + Controller integration test (코드 먼저 룰 4번 — 다음 사이클 일괄) |

**검증 의무**:
- `./gradlew :api:check` GREEN
- Swagger 4단 의무 (`AnalysisController` 5 endpoint 한국어 description + example)
- mock fallback 사용자 dogfooding evidence (curl로 5 endpoint 확인)
- audit_trail INSERT evidence (DB SELECT count)
- AI 호출 grep 검증 — api 외 영역(domain·consumer·batch) `spring.ai` import 0

### Phase 2 — frontend (frontend 단일 PR)

**담당**: frontend (Phase 1 머지 후 즉시 진입)
**branch**: `agent/frontend/t-ai-phase2-frontend`
**scope**: ~9 파일 ~400 LOC

| 영역 | 파일 |
|------|------|
| Components (3) | `frontend/src/components/ai/{AiCard, AiCommentTriggerButton, AiResponseRenderer}.tsx` |
| Hooks (1) | `frontend/src/hooks/useAiAnalysis.ts` (5 hook in 1 file — `useSymbolAnalysis`·`useCompanyAnalysis`·`useChartAnalysis`·`useIndicatorAnalysis`·`useJournalAnalysis`) |
| Pages (2) | `frontend/src/pages/SymbolPage.tsx` (격상 — AI 4 트리거 버튼 추가) + `JournalPage.tsx` (격상 — AI 복기 버튼) |
| API client (1) | `frontend/src/api/analysisApi.ts` |
| Test (~) | RTL component test 1~2건 (`feedback_dev_priority_qa_batch` 정합 — R3 일괄) |

**검증 의무**:
- `pnpm typecheck && pnpm build` GREEN
- Dogfooding evidence 5 path (사용자 클릭 → 자연어 응답 표시 + 면책 footer + 비용·토큰·모델 메타)
- 활성/비활성 배지 표시 (MAGICJAR_AI_ENABLED=false 시 "AI 비활성")

---

## §5. 비범위 (post-MVP / backlog 이관)

| 항목 | 사유 | 처리 |
|------|------|------|
| Kafka 비동기 사이클 | 의제 A-1 동기 채택 | backlog (사용자 동시 다중 호출 본격화 시) |
| Gemini · OpenAI provider | 의제 B-1 Anthropic 단독 | backlog v3.8 또는 post-MVP |
| 일일 사용자 토큰 한도 | 의제 D-1 post-MVP | backlog (dogfooding 비용 evidence 누적 후) |
| `prompt_template` DB 테이블 | 의제 E-1 catalog 파일 단독 | post-MVP (A/B 비교 필요 시) |
| 뉴스 감성 분석 | 의제 F-1 backlog §62 잔류 | T-NEWS 감성 분석 별도 트랙 |
| MCP server 5 tools | 의제 G-1 T-MCP 별도 | ADR 0027 baseline 별도 트랙 (T-AI 마감 후) |
| AI 매매 트리거 | ADR 0026 §2-2 LOCK — 매매 트리거 0 | 영구 비범위 (사용자 비전 LOCK) |
| 음성 입력 / 다국어 응답 | post-MVP | backlog |
| AI 응답 스트리밍 (Spring AI `.stream()`) | MVP scope 외 | post-MVP (`call()` 표준 LOCK) |

---

## §6. Rollback 시나리오

| 시나리오 | 대응 |
|---------|------|
| 부팅 후 ChatClient bean 실패 | application.yml `magicjar.ai.enabled=false` 강제 → 모든 endpoint 503 graceful |
| LLM 비용 폭주 | `application.yml magicjar.ai.enabled=false` 환경변수 즉시 변경 + 재기동 → 모든 호출 503 차단 |
| audit_trail 부담 ↑ | V42 `cost_estimate_usd` 기반 alert backlog § 추가 (post-MVP) |
| Flyway V42 실패 | rollback SQL 부재 (add-only) → DROP TABLE ai_analysis_log 수동 |
| 사용자 비전 위반 (AI가 매매 트리거 발생 의제) | 영구 비범위 LOCK — 본 ADR 0026 §2-2 baseline + ADR 0023 Signal Engine 룰 only |

---

## §7. KIS-first raw fetch 출처 매트릭스 (헌법 §3 정합 — 외부 SDK 적용)

| 출처 | 인용 부분 | WebFetch 시각 |
|------|-----------|---------------|
| `https://docs.spring.io/spring-ai/reference/1.0/api/chatclient.html` | ChatClient.Builder 패턴 · `.prompt().user().call().chatResponse()` · `ChatResponse.getMetadata().getUsage()` 토큰 회수 · Default System Prompt + Param · `entity(Class<T>)` · Anthropic provider qualifier 매트릭스 | 2026-05-19 KST (본 spec) |
| `https://docs.anthropic.com/` | Claude API model 식별자 (`claude-3-5-sonnet-20241022`) · prompt 권장 패턴 · 토큰 가격(USD per million tokens) | 후속 Phase 1 ai-engineer 진입 시 의무 (model 식별자 확정 시) |
| `_workspace/ai/prompt-catalog.md` (자체) | 5 분석 path 입력·출력 스키마 + 변수 치환 + 비용 통제 baseline | 2026-05-03 발행 |
| ADR 0026 (자체) | AI 옵션 보조 baseline 7 § | 2026-05-03 발행 |

---

## §8. 변경 매트릭스 (본 spec PR 산출물)

본 PR(architect 단독)에 포함:
1. `_workspace/specs/2026-05-19-t-ai-w4-entry-spec.md` — 본 spec
2. `_workspace/arch/adr/0088-t-ai-w4-entry.md` — ADR 0088 신설 (T-AI W4 진입 spec/plan Proposed)
3. `_workspace/arch/adr/README.md` — §10 + §11(0089 sequential) + §13 후속 예정 + §14 변경 이력 row
4. `docs/architecture/master-architecture.md` — §10 0088 row + §10 카운트 + §13 변경 이력 row
5. `docs/architecture/tracks-roadmap.md` — T-AI row 갱신 (spec 발행 + Phase 1·2 dispatch 매트릭스)
6. `_workspace/backlog.md` — §62 T-NEWS 감성 분석 row 강화 + §AI 보강 row + 비용 한도 backlog row
7. `CHANGELOG.md` — row prepend (작성: architect)

**운영 코드 0 touch** — Kotlin/TS/SQL 0 변경 (spec/plan 사이클).
**자본 영향 0** + **KIS endpoint 호출 0** + **AI 호출 0** + **mock 합성 0**.

---

## §9. 사용자 메모리 정합 검증 (8건)

| 메모리 | 정합 검증 |
|--------|----------|
| `feedback_release_policy_patch_first` (v3.7=W4 마감) | ✅ 본 사이클 = v3.7.0 minor 진입 1순위 |
| `feedback_spec_plan_scope` (작은 묶음 hotfix 외 spec 발행) | ✅ W4 본격 진입 = 신규 트랙 = spec 의무 발행 |
| `feedback_dev_priority_qa_batch` (개발 진도 우선 + QA 일괄) | ✅ Phase 1·2 직렬 + R3 일괄 테스트 룰 4 정합 |
| `feedback_no_mock_data` (사용자 흐름 mock 합성 금지) | ✅ MockAnalysisAssistant는 데이터 합성 0 + "AI 비활성 안내" graceful |
| `feedback_v3_skepticism_no_regression` (자동매매 필요? v1/v2 잔재?) | ✅ AI 옵션 = 자동매매 본체 미해체 + v3 baseline Spring AI 1.0.0-M3 |
| `feedback_kis_first_policy` (KIS-only 데이터 정책) | ✅ T-AI는 LLM 호출만 + KIS endpoint 호출 0 LOCK |
| `feedback_3tier_hierarchy` (orchestrator → architect → sub-agent) | ✅ 본 spec architect 발행 + Phase 1·2 sub-agent 디스패치 |
| `project_system_vision` (룰 본체 + AI 옵션) | ✅ AI 매매 트리거 0 + 사용자 트리거 한정 + 자연어 코멘트 |

---

## §10. architect 권장 종합

- **A-1** (동기 호출 HTTP)
- **B-1** (Anthropic 단독)
- **C-1** (mock fallback baseline)
- **D-1** (Redis cache 5분 + 일일 한도 post-MVP)
- **E-1** (catalog 파일 SoT)
- **F-1** (T-NEWS 감성 backlog §62 잔류)
- **G-1** (T-MCP 별도 트랙)
- **H-1** (Phase 1 backend + Phase 2 frontend 직렬)

사용자 결재 후 ai-engineer + backend-core 협업 Phase 1 단일 PR 디스패치 → 머지 후 frontend Phase 2 단일 PR 디스패치 → v3.7.0 minor cut.

---

> **결론**: T-AI 트랙은 baseline 매우 명확(ADR 0026 + prompt-catalog v1 + NaturalLanguageDslTranslator baseline + Spring AI 1.0.0-M3 의존 LOCK). 본 spec은 baseline 위에 본격 분석 5 path 구현 매트릭스를 LOCK. 운영 코드 0 touch · 자본 path 0 · KIS endpoint 0 LOCK. 사용자 결재 후 Phase 1·2 직렬 디스패치 → v3.7.0 cut.
