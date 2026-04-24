---
name: magicjar-spring-ai
description: magicJar에서 Spring AI를 사용한 LLM 통합 전체(프롬프트 설계, 구조화 출력, 감성 분석, 전략 설명, 자연어→조건식 변환)를 구현·튜닝·업데이트·재작성한다. 새 LLM 기능 추가, 프롬프트 수정, 모델 교체, JSON 스키마 변경, Spring AI 버전 업그레이드가 필요할 때 반드시 사용. "프롬프트 고쳐줘", "LLM 기능 추가", "감성 분석 개선"도 포함.
---

# magicjar-spring-ai — Spring AI 통합

## 기본 원칙

- **모델 추상화.** `org.springframework.ai.chat.model.ChatModel` / `ChatClient` 중심. 직접 SDK 호출 금지.
- **기본 공급자 = Anthropic Claude.** `spring-ai-anthropic` 사용. 대체 가능하도록 `application-ai.yml` 로 분리.
- **프롬프트 파일화.** `resources/prompts/{feature}/{variant}.st` 형식 (SpringTemplate) — 인라인 프롬프트 금지.
- **구조화 출력 필수.** `BeanOutputConverter<T>` 또는 `@JsonSchema` 기반. 문자열 파싱 금지.
- **설명 가능성.** 모든 출력 DTO에 `reasoning: String` 필드 포함.

## 4대 기능 스펙

### 1) 뉴스/공시 감성 분석

**입력:** `NewsItem`(title, body, instruments[])
**출력 스키마(`SentimentAnalysis`):**
```json
{
  "overallSentiment": "POSITIVE|NEUTRAL|NEGATIVE",
  "score": -1.0 ~ 1.0,
  "perInstrument": [{"code": "005930", "sentiment": "POSITIVE", "score": 0.7}],
  "eventTags": ["earnings","merger","regulation",...],
  "tradingHint": "BUY_BIAS|SELL_BIAS|NONE",
  "reasoning": "자연어 근거 1~3문장"
}
```

**프롬프트 파일:** `resources/prompts/sentiment/v1.st`

### 2) 전략 설명

**입력:** `BacktestResult` + 사용자 프로필(`riskTolerance`, `preferredSectors`)
**출력:** 마크다운 문자열. 섹션: 요약, 핵심 지표, 매매 근거, 리스크, 다음 단계.
**토큰 상한:** 출력 600 토큰 이내. 초과 시 압축 재호출.

### 3) 포트폴리오 리밸런싱 조언

**입력:** 현재 `Position[]`, 목표 자산배분, 시장 스냅샷
**출력 스키마(`RebalancingProposal`):**
```json
{
  "suggestions": [{"code":"005930","action":"REDUCE","deltaQty":-5,"rationale":"..."}],
  "riskBefore": {"concentration": 0.45},
  "riskAfter":  {"concentration": 0.28},
  "reasoning": "..."
}
```

### 4) 자연어 → 조건식 DSL

**입력:** 사용자 문장 (예: "최근 3일 RSI 과매도 풀리고 거래량 2배 이상")
**출력 스키마(`ConditionDsl`):**
```json
{
  "expression": "RSI(14, offset=0) > 30 AND RSI(14, offset=3) < 30 AND VOLUME > SMA(VOLUME,20)*2",
  "confidence": 0.82,
  "ambiguities": ["'최근 3일'의 기준을 영업일로 해석"],
  "reasoning": "..."
}
```

**중요:** 출력 후 반드시 strategy-engine의 DSL 파서로 검증. 실패 시 프롬프트에 에러 메시지와 함께 1회 재요청.

## 공통 패턴

```java
@Service
public class SentimentAnalyzer {
  private final ChatClient chat;
  private final BeanOutputConverter<SentimentAnalysis> converter =
      new BeanOutputConverter<>(SentimentAnalysis.class);

  public SentimentAnalysis analyze(NewsItem news) {
    var prompt = PromptTemplate.from(resource("prompts/sentiment/v1.st"))
                    .render(Map.of("news", news, "format", converter.getFormat()));
    var response = chat.prompt(prompt).call().content();
    return converter.convert(response);
  }
}
```

## 캐싱·비용

- Redis 캐시 키: `ai:sentiment:{newsId}`, TTL 24h
- 같은 `(promptVersion, inputHash)` 조합은 재호출 금지
- 토큰 사용량 집계: Micrometer 카운터 `ai.tokens.{feature}.{direction}`

## 재실행/업데이트 지침

- 프롬프트 수정 시 파일명에 버전 포함(`v2.st`), 기존 버전 보존.
- 스키마 변경은 하위호환 우선, 불가능 시 DTO 버전 필드 추가.
- 모델 교체 시 동일 입력에 대해 품질 비교(A/B) 후 `_workspace/ai/model-bench.md` 기록.
- Spring AI 버전업 시 WebFetch(context7 `query-docs`)로 breaking change 확인.

## 에러 처리

- LLM 타임아웃/5xx: 1회 재시도, 2회 연속 실패면 `null` 반환 + 플래그 `aiFailed=true`
- JSON 파싱 실패: raw 응답 로깅, 프롬프트에 포맷 예시 강화한 뒤 1회 재호출
- 민감한 사용자 데이터(계좌번호 등)는 프롬프트에 포함 금지, 마스킹 후 전달
