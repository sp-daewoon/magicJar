# R3-5c-3 — KIS 토큰 일원화 + Redis 분산 lock (Spec)

- 작성일: 2026-05-05
- 작성자: architect (orchestrator=architect, ADR 0030)
- 상태: Draft → User Review
- 트랙: R3-5c (Phase F+ — KIS 외부 API 정책 정공법 마무리)
- 선행 ADR: 0050 §2(KIS direct path), 0021(외부 어댑터), 0024(MSA), 0025(공유 Redis), 0043(multi-tenant), 0046(KIS HTTP precision)
- 신규 ADR 후보: R3-5c-3-a "KIS 토큰 단일 store + Redis 분산 보호", R3-5c-3-b "KIS WS approval 별도 store", R3-5c-3-c "KIS 설정 prefix 일원화"
- 관련 문서: `docs/architecture/external-api-policy.md` §2, `_workspace/arch/adr/0050-user-chart-cache-redis-zset.md`, `docs/superpowers/specs/2026-05-02-track1-kis-order-design.md`

---

## 0. 개요

R3-5c-1·5c-2에서 도입한 `KisTokenStorePort` + `InMemoryKisTokenStoreAdapter`는 **api 모듈의 KisChartCandleAdapter 1경로만** 통합되어 있다. 나머지 **10경로 (api 5 legacy + batch 3 + consumer 2)** 는 여전히 어댑터별 자체 `cachedToken` 또는 모듈 자체 `KisTokenStore`/`KisHttpSession`/`KisWsApprovalProvider`를 들고 있다. 정책(만료 마진), 동시 발급 보호, 설정 prefix가 모두 다르다. 결과: KIS 한도 "1분 1회 발급"을 운영 중에 어기기 쉽다.

본 사이클은 11경로(REST 9 + WS 2)를 단일 추상 + Redis 분산 single-flight로 통합하고, 같은 작업으로 multi-tenant(시스템 키 / 사용자 키) scope 분리를 처음부터 적용한다. **Phase 분할 없이 단일 PR/단일 사이클**로 처리한다(사용자 결정 A.1). PR 분할은 plan 작성 시 architect가 판단(사용자 결정 A.3).

본 spec은 **설계 문서**다. 코드 변경 0건. plan은 별도 사이클.

---

## §1. 배경 / 문제 정의

### 1-1. 11경로 진단 매트릭스

(R3-5c 진행 중 architect 진단 보고서 기준. 절대경로:line 인용.)

#### REST 9경로 — 시스템 키 (모드 단위 단일 인증)

| # | 경로 | 모듈 | line | 토큰 source | 만료 마진 | 발급 보호 | 설정 prefix |
|---|---|---|---|---|---|---|---|
| 1 | `KisChartCandleAdapter` | api | infrastructure/kis/KisChartCandleAdapter.kt:48 | **`KisTokenStorePort` (R3-5c-2 통합)** | 1h (port) | `synchronized` (port) | `spring.kis.*` |
| 2 | `KisInstrumentInfoAdapter` | api | infrastructure/kis/KisInstrumentInfoAdapter.kt:59,245 | self `cachedToken` + `issueToken()` | **없음** (null만 invalidate) | **없음** (`@Volatile` only) | `magicjar.kis.*` |
| 3 | `KisInvestorTrendAdapter` | api | infrastructure/kis/KisInvestorTrendAdapter.kt:56,143 | self `cachedToken` + `issueToken()` | **없음** | **없음** | `magicjar.kis.*` |
| 4 | `KisMarketIndexAdapter` | api | infrastructure/kis/KisMarketIndexAdapter.kt:52,111 | self `cachedToken` + `issueToken()` | **없음** | **없음** | `magicjar.kis.*` |
| 5 | `KisOrderbookAdapter` | api | infrastructure/kis/KisOrderbookAdapter.kt:60,138 | self `cachedToken` + `issueToken()` | **없음** | **없음** | `magicjar.kis.*` |
| 6 | `KisRecentTradesAdapter` | api | infrastructure/kis/KisRecentTradesAdapter.kt:54,141 | self `cachedToken` + `issueToken()` | **없음** | **없음** | `magicjar.kis.*` |
| 7 | `RealKisRestAdapter` | batch | infrastructure/kis/RealKisRestAdapter.kt:63,233 | self `cachedToken` + `issueToken()` | **없음** | **없음** | `magicjar.kis.*` (real-kis profile) |
| 8 | `KisCorpActionTokenHolder` | batch | infrastructure/kis/corp_action/KisCorpActionTokenHolder.kt:24,44 | self `cachedToken` + `issueToken()` | **없음** | **없음** | `magicjar.kis.*` |
| 9 | `MinuteCandleBackfillJob` | batch | job/backfill/MinuteCandleBackfillJob.kt:88,319 | self `cachedToken` + `issueToken()` | **없음** | **없음** | `magicjar.kis.*` |

#### REST 사용자 키 — 1경로 (계좌 단위 인증)

| # | 경로 | 모듈 | line | 토큰 source | 만료 마진 | 발급 보호 | 설정 prefix |
|---|---|---|---|---|---|---|---|
| 10 | `KisHttpSession` (RealKisOrderAdapter 등) | consumer | infrastructure/kis/KisHttpSession.kt:25,42 | `ConcurrentHashMap<appKey, CachedToken>` + `refresh()` | **60s** | **없음** (재진입 동시 발급 가능) | `kis.*` (consumer 단독) |

`KisHttpSession`은 `acquireToken(appKey, appSecret)` per-appKey 캐시이므로 **사용자별 키 진입 path와 호환**되지만 락이 없어 동시 갱신 시 `refresh()` 두 번 발생.

#### WebSocket approval — 2경로

| # | 경로 | 모듈 | line | scope | 만료 마진 | 발급 보호 | 설정 prefix |
|---|---|---|---|---|---|---|---|
| WS-1 | 시세 WS approval (`H0UNCNT0/H0UNASP0/H0UPCNT0`) | consumer | (현재 별도 holder 없음 — 시세 WS path 분기) | 시스템 (모드 단위) | 23h hardcoded | 없음 | `kis.*` |
| WS-2 | `KisWsApprovalProvider` (체결통보 `H0STCNI0/9`) | consumer | infrastructure/kis/KisWsApprovalProvider.kt:24,55 | **사용자 (htsId·계좌 종속)** | **23h** | `AtomicReference` (단일 JVM only) | `kis.*` |

**합계: REST 10 (시스템 9 + 사용자 1) + WS 2 = 11경로.**

> 본 spec의 "사용자 키 2경로"(배경 컨텍스트 §B)는 **REST 사용자 1 (KisHttpSession) + WS 사용자 1 (KisWsApprovalProvider) = 2경로**의 합산이다. REST 시스템 9경로는 시세·메타·지수·호가·체결·BackFill·CorpAction을 포함한다.

### 1-2. 정책 분기 3종

| 분기 | 현황 | 영향 |
|---|---|---|
| **만료 마진** | 1h (`InMemoryKisTokenStoreAdapter`·`KisTokenStore`) / 60s (`KisHttpSession`) / 없음 (legacy 어댑터 7 + WS approval 23h hardcoded TTL) | 외부 API 정책(`docs/architecture/external-api-policy.md` §2-2: "23h 시점 갱신 유도 — 1h 마진") 미준수. KIS 만료(24h) 직전 호출 시 401 + EGW00133(1분 1회) 폭주 가능. |
| **동시 발급 보호** | `synchronized`(InMemory port·KisTokenStore) / `AtomicReference`(WS approval) / **없음**(legacy 어댑터 9) | 동일 모듈 내 멀티스레드 → 토큰 발급 race. **모듈 간(api·batch·consumer 다중 JVM)** 은 어떤 보호도 없음. KIS "1분 1회 발급" 한도 위반 → EGW00133 폭주 → 운영 중 시세·주문 모두 차단. |
| **설정 prefix** | `spring.kis.*` (api KisChartCandleAdapter·InMemoryKisTokenStoreAdapter) / `magicjar.kis.*` (api legacy 5 + batch 3) / `kis.*` (consumer KisProperties) | 같은 키를 3 종류 prefix로 노출 → docker-compose env 매핑 분기 + 사용자 인지 부담 + 정책 변경 시 3곳 갱신. |

### 1-3. KIS API 제약 (불변)

`docs/architecture/external-api-policy.md` §2-1:

| 항목 | KIS 한도 |
|---|---|
| 토큰 발급 | **1분당 1회** (계정·앱키당) — 위반 시 EGW00133 |
| 토큰 유효 | 24시간 |
| 토큰 refresh API | **없음** (`/oauth2/tokenP` 단일, refresh_token grant 미지원) |
| REST 호출 | 초당 20건 (TPS) — 위반 시 EGW00201 |
| 모의/실전 키 분리 | 별도 발급 + base URL 다름 |

→ 우리 정책: 75% 마진(REST 초당 15건) + 23h 갱신 유도(1h 마진).

### 1-4. v2.0.1 기준 영향도

| 영역 | 영향 |
|---|---|
| 사용자 차트 화면 | KIS direct path (R3-5c-1·R3-5a). 11경로 미통합 시 다종목 동시 진입 시 토큰 발급 race. ADR 0050 §2-A 정합 위반. |
| 룰 엔진/백테스트 | batch 어댑터 3종 (RealKisRest·CorpAction·MinuteCandleBackfill)이 각자 토큰. 백테스트 동시 실행 시 race. |
| 주문 path (1번 트랙 dogfooding 재개 대기) | KisHttpSession이 단일 JVM lock 부재. 같은 사용자 동시 주문 시 토큰 race. |
| 향후 multi-user (5명 시드) | 사용자 키 path 자체가 1경로(KisHttpSession)에만 형식적으로 존재. 시스템 키 9경로엔 사용자 key scope 도입 path 0. |

---

## §2. 목표 / 비목표

### 2-1. 목표

1. **단일 추상**: 11경로(REST 10 + WS 2) 모두 `KisTokenStorePort`(또는 신규 `KisApprovalStorePort`) 기반. 어댑터별 `cachedToken` 필드 제거.
2. **Redis 분산 single-flight**: api·batch·consumer 다중 JVM이 동시에 토큰을 요청해도 **KIS `/oauth2/tokenP` 호출은 1회만** 발생.
3. **Multi-tenant scope 처음부터**: `Scope` sealed class — `System(mode)` / `User(userId, mode)`. 사용자 키 path가 1경로만 있어도 **시스템 키 9경로의 추상이 사용자 path를 동일 인터페이스로 흡수**한다.
4. **정책 일원화**: 만료 마진 1h, 동시 발급 Redis 분산 lock, 설정 prefix `magicjar.kis.*` 단일.
5. **회귀 0**: 11경로의 기존 동작(401 invalidate + 1회 retry, fail-soft return null/emptyList, mock profile 부팅) 보존.
6. **운영 가시화**: 토큰 발급 횟수·lock 대기 시간·Redis 다운 카운터 메트릭 노출. 외부 API 정책 문서 갱신.

### 2-2. 비목표 (out-of-scope)

| 항목 | 사유 |
|---|---|
| KIS 응답 데이터(시세·캔들·호가) 캐싱 | 본 사이클은 인증 토큰만. 데이터 캐시는 ADR 0050 (사용자 차트) 또는 별도 사이클. |
| pyworker 통합 | pyworker는 KIS 직접 호출 0(yfinance 전용, `docs/architecture/external-api-policy.md` §3). 토큰 무관. |
| KIS `/oauth2/revokeP` 자동화 | 사용자 명시 운영(키 회전 시 SettingsModal). |
| 실전 키(prod) 발급·전환 | 사용자 외부 절차. 본 spec은 mode 추상만 도입. |
| `/oauth2/Approval` (WS approval) **사전 갱신 cron** | KIS refresh API 부재(D5=a). in-process 캐시 expire(23h)이 곧 사전 갱신 트리거. 별도 스케줄러 만들지 않음. |
| Redis Sentinel/Cluster · Vault 도입 | 본 사이클은 단일 Redis (compose 인프라). 후속 운영 트랙. |
| KIS Pro 다계정 | 1번 트랙 비범위 + 본 spec도 비범위. |

---

## §3. Architectural Decisions (12건)

각 결정마다 "근거 + 대안 + 선택 이유" 명시. 12건은 사용자와 본 세션에서 확정한 결정사항(배경 컨텍스트 §A·D + 본 spec 신규 §3-7~12)의 합집합이다.

### D1. Phase 분할 폐기 — Redis 분산 single-flight 처음부터

근거: in-memory 1단계로 가도 어차피 Phase 2에서 모든 어댑터의 lock 패턴을 다시 손대야 함(in-memory `synchronized` → Redisson `RLock`). 단일 PR/단일 사이클 + multi-JVM single-flight 검증 한 번에 마치는 편이 회귀 노출이 짧음.

대안: (a) Phase 1(in-memory) → Phase 2(Redis), (b) 모듈별 점진(api 먼저, batch·consumer 후속). (a)는 11경로를 두 번 변경. (b)는 multi-JVM 보호가 마지막 모듈 통합 전까지 작동하지 않음.

선택: 단일 사이클 (사용자 결정 A.1).

### D2. Multi-tenant scope sealed class — System(mode) / User(userId, mode)

근거: 종목 정보·차트가 이미 multi-tenant(`{userId}` 키). 토큰만 single-tenant이면 사용자 path 진입 시 또 추상 변경 필요.

대안: (a) String key 단일(`"system:vps"` / `"user:74683223:vps"`), (b) `mode` 만 + 사용자 path는 mode override.

(a)는 타입 안전성 0(오타 시 런타임 폭발). (b)는 사용자 path 명시 표현 불가 → 락 키 분리 시 추가 분기 필요.

선택: sealed class.

```kotlin
sealed class KisAuthScope {
    abstract val mode: String  // "vps" (모의) | "prod" (실전)
    abstract fun tokenKey(): String
    abstract fun lockKey(): String

    data class System(override val mode: String) : KisAuthScope() {
        override fun tokenKey() = "magicjar:kis:token:system:$mode"
        override fun lockKey()  = "magicjar:kis:token:lock:system:$mode"
    }
    data class User(val userId: String, override val mode: String) : KisAuthScope() {
        override fun tokenKey() = "magicjar:kis:token:user:$userId:$mode"
        override fun lockKey()  = "magicjar:kis:token:lock:user:$userId:$mode"
    }
}

sealed class KisApprovalScope {
    abstract val mode: String
    abstract fun approvalKey(): String
    abstract fun lockKey(): String

    data class System(override val mode: String) : KisApprovalScope() {
        override fun approvalKey() = "magicjar:kis:approval:system:$mode"
        override fun lockKey()      = "magicjar:kis:approval:lock:system:$mode"
    }
    data class User(val userId: String, override val mode: String) : KisApprovalScope() {
        override fun approvalKey() = "magicjar:kis:approval:user:$userId:$mode"
        override fun lockKey()      = "magicjar:kis:approval:lock:user:$userId:$mode"
    }
}
```

`userId`는 String(`74683223`, V21 정합). `mode`는 `vps`/`prod`.

### D3. 락 키 = 토큰 scope 1:1 (사용자 명시 정정)

근거: 시스템 토큰 갱신과 사용자 토큰 갱신이 같은 락을 공유하면 무관한 사용자가 시스템 토큰 락에 묶여 대기. 사용자 A 토큰 갱신이 사용자 B 토큰 갱신과 묶이는 것도 부적절.

대안: 글로벌 단일 락(`magicjar:kis:token:lock`). 동시성 0 + 모든 갱신이 직렬화.

선택: scope별 분리 — `system:{mode}` / `user:{userId}:{mode}` / `approval:system:{mode}` / `approval:user:{userId}:{mode}` 4 종.

### D4. Redisson — `redisson-spring-boot-starter:3.40.2`

근거: Spring Boot 3.3.5 호환(`spring-boot-3.3` artifact), Spring Data Redis와 양립(Lettuce 유지), `RLock`(분산 락) + `RTopic`(invalidate broadcast)을 모두 제공. LUA로 직접 구현하면 holder 식별·취소·재진입을 다 수동 작성.

대안: (a) Spring Data Redis SETNX + Lua, (b) Redisson 다른 버전(`3.33.0`은 SB 3.3 미공식 매핑). (a)는 LUA 작성 필요 + holder 식별 수동. (b)는 호환성 위험.

선택: Redisson 3.40.2.

### D5. RLock 정책 — `tryLock(waitTime=10s, leaseTime=15s)` + 워치독 비활성화

근거:

- **leaseTime=15s**: KIS `/oauth2/tokenP` 평균 응답 1~3s. 네트워크 지연·DNS resolve·TLS handshake 포함 **최악 10s** 이내. 안전 5s 마진 → 15s. 클라이언트가 죽어도 15s 후 lock 자동 해제.
- **waitTime=10s**: Redisson `tryLock` 대기. 토큰 갱신은 사용자 path 직접 영향(차트·호가) → 10s 이상 대기는 사용자 timeout. 10s 후 fallback(in-memory 또는 expired token 재사용 후 401 trigger).
- **워치독 비활성화** (leaseTime 명시 → Redisson watchdog 자동 비활성): 워치독이 활성이면 leaseTime이 동적으로 연장되어 클라이언트 hang 시 락 영구 점유. KIS 토큰 발급 같은 짧은 critical section엔 명시적 leaseTime이 안전.

대안: leaseTime=30s(여유), waitTime=5s(빠른 fallback). 30s는 클라이언트 hang 시 다른 갱신자 너무 오래 대기. 5s는 KIS 응답 평균에 너무 가까움.

선택: 15s/10s.

### D6. RTopic — `magicjar:kis:token:invalidated` (단일 토픽 broadcast)

근거: 401 응답 시 invalidate를 호출한 JVM의 in-process 캐시는 즉시 비워지지만, 다른 JVM이 옛 토큰을 stale cache hit하면 또 401. Redis HASH의 토큰을 갱신해도 in-process 캐시(L1)는 모름. RTopic으로 모든 JVM에 invalidate 신호 broadcast → L1 캐시 즉시 invalidate.

페이로드: `{ "scope": "system:vps" }` 형식 (scope.tokenKey() 또는 scope 식별자). 받은 JVM은 자기 L1에서 해당 scope만 evict.

대안: (a) RTopic 없이 Redis HASH만(매 호출 GET → 응답 시간 증가), (b) Redis Pub/Sub 직접(Redisson 없이도 가능하나 Redisson과 같은 conn pool 재사용 어려움).

선택: Redisson `RTopic`. broadcast + L1 in-process Caffeine/AtomicReference 둘 다 자동 invalidate.

### D7. Redis 다운 fallback — in-memory + ERROR 로그(P0)

근거: Redis 다운 시 토큰 발급·갱신·invalidate 모두 실패하면 사용자 차트·주문·시세 모두 차단. 그러나 Redis 다운 중 in-memory fallback을 무한 사용하면 multi-JVM race 위험(KIS 한도 초과).

선택: 절충 — Redis 호출 실패 시 **in-memory cache 사용**(L1만으로 작동) + `ERROR` 로그(`[kis-token] redis-unavailable scope=... fallback=in-memory race-risk-high`) + Prometheus counter `kis_token_redis_unavailable_total`. 운영 알람(P0) 트리거. 새 발급은 in-memory `synchronized` per-JVM. multi-JVM race 위험 명시(EGW00133 폭주 가능). 운영자가 Redis 복구 후 자동 회복.

대안: (a) Redis 다운 시 fail-fast(503), (b) circuit breaker로 일정 시간 차단. (a)는 사용자 path 즉시 차단. (b)는 1차 사이클 범위 초과.

선택: in-memory fallback + ERROR + 카운터. 운영 runbook 별도 작성(`_workspace/operations/redis-outage-runbook.md` 후속).

### D8. KIS refresh 사전 갱신 cron 미도입 (D5=a)

근거: KIS는 별도 refresh API 없음. `/oauth2/tokenP` 단일, refresh_token grant 미지원. **만료 1h 전 사전 갱신 ≠ refresh API 호출** — 새 발급(`/oauth2/tokenP`)을 1h 일찍 트리거하는 것뿐. 굳이 cron 없이 in-process 캐시 expire(23h)이 곧 사전 갱신 트리거.

흐름: 23h 시점 다음 호출에서 `cached.isValid(now) == false` → Redis HASH 재조회 → Redis HASH도 23h 지났으면 RLock 획득 → 새 발급 → Redis HASH put + RTopic broadcast.

대안: (a) `@Scheduled(fixedRate = 23h)` 사전 갱신 작업. 단일 책임 위반(컨테이너 재시작 시 누락) + Quartz·shedlock 같은 분산 스케줄러 필요. (b) 외부 cron(host-level). 컨테이너 외부 의존.

선택: in-process expire 트리거.

### D9. 모듈별 어댑터 + 도메인 port + helper 추출

근거: api·batch·consumer 모두 같은 KIS endpoint(`/oauth2/tokenP`)를 호출. 그러나 모듈별 RestClient 설정(SimpleClientHttpRequestFactory vs OkHttp), 메트릭 명명, ApplicationContext 의존이 다름. 도메인 port에 RestClient를 두면 도메인이 인프라를 알게 됨.

선택:
- **domain port**: `KisTokenStorePort` + 신규 `KisApprovalStorePort` — `currentTokenOrIssue(scope): String`, `invalidate(scope)`, `currentApprovalOrIssue(scope)`, `invalidate(scope)`.
- **공유 helper(infrastructure 공통 모듈 또는 abstract base)**: `AbstractRedisKisAuthAdapter` — Redis HASH 조회/저장, RLock 획득/해제, RTopic publish/subscribe, L1 in-memory 캐시. **HTTP 호출은 abstract method로 위임** (`protected abstract fun fetchFromKis(scope, credentials): IssuedToken`).
- **모듈별 어댑터**: `ApiRedisKisTokenStoreAdapter`, `BatchRedisKisTokenStoreAdapter`, `ConsumerRedisKisTokenStoreAdapter`, `ConsumerRedisKisApprovalStoreAdapter` — 각자 모듈 RestClient 빈 + 모듈별 credentials provider 주입.
- **공유 helper 위치 후보**: ① 신규 `:shared-kis-auth` Gradle 모듈, ② `domain/.../market/kis/` 패키지 + Redisson 의존(domain이 Redisson 의존 가지면 hexagonal 위반). plan에서 결정.

대안: (a) 단일 어댑터 모듈 통합 → 모듈별 다른 RestClient·메트릭 빈 추출 비용. (b) 어댑터 11개 각자 풀구현 → 공유 로직 중복 (Redis 키·lock·RTopic 구현 11번).

선택: 도메인 port + 공유 helper + 모듈별 어댑터 3 (REST) + 1 (Approval).

### D10. 기존 어댑터 11경로 → port 주입 + `cachedToken` 필드 제거

근거: 11경로 어댑터 모두 `private var cachedToken` + `private fun issueToken()` 패턴이 동일. port 주입 시 한 줄로 대체(`val token = tokenStore.currentTokenOrIssue(scope)`).

특이 변종 — `KisMarketIndexAdapter`는 401 시 `cachedToken = null` + 즉시 재발급(retry 없이 다음 호출에서 새 토큰) 패턴. 다른 어댑터(401 → invalidate + retry once)와 다름. **폐기**(D9 결정 — 모든 어댑터가 port + 1회 retry 단일 패턴).

대안: 점진 통합(트랙 분할). 본 사이클 D1 결정 위반.

선택: 11경로 단일 PR/단일 사이클 일괄 전환. 변종은 표준 패턴으로 통일.

### D11. 설정 prefix 일원화 — `magicjar.kis.*`

근거: 현재 3종(`spring.kis.*` 2 + `magicjar.kis.*` 8 + `kis.*` 1). `spring.*`은 Spring Framework reserved namespace 오용. `kis.*`은 충돌 위험(다른 라이브러리 prefix와). `magicjar.kis.*`이 v2 정공법.

선택: `magicjar.kis.*` 단일. application.yml 4개(api·batch·consumer·dev profile) + docker-compose env 매핑 + `.env.example` 단일 prefix. `spring.kis.*` / `kis.*` deprecate(이전 사이클 transition 없이 바로 제거 — 본 사이클 단일 PR).

| 신규 키 | 의미 |
|---|---|
| `magicjar.kis.base-url.prod` | 실전 base URL |
| `magicjar.kis.base-url.vps` | 모의 base URL |
| `magicjar.kis.system.app-key.prod` | 시스템 키(데이터 조회) prod appKey |
| `magicjar.kis.system.app-secret.prod` | 시스템 키 prod appSecret |
| `magicjar.kis.system.app-key.vps` | (선택) 시스템 키 vps appKey |
| `magicjar.kis.system.app-secret.vps` | (선택) 시스템 키 vps appSecret |
| `magicjar.kis.token.lease-time-seconds` | RLock leaseTime (default 15) |
| `magicjar.kis.token.wait-time-seconds` | RLock waitTime (default 10) |
| `magicjar.kis.token.refresh-margin-seconds` | 만료 마진 (default 3600 = 1h) |

사용자 키(`magicjar.kis.user.*`)는 현재 DB(`user_trading_config`) source. property 미사용.

### D12. 운영 가시화 — 메트릭 + 로그 표준 + runbook

근거: 운영 중 KIS 한도 위반(EGW00133)은 즉시 사용자 path 차단. 토큰 발급 횟수·lock 대기·Redis 다운을 즉시 알림 가능해야 함.

선택:
- **로그 표준 4 이벤트**: `[kis-token] issued scope=... source=fresh/redis-hit/in-memory ttl=...`, `[kis-token] invalidated scope=... reason=401/manual/rtopic`, `[kis-token] lock-acquired scope=... wait-ms=...`, `[kis-token] redis-unavailable scope=... fallback=in-memory`.
- **메트릭** (Micrometer counter/timer):
  - `kis_token_issuance_total{scope, source}` — fresh/redis-hit/in-memory 분기
  - `kis_token_invalidation_total{scope, reason}`
  - `kis_token_lock_wait_seconds{scope}` (timer)
  - `kis_token_redis_unavailable_total{scope}` (P0 알람 후보)
  - `kis_approval_issuance_total{scope, source}` (WS)
- **운영 가이드**: `docs/architecture/external-api-policy.md` §5 갱신 + `_workspace/operations/redis-outage-runbook.md` 신규(후속 작업, 본 spec 범위 외).

---

## §4. 영역 분류 (B + C 정리표)

### 4-1. REST 시스템 키 9경로 (System scope)

| 어댑터 | 모듈 | TR_ID 사용 (대표) | 자격 source | port 주입 후 변경 핵심 |
|---|---|---|---|---|
| `KisChartCandleAdapter` | api | FHKST03010100 (D/W/M chart) | `magicjar.kis.system.app-{key,secret}.prod` | 이미 R3-5c-2 통합 — `KisTokenStorePort` 호출 인자 `mode: String` → `scope: KisAuthScope.System`. |
| `KisInstrumentInfoAdapter` | api | CTPF1002R + FHKST01010100 | 동상 | `cachedToken`/`issueToken()` 제거 + port 주입. 401 retry는 port 내부에서 처리 → 어댑터 코드 단순화. |
| `KisInvestorTrendAdapter` | api | (투자자 동향 endpoint) | 동상 | 동상. |
| `KisMarketIndexAdapter` | api | FHPUP02100000 | 동상 | 동상 + 변종(401 즉시 재발급) 표준 패턴 통일 (D10). |
| `KisOrderbookAdapter` | api | FHKST01010200 | 동상 | 동상. |
| `KisRecentTradesAdapter` | api | FHPST01060000 | 동상 | 동상. |
| `RealKisRestAdapter` | batch | FHKST03010100 (D backfill) | `magicjar.kis.system.app-{key,secret}.prod` (real-kis profile) | 동상. WireMock IT(시나리오 7·8 R3-4) 호환 — `KisTokenStorePort` mock 주입. |
| `KisCorpActionTokenHolder` | batch | (자체 token holder) | 동상 | **클래스 자체 폐기**. 사용처(`RealKisCorpActionAdapter`)에 port 직접 주입. |
| `MinuteCandleBackfillJob` | batch | FHKST03010230 | 동상 | `cachedToken`/`issueToken()` 제거 + port 주입. |

### 4-2. REST 사용자 키 1경로 (User scope)

| 어댑터 | 모듈 | TR_ID 사용 | 자격 source | port 주입 후 변경 핵심 |
|---|---|---|---|---|
| `KisHttpSession` | consumer | TTTC0011U/0012U/0013U/8434R/0081R + V접두 (모의) | `user_trading_config` 테이블 — `KisCredentialProvider.forUser(userId)` (track1 Phase 3 산출) | `acquireToken(appKey, appSecret)` → `acquireToken(scope: KisAuthScope.User)`. ConcurrentHashMap 캐시 폐기, port가 L1 캐시 보유. `executeWithRetry`는 그대로(401 → port.invalidate(scope) + acquireToken(scope) + retry). |

`KisHttpSession`은 `RealKisOrderAdapter`·`KisHashKeyClient`·`KisRestClient`(consumer)에서 주입받는다. port 주입 시 KisHttpSession 자체가 thin wrapper로 남는다(또는 port 직접 사용으로 흡수 — plan에서 결정).

### 4-3. WebSocket approval 2경로

| 경로 | 모듈 | TR_ID | scope | port 주입 후 변경 핵심 |
|---|---|---|---|---|
| 시세 WS approval | consumer | H0UNCNT0/H0UNASP0/H0UPCNT0 | **System(mode)** | 현재 별도 holder 없이 시세 WS path가 자체 발급(또는 KisWsRouter 내부) — 신규 `KisApprovalStorePort` + `ConsumerRedisKisApprovalStoreAdapter` 통합. |
| 체결통보 WS approval | consumer | H0STCNI0/9 | **User(userId, mode)** | `KisWsApprovalProvider`의 `AtomicReference` + `fetch()` 자체발급 → `KisApprovalStorePort.currentApprovalOrIssue(scope)` 호출로 단순화. `KisWsApprovalProvider`는 thin wrapper로 잔존(또는 흡수). |

WS approval(`/oauth2/Approval`)은 KIS 명세상 token과 별도 endpoint·별도 응답(`approval_key`). 그래서 `KisTokenStorePort`와 분리한 신규 port 도입.

---

## §5. 도메인·포트 변경

### 5-1. `KisTokenStorePort` (도메인 port — 시그니처 일반화)

현재 `currentTokenOrIssue(mode: String)` → 변경.

```kotlin
package com.mj.domain.market.kis.port

interface KisTokenStorePort {
    fun currentTokenOrIssue(scope: KisAuthScope): String
    fun invalidate(scope: KisAuthScope)
}
```

**호환성**: R3-5c-2 통합된 `KisChartCandleAdapter`는 `currentTokenOrIssue("prod")` 호출. 본 사이클에서 `currentTokenOrIssue(KisAuthScope.System("prod"))`로 변경. caller 1곳뿐(D11 prefix 일원화 PR과 함께 묶음).

### 5-2. `KisApprovalStorePort` (도메인 port — 신규)

```kotlin
package com.mj.domain.market.kis.port

interface KisApprovalStorePort {
    fun currentApprovalOrIssue(scope: KisApprovalScope): String
    fun invalidate(scope: KisApprovalScope)
}
```

### 5-3. `KisAuthScope` / `KisApprovalScope` (도메인 sealed class — 신규)

§3 D2 인용. `domain/market/kis/port/` 패키지 또는 `domain/market/kis/scope/` 신규 패키지. plan에서 위치 결정.

### 5-4. `KisCredentialProvider` (track1 Phase 3 산출 — 본 spec과 통합 인터페이스 보강)

```kotlin
interface KisCredentialProvider {
    fun forSystem(mode: String): KisCredential   // 신규 — 시스템 키 9경로용
    fun forUser(scope: KisAuthScope.User): KisCredential   // 기존 보강 — 사용자 키
}
```

`forSystem`은 application.yml `magicjar.kis.system.*` 직접 읽기. `forUser`는 `user_trading_config` DB 조회 + `SecretCipher.decrypt()` (track1 산출).

---

## §6. 모듈별 어댑터 설계

### 6-1. 공유 helper (위치는 plan에서 결정)

```kotlin
abstract class AbstractRedisKisAuthAdapter<S, P : Any>(
    protected val redissonClient: RedissonClient,
    protected val refreshMarginSeconds: Long = 3600,
    protected val lockWaitTimeSeconds: Long = 10,
    protected val lockLeaseTimeSeconds: Long = 15,
) {
    private val l1Cache = ConcurrentHashMap<String, AtomicReference<CachedAuth?>>()
    private val log = LoggerFactory.getLogger(javaClass)

    init { subscribeInvalidate() }

    protected fun resolve(scope: S, tokenKey: String, lockKey: String, fetcher: () -> P): String {
        // 1. L1 cache hit (in-process AtomicReference)
        l1Cache[tokenKey]?.get()?.let { if (it.isValid(now())) return it.token }

        // 2. Redis HASH read
        try {
            val redisHit = readRedisHash(tokenKey)
            if (redisHit != null && redisHit.isValid(now())) {
                l1Cache.compute(tokenKey) { _, _ -> AtomicReference(redisHit) }
                return redisHit.token
            }
        } catch (ex: RedisConnectionException) {
            // D7 fallback — log ERROR + counter
            return inMemoryFallback(scope, tokenKey, fetcher)
        }

        // 3. Redis miss → RLock try
        val lock = redissonClient.getLock(lockKey)
        if (!lock.tryLock(lockWaitTimeSeconds, lockLeaseTimeSeconds, TimeUnit.SECONDS)) {
            // wait 시간 초과 — fallback (옛 토큰 또는 in-memory)
            return waitTimeoutFallback(tokenKey)
        }
        try {
            // double-check (다른 JVM이 그 사이에 발급했을 수 있음)
            val recheck = readRedisHash(tokenKey)
            if (recheck != null && recheck.isValid(now())) {
                l1Cache.compute(tokenKey) { _, _ -> AtomicReference(recheck) }
                return recheck.token
            }
            // 실 발급
            val fresh = fetcher()
            writeRedisHash(tokenKey, fresh)
            publishInvalidate(scope)  // 다른 JVM L1 evict
            l1Cache.compute(tokenKey) { _, _ -> AtomicReference(fresh.toCached()) }
            return fresh.token
        } finally {
            lock.unlock()
        }
    }

    protected fun invalidateInternal(scope: S, tokenKey: String) {
        l1Cache.remove(tokenKey)
        deleteRedisHash(tokenKey)
        publishInvalidate(scope)
    }

    // L1 invalidate broadcast
    private fun subscribeInvalidate() {
        redissonClient.getTopic(INVALIDATE_TOPIC).addListener(String::class.java) { _, scopeKey ->
            l1Cache.remove(scopeKey)
        }
    }

    protected abstract fun readRedisHash(key: String): CachedAuth?
    protected abstract fun writeRedisHash(key: String, fresh: P)
    protected abstract fun deleteRedisHash(key: String)
    protected abstract fun inMemoryFallback(scope: S, key: String, fetcher: () -> P): String
    protected abstract fun waitTimeoutFallback(key: String): String

    companion object {
        const val INVALIDATE_TOPIC = "magicjar:kis:token:invalidated"
    }
}
```

(단순화 형태. 실제 구현은 plan에서.)

### 6-2. 모듈별 어댑터

| 어댑터 | 모듈 | scope | base URL | credentials source |
|---|---|---|---|---|
| `ApiRedisKisTokenStoreAdapter` | api | System(prod) | `magicjar.kis.base-url.prod` | `magicjar.kis.system.app-{key,secret}.prod` |
| `BatchRedisKisTokenStoreAdapter` | batch | System(prod) | 동상 | 동상 |
| `ConsumerRedisKisTokenStoreAdapter` | consumer | System(prod·vps) + User(userId, prod·vps) | `magicjar.kis.base-url.{mode}` | System: yml / User: `KisCredentialProvider.forUser` (track1) |
| `ConsumerRedisKisApprovalStoreAdapter` | consumer | System(mode) + User(userId, mode) | 동상 | 동상 |

api·batch는 시스템 키만(데이터 조회만 함). consumer만 시스템(시세 WS + 시세 backfill 보조) + 사용자(주문 + 체결통보 WS).

**왜 Redis 어댑터 3개가 분리되어야 하는가?**

- 같은 Redis 키 schema·같은 Redisson 클라이언트를 사용하지만, 모듈별 RestClient 빈·모듈별 credentials 주입·메트릭 명명(`api_kis_token_issuance` vs `batch_kis_token_issuance`)이 다름.
- 공유 helper(`AbstractRedisKisAuthAdapter`)에 모듈 무관 로직 응집. 모듈별 어댑터는 RestClient 호출 + credentials 주입만.

**대안 검토**: 단일 어댑터(application.yml로 모드 선택)는 multi-module Spring Boot 환경에서 `@Component` scan 분리 + 빈 conflict 회피가 어려움 + 모듈별 RestClient 의존성 차이 흡수 부담. 모듈별 분리 채택.

---

## §7. Redisson 통합 설계

### 7-1. Bean 구성

`shared-kis-auth` 모듈(또는 각 모듈 자체)에 공통 `@Configuration`:

```kotlin
@Configuration
@ConditionalOnProperty("magicjar.kis.redis-token.enabled", havingValue = "true", matchIfMissing = true)
class KisAuthRedissonConfig {
    @Bean
    @ConditionalOnMissingBean(RedissonClient::class)
    fun kisAuthRedissonClient(redisProps: RedisProperties): RedissonClient {
        val config = Config().apply {
            useSingleServer().apply {
                address = "redis://${redisProps.host}:${redisProps.port}"
                redisProps.password?.let { password = it }
                connectionPoolSize = 16
                connectionMinimumIdleSize = 4
            }
        }
        return Redisson.create(config)
    }
}
```

**기존 Spring Data Redis와 양립**: Lettuce는 `RedisConnectionFactory`로 사용 중(`docs/architecture/master-architecture.md` §3-4 캐시 인프라). Redisson은 자체 connection pool. 두 클라이언트가 같은 Redis 인스턴스를 공유. compose `redis:7.4.x` 단일.

### 7-2. RLock 호출 시퀀스 (double-check 패턴)

§6-1 코드 참조. 핵심:

```
read L1 → hit? return
read Redis HASH → hit? L1 fill + return
                  miss? → tryLock(10s/15s)
                          on acquired:
                              recheck Redis HASH ← 다른 JVM이 그 사이 발급했을 수 있음
                              hit? L1 fill + return
                              miss? fetch from KIS → write Redis HASH → publish RTopic → L1 fill → return
                          on lock timeout (10s 초과):
                              waitTimeoutFallback (옛 토큰 + 401 trigger 또는 in-memory)
```

double-check 없이 lock 획득 후 즉시 발급하면 동시 다중 JVM 진입 시 (lock 직렬화 → 각자 발급) KIS 호출 N회. double-check로 1회만.

### 7-3. RTopic publisher/subscriber 매트릭스

| 발행 시점 | 페이로드 | 효과 |
|---|---|---|
| 토큰 발급 성공 (write Redis HASH 직후) | `scope.tokenKey()` 또는 식별자 | 다른 JVM L1 캐시 evict (옛 토큰 흔적 제거) |
| 401 invalidate | `scope.tokenKey()` | 즉시 모든 JVM L1 + Redis HASH evict |

**모든 JVM이 발행자이자 구독자.** Redisson `RTopic.addListener`가 메시지 수신 시 `l1Cache.remove(key)` 자동 호출. 발행자 자신은 자신의 L1 evict는 publish 직전에 직접 호출(자기 메시지 self-receive 회피).

### 7-4. leaseTime / waitTime 정책 근거

§3 D5 인용. 운영 중 변경 가능 — `magicjar.kis.token.{lease-time,wait-time}-seconds` property로 노출.

### 7-5. 워치독 비활성화 이유

§3 D5 인용. `tryLock(waitTime, leaseTime, unit)` 시그니처 사용 시 Redisson은 자동으로 워치독 비활성. `tryLock(waitTime, unit)` (leaseTime 미지정)을 쓰면 워치독 활성 + leaseTime 30s 자동 부여 + 5s마다 leaseTime 연장. 우리 critical section은 KIS 호출(평균 1~3s, 최악 10s)이라 명시 leaseTime 15s가 정확.

---

## §8. Redis 키 스키마 / 직렬화 정책

### 8-1. 키 schema

```
# REST 토큰 (HASH)
magicjar:kis:token:system:{mode}                  # 시스템 REST 토큰
magicjar:kis:token:user:{userId}:{mode}           # 사용자 REST 토큰

# WS approval (HASH)
magicjar:kis:approval:system:{mode}               # 시스템 WS approval (시세)
magicjar:kis:approval:user:{userId}:{mode}        # 사용자 WS approval (체결통보)

# 락 키 (Redisson RLock — 토큰 scope과 1:1)
magicjar:kis:token:lock:system:{mode}
magicjar:kis:token:lock:user:{userId}:{mode}
magicjar:kis:approval:lock:system:{mode}
magicjar:kis:approval:lock:user:{userId}:{mode}

# RTopic
magicjar:kis:token:invalidated                    # publish: scope.tokenKey() 또는 scope 식별자
```

`{mode}` = `prod` | `vps`
`{userId}` = String (예: `74683223`, V21 정합)

### 8-2. HASH 필드 정의

| 필드 | 타입 | 의미 |
|---|---|---|
| `access_token` | String | KIS access token |
| `expires_at` | Long (epoch second) | KIS 만료 시각 |
| `issued_at` | Long (epoch second) | 우리 발급 시각 |
| `issuer_jvm` | String | 발급한 JVM 식별자(hostname + pid 또는 random UUID) — 운영 메타 |
| `kis_token_type` | String | `Bearer` (응답 정합) — 옵션 |

WS approval은 `approval_key` 필드 + `expires_at` (23h hardcoded) + `issued_at` + `issuer_jvm`.

### 8-3. TTL

- Redis 키 자체 TTL = **25h** (KIS 24h + 1h 안전 마진). 만료 후 keyspace eviction → 다음 호출 시 miss → 새 발급.
- L1 in-memory: 만료 마진 1h 시점에 stale 처리(`isValid(now)` false) → Redis 재조회.

### 8-4. 키 마이그레이션 / 충돌 검토

- 기존 사용자 차트 캐시 키(ADR 0050): `candle:{userId}:*`, `candle:recent:*`, `candle:lastfetch:*`. **충돌 없음** (`magicjar:kis:*` 와 prefix 다름).
- 기존 quote/orderbook 캐시(있을 경우): `quote:*`, `orderbook:*` — 충돌 없음.
- `KisHttpSession`의 in-process `ConcurrentHashMap<appKey, CachedToken>` → 어댑터에서 port로 위임 후 폐기. 운영 중 데이터 마이그 불요(in-process는 컨테이너 재시작 시 자동 비움).
- Consumer의 `KisTokenStore` (legacy) → consumer 측 신규 어댑터로 흡수 후 클래스 삭제. 같은 데이터 마이그 불요.
- `KisCorpActionTokenHolder` → 클래스 삭제. 사용처(`RealKisCorpActionAdapter`)에 port 직접 주입.

### 8-5. 운영 메타 확장 가이드

`issuer_jvm` 외 추가 필드는 HASH에 자유 추가 가능(필드별 TTL 불가). 향후 `usage_count`(발급 후 호출 수) 같은 필드 추가 시 RWHASH(원자적 GETSET 패턴) 또는 별도 키.

---

## §9. 만료·갱신·재시도 정책

### 9-1. 23h in-process 사전 갱신 → 25h Redis TTL 흐름도

```
[t=0]    KIS 발급 → Redis HASH put(expires_at=t+24h, TTL=25h) + L1 cache(expires_at=t+24h)
[t=23h]  caller A 호출 → L1.isValid(t+23h) ⇒ false (1h 마진)
                       → Redis HASH read → expires_at=t+24h, isValid=false (1h 마진)
                       → tryLock → fetch → 새 발급 (t+23h+δ) → HASH put (expires_at=t+47h+δ, TTL=25h)
                       → RTopic publish → 다른 JVM L1 evict
[t=23h+δ] caller B (다른 JVM) 호출 → L1 evicted → Redis HASH read → 새 토큰 hit
[t=24h]  KIS 측 만료 → 우리는 23h 시점에 이미 갱신했으므로 영향 없음
[t=25h]  Redis 키 자동 eviction (TTL 만료) — 만일 23h 시점 갱신 실패했다면 keyspace에서도 사라져 다음 호출이 새 발급
```

### 9-2. 401 invalidate + 1회 retry 단일 패턴

```
caller → port.currentTokenOrIssue(scope) → token T1
caller → KIS API call with T1 → 401 (token revoked or KIS measure)
caller → port.invalidate(scope) → L1 evict + Redis HASH del + RTopic broadcast
caller → port.currentTokenOrIssue(scope) → 새 발급 → token T2
caller → KIS API call with T2 → 200 (or 실패 시 그대로 throw)
```

11경로 어댑터 모두 같은 패턴. 어댑터 내부 코드 → 공통 helper(`KisRetryExecutor.executeWithTokenRetry(scope, action)`) 추출 후보 (plan에서 결정).

### 9-3. KisMarketIndexAdapter 변종 폐기

현재 `KisMarketIndexAdapter:94-95` — 401 시 `cachedToken = null` + 즉시 재발급(retry 없이 그대로 throw). 다른 어댑터(401 → invalidate + retry once)와 다름. 변종 사유: 코스피·코스닥 지수는 5초 polling이라 1회 실패해도 다음 호출에서 회복. **그러나 단일 패턴 통일 (D10) — 표준 retry once 패턴 적용**. polling 빈도 자체로 충분히 self-heal.

### 9-4. EGW00133 / EGW00201 처리 (참고)

`docs/architecture/external-api-policy.md` §2-2:
- `EGW00133` (1분 1회 발급 위반) → 60초 대기 + 1회 재시도. 본 사이클의 single-flight가 발생을 차단하므로 발생 시는 운영 알람.
- `EGW00201` (TPS 초과) → 1초 대기 + 1회 재시도. 본 사이클 비범위(별도 rate limiter).

---

## §10. Redis 다운 fallback 정책

### 10-1. in-memory fallback 동작

§3 D7 인용. Redis `RedisConnectionException` 또는 timeout(2s) 시:
1. ERROR 로그 발행
2. `kis_token_redis_unavailable_total{scope}` 카운터 증가
3. L1 in-memory 캐시 hit → 사용
4. L1 miss → 모듈별 `synchronized` 블록(per-JVM) → 직접 KIS 발급 → L1 저장
5. Redis 회복 후 다음 호출에서 자동으로 Redis path 재사용. L1 → Redis sync는 별도 작업 없음(L1 expire 후 자연스럽게).

### 10-2. multi-JVM race 위험 명시

Redis 다운 중 multi-JVM이 같은 KIS 키로 동시 발급 → KIS 1분 1회 한도 위반(EGW00133) 가능. **이 리스크는 spec에 명시하고 사용자에게 알린다.** 운영자가 Redis 즉시 복구하는 것이 P0.

대안: Redis 다운 중 토큰 발급 자체 차단(503). 사용자 path 전체 다운. 채택하지 않음(D7 trade-off).

### 10-3. ERROR 로그 메시지 양식 (D7 보강 — 사용자 결정 정합)

표준 prefix `[kis-auth][redis-down]` — 향후 알림 시스템(grep/loki/promtail) 정합 위해 모든 모듈에서 동일.

```
ERROR [kis-auth][redis-down] fallback=in-memory race-risk-high scope={scope} op=current source={hit|fresh}
ERROR [kis-auth][redis-down] fallback=in-memory race-risk-high scope={scope} op=invalidate
ERROR [kis-auth][redis-down] fallback=in-memory race-risk-high scope={scope} op=lock-acquire reason={timeout|ConnectionException}
INFO  [kis-auth][redis-recovered] scope={scope}  ← Redis 복구 후 첫 성공 시 (PR3+ 어댑터에서 발행)
```

각 ERROR 라인은 `KisAuthMetrics.redisUnavailable(scope)` + `fallbackUsed(scope, kind)` 호출과 1:1 정합한다. 향후 알림 시스템 도입 시 `grep -E '\[kis-auth\]\[redis-down\]'` 단일 패턴으로 슬랙/PagerDuty trigger 가능.

P0 알람 트리거: Prometheus rule `increase(kis_token_redis_unavailable_total[5m]) > 0` (운영 트랙 후속 — 본 spec은 메트릭 + 로그 노출까지).

### 10-4. 프론트 노출 surface (D7 보강 — 사용자 결정 정합)

사용자 결정: Redis 다운 fallback 시 프론트에 "서비스 제한" 상태 노출 + 향후 알림 시스템 통합 path 명시.

**1차 채택 — Spring Actuator HealthIndicator (api 모듈)**:

- 위치: `api/src/main/kotlin/com/mj/api/infrastructure/kis/auth/KisAuthHealthIndicator.kt` (PR3에서 신설).
- 노출 endpoint: `/actuator/health/kisAuth` (component health). `/actuator/health` 전체에도 합산.
- 상태 매핑:
  - `UP` — Redis 정상 + 최근 5분 `kis_token_redis_unavailable_total` 증가 0.
  - `DEGRADED` (커스텀 status, Spring 표준 `OUT_OF_SERVICE` 매핑) — 최근 5분 fallback 발생.
  - `DOWN` — 본 사이클 미사용(Redis 다운 자체로 사용자 path는 in-memory fallback이라 작동 중).
- detail JSON 예시:

```json
{
  "status": "DEGRADED",
  "details": {
    "redisUnavailableLast5m": 12,
    "fallbackUsedLast5m": 247,
    "lastDownAt": "2026-05-05T16:42:11+09:00",
    "raceRisk": "high",
    "advice": "redis 컨테이너 즉시 복구 — 다중 JVM이 KIS API에 동시 발급 중 (EGW00133 한도 초과 위험)"
  }
}
```

**프론트 통합 (별도 사이클, 본 spec은 backend surface까지)**:
- `useQuery(['health', 'kis-auth'], () => GET /actuator/health/kisAuth, { refetchInterval: 30s })`
- DEGRADED 응답 시 상단 banner "차트·시세 서비스가 일시적으로 제한될 수 있습니다 (KIS 인증 캐시 장애)" + `data.details.advice` 표시.

**2차 후보 (미채택)** — HTTP 응답 헤더 `X-MagicJar-KIS-Degraded: true`:
- 모든 응답에 헤더 추가 — Filter 1개로 가능하나 frontend 모든 fetch 코드가 헤더 검사 필요.
- HealthIndicator는 단일 폴링 endpoint로 깔끔. 채택하지 않음.

**알림 시스템 미구현 명시**: 본 사이클은 메트릭 + 로그 + HealthIndicator surface 까지. Slack webhook / PagerDuty 통합은 별도 운영 트랙(향후 사이클).

### 10-5. 운영 runbook 위치

`_workspace/operations/redis-outage-runbook.md` (후속 작성). 본 spec은 작성 명시만:

- 증상: ERROR 로그 패턴 (`grep '\[kis-auth\]\[redis-down\]'`) + 카운터 증가 + `/actuator/health/kisAuth` DEGRADED.
- 즉시 조치: docker compose `redis` 컨테이너 healthy 확인 → 재시작 → 자동 회복.
- 사후 분석: KIS 호출 로그(`grep '\[kis-auth\] issued'`)로 race 발생 여부 + `kis_token_issuance_total{source="in-memory"}` 누적량 확인.
- 예방: Redis Sentinel/Cluster 도입(별도 운영 트랙).

---

## §11. 설정 통일

### 11-1. application.yml 4개 정정 범위

| 파일 | 현재 | 변경 |
|---|---|---|
| `api/src/main/resources/application.yml:117-122` | `spring.kis.{base-url, app-key, app-secret}` + `magicjar.kis.{base-url, app-key, app-secret}` 2종 prefix 혼재 | `magicjar.kis.{base-url.prod, system.app-key.prod, system.app-secret.prod}` 단일 |
| `batch/src/main/resources/application.yml:84-120` | `magicjar.kis.{mode, base-url, app-key, app-secret}` (real-kis profile) | `magicjar.kis.{base-url.prod, system.app-key.prod, system.app-secret.prod}` + profile 통일 (`@Profile("dev | prd")`로 일원화, `real-kis`/`stub-kis` deprecate) |
| `consumer/src/main/resources/application.yml:96-110` | `kis.{app-key, app-secret, account-no, hts-id, base-url, ws-url}` | `magicjar.kis.{base-url.{mode}, ws-url.{mode}, system.app-{key,secret}.{mode}}` (사용자 키는 DB에서 조회) |
| `*/src/main/resources/application-dev.yml` 또는 dev profile section | 분기 yml 각각 | 통일 prefix 사용 |

### 11-2. env 변수 매핑 단순화

| 신규 env (.env.example) | 매핑 |
|---|---|
| `KIS_PROD_APP_KEY` | `magicjar.kis.system.app-key.prod` |
| `KIS_PROD_APP_SECRET` | `magicjar.kis.system.app-secret.prod` |
| `KIS_VPS_APP_KEY` | `magicjar.kis.system.app-key.vps` (선택) |
| `KIS_VPS_APP_SECRET` | `magicjar.kis.system.app-secret.vps` (선택) |
| `KIS_PROD_BASE_URL` | `magicjar.kis.base-url.prod` (default 공식 host) |
| `KIS_VPS_BASE_URL` | `magicjar.kis.base-url.vps` (default 모의 host) |
| `KIS_PROD_WS_URL` | `magicjar.kis.ws-url.prod` |
| `KIS_VPS_WS_URL` | `magicjar.kis.ws-url.vps` |

기존 env (`KIS_APP_KEY`, `KIS_APP_SECRET`, `KIS_BASE_URL`, `KIS_WS_URL`, `KIS_MOCK_APP_KEY`, `KIS_MOCK_APP_SECRET`)는 deprecate. 사용자 .env 갱신은 본 PR 머지 시 1회.

docker-compose env 매핑(api/batch/consumer 3 서비스)도 신규 prefix로 일괄 정정.

---

## §12. 테스트 전략

### 12-1. 단위 테스트 (각 Redis 어댑터)

- `AbstractRedisKisAuthAdapter` — testcontainers Redis(또는 embedded redis). cache hit/miss/RLock acquired/RTopic publish/Redis 다운 fallback 5 시나리오.
- 모듈별 어댑터 — RestClient mock + Redis adapter mock. credentials 주입 정상/없음 분기.
- `KisAuthScope` / `KisApprovalScope` — `tokenKey()`/`lockKey()`/`approvalKey()` 정합.

### 12-2. RLock 동시성 테스트

- testcontainers Redis 1개 + JVM 단일에서 멀티스레드 100개 동시 `currentTokenOrIssue(scope)` → KIS mock 호출 1회만 발생 검증.
- 옵션: testcontainers + 두 JVM 프로세스(별도 main 클래스 fork) 동시 호출 → KIS mock 호출 1회 검증. (구현 복잡도 보고 plan에서 결정.)

### 12-3. RTopic publish/subscribe 테스트

- 두 어댑터 인스턴스(같은 RedissonClient or 두 RedissonClient + 동일 Redis) 생성. 한쪽 invalidate → 다른 쪽 L1 evict 검증.

### 12-4. 통합 (IT)

- Testcontainers postgres + redis + kafka. api·batch·consumer 3 컨텍스트 동시 부팅 + 동시 토큰 요청 → KIS mock(WireMock) 호출 1회 검증. KIS 호출 횟수 카운터 측정.
- WireMock + 401 응답 → invalidate + retry 동작 회귀 (PR #32 시나리오 정합).
- WireMock 시나리오 7·8 (R3-4 WEEK·MONTH) 회귀.

### 12-5. 회귀 영역 (11경로 각각)

기존 동작 보존:
- `KisChartCandleAdapter` — D1·W·MO 200 OK + 401 retry.
- `KisInstrumentInfoAdapter` — search-stock-info + inquire-price 200 OK + 401 retry.
- `KisInvestorTrendAdapter` / `KisOrderbookAdapter` / `KisRecentTradesAdapter` — 200 OK + 401 retry.
- `KisMarketIndexAdapter` — 200 OK + **401 시 표준 retry once 패턴 통일**(변종 폐기).
- `RealKisRestAdapter` (batch) — D1 backfill + WireMock IT 회귀.
- `KisCorpActionTokenHolder` 폐기 → `RealKisCorpActionAdapter` 회귀(권리주 일정 fetch).
- `MinuteCandleBackfillJob` — 분봉 backfill 회귀.
- `KisHttpSession` — RealKisOrderAdapter (1번 트랙 dogfooding 시 검증).
- `KisWsApprovalProvider` — 체결통보 WS subscribe.
- 시세 WS approval — 시세 WS connect 회귀.

### 12-6. KIS 호출 횟수 메트릭 측정

WireMock에 `verify(count, postRequestedFor("/oauth2/tokenP"))` 또는 `getAllServeEvents()` 카운트. multi-JVM 동시 진입 100회 시 KIS 호출 1회 검증.

---

## §13. 마이그레이션 / 호환성

### 13-1. 11경로 단계적 vs 일괄 전환

D1 결정 — 단일 사이클 일괄 전환. PR 분할은 plan에서 (예: ① domain port + scope + helper, ② api 6 어댑터 통합, ③ batch 3 통합, ④ consumer REST + WS 통합, ⑤ 설정 prefix + docker-compose + .env.example, ⑥ 회귀 검증). 분할 결정 권한 = architect (사용자 결정 A.3).

### 13-2. 운영 중 무중단 전환 가능성

가능. 단, 컨테이너 재시작 필요(설정 prefix 변경 + 신규 빈 + Redis 키 schema 신규).

흐름:
1. PR merge → 이미지 재빌드 → docker compose `up -d` 재기동
2. 첫 호출 시 Redis HASH miss → tryLock → fresh fetch → put. 정상 작동.
3. 기존 in-process 토큰은 재시작 시 자동 폐기. KIS 측 토큰은 24h 유효라 그대로 작동(재발급 시 옛 토큰 KIS 측 자동 invalidate).

다운타임: 컨테이너 재기동 시간(현재 ~30s × 3 서비스). 1번 트랙 dogfooding 시 감안.

### 13-3. 1번 트랙 dogfooding과의 의존성

R3-5c-3 머지 후 dogfooding 재개 권장. 이유:
- KisHttpSession이 port로 흡수되어 1번 트랙 코드 path 일부 변경. 회귀 검증 후 dogfooding이 안전.
- multi-tenant scope 도입으로 사용자 키 path가 명시화 → dogfooding이 본격 multi-user 시드 진입 토대.

dogfooding 실행 시점: 본 사이클 PR 머지 + 사용자 docker rebuild + KIS 키(.env 정정) 재등록 + 1주 매수 → 체결 → 매도 → 잔고 0 (track1 spec §11 Phase 7).

### 13-4. 1번 트랙(track1) 산출물과의 충돌 회피

track1 spec §6.2 — `domain/secret/SecretCipher.kt`, `domain/user/port/KisCredentialProvider.kt`. 본 사이클은 `KisCredentialProvider`에 `forSystem(mode)` 메서드만 추가. `forUser` 시그니처 유지(track1 산출물 호환).

`KisHttpSession` — track1이 PR #32 토큰 로직 추출 산출물. 본 사이클이 port로 위임시켜 thin wrapper화. track1 dogfooding 시점 충돌 방지를 위해 본 사이클 PR 머지 → dogfooding 재개 순서.

---

## §14. 관측성 / 운영

### 14-1. 로그 표준 (4 이벤트)

§3 D12 인용. 로그 prefix `[kis-token]` / `[kis-approval]` 분리.

### 14-2. 메트릭 (Micrometer)

§3 D12 인용. `actuator/metrics` 노출. Prometheus scrape 후 Grafana 패널은 별도 트랙.

### 14-3. 운영 가이드 갱신 범위

`docs/architecture/external-api-policy.md`:
- §2-2 "토큰" 행 — `KisTokenStorePort` 단일 store + Redis HASH 영속 + Redisson RLock 분산 lock 명시
- §2-3 "어댑터 매핑" — 11경로 → 단일 store 통합 표 갱신
- §5-2 "모니터링" — 신규 메트릭 4종 추가
- §5-3 "KIS 키 회전" — RTopic invalidate 절차 추가
- §6 변경 이력 — R3-5c-3 한 줄

---

## §15. 후속 작업 (out-of-scope)

| 항목 | 사유 / 후속 사이클 |
|---|---|
| `_workspace/operations/redis-outage-runbook.md` | 본 spec은 위치 명시만. plan 또는 별도 운영 트랙. |
| Redis Sentinel/Cluster 도입 | 운영 트랙. 본 사이클은 단일 Redis. |
| pyworker 통합 | pyworker는 KIS 직접 호출 0. 토큰 무관. |
| 1번 트랙 dogfooding 재개 | 본 사이클 머지 후 사용자가 직접 수행 (track1 spec §11 Phase 7). |
| multi-tenant 본격 도입 (5명 시드) | 본 사이클은 시스템 키 + dev 단일 사용자(`74683223`) 시드 위주 작동. multi-user 추가는 별도 (인증 ADR + SettingsModal). |
| KIS 응답 데이터(시세·캔들) 캐싱 / Redis 영속 | ADR 0050 (사용자 차트) 또는 별도 사이클. |
| Vault·secret manager 도입 | track1 산출물(`SecretCipher` 추상)이 미래 교체 path. 본 사이클은 master key + AES-GCM 그대로. |
| 실전 키(prod) 발급·전환 | 사용자 외부 절차. |
| KIS Pro 다계정 | 1번 트랙·본 사이클 비범위. |
| `/oauth2/revokeP` 자동화 | 사용자 명시 운영 (SettingsModal). |
| pre-MVP는 토큰 발급 횟수 P0 알람 자동화 | 메트릭 노출까지 본 사이클. Prometheus rule + Grafana는 별도 운영 트랙. |

---

## §16. 다음 단계

### 16-1. plan 작성 사이클

본 spec 사용자 review 후 plan 작성:
- PR 분할 결정 (예: 6 PR 또는 단일 PR — architect 판단)
- 작업 순서 (도메인 port → 공유 helper → 어댑터 모듈별 → 설정 prefix → 회귀 검증)
- 회귀 검증 단위 (어댑터별 단위 테스트 + multi-JVM IT)
- 사용자 액션 (.env 정정 시점, docker rebuild 시점)
- ADR 발행 (R3-5c-3-a/b/c 3건 또는 1건 통합 — plan에서 결정)

### 16-2. 사용자 컨펌 후 진입

본 spec의 12 결정사항(D1~D12) 중 사용자 재확인 필요 후보:
- D7 (Redis 다운 fallback policy) — multi-JVM race 위험 수용 확인
- D11 (설정 prefix 일원화) — 기존 env (`KIS_APP_KEY`) deprecate + .env 정정 부담 확인
- D9 (공유 helper 위치 — 신규 모듈 vs domain 패키지) — plan에서 결정 권한 위임 확인

나머지(D1·D2·D3·D4·D5·D6·D8·D10·D12)는 사용자 결정사항(배경 §A·D) 또는 자명한 결정으로 컨펌 불요.

---

## §17. 참조

- `docs/architecture/external-api-policy.md` — KIS API 제약 (§1-3 인용 source)
- `docs/architecture/master-architecture.md` — v2 비전 (시스템 본체 + 외부 API 옵션)
- `_workspace/arch/adr/0050-user-chart-cache-redis-zset.md` — Redis ZSet/RedissonClient 패턴 선례 (R3-5c-2 통합)
- `docs/superpowers/specs/2026-05-02-track1-kis-order-design.md` — `KisCredentialProvider`/`SecretCipher` 산출물 (track1 Phase 3)
- `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` — spec 형식 참조
- KIS 공식 문서: https://github.com/koreainvestment/open-trading-api
