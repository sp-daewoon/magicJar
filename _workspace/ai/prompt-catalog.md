# magicJar AI 프롬프트 카탈로그 — T16 R1

> AI 분석 보조 (사용자 트리거 한정, ADR 0046).
> **AI 매매 결정권 0.** 어떤 응답도 BUY/SELL 트리거를 발생시키지 않는다.

## 프롬프트 위치

`api/src/main/resources/prompts/analysis/`

| 파일 | 용도 | promptVersion |
|---|---|---|
| `_system_base.st` | 모든 분석 호출 공통 system 프롬프트 — 한국어 강제·면책 강제·매매 결정 금지 | v1 |
| `symbol_commentary.st` | 종목 코멘트 — 추세·주의·장기 검토 | v1 |
| `company_analysis.st` | 기업 분석 — 펀더멘털 단편(PER·PBR·ROE·시총) | v1 |
| `chart_interpretation.st` | 차트 해석 — 캔들·지표 컨텍스트 | v1 |
| `indicator_meaning.st` | 지표 의미 설명 — 정의·시그널·한계·관련 지표 | v1 |
| `journal_retrospective.st` | 매매일지 복기 — 진입 사유·청산·체크포인트 | v1 |

각 프롬프트 마지막에 자동 prepend되는 면책 문구:
```
[면책] 본 분석은 AI 옵션 보조용이며 매매 결정·추천이 아닙니다. 투자 책임은 사용자에게 있습니다.
```

## 입력·출력 스키마

| 분석 타입 | 입력 (sealed subclass) | 출력 (`AnalysisResult`) | endpoint |
|---|---|---|---|
| SYMBOL_COMMENTARY | `AnalysisRequest.SymbolCommentary` (code · contextSnapshot) | text·confidence·model·promptVersion·tokensUsed·costEstimate·durationMs | `POST /api/analysis/symbol/{code}/commentary` |
| COMPANY_ANALYSIS | `AnalysisRequest.CompanyAnalysis` (code · financialsHint?) | 동상 | `POST /api/analysis/symbol/{code}/company` |
| CHART_INTERPRETATION | `AnalysisRequest.ChartInterpretation` (code · interval · candles · indicators) | 동상 | `POST /api/analysis/symbol/{code}/chart` |
| INDICATOR_MEANING | `AnalysisRequest.IndicatorMeaning` (indicatorId · params) | 동상 | `POST /api/analysis/indicator/{id}/explain` |
| JOURNAL_RETROSPECTIVE | `AnalysisRequest.JournalRetrospective` (tradeId · journalEntry) | 동상 | `POST /api/analysis/journal/{tradeId}/retrospective` |

## 변수 치환 규약

`{{key}}` 단순 치환 (Spring Template + 자체 `AnalysisPromptLoader.render`).
변수 미공급 시 `(데이터 부족)` · `(미상)` · `(없음)` 등으로 fallback. LLM은 "데이터 부족"으로 자연어 처리.

### 공통 변수
- `{{code}}` — 종목 코드
- `{{nameKr}}` — 종목명 (없으면 `(미상)`)
- `{{sector}}` — 섹터

### symbol_commentary 변수
`{{code}} {{nameKr}} {{sector}} {{lastClose}} {{change1d}} {{change5d}} {{patterns}} {{recentNews}}`

### company_analysis 변수
`{{code}} {{per}} {{pbr}} {{roe}} {{marketCap}}`

### chart_interpretation 변수
`{{code}} {{interval}} {{candleTable}} {{indicatorTable}}`
- candles는 마지막 30개로 truncate
- candle 형식: `time | O=open H=high L=low C=close V=volume`

### indicator_meaning 변수
`{{indicatorId}} {{params}}`

### journal_retrospective 변수
`{{symbol}} {{side}} {{entryPrice}} {{exitPrice}} {{pnlPct}} {{rationale}} {{openedAt}} {{closedAt}}`

## 비용 통제

- mock fallback default — `MAGICJAR_AI_ENABLED=false` 시 `MockAnalysisAssistant` 등록 (LLM 호출 0)
- `magicjar.ai.mock-mode=auto` — ANTHROPIC_API_KEY 부재 시 자동 mock
- 모든 호출은 `ai_analysis_log` 테이블 (V17) 기록 — 사용자 비용·토큰 추적
- input·output 토큰은 어댑터에서 길이 기반 추정 (Spring AI v0 — 정확치 회수 어려움). post-MVP에서 raw response metadata 파싱.

## 변경 이력

| 날짜 | 변경 | 비고 |
|---|---|---|
| 2026-05-03 | 초기 v1 — 5종 프롬프트 작성 (T16 R1) | ADR 0046 |
