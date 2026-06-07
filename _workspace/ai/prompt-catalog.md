# magicJar 프롬프트 카탈로그 (현행)

> AI 분석 보조 (사용자 트리거 한정, ADR 0026·ADR 0023).
> **AI 매매 결정권 0.** 어떤 응답도 BUY/SELL 트리거를 발생시키지 않는다.
> **본 문서는 현재 워크트리에 실제 존재하는 프롬프트만 표기.** T-AI R1 baseline(W4 진입 시) 발행 예정 분은 §3 미래 의제로 분리.

---

## §1. 현행 실 프롬프트 (PR #552 cross-validation evidence 정합)

### 1-1. 위치 매트릭스

| 파일 | 모듈 | 용도 | 호출자 | promptVersion |
|---|---|---|---|---|
| `api/src/main/resources/prompts/dsl-translate.md` | api | 자연어 → magicJar/KIS 호환 DSL 한 줄 변환 helper (T-DSL Phase) | `NaturalLanguageDslTranslator` | v1 (PR-35) |

**근거**: 2026-05-19 cross-validation `find api/src/main/resources/prompts -type f` 결과 `dsl-translate.md` 1건 단독 — `analysis/` 하위 5종(`_system_base`·`symbol_commentary`·`company_analysis`·`chart_interpretation`·`indicator_meaning`·`journal_retrospective`)은 **부재**.

### 1-2. dsl-translate 입력·출력 스키마

| 항목 | 값 |
|---|---|
| 입력 변수 | `{{naturalLanguage}}` (사용자 자연어 1줄) |
| 출력 형식 | KIS 텍스트 1줄 표현식 (PR-35 spec §5-1 LOCK — raw `parser.py` 정합) |
| 안전 출력 | `UNTRANSLATABLE` (변환 불가 판정 시) |
| 화이트리스트 | `^[a-zA-Z0-9_().,\s+\-*/%<>=!"\[\]]+$` (식별자·연산자·괄호만, 카탈로그 외 차단) |
| endpoint | `POST /api/ai/dsl/translate` (사용자 트리거 한정, ADR 0023) |

### 1-3. 운영 게이트 (NaturalLanguageDslTranslator.kt 기준)

1. `MAGICJAR_AI_ENABLED=false` → endpoint 503 (Controller 책임) + helper `DISABLED` 모드 untranslatable 즉시 반환.
2. `MAGICJAR_AI_MOCK_MODE=mock` 또는 `auto` + `ANTHROPIC_API_KEY` 미설정 → `MockDslHeuristic` 룰 fallback.
3. 위 외 → Spring AI ChatClient(Anthropic) 호출 + 1회 재시도 + UNTRANSLATABLE / 화이트리스트 외 식별자 graceful 차단.

### 1-4. 면책 prepend

```
[면책] 본 분석은 AI 옵션 보조용이며 매매 결정·추천이 아닙니다. 투자 책임은 사용자에게 있습니다.
```

---

## §2. 변수 치환 규약 (현행)

`{{key}}` 단순 치환 (Spring Template + 자체 `AnalysisPromptLoader.render`) — 본격 도입은 §3 미래 분석 프롬프트 발행 시. 현재 `dsl-translate.md` 한정으로 `{{naturalLanguage}}` 단일 변수만 사용.

---

## §2-5. W4 R1 T-AI Phase 1 (2026-05-24 발행 — Flyway V49·V50)

> ADR 0026 amend (2026-05-24) — 5 진입점 (SYMBOL/CHART/STRATEGY/SIGNAL/MARKET) prompt template DB 영속 + 3 provider plug-in.
> 본 § 의 5 prompt 는 `prompt_template` 표 (V50 seed) 에 보관 — 운영 중 type+version 갱신 가능.

### 2-5-1. V50 seed 5건 매트릭스

| type | version | 진입점 (frontend) | 변수 schema | endpoint |
|---|---|---|---|---|
| `symbol`   | v1 | `/symbol/:code` "AI 종목 분석"      | code · name · currentPrice · indicators · patterns · news | `POST /api/ai/explain` (type=SYMBOL) |
| `chart`    | v1 | `/symbol/:code` "차트 해석"         | code · timeframe · indicators · priceFlow | `POST /api/ai/explain` (type=CHART) |
| `strategy` | v1 | `/strategy/:id` "전략 평가"         | strategyName · dsl · backtestResult · indicatorsUsed | `POST /api/ai/explain` (type=STRATEGY) |
| `signal`   | v1 | `/dashboard` "이 시그널 해석"       | code · signalKind · firedAt · symbolState · strategyName | `POST /api/ai/explain` (type=SIGNAL) |
| `market`   | v1 | `/market/trend` "시장 추세 해석" (신규) | kospiTrend · kosdaqTrend · nasdaqTrend · spxTrend · sectorTopList · **maPeriods · rsPeriod** (ADR 0101 정합) | `POST /api/ai/explain` (type=MARKET) |

모든 prompt 는 한국어 응답 + 매매 추천 표현 금지 + 응답 길이 가이드 (250-600 자).

### 2-5-2. 3 provider 매트릭스

| provider | 모델 default | input $/1M | output $/1M | env (key) |
|---|---|---|---|---|
| anthropic | claude-3-5-haiku-20241022 | 0.80 | 4.00  | `ANTHROPIC_API_KEY` |
| gemini    | gemini-1.5-flash          | 0.075 | 0.30 | `MAGICJAR_AI_GEMINI_API_KEY` |
| openai    | gpt-4o-mini               | 0.15  | 0.60 | `MAGICJAR_AI_OPENAI_API_KEY` |
| mock      | mock-1.0                  | 0     | 0    | (key 미설정 시 auto fallback) |

env 토글: `MAGICJAR_AI_PROVIDER=anthropic|gemini|openai`.

### 2-5-3. 비용 통제 (ADR 0026 §2-5 amend · ADR 0095 amend S-AI-1)

| 가드 | 우선 | 분기 | 사용자 응답 |
|---|---|---|---|
| KillSwitch | P0 | `MAGICJAR_AI_KILLSWITCH=true` 시 모든 호출 즉시 차단 | "AI 기능이 운영자에 의해 일시 차단되었습니다." |
| 전역 비활성 | P1 | `MAGICJAR_AI_ENABLED=false` (default) | "AI 기능이 비활성 상태입니다. 설정에서 활성화한 후 다시 시도해 주세요." |
| 1일 토큰 한도 | P2 | 1000 토큰/사용자/일 (env override) — `ai_comment_log` 집계 | "오늘 사용 가능한 AI 호출 한도(1000 토큰)를 초과했습니다." |
| prompt 미존재 | P3 | `prompt_template.is_active=TRUE` 행 0건 | "AI 응답을 준비하는 중 문제가 발생했습니다." |
| 월 임계 ($10) | P4 (alert) | `AiCostTracker.isOverThreshold()` → Slack CRITICAL | (사용자 알림 0건 · 운영자 alert) |

### 2-5-4. Redis prompt cache (5분 TTL)

- 키: `ai:prompt:cache:{SHA-256(prompt)[0..16]}`
- hit 시 LLM 호출 0건 + `cached=TRUE` flag + 새 requestId 로 wrap (STOMP fanout 정합)
- TTL 5분 — 같은 입력 반복 시 비용 절감

### 2-5-5. 영속 + Kafka topic

| 자원 | 위치 |
|---|---|
| `ai_comment_log` | api Flyway V49 (audit 표 — cost_usd / tokens / cached / graceful) |
| `prompt_template` | api Flyway V50 (5 seed + variables_schema JSONB) |
| `ai.comment.requested.v1` | Kafka topic (api → consumer · partition key=userId) |
| `ai.comment.responded.v1` | Kafka topic (consumer → api STOMP forwarder + audit listener) |
| `/topic/ai.explain.{requestId}` | STOMP fanout (consumer 응답 → frontend AiCard) |

---

## §3. 미래 의제 — T-AI Phase 2 이상 (W4 후속)

> Phase 1 (2026-05-24 발행) 이후 ADR 0026 §3 amend 확장 후보. ai-engineer 단독 PR.

| 파일 (예정) | 용도 (예정) | promptVersion | 발행 트리거 |
|---|---|---|---|
| `api/src/main/resources/prompts/analysis/_system_base.st` | 모든 분석 호출 공통 system — 한국어 강제·면책·매매 결정 금지 | (미발행) | T-AI baseline PR |
| `api/src/main/resources/prompts/analysis/symbol_commentary.st` | 종목 코멘트 — 추세·주의·장기 검토 | (미발행) | T-AI baseline PR |
| `api/src/main/resources/prompts/analysis/company_analysis.st` | 기업 분석 — 펀더멘털 단편(PER·PBR·ROE·시총) | (미발행) | T-AI baseline PR |
| `api/src/main/resources/prompts/analysis/chart_interpretation.st` | 차트 해석 — 캔들·지표 컨텍스트 | (미발행) | T-AI baseline PR |
| `api/src/main/resources/prompts/analysis/indicator_meaning.st` | 지표 의미 설명 — 정의·시그널·한계·관련 지표 | (미발행) | T-AI baseline PR |
| `api/src/main/resources/prompts/analysis/journal_retrospective.st` | 매매일지 복기 — 진입 사유·청산·체크포인트 | (미발행) | T-AI baseline PR |

### 3-1. 미래 분석 프롬프트의 입력·출력 스키마 (예정 — W4 진입 시 확정)

| 분석 타입 (예정) | 입력 (예정) | 출력 (예정) | endpoint (예정) |
|---|---|---|---|
| SYMBOL_COMMENTARY | `AnalysisRequest.SymbolCommentary` (code · contextSnapshot) | text·confidence·model·promptVersion·tokensUsed·costEstimate·durationMs | `POST /api/analysis/symbol/{code}/commentary` |
| COMPANY_ANALYSIS | `AnalysisRequest.CompanyAnalysis` (code · financialsHint?) | 동상 | `POST /api/analysis/symbol/{code}/company` |
| CHART_INTERPRETATION | `AnalysisRequest.ChartInterpretation` (code · interval · candles · indicators) | 동상 | `POST /api/analysis/symbol/{code}/chart` |
| INDICATOR_MEANING | `AnalysisRequest.IndicatorMeaning` (indicatorId · params) | 동상 | `POST /api/analysis/indicator/{id}/explain` |
| JOURNAL_RETROSPECTIVE | `AnalysisRequest.JournalRetrospective` (tradeId · journalEntry) | 동상 | `POST /api/analysis/journal/{tradeId}/retrospective` |

### 3-2. 미래 변수 예정 (확정 아님)

- `{{code}}` · `{{nameKr}}` · `{{sector}}` 공통
- symbol_commentary: `{{lastClose}} {{change1d}} {{change5d}} {{patterns}} {{recentNews}}`
- company_analysis: `{{per}} {{pbr}} {{roe}} {{marketCap}}`
- chart_interpretation: `{{interval}} {{candleTable}} {{indicatorTable}}` (마지막 30 candle truncate)
- indicator_meaning: `{{indicatorId}} {{params}}`
- journal_retrospective: `{{symbol}} {{side}} {{entryPrice}} {{exitPrice}} {{pnlPct}} {{rationale}} {{openedAt}} {{closedAt}}`

---

## §4. 비용 통제 (현행 — dsl-translate 한정)

- mock fallback default — `MAGICJAR_AI_ENABLED=false` 시 endpoint 503.
- `magicjar.ai.mock-mode=auto` — `ANTHROPIC_API_KEY` 부재 시 자동 mock(`MockDslHeuristic`).
- 모든 호출은 `ai_analysis_log` 테이블(V17) 기록 — 사용자 비용·토큰 추적.
- input·output 토큰은 어댑터에서 길이 기반 추정 (Spring AI 1.0.0-M3 — raw metadata 회수 어려움). post-W4에서 raw response metadata 파싱.

---

## §2-6. AI 프리셋 추천 메타 프롬프트 (2026-06-06 발행)

> **위치**: `StrategyResearchAiDiscoveryService.buildPresetRecommendPrompt()` (인라인 빌더 — core wrapper·기존 프리셋·시스템 능력을 런타임 조합하므로 정적 리소스 파일이 아니라 코드 빌더).
> **엔드포인트**: `POST /api/strategy-research/ai-discovery/presets/recommend`
> **provider**: `StrategyResearchAiTextClient`(claude-cli / claude-cli-bridge) 재사용. timeout = `magicjar.strategy-research.ai-discovery.timeout-seconds`(기본 300s) 재사용.
> **저장 안 함** — 추천만 반환. 저장은 사용자가 기존 `createPreset`(POST .../presets)으로.

### 2-6-1. 메타 프롬프트 6 컨텍스트 (이 기능의 본체)

| # | 컨텍스트 | 출처(권위) | 목적 |
|---|---|---|---|
| 1 | 역할 정의 | 인라인 | 프리셋 = 매수 추천 X, Screener+Backtest 검증용 후보 가설 템플릿. core wrapper [분석 원칙] 제약 인용 |
| 2 | core wrapper 풀텍스트 | `corePromptText()` (DB 권위, 없으면 `DEFAULT_CORE_PROMPT`) | 추천 프리셋이 이 wrapper 뒤에 주입됨을 명시 |
| 3 | 기존 프리셋 전체 | `presetRepository.findAllByOrderBySortOrderAscIdAsc()` (label+description+promptText) | 중복/유사 컨셉 회피, 빈 영역 채우기 |
| 4 | magicJar 시스템 능력 | `systemCapabilitySummary()` ← `IndicatorCatalog`(도메인 권위) + candle interval 13종 | 보유 지표/캔들로 '검증 가능한' 프리셋만 추천 |
| 5 | 사용자 hint | request.hint (옵션) | 있으면 최우선 반영, 없으면 시장 보편 + 기존과 차별화 |
| 6 | 출력 스키마 + 필드 제약 | 인라인 JSON | label≤20·description≤80('~를 찾습니다')·promptText 2~4문장 명령형·rationale 1문장 |

**시스템 능력 요약은 `IndicatorCatalog`를 직접 읽어 생성** — 지표가 추가/삭제되면 메타 프롬프트도 자동 정합(코드 권위 추종). FVG·오더블록(BOS/장악형)·스윙은 스마트머니 그룹으로 별도 강조.

### 2-6-2. 입출력 스키마

- 요청: `{userId, hint?, count?}` — count 1~5 검증(서비스에서 coerceIn 추가 방어), DTO `@Min(1)@Max(5)`
- 응답: `{provider, generatedAt, warnings[], items:[{label, description, promptText, rationale}]}`
- 파싱: `parsePresetRecommendations()` — `extractJson`(코드블록/객체 추출) 재사용 + label·promptText 누락 항목 폐기, label/description 길이 clip. 빈 결과 → 503.
- output schema 파일: `_workspace/ai/output-schemas/preset-recommend.schema.json`
- sample: `_workspace/ai/sample-outputs/2026-06-06-preset-recommend.md`

### 2-6-3. 에러 처리

| 상황 | 동작 |
|---|---|
| Claude CLI/브릿지 비활성·실패·타임아웃 | `StrategyResearchAiTextClient`가 던진 `ResponseStatusException`(503/504/429) 그대로 전파 (mock 합성 금지) |
| JSON 파싱 실패 | 503 "Claude 추천 JSON 파싱 실패" |
| 사용 가능한 item 0건 | 503 "Claude 응답에서 추천 프리셋을 읽지 못했습니다" |
| count 범위 밖(0·6) | DTO bean validation 400 |

---

## §2-7. 전략빌더 AI 전략 생성 메타 프롬프트 (2026-06-08 발행)

> **위치**: `StrategyBuilderAiGenerateService.buildGeneratePrompt()` / `buildRegeneratePrompt()` (인라인 빌더 — DSL 문법·지표 카탈로그·기존 전략을 런타임 조합).
> **엔드포인트**: `POST /api/strategies/ai-generate`
> **provider**: `StrategyResearchAiTextClient`(claude-cli / claude-cli-bridge) 재사용.
> **저장 안 함** — 빌더 draft 로드용 제안만 반환. 저장은 기존 전략 저장 API(AI 결정권 0 · ADR 0112 정합).
> **패턴**: Live-Context Meta-Prompt(AI 프리셋 추천 §2-6 선례)의 2번째 이식. 컨텍스트만 교체("발굴 프리셋 설계" → "DSL 전략 설계").

### 2-7-1. 메타 프롬프트 6 컨텍스트 (이 기능의 본체)

| # | 컨텍스트 | 출처(권위) | 목적 |
|---|---|---|---|
| 1 | 역할 정의 | 인라인 (`researchCoreWrapperRolePrefix()`) | 전략 = 매수 추천 X, 백테스트·MOCK 검증 대상 가설. core wrapper [분석 원칙] 톤 인용 |
| 2 | DSL 문법·지표 카탈로그 | `KisYamlDslParser.SUPPORTED_IDENTIFIERS`(파서 화이트리스트 코드 추출) + `IndicatorCatalog`(카테고리별 id+displayName) | 실제 파싱 가능한 문법·연산자·함수만 노출. `prev_drop`·`volume[1]`·`close[-1]` 금지 명시 |
| 3 | 기존 사용자 전략 목록 | `strategyRepository.findAllByUserIdAndStatusNot(DELETED)` (name+entryDsl, 최대 30건) | 중복 컨셉 회피 |
| 4 | RiskPlan 스키마 + 잔익절 규칙 | 인라인 | takeProfitPct·fixedStopLossPct·minHoldBars 의미 + "익절 0.5% 이상 권장, 0.3% 이하 경고" + 손절 권장 |
| 5 | 사용자 prompt (필수) | request.prompt | 발굴과 달리 빈 값 400. 의도 최우선 반영 |
| 6 | 출력 JSON 계약 | 인라인 | name≤60·description 1~2문장·entryDsl(허용 문법)·exitDsl|null·riskPlan·rationale |

**DSL 문법 블록은 파서 화이트리스트를 코드에서 직접 추출** — 지표/별칭이 추가되면 메타 프롬프트도 자동 정합(파서 권위 추종). `KisYamlDslParser`에 `SUPPORTED_IDENTIFIERS` 공개 read 접근자 추가.

### 2-7-2. ★ 생성 후 서버측 DSL 검증 게이트 (품질 차별점)

| 단계 | 동작 |
|---|---|
| 1차 검증 | 각 item의 entryDsl/exitDsl을 **실제 `KisYamlDslParser.parse()` + `validateStatic()`** 로 검증 → `dslValid`/`dslError` 채움 |
| 1회 자동 재생성 | invalid item이 있으면 실패 DSL + 파서 에러를 동봉한 `buildRegeneratePrompt()`로 1회 재요청. valid 결과로 invalid 슬롯 순서대로 교체 |
| invalid 잔존 | 재생성도 실패하면 `dslValid=false`로 응답에 **포함**(frontend가 draft 로드만 비활성). mock 합성 0 |
| riskPlan 범위 | takeProfitPct ≤ 0.3% → warnings에 잔익절 경고. 손절 null → 손절 권장 경고 |

### 2-7-3. 입출력 스키마

- 요청: `{userId, prompt(필수), interval?, count?(1~3, 기본 1)}` — DTO `@NotBlank prompt` + 서비스 공백 방어 400, `@Min(1)@Max(3)` count
- 응답: `{provider, generatedAt, warnings[], items:[{name, description, entryDsl, exitDsl|null, riskPlan{takeProfitPct?, fixedStopLossPct?, minHoldBars?}, rationale, dslValid, dslError|null}]}`
- 파싱: `parseGeneratedStrategies()` — `extractJson` 재사용 + name·entryDsl 누락 항목 폐기. 빈 결과 → 503.
- output schema 파일(Claude 출력 계약, dslValid/dslError 제외): `_workspace/ai/output-schemas/builder-strategy-generate.schema.json`
- sample: `_workspace/ai/sample-outputs/builder-ai-generate-meta-prompt.txt`(실 프롬프트 덤프) · `builder-ai-generate-sample-output.json`(valid) · `builder-ai-generate-sample-output-invalid.json`(invalid+재생성)

### 2-7-4. 에러 처리

| 상황 | 동작 |
|---|---|
| Claude CLI/브릿지 비활성·실패·타임아웃 | `StrategyResearchAiTextClient`가 던진 `ResponseStatusException`(503/504/429) 그대로 전파 (mock 합성 금지) |
| JSON 파싱 실패 | 503 "Claude 전략 JSON 파싱 실패" |
| 사용 가능한 item 0건 | 503 "Claude 응답에서 전략 후보를 읽지 못했습니다" |
| prompt 빈 값(공백) | 400 (DTO `@NotBlank` + 서비스 trim 방어) |
| count 범위 밖(0·4) | DTO bean validation 400 (서비스 `coerceIn(1,3)` 추가 방어) |
| 재생성 호출 실패 | warn 로그 + 원래 invalid 유지(전체 실패로 끌어올리지 않음) |

---

## §5. 변경 이력

| 날짜 | 변경 | 비고 |
|---|---|---|
| 2026-06-08 | **전략빌더 AI 전략 생성 메타 프롬프트 발행** — §2-7 신설. DSL 문법(파서 화이트리스트 코드 추출)·지표 카탈로그·기존 전략·RiskPlan 잔익절 규칙·사용자 prompt(필수) 6 컨텍스트. ★ 생성 후 실제 DSL 파서 검증 게이트 + 1회 자동 재생성. `generate()` + `POST /api/strategies/ai-generate`. 빌더 draft 제안만 반환(저장 X · AI 결정권 0) | agent/ai · T-20260608-0200 |
| 2026-06-06 | **AI 프리셋 추천 메타 프롬프트 발행** — §2-6 신설. core wrapper·기존 프리셋 전체·IndicatorCatalog 기반 시스템 능력·hint 6 컨텍스트 조합. `recommendPresets()` + `POST .../presets/recommend`. 추천만 반환(저장 X) | agent/ai · T-20260606-2247 |
| 2026-05-03 | 초기 v1 — 5종 분석 프롬프트 작성 명시(드리프트 진입) | ADR 0026 (이후 사이클에서 분석 5종은 발행 보류 — W4 T-AI baseline까지 미발행) |
| 2026-05-24 | **W4 R1 T-AI Phase 1 발행** — §2-5 신설 (5 진입점 prompt seed V50 · 3 provider plug-in · KillSwitch / 1일 한도 / 월 임계 alert / Redis cache 5분) | ADR 0026 amend · ADR 0094 §4-4-B · ADR 0095 amend S-AI-1 · Flyway V49·V50 (R0 amend shift · ADR 0102 정합) |
| 2026-05-19 | **드리프트 정정** — §1 현행 1건(`dsl-translate.md`) 단독 LOCK + §3 미래 의제(W4 진입 시 발행)로 5종 분리 | PR #552 cross-validation evidence (ai-engineer audit 보고서 `_workspace/reports/2026-05-19-system-audit-ai-engineer.md` §). 광고-실재 매트릭스 정합 회복. |
