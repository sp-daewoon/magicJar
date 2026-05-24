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

## §5. 변경 이력

| 날짜 | 변경 | 비고 |
|---|---|---|
| 2026-05-03 | 초기 v1 — 5종 분석 프롬프트 작성 명시(드리프트 진입) | ADR 0026 (이후 사이클에서 분석 5종은 발행 보류 — W4 T-AI baseline까지 미발행) |
| 2026-05-24 | **W4 R1 T-AI Phase 1 발행** — §2-5 신설 (5 진입점 prompt seed V50 · 3 provider plug-in · KillSwitch / 1일 한도 / 월 임계 alert / Redis cache 5분) | ADR 0026 amend · ADR 0094 §4-4-B · ADR 0095 amend S-AI-1 · Flyway V49·V50 (R0 amend shift · ADR 0102 정합) |
| 2026-05-19 | **드리프트 정정** — §1 현행 1건(`dsl-translate.md`) 단독 LOCK + §3 미래 의제(W4 진입 시 발행)로 5종 분리 | PR #552 cross-validation evidence (ai-engineer audit 보고서 `_workspace/reports/2026-05-19-system-audit-ai-engineer.md` §). 광고-실재 매트릭스 정합 회복. |
