# ai 모듈 — Phase B-2 pre-QA 작업 요약

**시점:** 2026-04-25 17:20 KST · **상태:** Phase C(QA) 검수 직전
**브랜치:** `agent/ai-engineer/spring-ai-core-services` · **PR:** #4 (base=qa)
**에이전트:** ai-engineer · **스킬:** magicjar-spring-ai

## 무엇을 작업했나

| 영역 | 산출물 |
|------|-------|
| 클라이언트 | `SpringAiClient`(Anthropic Claude) · `OllamaFallbackClient` · `AiClientComposite`(Resilience4j CircuitBreaker) |
| AI 서비스 | `SentimentAnalyzer` · `StrategyExplainer` · `RebalancingAdvisor` · `ConditionDslTranslator` · `TradeReviewer` + `TradeReviewEventListener` |
| 설정 | `AiProperties` · `AiModuleConfig` · `AiFeatureFlag` |
| 프롬프트 | `prompts/*.st` 5개 (한국어, JSON 출력 스키마 명시) |
| 테스트 | 4개 unit (`SentimentAnalyzerTest`, `StrategyExplainerTest`, `ConditionDslTranslatorTest`, `TradeReviewEventListenerTest`) — MockK 기반, API 키 불필요 |
| 인프라 | `AiClient` 포트 구현 → app의 `NoOpAiClient` 자동 대체 (`@ConditionalOnMissingBean`) |

## 빌드 상태
- `./gradlew :ai:build` → **BUILD SUCCESSFUL**
- `./gradlew ktlintCheck` → **BUILD SUCCESSFUL**

## 의존 / 경계
- core 포트만 의존: `core.port.AiClient`, `core.event.AiReviewRequestedEvent`, `core.port.TradeJournalRepository`(주입만, 구현은 strategy 모듈)
- 외부 모듈 직접 import 없음

## QA 체크 포인트
- `ANTHROPIC_API_KEY` 미설정 시 NoOpAiClient 유지로 부팅 성공 여부
- Anthropic prompt caching 메타데이터(`cacheControl: ephemeral`) 적용 확인
- `AiReviewRequestedEvent` 리스너가 `TradeJournalRepository`를 통해 `aiReview` 업데이트하는 플로우(strategy 통합 후)
- 5개 프롬프트의 토큰/비용 추정 (`_workspace/ai/cost-estimation.md`)

## 알려진 TODO (후속)
- Spring AI 1.0 OllamaOptions builder API 회피로 모델/temperature는 application.yml에서 주입(런타임 변경 불가) — 필요 시 반사적으로 builder 호출 추가
- Spring AI batch / streaming 미사용 (단일 chat call만)
