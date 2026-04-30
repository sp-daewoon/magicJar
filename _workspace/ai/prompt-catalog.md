# Prompt Catalog — magicJar AI

> 모든 LLM 프롬프트는 `domain/src/main/resources/prompts/*.st`로 관리. 인라인 문자열 금지.
> 출력은 JSON Schema 기반 구조화 파싱(`@JsonProperty` DTO + Jackson).

## 사용 중인 프롬프트

| 파일 | 용도 | 호출 service | 입력 | 출력 스키마 | 활성 |
|------|------|--------------|------|-------------|------|
| `prompts/sentiment-analyzer.st` | 뉴스 감성 분석 (단건·다건) | `AnthropicNewsSentimentAnalyzer`(infra, 단건) / `SentimentAnalyzer`(application, 다건) | NewsRawEvent → 1건 또는 N건 | `output-schemas/sentiment-single.json`, `output-schemas/sentiment-batch.json` | ON (토글 시) |
| `prompts/strategy-explainer.st` | 매매 신호 자연어 설명 | `StrategyExplainer` | Signal + 백테스트 결과 | TBD | OFF (PR #4 stub) |
| `prompts/condition-dsl-translator.st` | 자연어 → 조건식 DSL | `ConditionDslTranslator` | 자연어 쿼리 | TBD | OFF (PR #4 stub) |
| `prompts/rebalancing-advisor.st` | 포트폴리오 리밸런싱 | `RebalancingAdvisor` | 포지션 + 시장 + 목표 | TBD | OFF (PR #4 stub) |
| `prompts/trade-reviewer.st` | 매매일지 자동 리뷰 | `TradeReviewer` | 거래 기록 | TBD | OFF (PR #4 stub) |

## 변경 이력

| 날짜 | 변경 | 사유 |
|------|------|------|
| 2026-04-25 (PR #4) | 초기 5종 프롬프트 등록 | spring-ai-core-services |
| 2026-04-30 (news-sentiment-v1) | `sentiment-analyzer.st`에 단건 스키마(`{sentiment, confidence, rationale}`) 추가 — `AnthropicNewsSentimentAnalyzer`가 ChatClient prompt에 동적 주입 | Phase E AI 1차 통합 — Anthropic Claude haiku 4.5 활성화 |

## 단건 분석 스키마 강제 방식

`AnthropicNewsSentimentAnalyzer.wrapWithSchema()`는 base prompt 뒤에 다음을 append한다:

```
## 단건 분석 출력 포맷 (반드시 준수)
아래 JSON 스키마를 엄격히 따르는 **JSON 하나**만 반환하라. 주석/마크다운/설명문 금지.
```json
{
  "sentiment": "POSITIVE|NEUTRAL|NEGATIVE",
  "confidence": 0.0,
  "rationale": "한국어 1~2문장 근거"
}
```
- sentiment: POSITIVE / NEUTRAL / NEGATIVE 중 하나
- confidence: 0.0 ~ 1.0 사이 실수
- rationale: 한국어 1~2 문장, 본문 근거 인용
```

LLM이 ```` ```json ```` 펜스를 두르더라도 `extractJson()`이 본문만 추출.
parsing 실패 시 1회 재시도(LLM 호출 단에서) 후 `null` 반환 — 호출 측은 결과 없이 진행 가능.

## 토큰 비용 의식

- 단건 입력: 본문 1500자 truncate (`MAX_BODY_CHARS`)
- 다건 입력: 최대 20건 (`SentimentAnalyzer.MAX_NEWS`)
- 응답 max-tokens: 1024
- 모델: `claude-haiku-4-5-20251001` (감성 분석 충분, 비용 최소화)
- 동일 종목 동일 일자 뉴스 캐시: TODO (Redis, 후속 PR)
