# SYMBOL 진입점 — KillSwitch refuse 샘플

> 발행: 2026-05-24 W4 R1 T-AI Phase 1
> 권위: ADR 0094 §4-4-B amend (AI KillSwitch) · ADR 0026 §2-5 amend (graceful refuse)

## 시나리오

`MAGICJAR_AI_KILLSWITCH=true` (운영자 긴급 비용 차단) 상태에서 사용자가 종목 분석을 요청.

## 요청

```bash
curl -X POST http://localhost:8080/api/ai/explain \
  -H "Content-Type: application/json" \
  -d '{
    "type": "SYMBOL",
    "userId": 1,
    "context": {
      "code": "005930",
      "name": "삼성전자",
      "currentPrice": 78500
    }
  }'
```

## 응답 (HTTP 200 · status=REFUSED)

```json
{
  "requestId": "660f9511-f30c-52e5-b827-557766551111",
  "status": "REFUSED",
  "refuseCode": "KILLSWITCH_ENGAGED",
  "userMessage": "AI 기능이 운영자에 의해 일시 차단되었습니다. 잠시 후 다시 시도해 주세요."
}
```

## audit (ai_comment_log · V49)

| 컬럼 | 값 |
|---|---|
| request_id | 660f9511-... |
| user_id | 1 |
| comment_type | SYMBOL |
| provider | none |
| model | none |
| prompt_template | symbol_unresolved (가드 통과 전 단계라 resolve 미수행) |
| tokens_in / tokens_out | 0 / 0 |
| cost_usd | 0.000000 |
| latency_ms | 0 |
| cached | false |
| graceful | **true** |
| error_message | "KILLSWITCH_ENGAGED: AI 기능이 운영자에 의해 일시 차단되었습니다. 잠시 후 다시 시도해 주세요." |

## 동작 검증 포인트

1. **Kafka publish 0건** — `ai.comment.requested.v1` 발행 0건 (가드 통과 전 차단)
2. **LLM 호출 0건** — adapter 호출 0건 (비용 0)
3. **STOMP fanout 0건** — `/topic/ai.explain.{requestId}` 메시지 미발행 (controller 즉시 응답)
4. **본체 흐름 영향 0** — 시그널 / 주문 / 백테스트 path 0 touch
5. **frontend AiCard** — `status=REFUSED` 분기로 사용자 친화 한국어 메시지 표시

## 우선순위 LOCK (4단계 가드)

1. KillSwitch (본 케이스)
2. 전역 비활성 (`MAGICJAR_AI_ENABLED=false` default)
3. 1일 토큰 한도 (default 1000 / 사용자 / 일)
4. prompt template 미존재 (`is_active=TRUE` 행 0건)

각 가드는 즉시 `REFUSED` 응답 + `ai_comment_log` audit 1건 (graceful=TRUE).
