# MARKET 진입점 (mock 응답 sample)

> 발행: 2026-05-24 W4 R1 T-AI Phase 1
> 권위: ADR 0026 §2-2 amend (MARKET 진입점 신규) · `MockCommentAdapter` 응답 매트릭스

## 요청

```bash
curl -X POST http://localhost:8080/api/ai/explain \
  -H "Content-Type: application/json" \
  -d '{
    "type": "MARKET",
    "userId": 1,
    "context": {
      "kospiTrend": {"label": "STRONG_UP", "ma_5": 2680.50, "ma_20": 2620.10},
      "kosdaqTrend": {"label": "UP"},
      "nasdaqTrend": {"label": "NEUTRAL"},
      "spxTrend": {"label": "UP"},
      "sectorTopList": [
        {"sector": "반도체", "rs": 1.42},
        {"sector": "2차전지", "rs": 1.28},
        {"sector": "방산", "rs": 1.21},
        {"sector": "조선", "rs": 1.15},
        {"sector": "금융", "rs": 1.08}
      ],
      "maPeriods": [5, 20, 60, 120],
      "rsPeriod": 60
    }
  }'
```

## 응답 (HTTP 200 · status=ACCEPTED)

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "ACCEPTED",
  "refuseCode": null,
  "userMessage": null
}
```

## STOMP `/topic/ai.explain.{requestId}` fanout (mock mode)

```json
{
  "eventId": "abc12345-...",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 1,
  "commentType": "MARKET",
  "provider": "mock",
  "model": "mock-1.0",
  "promptTemplate": "market_v1",
  "content": "[Mock 응답 — provider 미설정 또는 mock 강제 모드]\n\nMARKET 분석을 mock 결과로 응답합니다.\n\n• 시장 위치: 데이터 부족 (실제 LLM 응답 시 컨텍스트 기반 분석)\n• 흐름 평가: 데이터 부족 (실제 응답 시 지표·뉴스 종합 평가)\n• 주의 사항: 본 응답은 mock 입니다. 실제 활용 전 .env 의 API key (ANTHROPIC_API_KEY / GEMINI_API_KEY / OPENAI_API_KEY) 설정 후 재시도 해 주세요.\n\n매매 결정권 0 — advisory only.",
  "tokensIn": 0,
  "tokensOut": 0,
  "latencyMs": 0,
  "costUsd": "0",
  "cached": false,
  "graceful": false,
  "errorMessage": null,
  "occurredAt": "2026-05-24T15:30:00+09:00",
  "schemaVersion": 1
}
```

## 비고

- `MAGICJAR_AI_MOCK_MODE=auto` + 모든 provider key 미설정 시 자동 mock fallback (mock adapter 활성)
- 정상 LLM 응답으로 전환 → `.env` 의 `ANTHROPIC_API_KEY` 설정 + `MAGICJAR_AI_ENABLED=true` + 재기동
- `ai_comment_log` 행 1건 INSERT (V49 · graceful=FALSE · tokens=0 · cost=0 · provider=mock)
- Redis cache (`ai:prompt:cache:{hash}`) 5분 TTL 저장 — 동일 prompt 재호출 시 `cached=true`
