# ADR 0026: AI 옵션 보조 (사용자 트리거 한정)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 사용자 비전(2026-05-02) + 결정(default 12 §5) 정합
- master §위치: `docs/architecture/master-architecture.md` v2 §1·§3-3·§D.17·§7
- 트랙: T16 (W4)
- 선행: ADR 0001, 0041 (Signal Engine 룰 only)
- 폐기 대체: 1차안 LLM 공급자 ADR (본 ADR로 superseded)

---

## 1. 맥락

이전 1차안 LLM 공급자 ADR은 "Anthropic + Ollama 폴백, 매매 의사결정 흐름의 노드"를 가정했다. 사용자(2026-05-02) 명시:

> "AI가 그냥 이거 산다 만다의 결정권을 갖는 시스템이 아님 ... AI는 옵션이다 ... AI가 해당 추출 종목 및 차트의 지표 여러 분석을 옵션으로 코멘트 밑 기업분석을 목표로함"

AI = **사용자 트리거 한정 분석 코멘트**. 매매 트리거 0.

## 2. 결정

### 2-1. AI provider 1차 default (default 12 §5)

**Anthropic Claude** (이미 sentiment 트랙에서 사용 중). dual provider도 가능:
- Anthropic Claude (1차)
- Google Gemini (free tier — dev 디버깅·1차 무료 옵션)
- OpenAI GPT (선택 — 백업·비교)

`MAGICJAR_AI_PROVIDER=anthropic|gemini|openai` env로 토글.

### 2-2. 호출 트리거

**사용자 명시 클릭만** — 자동 호출 0. UI 진입점:

| 위치 | 버튼 | 입력 | 응답 |
|---|---|---|---|
| `/symbol/:code` 종목 상세 | "AI 종목 분석" | code + 현재 지표·패턴·뉴스 컨텍스트 | 코멘트 (시장 위치·재무 흐름·주의 사항) |
| `/symbol/:code` 차트 | "차트 해석" | code + timeframe + 표시 지표 | 차트 흐름 코멘트 (추세·과열·과매도) |
| `/strategy/:id` 전략 | "전략 평가" | 전략 DSL + 백테스트 결과 | 전략 강·약점 코멘트 |
| `/dashboard` 시그널 카드 | "이 시그널 해석" | 시그널 + 종목 상태 | 코멘트 |

**매매 트리거 0** — UI에서 "이 시그널을 받아 자동 매수" 같은 결정 버튼 X.

### 2-3. Domain

```kotlin
// domain/.../ai/
data class AiCommentRequest(
    val type: AiCommentType,            // SYMBOL · CHART · STRATEGY · SIGNAL · INDICATOR
    val context: Map<String, Any>,      // code · indicators · patterns · news 등
    val userId: Long,
    val requestId: UUID,
)

data class AiCommentResponse(
    val requestId: UUID,
    val provider: String,
    val model: String,
    val content: String,                // 자연어 코멘트
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Long,
    val cached: Boolean,
)

interface AiCommentProvider {
    val name: String
    fun comment(request: AiCommentRequest, prompt: String): AiCommentResponse
}
```

### 2-4. 흐름

```
사용자 클릭 → POST /api/ai/explain (api)
   ↓
api MagicJarController → Kafka ai.comment.requested.v1
   ↓
consumer AiCommentDispatcher
   ↓ (Redis ai:prompt:cache:{hash} 5분 hit 검사)
   ↓ MISS → AnthropicCommentAdapter.comment()
   ↓
LLM 호출 (Anthropic SDK) → 응답
   ↓
ai_comment_log INSERT (V20)
   ↓
Kafka ai.comment.responded.v1
   ↓
api STOMP /topic/ai.explain.{requestId}
   ↓
frontend AiCard 표시
```

### 2-5. 비용 통제

- 5분 prompt cache (`ai:prompt:cache:{templateHash}`) — 동일 입력 재사용
- `MAGICJAR_AI_OPTION_ENABLED=false` default — 사용자 명시 활성 후 사용
- audit 테이블 `ai_comment_log` (V20) — model·tokens·cost_usd 기록
- 1일 1사용자 토큰 한도 (post-MVP)

### 2-6. AI 응답 실패 시 본체 영향

- LLM 응답 실패·타임아웃 → frontend "AI 응답 없음" 표시
- **본체 흐름 영향 0** — 시그널·주문·screener 모두 정상 작동
- Circuit Breaker (Resilience4j) — 30s open + 3회 retry

### 2-7. prompt 표준 (post-W4 보강)

`prompt_template` 테이블 (V20):
- type · version · template_text · variables_schema · created_at
- 같은 type의 prompt는 version으로 관리 — A/B 비교·드리프트 추적
- 변수 치환 — `{{code}}`·`{{indicators}}`·`{{news}}`

## 3. 결과·영향

- W1·W2·W3 트랙 코드에 AI provider import 0건 — 트랙 R3 진입 시 architect grep 검증.
- W4 진입 시 본 ADR 의존 — T16·T17 트랙.
- 사용자 비용 통제 — `MAGICJAR_AI_OPTION_ENABLED=false` default.

## 4. 검토 대안

- **(a) AI 자동 호출** (1차안) — 사용자 비전 위반.
- **(b) AI fully 배제** — 사용자 의도 "AI 옵션 보조는 활용" 정합 X.
- **(c) 사용자 트리거 한정 + 5분 cache + 본체 영향 0 (채택)** — 사용자 비전 정합.

## 5. References

- master-architecture.md §1, §3-3, §D.17, §7
- ADR 0001 (비전 v2)
- ADR 0023 (Signal Engine 룰 only)
- ADR 0027 (MCP server)
- 폐기 대체: 1차안 LLM 공급자 ADR (본 ADR로 superseded)
- Anthropic API: https://docs.anthropic.com/
