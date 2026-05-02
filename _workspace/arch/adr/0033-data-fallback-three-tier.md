# ADR 0033: 시세 데이터 Fallback (3-tier: live → cache → last-day-close)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §A.2·§3-2·§외부.KIS
- 트랙: T2 (W1)
- 선행: ADR 0031, 0032

---

## 1. 맥락

KIS WebSocket은 분당 한도·세션 한도·인터넷 끊김으로 실시간 tick이 끊길 수 있다. KIS REST 도 ratelimit·점검·EGW 라우팅 오류로 일시 불가. 사용자 비전(ADR 0031): "AI·외부 API·인터넷 끊겨도 시스템은 동작한다 — 영속 candle + 마지막 영업일 close 데이터로 차트·지표·screener 정상". 단순한 "시세 끊김 → 빈 화면"은 v2 수용 불가.

## 2. 결정

3-tier fallback 적용:

| Tier | source | 트리거 | TTL/표시 |
|---|---|---|---|
| **T1 live** | KIS WebSocket tick (또는 mock profile MockTickGenerator) | session=Open + WS 정상 | 실시간, stale=false |
| **T2 cache** | Redis `quote:last:{code}` (마지막 tick 보존, 7일) | T1 끊김 (WS 재연결 중·세션 한도 등) — 10초 무신호 시 | stale=true, "캐시 데이터 N초 전" |
| **T3 last-day-close** | Redis `quote:lastbusinessday:{code}:{yyyyMMdd}` (14일) 또는 영속 candle 마지막 일봉 close | session=Closed (장외·비영업일) | stale=true, "휴장 중, 장 마감 14:30 기준" |

`TickFallbackEmitter`는 10초 주기 — T1 신호 없거나 session=Closed 시 T2/T3 source의 TickEvent를 합성 publish. Kafka 토픽 `market.tick.v1` 그대로 발행, payload에 `stale: boolean` + `asOf: Instant` + `source: "live"|"cache"|"last-day-close"` 추가.

### 2-1. UI 표시

- frontend: stale=true 시 차트 우상단 배지 (회색 — "캐시" 또는 "휴장 중") + 가격 색상 흐림.
- 새로고침·재진입 시 캐시 cold 상태면 "데이터 수집 중" 명시 (영업일 첫 tick 도달 전).

### 2-2. Cold cache 전략

배포 직후·Redis flush 후엔 `quote:last:*`·`quote:lastbusinessday:*` 둘 다 빈 상태. 이 때 batch DailyCandleBackfillJob의 마지막 일봉 close를 사용하거나, 영속 candle 테이블의 latest close를 직접 조회. 이 경로의 TTL=session=Closed 동안 영구.

### 2-3. Profile 분기 (master §7)

- mock: T1 = MockTickGenerator. T2/T3 비활성 (T1 항상 정상)
- dev: T1 KIS WS 모의. session=Closed 시 T3 활성, 끊김 시 T2 활성.
- prd MOCK_KIS / REAL_KIS: 동일 정책, source 차이만.

## 3. 결과·영향

- 영속 candle + Redis last 두 source가 fallback의 진입점 — 둘 다 정상 운영 의무 (모니터링 항목).
- **본체 흐름은 stale 데이터로도 정상 평가** — DSL 룰 평가·screener·signal 모두 stale 데이터 OK. 단 사용자 UI에 stale 표시 의무.
- **주문 흐름은 stale 데이터에선 차단** — Risk Guard chain의 PriceSanity가 stale tick → reject (ADR 0042).

## 4. 검토 대안

- **(a) live 끊기면 차트 정지** — 사용자 비전 위반.
- **(b) live 끊기면 mock 폴백** — 가짜 데이터로 사용자 혼란. 거부.
- **(c) 3-tier fallback (채택)** — stale 명시 + 본체 흐름 영향 0.

## 5. References

- master-architecture.md §A.2, §3-2, §외부 매트릭스
- ADR 0031 (오프라인 1순위)
- ADR 0032 (Market Session FSM)
- ADR 0042 (Risk Guard PriceSanity)
- 트랙2 spec §3, §5 (TickFallbackEmitter 설계)
