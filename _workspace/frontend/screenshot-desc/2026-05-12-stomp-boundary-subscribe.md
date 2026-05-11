# 2026-05-12 ADR 0064 §2-C STOMP 5종 boundary subscribe 검증 기록

## 검증 일시
2026-05-12 (KST 기준 장 마감 후 dogfooding)

## Kafka → STOMP 파이프라인 E2E 검증

### 테스트 방법
Kafka console producer로 각 boundary closed 토픽에 테스트 payload 직접 produce.

### 테스트 payload (5m 예시)
```json
{
  "code": "035420",
  "interval": "5m",
  "ts": "2026-05-11T16:41:50Z",
  "open": "58000",
  "high": "58500",
  "low": "57800",
  "close": "58200",
  "volume": 12345,
  "source": "aggregated",
  "publishedAt": "2026-05-11T16:41:50Z"
}
```

### consumer group offset 확인 결과
| 토픽 | 그룹 | CURRENT-OFFSET | LOG-END-OFFSET | LAG |
|------|------|----------------|----------------|-----|
| market.candle.5m.closed.v1 | magicjar-api-stomp-boundary-5m | 2 | 2 | 0 |
| market.candle.15m.closed.v1 | magicjar-api-stomp-boundary-15m | 1 | 1 | 0 |
| market.candle.30m.closed.v1 | magicjar-api-stomp-boundary-30m | 1 | 1 | 0 |
| market.candle.90m.closed.v1 | magicjar-api-stomp-boundary-90m | 1 | 1 | 0 |
| market.candle.1h.closed.v1 | magicjar-api-stomp-boundary-1h | 1 | 1 | 0 |

LAG=0 → 5종 모두 CandleClosedStompListener가 정상 소비 + STOMP fanout 완료.

## 화면 동작 설명 (Vite dev server 5173 기준)

### 화면 1: /symbol/035420 진입 후 IntervalToggle [5분] 선택
- 상태: LWC 차트 5m 캔들 표시 (데이터 있을 경우)
- 차트 우측 상단: "● 실시간 갱신 중" 녹색 배지 표시 (AGGREGATED_INTERVALS 해당)
- 브라우저 console: "[STOMP] SUBSCRIBE /topic/market.candle.5m.035420" 로그 확인
- `useSymbolCandles.ts` debug 로그: boundary closed 수신 시
  "[useSymbolCandles] boundary closed 수신: code=035420 interval=5m ts=... close=..."

### 화면 2: IntervalToggle 전환 시 topic 재구독
- [5분] → [15분] 전환 시:
  - "[STOMP] UNSUBSCRIBE /topic/market.candle.5m.035420" (기존 구독 해제)
  - "[STOMP] SUBSCRIBE /topic/market.candle.15m.035420" (새 구독)
- [5분] → [일] 전환 시: boundary 토픽 구독 없음 (isBoundaryInterval 검사로 enabled=false)
- [일] → [5분] 전환 시: boundary 토픽 재구독

### 화면 3: 빈 배열 상태 (AggregateBackfillRunner 미완)
- 5m 선택 시 차트 영역에 오버레이: "5m 합산 데이터 준비 중 / boundary 마감 시점에 자동 갱신됩니다"
- LWC 차트는 숨겨지지 않고 렌더링 유지 → boundary candle 도착 즉시 series.update() 가능

## typecheck / build 결과
- pnpm typecheck: GREEN (오류 0건)
- pnpm build: GREEN (1.25s, 211 modules transformed)
