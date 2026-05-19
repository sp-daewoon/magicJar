# ADR 0088: T-AI W4 진입 — AI 옵션 보조 본격 사이클

- 상태: **Proposed** (2026-05-19, 사용자 결정 의제 8건 위임 — architect 권장 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1)
- 작성일: 2026-05-19 KST
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` §10 (본 ADR row) · §D.18 (T-AI 트랙)
- 트랙: **T-AI (W4 첫 트랙)** — `tracks-roadmap.md` §2 T-AI row 정합
- 선행 ADR: 0001(시스템 비전) · 0023(Signal Engine 룰 only · AI 매매 트리거 0 LOCK) · **0026(AI 옵션 보조)** ★ baseline · 0026 R1 baseline 발행 완료 (`api/.../ai/dsl/NaturalLanguageDslTranslator.kt` + `_workspace/ai/prompt-catalog.md` v1)
- 후행 ADR: 0027(MCP server T-MCP 별도 트랙)
- spec 본문: `_workspace/specs/2026-05-19-t-ai-w4-entry-spec.md` (10 § 풀세트)

---

## §1. Context

### §1-A. W3 마감 evidence + W4 진입 시점 정합

사용자 메모리 `feedback_release_policy_patch_first` LOCK = **v3.7 = W4 마감**. W3 마감 7 트랙(T-O · T-RECON · T-NOTIFY · T-J · T-PERF · T-NEWS · T-ACCT) + W3 보강 6 Accepted(indicator-impl · T-MAN · T-CC Phase 1+2-A · 페르소나 0085 · 0086 · 0087 Proposed Phase 1 evidence 누적 진행 중) → **본 사이클 W4 T-AI 진입 1순위 LOCK**.

### §1-B. ADR 0026 baseline 정독

ADR 0026 §1~§7 정합:
- §2-1 Anthropic 1차 default + Gemini/OpenAI 옵션 (本 ADR 결정 의제 B로 단일 vs 다중 위임)
- §2-2 5 트리거 (사용자 명시 클릭만 + 매매 트리거 0)
- §2-3 Domain port `AnalysisAssistant` baseline
- §2-4 Kafka 비동기 흐름 baseline (本 ADR 결정 의제 A로 동기 vs 비동기 위임)
- §2-5 비용 통제 Redis cache 5분 + `ai_comment_log (V20)` — **V20 충돌 정정** (현 baseline V41 → V42 신규)
- §2-6 본체 영향 0 + Resilience4j
- §2-7 `prompt_template` 테이블 post-W4 보강 — **본 사이클 비범위** (catalog 파일 SoT 단독)

### §1-C. baseline 코드 footprint

T-AI R1 baseline 이미 발행:
1. `api/src/main/kotlin/.../ai/dsl/NaturalLanguageDslTranslator.kt` — Spring AI ChatClient + mock fallback baseline
2. `_workspace/ai/prompt-catalog.md` — 5 분석 카탈로그 + 변수 치환 + 비용 통제 baseline (2026-05-03)
3. `application.yml` 73~76 — `magicjar.ai.{enabled, mock-mode, provider}` 3 prop baseline
4. `build.gradle.kts` — `spring-ai-bom:1.0.0-M3` + api 모듈 `spring-ai-anthropic-spring-boot-starter` 의존 baseline
5. `.env.example` 38~42 — ANTHROPIC_API_KEY · MAGICJAR_AI_* 4 var baseline

본 ADR은 baseline 위에 **본격 분석 5 path 구현 매트릭스**를 LOCK.

### §1-D. Spring AI 1.0.0-M3 ChatClient raw 인용 (WebFetch 2026-05-19 KST)

출처: `https://docs.spring.io/spring-ai/reference/1.0/api/chatclient.html` (1.0.0-M3 baseline 정합)

```java
// raw 인용 — Builder 패턴 + ChatResponse 토큰 정확치 회수
ChatResponse chatResponse = chatClient.prompt()
    .user("Tell me a joke")
    .call()
    .chatResponse();

Usage usage = chatResponse.getMetadata().getUsage();
int inputTokens = usage.getPromptTokens();
int outputTokens = usage.getGenerationTokens();
String modelName = chatResponse.getMetadata().getModel();
```

- 토큰 정확치 회수 가능 → prompt-catalog §비용 통제 "Spring AI v0 — 정확치 회수 어려움 post-MVP에서 raw response metadata 파싱" 갱신 baseline LOCK (본 사이클부터 정확치)
- `ChatClient.Builder` autowire pattern 이미 NaturalLanguageDslTranslator.kt에서 적용 중

---

## §2. Decision

### §2-A. 트랙 정의

T-AI 트랙 W4 본격 진입. **5 분석 path**(`_workspace/ai/prompt-catalog.md` baseline) 본격 구현 + REST endpoint 5 + 비용 통제 Redis cache + audit_trail V42 + Resilience4j Circuit Breaker + frontend AiCard.

### §2-B. 5 분석 path

| 분석 타입 | endpoint | UI 위치 |
|-----------|----------|--------|
| SYMBOL_COMMENTARY | `POST /api/analysis/symbol/{code}/commentary` | `/symbol/:code` "AI 종목 분석" 버튼 |
| COMPANY_ANALYSIS | `POST /api/analysis/symbol/{code}/company` | `/symbol/:code` "기업 분석" 버튼 |
| CHART_INTERPRETATION | `POST /api/analysis/symbol/{code}/chart` | `/symbol/:code` 차트 패널 "차트 해석" 버튼 |
| INDICATOR_MEANING | `POST /api/analysis/indicator/{id}/explain` | `/symbol/:code` 지표 패널 "지표 설명" 버튼 |
| JOURNAL_RETROSPECTIVE | `POST /api/analysis/journal/{tradeId}/retrospective` | `/journal` "AI 복기" 버튼 |

### §2-C. 사용자 결정 의제 8건 + architect 권장 LOCK

| 의제 | architect 권장 | 사유 |
|------|---------------|------|
| A 동기 vs 비동기 | **A-1 동기 호출** | MVP fitness 정합 + Kafka·STOMP 신규 0 + consumer 모듈 0 touch |
| B Provider 매트릭스 | **B-1 Anthropic 단독** | baseline 정합 + 1차 dependency 최소 + v3.8 또는 post-MVP 보강 |
| C Mock fallback 정책 | **C-1 baseline 정합 LOCK** | MAGICJAR_AI_ENABLED=false default + auto mock fallback + 데이터 합성 0 |
| D 비용 통제 | **D-1 Redis cache 5분 + 일일 한도 post-MVP** | chart-curator 햇징 룰 §6 "임의 임계치 영구 차단" 정합 |
| E 프롬프트 버전 관리 | **E-1 catalog 파일 SoT** | DB `prompt_template` post-MVP backlog |
| F T-NEWS 감성 통합 | **F-1 backlog §62 잔류** | feedback_spec_plan_scope 정합 |
| G MCP server 합류 | **G-1 T-MCP 별도 트랙 (ADR 0027)** | tracks-roadmap baseline 정합 |
| H Phase 분리 | **H-1 Phase 1 backend + Phase 2 frontend 직렬** | feedback_dev_priority_qa_batch 정합 |

### §2-D. 모듈 경계 (ADR 0004 정합)

- **domain** — `AnalysisRequest`·`AnalysisResult`·`AnalysisAssistant` port + enum·DTO 보조 (~8 파일 · Spring 의존 0)
- **api** — REST 5 + Service + Cache + Anthropic adapter + Mock adapter + Audit + Prompts (~14 파일 + 6 `.st` + Flyway V42)
- **consumer** — **0 touch** (A-1 동기 채택 정합)
- **batch** — **0 touch**
- **frontend** — AiCard + 5 hook + 페이지 격상 (~9 파일)

**자본 path 영향 0 LOCK** — chart-curator cross-review 불필요.

### §2-E. Flyway V42

`V42__ai_analysis_log.sql` add-only LOCK (운영 baseline 변경 0). `id BIGSERIAL PK · request_id UUID · user_id · type · code · provider · model · prompt_version · prompt_hash · tokens_in · tokens_out · cost_estimate_usd · duration_ms · cached · status · confidence · error_message · response_text · created_at`. 인덱스 3건 (user_created · type_created · request_id).

### §2-F. 검증 의무

- `./gradlew :api:check` GREEN
- Swagger 4단 의무 (5 endpoint 한국어 description + example)
- mock fallback dogfooding evidence (curl 5 path)
- audit_trail INSERT evidence (DB SELECT count)
- AI 호출 grep 검증 — api 외 영역(domain·consumer·batch) `spring.ai` import 0

---

## §3. Result · 영향

- 사용자 명시적 트리거에 한정 5 분석 path 활성 → AI 옵션 보조 비전 정합
- 매매 트리거 0 LOCK (ADR 0023·0026 baseline 정합)
- Spring AI 1.0.0-M3 baseline 활용 + Resilience4j Circuit Breaker로 본체 영향 0
- Redis cache 5분 + audit V42로 비용 가시화
- v3.7.0 minor cut LOCK (W4 마감 baseline 정합)
- T-MCP 후속 트랙 진입 path 확보

---

## §4. Alternatives 검토

### (a) AI 자동 호출 (시그널·주문 자동 트리거)
- 사용자 비전 위반 (ADR 0001·0023·0026 LOCK) — 영구 비범위

### (b) AI 풀세트 (3 provider + Kafka 비동기 + DB prompt_template)
- MVP scope 과도 확장 + 첫 dogfooding evidence 부재 시 운영 부담 ↑
- 의제 B·D·E 의 옵션 2·3안

### (c) AI 트랙 자체 보류 (W4 진입 안 함)
- W4 LOCK 미준수 + 사용자 비전 미합류

### (d) Phase 1 backend + Phase 2 frontend 직렬 (채택) ★
- 자본 path 영향 0 LOCK + 영역 분리 자연 정합 + 직렬 단순

---

## §5. References

- ADR 0001 (시스템 비전 v2)
- ADR 0023 (Signal Engine 룰 only)
- **ADR 0026 (AI 옵션 보조) — baseline**
- ADR 0027 (MCP server T-MCP 별도 트랙)
- spec: `_workspace/specs/2026-05-19-t-ai-w4-entry-spec.md` (10 § 풀세트)
- prompt-catalog: `_workspace/ai/prompt-catalog.md` (v1)
- Spring AI ChatClient API: https://docs.spring.io/spring-ai/reference/1.0/api/chatclient.html
- Anthropic API: https://docs.anthropic.com/
- master §10 본 ADR row · §D.18
- tracks-roadmap T-AI row

---

## §6. 변경 이력

| 일자 | 변경 | 사유 |
|------|------|------|
| 2026-05-19 | 본 ADR 0088 Proposed 발행 | W4 T-AI 진입 spec/plan 본격 발행 — 사용자 결정 의제 8건 위임 (architect 권장 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1) · ADR 0026 baseline 정합 + Spring AI 1.0.0-M3 raw fetch evidence (architect, opus 4.7 1M) |
