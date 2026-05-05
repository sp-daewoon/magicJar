# ADR 0037 — KIS API Rate Limit 강제 (초당 10건 + 토큰 버킷)

- 상태: Accepted
- 작성일: TBD
- 결정자: architect
- 관계: ADR 0003(KIS OpenAPI), ADR 0028(Watchlist Ingestion), ADR 0036(KIS OAuth 분산락)

## 1. 컨텍스트

KIS OpenAPI 공식 한도:
- **REST 호출 초당 10건** (계정·앱키당) — 위반 시 `EGW00201` 또는 HTTP 429
- 토큰 발급 1분당 1회 (별도, ADR 0036)
- WebSocket 동시 구독 41건

Rate Limit 위반은 단발 에러가 아니라 **계정·앱키 차단**으로 이어질 수 있다. 본체 매매·시세·주문 흐름 전체 중단의 risk.

magicJar는 multi-JVM(api·batch·consumer)에서 KIS REST를 동시 호출하므로 단순 어댑터별 제어로는 부족. **공통 RateLimiter 통과 의무**가 강제되어야 한다.

## 2. 결정

### 2-1. 코드 레벨 강제

| 항목 | 정책 |
|---|---|
| **한도** | **초당 10건** (KIS 공식). 마진 없이 정확히 10건 — 우선순위 큐로 사용자 path 보장 |
| **메커니즘** | **토큰 버킷 (Bucket4j)** 또는 **Resilience4j RateLimiter** 채택. 분산 모드는 Bucket4j Redis backend |
| **공통 진입점** | `domain/.../market/ratelimit/KisRateLimiterPort` + `api/batch/consumer` 각각 어댑터. 모든 KIS REST 호출 직전 `acquire()` 통과 의무 |
| **우회 금지** | 어댑터별 직접 RestClient 호출 금지 — 빌드 게이트(ArchUnit 또는 ktlint custom rule)로 강제 |
| **큐 우선순위** | 사용자 path(현재가·차트·주문) > Watchlist Ingestion > backfill·corp action |
| **leaky bucket** | 큐 가득 시 oldest backfill 요청 drop (사용자 path는 drop 0) |

### 2-2. 위반 시 처리

| 응답 | 처리 |
|---|---|
| `EGW00201` (TPS 초과) | 1초 대기 + 1회 재시도 → 그래도 실패 시 사용자에 명시 에러 (mock 합성 0, ADR 0014 정합) |
| HTTP 429 | EGW00201과 동일 |
| 연속 5분 50% 이상 EGW00201 | **Circuit Breaker 5분 차단** + Slack alert. backfill 큐 자동 pause |

### 2-3. 멀티 JVM 정합

| 모듈 | RateLimiter 인스턴스 |
|---|---|
| api (사용자 REST) | 5/10 share (사용자 path 우선) |
| batch (backfill·corp action) | 3/10 share |
| consumer (Ingestion·룰 엔진) | 2/10 share |

share 합계 = 10 (KIS 한도 정확). 동적 조정은 Redis pub/sub으로 가능 (post-MVP).

또는 **Bucket4j-Redis 분산 모드** — 단일 token bucket을 Redis에 두고 모든 JVM이 동시 acquire. share 분할 불요. **권장**.

### 2-4. 빌드 게이트 (CI 강제)

- ArchUnit test — `RestClient.get()` 직접 호출이 KIS 도메인 어댑터 외에서 발견 시 실패
- ktlint custom rule — `KisRateLimiterPort` 미경유 KIS endpoint 호출 검출
- 단위 테스트 fixture — 초당 10건 초과 호출 시 에러 검증 + CI 실패

## 3. 결과

- **KIS 차단 위험 0**
- **사용자 path 우선순위 보장** — backfill 폭주 시도 매매 흐름 중단 차단
- **multi-JVM 정합** — 풀 한도가 코드 레벨에서 강제

## 4. 참고

- KIS 공식 문서 — REST TPS 한도
- ADR 0028 — Watchlist Ingestion (rate limit 큐 통과)
- ADR 0036 — KIS OAuth 분산락 (rate limit과 짝)
- `external-api-policy.md` §2 — 운영 매트릭스
- `domain/.../market/ratelimit/KisRateLimiterPort.kt` — 본 ADR 구현 진입점
