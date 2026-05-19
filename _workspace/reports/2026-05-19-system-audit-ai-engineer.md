# 시스템 점검 보고서 — ai-engineer 영역 (2026-05-19)

> **발행자**: ai-engineer sub-agent (opus 4.7)
> **사이클**: 2026-05-19 시스템 점검 (orchestrator 디스패치)
> **영역 경계**: Spring AI · 프롬프트 · AI 옵션 (사용자 트리거만) — 운영 코드 0 touch
> **main HEAD 기준**: `2a2225d` (v3.6.16 cut 후속)

## 요약 (TL;DR)

ai-engineer 영역은 **W2 단계에서 1건의 helper만 실 구현**되어 있다 — `api/ai/dsl/NaturalLanguageDslTranslator` (T-DSL PR-36 머지, ADR 0026·0023 정합). W4 T-AI 본체(5종 분석 prompt + AnalysisAssistant + V17 ai_analysis_log)는 **미구현**이지만, `_workspace/ai/prompt-catalog.md`가 미구현 자산을 광고 중인 **catalog 드리프트 1건**과, **`docs/architecture/policies/ai-trigger-gate.md` 미발행** + **컨슈머 모듈 dead env binding 1건** + **OPENAI_API_KEY dead config 1건** + **NaturalLanguageDslTranslator가 존재하지 않는 policy 문서를 인용**하는 정합 결함이 발견됐다. 모두 운영 무영향 hotfix급 의제(P2~P3). 룰 5(W1·W2·W3 LLM provider import 0)는 strategy/domain/consumer/batch 4 영역에서 그대로 **GREEN**(ADR 0026 사용자 트리거 예외만 api/ai 1 패키지에 격리됨).

---

## 1. Spring AI 의존 매트릭스

### 1-1. BOM·starter 선언

| 위치 | 선언 | 비고 |
|---|---|---|
| `build.gradle.kts:39` | `mavenBom("org.springframework.ai:spring-ai-bom:1.0.0-M3")` | 루트 BOM — 모든 모듈 적용 |
| `api/build.gradle.kts:41` | `implementation("org.springframework.ai:spring-ai-anthropic-spring-boot-starter")` | api 모듈만 starter. 다른 모듈은 BOM만 |
| `build.gradle.kts:52` (주석) | "Kotest autoscan(classgraph) + Spring AI 신규 의존성(anthropic/openai 양쪽) 합쳐" | openai 의존 미설치 — 주석 잔재 |

→ Spring AI 1.0.0-M3 + Anthropic provider 한정. OpenAI provider 의존 0 (사용자 트리거 옵션 default는 Anthropic Claude — ADR 0026 정합).

### 1-2. 모듈별 LLM provider import (룰 5 검증)

```bash
grep -rn "anthropic\|openai\|langchain\|spring-ai\|spring\.ai\|ChatClient\|ChatModel" \
  --include="*.kt" --include="*.kts" -- domain/ consumer/ batch/
```

| 모듈 | hit | 정합 |
|---|---|---|
| `domain/` | 0건 | GREEN |
| `consumer/` | 0건 (코드) — yml에 env binding 2건만 (§4 참조) | GREEN (코드) / dead binding 1건 |
| `batch/` | 0건 | GREEN |
| `api/` | main `api/ai/dsl/NaturalLanguageDslTranslator.kt` 1 파일 + test 1 파일 | **룰 5 예외 인정** (ADR 0026 사용자 트리거 helper) |

→ 룰 5 본체 LOCK은 strategy·domain·consumer 코드 기준. api/ai/dsl/ 1 패키지 격리는 spec pr-35 §4-2·NaturalLanguageDslTranslator.kt §30-32 주석에서 명문화. **위반 0**.

### 1-3. ai 패키지 파일 매트릭스

`api/src/main/kotlin/kr/spdaewoon/magicjar/api/ai/dsl/` (5 파일):

| 파일 | 책임 |
|---|---|
| `DslTranslateController.kt` | `POST /api/dsl/translate-from-natural` REST endpoint + 503 비활성 응답 |
| `DslTranslateDto.kt` | request·response DTO |
| `DslTranslateResult.kt` | 결과 sealed (success / untranslatable) + TranslateMode (REAL·MOCK·DISABLED) |
| `MockDslHeuristic.kt` | LLM 호출 0 룰 기반 fallback |
| `NaturalLanguageDslTranslator.kt` | Spring AI ChatClient(Anthropic) 호출 + mock fallback + 화이트리스트 sanitize + 1회 재시도 |

`api/src/test/...api/ai/dsl/` (3 파일): Controller / Translator / MockDslHeuristic 단위.

---

## 2. 프롬프트 라이브러리 정합

### 2-1. 실 코드 prompt resource (1건)

| 파일 | 용도 | 변수 | 호출 위치 |
|---|---|---|---|
| `api/src/main/resources/prompts/dsl-translate.md` | 자연어 → KIS .kis.yaml 호환 DSL 1줄 변환 (T-DSL helper) | `{{naturalLanguage}}` | `NaturalLanguageDslTranslator.loadPromptTemplate()` (line 204-207) |

### 2-2. catalog 광고 prompt (5건 — **미존재**)

`_workspace/ai/prompt-catalog.md` (T-AI R1 카탈로그)는 다음 5종을 광고:
- `_system_base.st`
- `symbol_commentary.st`
- `company_analysis.st`
- `chart_interpretation.st`
- `indicator_meaning.st`
- `journal_retrospective.st`

→ `api/src/main/resources/prompts/analysis/` 디렉토리 **부재**. AnalysisAssistant·AnalysisRequest·AnalysisResult 클래스 **부재**. `ai_analysis_log` 테이블 (V17 슬롯) **부재** (V17은 `risk_event`로 점유). catalog는 향후 T-AI 진입 시 합류 의도였으나 **현재 시점 catalog vs 실 코드 드리프트 1건**.

### 2-3. 면책 문구 정합

catalog가 자동 prepend된다고 주장한 면책 문구:
> `[면책] 본 분석은 AI 옵션 보조용이며 매매 결정·추천이 아닙니다. 투자 책임은 사용자에게 있습니다.`

→ T-DSL helper(실 구현)는 본 면책 prepend **0건**. catalog가 명시한 prepend 로직은 T-AI 진입 시 도입 의도였음. T-DSL helper는 DSL 텍스트 1줄 변환이라 면책 prepend가 부적절(사용자에게 텍스트가 그대로 노출되지 않고 GUI 트리에 재구성됨 — pr-44 §43). 따라서 면책 미부착은 결함 아님 — 다만 catalog가 "모든 분석 호출 공통"으로 광고하는 점은 T-AI R1 진입 후 다시 작성될 prompt 한정 적용으로 재기술 필요.

---

## 3. AI 옵션 게이트 정합 (ADR 0026·0023 정독)

### 3-1. 4 단계 게이트 매트릭스

| 단계 | 위치 | 정합 |
|---|---|---|
| **endpoint 인증·세션** | `DslTranslateController` (`@PostMapping("/api/dsl/translate-from-natural")`) | OK — Spring Security 일반 path |
| **`MAGICJAR_AI_ENABLED=false` → 503** | `DslTranslateController:71-83` + `NaturalLanguageDslTranslator:61-66` 이중 안전 | OK |
| **mock-mode 분기 (`auto` + key 부재 → MOCK)** | `NaturalLanguageDslTranslator.resolveMode()` (line 82-95) | OK — `MockDslHeuristic` 룰 fallback, LLM 호출 0 |
| **출력 화이트리스트 (`safeDslPattern`)** | `NaturalLanguageDslTranslator:57` regex `^[a-zA-Z0-9_().,\s+\-*/%<>=!"\[\]]+$` + `parseLlmResponse:169-176` | OK — 카탈로그 외 식별자/제어문자/SQL injection 방지 |

### 3-2. env 정합

`.env.example`:
```
ANTHROPIC_API_KEY=
OPENAI_API_KEY=
MAGICJAR_AI_ENABLED=false
MAGICJAR_AI_PROVIDER=anthropic
MAGICJAR_AI_MOCK_MODE=auto
```

- `ANTHROPIC_API_KEY` → `application.yml:50` `spring.ai.anthropic.api-key` 바인딩 → `NaturalLanguageDslTranslator:41`에서 read. **GREEN**
- `OPENAI_API_KEY` → `docker/docker-compose.yml:238` 노출만, 코드에서 read **0건**. **dead config 1건** (hotfix 의제).
- `MAGICJAR_AI_PROVIDER` → `application.yml:76` 바인딩 → `NaturalLanguageDslTranslator:40`에서 read, `"anthropic"` 외에는 mock fallback. **GREEN**
- `MAGICJAR_AI_*` 3종 → api `application.yml:74-76` + consumer `application.yml:60-61`에 binding. consumer는 read 코드 **0건** (§4 참조).

### 3-3. ADR 0023 (Signal Engine 룰 매칭 only) 정합

- consumer 모듈은 시그널 엔진 본체. AI 호출이 시그널 흐름의 정상 노드가 되어선 안 됨.
- 실제 consumer 코드의 LLM provider import: **0건** (§1-2 grep evidence).
- `consumer/application.yml:60-61`의 `MAGICJAR_AI_ENABLED·MOCK_MODE` 바인딩은 dead binding (read 없음). 룰 5·ADR 0023 위반은 아니지만 yml 노이즈 + 미래 sub-agent에게 "consumer가 AI 사용한다"는 오해 risk → hotfix 의제 H2 (§5 참조).

---

## 4. consumer 모듈 dead binding (정합 결함)

`consumer/src/main/resources/application.yml:58-61`:
```yaml
magicjar:
  ai:
    enabled: ${MAGICJAR_AI_ENABLED:false}
    mock-mode: ${MAGICJAR_AI_MOCK_MODE:auto}
```

| 항목 | 상태 |
|---|---|
| 코드 read | `grep -rn "magicjar.ai\|MAGICJAR_AI" consumer/src/main/kotlin` → **0건** |
| 사용 의도 | ADR 0023 (Signal Engine 룰 only) 정합 측면에서 consumer는 AI 호출 0 — 본래 binding이 부재해야 함 |
| 영향 | 0 (실 동작 영향 없음) |
| 결함 분류 | yml 노이즈 + 미래 룰 5 audit risk |

→ hotfix 의제 H2.

---

## 5. hotfix 의제 도출

총 4건 (P2 1건 + P3 3건). 모두 운영 무영향 정합 결함 — 다음 patch 사이클에 묶음 처리 권장.

| ID | 우선순위 | 의제 | evidence | 처리 size |
|---|---|---|---|---|
| **H1** | P2 | `_workspace/ai/prompt-catalog.md` 재기술 — T-AI R1 미구현 명시 + 현행 T-DSL helper 1건 합류 (실 prompt 1건만 등재) | catalog는 5종 광고 / 실 prompt 1건. analysis/ 디렉토리 부재. AnalysisAssistant 부재. V17 = risk_event(타용) | M (catalog 본문 재작성, 코드 0 touch) |
| **H2** | P3 | `consumer/application.yml` `magicjar.ai.*` 2 줄 제거 — read 코드 부재 dead binding 정리 | `grep magicjar.ai consumer/src/main/kotlin` 0건 | S (2 줄 삭제) |
| **H3** | P3 | `.env.example` + `docker/docker-compose.yml` OPENAI_API_KEY 라인 제거 (또는 "T-AI R1 미진입 — 사용 0" 주석) | 코드 read 0건. ADR 0026 default provider anthropic | S (2 위치 1줄씩) |
| **H4** | P3 | `NaturalLanguageDslTranslator.kt:102`의 `docs/architecture/policies/ai-options.md` 인용 → 존재 path로 교정 + `ai-trigger-gate.md` policy 발행 의제 backlog 등록 | policies/README.md "AI 호출 게이트 W4 진입 시" 명시 — T-DSL helper 진입으로 이미 W2 시점에서 필요 | S (1 line 교정 + backlog row) |

추가 메모:
- `build.gradle.kts:52` 주석의 "anthropic/openai 양쪽" 표현은 openai 의존 미설치 상태와 충돌. H3와 함께 1 line 정정 권장 (룰 위반 0, 인지 부담 감소).

---

## 6. MCP 서버 미래 의제 매트릭스 (T-MCP — W4)

ADR 0027 (`_workspace/arch/adr/0027-mcp-server-future.md`)이 T-MCP 미래 의제 baseline. 본 audit 시점에서는:
- 코드 구현 0건
- ADR 0027 §expose tool 5종 가설 (instrument lookup·candle fetch·signal status·watchlist read·strategy validate)
- T-AI 완료 후 진입 (`tracks-roadmap.md` line 53 — `prerequisite: T-AI`)
- ADR 0080 (전 에이전트 opus 4.7) §17이 W4 (T-AI·T-MCP) 진입을 명시

→ 본 audit 사이클에서 T-MCP 정합 결함 0. ai-engineer는 T-MCP 진입 시 tool 시그니처 의제(어떤 tool을 MCP로 노출할지 prompt-friendly로 design)에 기여 책임만 보유.

---

## 7. 룰 5 (W1·W2·W3 트랙 코드 AI 호출 자제) 정합 grep evidence

```bash
# 1) main 코드 LLM provider import (api/ai/dsl/ 예외 외)
grep -rn "anthropic\|openai\|langchain\|ChatClient\|ChatModel" \
  --include="*.kt" -- domain/src/main consumer/src/main batch/src/main
# → 0건 (GREEN)

# 2) api main 코드 (예외 1 패키지만 격리)
find api/src/main/kotlin/kr/spdaewoon/magicjar/api -name "*.kt" | xargs grep -l "ChatClient\|spring\.ai"
# → api/ai/dsl/NaturalLanguageDslTranslator.kt (1 파일 / 예외 인정)

# 3) PR-35 T-DSL spec 명시 grep (W2 본체 0건 LOCK)
grep -rE "naturalLanguage|nlToDsl|prompt" api/src/main consumer/src/main batch/src/main domain/src/main
# → api/src/main/resources/prompts/dsl-translate.md (resource)
#   api/src/main/kotlin/.../ai/dsl/* (helper 패키지 격리)
#   consumer/.../config/KafkaTopicConfig.kt (T-AI prompt input 주석 — read 없음)
#   api/.../naver/NewsKafkaProducer.kt (T-AI prompt input 주석 — read 없음)
```

→ 본체(strategy/domain/consumer/batch) **0건 GREEN**. api/ai/dsl/ 1 패키지 격리 + 주석으로 명문화된 예외만 존재. PR-35 spec §61 `0건 의무` 와 PR-36 ai-engineer 머지의 사용자 결재 예외가 정합되어 발효 중.

---

## 8. 산출물 체크리스트 ✅

- [x] **Spring AI 의존 매트릭스** — §1 (BOM · starter · 모듈별 grep)
- [x] **프롬프트 라이브러리 정합** — §2 (실 1건 vs catalog 광고 5건 드리프트 식별)
- [x] **AI 옵션 게이트 정합** — §3 (4 단계 게이트 GREEN + env 정합 + ADR 0023 정합)
- [x] **룰 5 정합 grep** — §7 (W1·W2·W3 본체 0건 + ai/dsl 1 패키지 격리 예외 명문화)
- [x] **hotfix 의제** — §5 (H1 P2 + H2·H3·H4 P3, 다음 patch 사이클 묶음 처리 권장)

---

## 9. 결론·권장

ai-engineer 영역은 **현재 시점에서 명백한 운영 위험 0**. 룰 5 본체 LOCK GREEN, ADR 0026·0023 게이트 정합, ADR 0080 opus 4.7 정합. 발견된 4건은 모두 정합·일관성 결함 — 묶음 patch 1건으로 마감 가능.

**다음 ai-engineer 가동 시점**: W4 T-AI 진입(v3.7.0 baseline 후) — 5종 분석 prompt 본격 구현 + AnalysisAssistant 도입 + V42(or 신규) ai_analysis_log 테이블 + `docs/architecture/policies/ai-trigger-gate.md` 발행 + catalog 재정합 동시 묶음.

본 사이클 디스패치 가능 후속 작업(orchestrator 판단 필요):
- H1·H2·H3·H4 묶음 → 다음 ai-engineer 단일 patch PR
- T-AI v3.7.0 진입 — architect spec/plan 필요 (영역 외 — 본 sub-agent 책임 X)

---

## 부록 A. 명문화된 룰 5 예외 (NaturalLanguageDslTranslator.kt §30-32)

```kotlin
/**
 * 룰 5 (W2 LLM import 0) 예외:
 * - ai-engineer 영역(api/.../ai/dsl/)은 사용자 결재로 W2 진입 — 룰 5 본체 LOCK은 strategy/domain/consumer 기준.
 * - 본 클래스는 자동매매 본체 데이터 흐름이 아니라 사용자 트리거 helper.
 */
```

ADR 0026 §사용자 트리거 한정 + spec pr-35 §사용자 결재 예외 + commit `5a34bc8 feat(ai-engineer): T-DSL PR-36 자연어 → DSL 변환 helper (사용자 트리거, 매매 결정권 0)`로 다중 정합.

## 부록 B. 변경 이력

| 날짜 | 변경 | 산출 |
|---|---|---|
| 2026-05-19 | 초판 발행 — 시스템 점검 사이클 ai-engineer 영역 audit | 본 보고서 |
