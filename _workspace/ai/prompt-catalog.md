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

## §3. 미래 의제 — T-AI R1 baseline 분석 프롬프트 (W4 진입 시 발행)

> ADR 0026 (AI 옵션 4대 기능)·헌법 룰 5 (W1·W2·W3 LLM provider import 자제)·`feedback_release_policy_patch_first` v3.7=W4 마감 정합. **W4 T-AI baseline 진입 시 ai-engineer 단독 PR로 발행 예정.** 본 §은 광고가 아니라 발행 백로그.

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
| 2026-05-19 | **드리프트 정정** — §1 현행 1건(`dsl-translate.md`) 단독 LOCK + §3 미래 의제(W4 진입 시 발행)로 5종 분리 | PR #552 cross-validation evidence (ai-engineer audit 보고서 `_workspace/reports/2026-05-19-system-audit-ai-engineer.md` §). 광고-실재 매트릭스 정합 회복. |
