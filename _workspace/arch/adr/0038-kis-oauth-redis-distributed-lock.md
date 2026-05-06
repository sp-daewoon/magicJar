# ADR 0038 — KIS OAuth 24h 토큰 + Redis 분산락 공통 모듈

- 상태: Accepted
- 작성일: TBD
- 결정자: architect
- 관계: ADR 0003(KIS OpenAPI), ADR 0037(KIS Rate Limit)

## 1. 컨텍스트

KIS OAuth 토큰 정책:
- **24시간 유효** — 발급 후 24h 동안 재사용
- **발급 1분당 1회 제한** — 위반 시 `EGW00133`. 단발 위반도 1분 차단

magicJar는 multi-JVM(api·batch·consumer) + 다중 thread에서 KIS REST를 호출. 토큰이 만료되거나 401 응답 시 동시 발급 시도가 race condition으로 EGW00133 위반 → 1분 차단 → 본체 매매·시세 중단으로 이어진다.

해결: **토큰을 단일 store에 두고 multi-JVM single-flight 발급**.

## 2. 결정

### 2-1. KisAuthCommonModule 구조

```
domain/.../market/auth/
├── KisAuthCommonModule              # 도메인 facade
├── KisTokenStorePort                # 토큰 read/write/invalidate
├── KisApprovalStorePort             # WS approval_key 별도 store
└── KisAuthScope                     # 시스템 키 vs 사용자 키 구분
```

```
api/batch/consumer/.../infrastructure/kis/auth/
├── RedisKisTokenStoreAdapter        # Redis HASH + Redisson RLock + RTopic
├── InMemoryKisTokenStoreAdapter     # Redis 다운 시 fallback
└── KisAuthHealthIndicator           # /actuator/health/kisAuth (DEGRADED 노출)
```

### 2-2. 토큰 발급 흐름 (분산락)

```
모듈 A 어댑터 호출 → KisTokenStorePort.get(scope)
    ├─ Redis HASH hit 확인
    │   ├─ 유효 (≤23h) → 반환
    │   └─ 만료 (>23h) 또는 401 invalidate → 발급 path
    │       ↓
    ├─ Redisson tryLock("kis:token:{scope}", waitTime=10s, leaseTime=15s)
    │   ├─ acquire 성공
    │   │   ├─ Redis 재확인 (다른 JVM이 이미 발급?)
    │   │   │   ├─ 발급됨 → 그것 반환 (own 발급 0)
    │   │   │   └─ 미발급 → KIS POST /oauth2/tokenP 호출
    │   │   ├─ Redis HASH SET (24h - 1h 마진 = 23h TTL)
    │   │   ├─ Redisson RTopic publish (다른 JVM 캐시 invalidate)
    │   │   └─ unlock
    │   └─ acquire 실패 (10s 대기 후) → Redis 재read (다른 JVM이 발급 완료)
    └─ 반환
```

### 2-3. 토큰 영속 정책

| 항목 | 정책 |
|---|---|
| **store** | Redis HASH `kis:token:{scope}` |
| **유효** | 24h (KIS 공식) |
| **갱신 마진** | **23h 시점** 사전 갱신 (1h 마진) — manual `ApiKisTokenRefreshScheduler` |
| **invalidate trigger** | 401 Unauthorized 응답 시 즉시 invalidate + 분산락 재발급 |
| **broadcast** | Redisson RTopic — 한 JVM이 invalidate하면 다른 JVM의 in-memory L1 캐시도 invalidate |
| **L1 캐시** | 각 JVM이 in-memory copy (Redis read 빈도 절감). RTopic 수신 시 즉시 무효화 |

### 2-4. EGW00133 처리

토큰 발급 실패가 EGW00133로 떨어진 경우:
- 60초 대기 + 1회 재시도
- 또 실패 시 사용자 path는 명시 에러 (mock 합성 0)
- 백그라운드 작업(Ingestion·backfill)은 5분 후 재시도

### 2-5. Redis 다운 시 Fallback

| 상황 | 정책 |
|---|---|
| Redis 다운 | `InMemoryKisTokenStoreAdapter` 자동 활성. JVM 단위 single-flight (multi-JVM 정합 일시 상실) |
| 로그 | `[kis-auth][redis-down]` ERROR 로그 + 메트릭 (P0 알람 후보) |
| Health Indicator | `/actuator/health/kisAuth` → DEGRADED |
| 복구 | Redis 재기동 후 자동으로 Redis 어댑터 활성 (RTopic 재구독) |

### 2-6. 사용자 키 vs 시스템 키 분리

| Scope | 용도 | 발급 빈도 |
|---|---|---|
| `KisAuthScope.System` | 시세 조회 (모의 키 또는 실전 키 — 데이터) | 1회/24h × 1 share |
| `KisAuthScope.User(userId)` | 사용자 주문·잔고·체결 (사용자별 모의/실전 키) | 1회/24h × user N |

사용자별 분산락 — `kis:token:user:{userId}`. 인증 발급 자체는 사용자별 1분 1회 한도.

## 3. 결과

- **multi-JVM 토큰 발급 1회/24h** (이전 multi-JVM마다 N회 → 1회)
- **EGW00133 발생 0**
- **401 시 자동 재발급 + 분산락 single-flight**
- **모든 KIS 어댑터(api 6 + batch 3 + consumer 3) 단일 store 공유**
- **Redis 다운 시 graceful degradation**

## 4. 참고

- ADR 0003 — KIS OpenAPI
- ADR 0037 — KIS Rate Limit (rate limit과 짝)
- `external-api-policy.md` §2-2 — 운영 매트릭스
- `domain/.../market/auth/KisAuthCommonModule.kt` — 본 ADR 구현 진입점
- Redisson docs — RLock + RTopic + Redis HASH
