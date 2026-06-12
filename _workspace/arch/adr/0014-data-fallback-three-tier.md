# ADR 0014: 시세 데이터 Fallback (3-tier: live → cache → last-day-close)

- 상태: Accepted (2026-06-13 현행화 amend — mock profile·MockTickGenerator v1/v2 잔재 제거 + source 표기 현행 정정 + KIS-only 정책 정합)
- 작성일: 2026-05-03 (발행) · 2026-06-13 (amend)
- 결정자: architect (opus 4.7 1M 발행 · opus 4.8 1M amend)
- master §위치: `docs/architecture/master-architecture.md` v2 §A.2·§3-2·§외부.KIS
- 트랙: T-MS1 (Market Session FSM, W1) + T-W1 (KIS HTTP Adapter, W1)
- 선행: ADR 0001, 0032

---

## 1. 맥락

KIS WebSocket은 분당 한도·세션 한도·인터넷 끊김으로 실시간 tick이 끊길 수 있다. KIS REST 도 ratelimit·점검·EGW 라우팅 오류로 일시 불가. 사용자 비전(ADR 0001): "AI·외부 API·인터넷 끊겨도 시스템은 동작한다 — 영속 candle + 마지막 영업일 close 데이터로 차트·지표·screener 정상". 단순한 "시세 끊김 → 빈 화면"은 v2 수용 불가.

## 2. 결정

3-tier fallback 적용:

| Tier | source 표기 | source | 트리거 | TTL/표시 |
|---|---|---|---|---|
| **T1 live** | `"live"` | KIS WebSocket tick (`H0UNCNT0` 통합 체결가 — ADR 0124) | session=Open + WS 정상 | 실시간, stale=false |
| **T2 polling** | `"polling"` | KIS REST(`FHKST01010100` `inquire-price`) 5초 polling → Redis `quote:last:{code}` (마지막 값 보존, 7일) | T1 끊김 (WS 재연결 중·세션 한도 등) — 10초 무신호 시 | stale=true, "폴링 데이터 N초 전" |
| **T3 last-day-close** | 전일종가 | Redis `quote:lastbusinessday:{code}:{yyyyMMdd}` (14일) 또는 영속 candle 마지막 일봉 close | session=Closed (장외·비영업일) | stale=true, "휴장 중, 장 마감 기준" |

`TickFallbackEmitter`는 10초 주기 — T1 신호 없거나 session=Closed 시 T2/T3 source의 TickEvent를 합성 publish. Kafka 토픽 `market.tick.v1` 그대로 발행, payload에 `stale: boolean` + `asOf: Instant` + `source: "live"|"polling"|전일종가` 추가. **데이터 출처는 전 tier KIS OpenAPI 단일 출처**(KIS-only 정책 — Yahoo 등 외부 fallback 폐기). 시세·캔들·호가는 모두 실전 키로 수집한다.

### 2-1. UI 표시

- frontend: stale=true 시 차트 우상단 배지 (회색 — "캐시" 또는 "휴장 중") + 가격 색상 흐림.
- 새로고침·재진입 시 캐시 cold 상태면 "데이터 수집 중" 명시 (영업일 첫 tick 도달 전).

### 2-2. Cold cache 전략

배포 직후·Redis flush 후엔 `quote:last:*`·`quote:lastbusinessday:*` 둘 다 빈 상태. 이 때 batch DailyCandleBackfillJob의 마지막 일봉 close를 사용하거나, 영속 candle 테이블의 latest close를 직접 조회. 이 경로의 TTL=session=Closed 동안 영구.

### 2-3. Profile (현행 — mock 부팅 path 폐기)

default profile = `dev,real-kis` 단일 경로다. mock 부팅 path는 폐기됐고, mock은 JVM test fixture 전용이며 사용자 흐름에서 사용 금지다(`feedback_no_mock_data` 정합). 모든 profile에서 시세 출처는 실전 KIS 키이며(정보 수집은 모의/실전 분기 없음 — `feedback_kis_key_realonly_for_data`), 모의/실전 분기는 주문 액션에만 적용된다. T1 KIS WS 정상 → session=Closed 시 T3, 끊김 시 T2 polling 활성. 사용자 흐름에서 backend 0건/에러 시 mock 합성 없이 "데이터 없음"을 명시한다.

## 3. 결과·영향

- 영속 candle + Redis last 두 source가 fallback의 진입점 — 둘 다 정상 운영 의무 (모니터링 항목).
- **본체 흐름은 stale 데이터로도 정상 평가** — DSL 룰 평가·screener·signal 모두 stale 데이터 OK. 단 사용자 UI에 stale 표시 의무.
- **주문 흐름은 stale 데이터에선 차단** — Risk Guard chain의 PriceSanity가 stale tick → reject (ADR 0024).

## 4. 검토 대안

- **(a) live 끊기면 차트 정지** — 사용자 비전 위반.
- **(b) live 끊기면 가짜·합성 데이터 폴백** — 사용자 혼란. 거부(mock 합성 금지 정책 정합 — 차단된 경우 "데이터 없음" 명시).
- **(c) 3-tier fallback: live → polling → 전일종가 (채택)** — stale 명시 + 본체 흐름 영향 0 + 전 tier KIS-only 출처.

## 5. References

- master-architecture.md §A.2, §3-2, §외부 매트릭스
- ADR 0001 (오프라인 1순위)
- ADR 0013 (Market Session FSM)
- ADR 0024 (Risk Guard PriceSanity)
- ADR 0124 (KIS WS H0UNCNT0 단일 채널 — T1 live source)
- 트랙2 spec §3, §5 (TickFallbackEmitter 설계)

## 6. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-03 | 발행 — 3-tier fallback (live → cache → last-day-close) (architect, opus 4.7 1M) |
| 2026-06-13 | **현행화 amend** (최고 관리자 결재) — mock profile·MockTickGenerator v1/v2 잔재 제거(mock 부팅 path 폐기 정합) + source 표기 `"live"`\|`"polling"`\|전일종가로 정정 + T1 live source = H0UNCNT0(ADR 0124) + KIS-only 단일 출처 명시. 3-tier 골격은 현행 유효 유지. (architect, opus 4.8 1M) |
