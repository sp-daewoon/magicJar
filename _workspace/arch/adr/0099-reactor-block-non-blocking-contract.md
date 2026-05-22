# ADR 0099: Reactor `.block()` non-blocking 계약 정합 패턴 LOCK

- 상태: **Accepted (2026-05-23 — architect 결재, 코덱스 협업 P0 4건 일괄 fix 사이클 PR-2 — v3.8.0 hotfix root cause 정정)**
- 작성일: 2026-05-23
- 결정자: architect (자동매매 본질 결함 매트릭스 §3 + 횡단 컨벤션 본문화)
- 영역: 횡단 (api·consumer·batch 모듈 — WebClient + `.block()` 호출 모든 경로)
- Amends: 없음 (신규 횡단 컨벤션 LOCK)
- 관련 ADR: 0037 (KIS API Rate Limit — WebClient + Bucket4j 호출 path) · 0038 (KIS OAuth 24h 토큰 — `KisAuthService.fetchTokenP()` reactor block 대표 사례) · 0048 (KIS WebSocket — reconnect callback caller thread) · 0084 (주문 path 안정성 P0 4건 — WS race silent skip + reactor block 정합) · 0068 (T-O 주문 실행 — KisOrderInquireCcldHttpAdapter reactor block caller)

## 1. 배경

### 1-1. v3.8.0 cut hotfix가 실측 ERROR 잔존 — root cause 오인

v3.8.0 cut 직전 dogfooding evidence:

```
2026-05-22 14:23:18.445 [reactor-http-nio-3] ERROR — BlockHound detected blocking call
  in non-blocking thread: reactor-http-nio-3
  at reactor.core.publisher.Mono.block(Mono.java:1737)
  at kr.spdaewoon.magicjar.api.kis.adapter.KisOrderInquireCcldHttpAdapter.fetchExecutions
```

v3.8.0 cut 시 적용한 hotfix:

```kotlin
// hotfix v3.8.0 (잘못된 fix)
return webClient.get()
    .uri(...)
    .retrieve()
    .bodyToMono(Response::class.java)
    .publishOn(Schedulers.boundedElastic())   // ← 이게 root cause cover 안 함
    .block()
```

**hotfix 후에도 동일 ERROR 잔존** — 실측 운영 로그 grep 결과 `reactor-http-nio-*` thread에서 BlockHound 차단 그대로.

### 1-2. root cause — `publishOn` vs `subscribeOn` 의미 차이 오인

| operator | 실행 위치 변경 대상 | subscription·request·`.block()` 호출 thread |
|----------|-------------------|----------------------------------------------|
| `publishOn(Scheduler)` | downstream operator (map·filter·doOnNext 등) | **caller thread 그대로** |
| `subscribeOn(Scheduler)` | subscription chain 전체 (source emission + downstream) | **worker thread로 위임** |

v3.8.0 hotfix는 `publishOn(boundedElastic)`를 적용했으나:

1. `webClient.get()...retrieve().bodyToMono()` — subscription 시 HTTP request 발사
2. `.publishOn(boundedElastic)` — 응답 도착 후 downstream만 boundedElastic로 publishOn
3. `.block()` — **caller thread**에서 호출 (reactor-http-nio면 BlockHound 차단)

→ `publishOn`은 응답 수신 후 downstream 처리 위치만 변경, **subscription + `.block()` 호출 thread는 caller 그대로**.

`subscribeOn(boundedElastic)`은 subscription chain 전체를 worker로 위임 → WebClient request + `.block()` 모두 worker thread에서 실행 → BlockHound non-issue.

### 1-3. magicJar 통신 thread 모델

| thread prefix | source | blocking 허용 여부 |
|---------------|--------|------------------|
| `reactor-http-nio-*` | Netty event-loop (WebClient HTTP 인입) | **금지** (non-blocking 전용 — BlockHound 차단 대상) |
| `boundedElastic-*` | Schedulers.boundedElastic() worker pool | 허용 (blocking 의도 worker pool) |
| `scheduler-*` | @Scheduled cron worker (Spring TaskScheduler) | 허용 |
| `parallel-*` | Schedulers.parallel() (CPU bound) | 권장 안 함 (CPU bound 전용) |
| `kafka-*` | Kafka listener container thread | 허용 (Spring Kafka standard) |
| `http-nio-*` | Tomcat servlet thread | 허용 (Spring MVC standard) |

### 1-4. magicJar async re-entry 경로 — KIS WS reconnect callback

v3.7.x dogfooding 결함 root cause:

```
KIS WebSocket disconnect
  → Netty event-loop callback
  → onDisconnect handler
  → KisWebSocketReconnector.attemptReconnect()
  → KisAuthService.refreshToken()   // ← 여기서 .block() 호출
  → BlockHound detected (reactor-http-nio-*)
```

KIS WS reconnect callback이 **reactor-http-nio thread에서** `.block()`을 호출 → caller가 이미 non-blocking thread → `subscribeOn`도 caller 보호 못 함 (caller 자체가 non-blocking thread).

→ **2단 방어 의무**: callee `subscribeOn` + caller `isInNonBlockingThread` 가드.

### 1-5. 결함 영향 영역 — 3건 LIVE 발견

PR-1 사이클(market-data dispatch)에서 hotfix:

| 파일 | 결함 | 본 ADR 적용 패턴 |
|------|------|----------------|
| `KisOrderInquireCcldHttpAdapter` | publishOn 잘못 적용 | subscribeOn 정정 + caller 가드 |
| `KisOrderInquireDailyCcldHttpAdapter` | publishOn 잘못 적용 | subscribeOn 정정 + caller 가드 |
| `KisExecutionBackfillService` | caller가 reactor-http-nio (WS callback) | `Schedulers.isInNonBlockingThread()` 가드 |

→ 본 ADR은 root cause 본문 LOCK + 컨벤션 등재 + 양 path 적용 의무.

---

## 2. 결정

### 2-1. 결정 1 — WebClient + `.block()` 동시 사용 시 **2단 방어 의무**

#### 2-1-1. callee 측 — `subscribeOn(Schedulers.boundedElastic())` 부착

operator chain에 **반드시** `subscribeOn(boundedElastic)` 부착. subscription chain 전체를 worker thread로 위임.

```kotlin
// ✅ 올바른 패턴
fun fetchExecutions(...): Response {
    return webClient.get()
        .uri(...)
        .retrieve()
        .bodyToMono(Response::class.java)
        .subscribeOn(Schedulers.boundedElastic())   // ← subscription chain 전체 위임
        .block() ?: throw KisApiException("empty response")
}

// ❌ 잘못된 패턴 (v3.8.0 hotfix)
fun fetchExecutions(...): Response {
    return webClient.get()
        .uri(...)
        .retrieve()
        .bodyToMono(Response::class.java)
        .publishOn(Schedulers.boundedElastic())     // ← downstream만 위임 (caller thread 그대로)
        .block() ?: throw KisApiException("empty response")
}
```

#### 2-1-2. caller 측 — async re-entry path에 `isInNonBlockingThread()` 가드

caller가 reactor-http-nio thread일 가능성이 있는 path(WS callback·@Scheduled가 event-loop 빌려쓰는 경우·Mono callback chain 등)에서 worker thread로 re-schedule 의무:

```kotlin
// ✅ 올바른 패턴
fun reconnectAndRefresh() {
    if (Schedulers.isInNonBlockingThread()) {
        Schedulers.boundedElastic().schedule {
            kisAuthService.refreshToken()    // .block() 호출 caller
        }
    } else {
        kisAuthService.refreshToken()
    }
}
```

### 2-2. 결정 2 — 5단 진단 체크리스트 LOCK

신규 WebClient + `.block()` 코드 작성·결함 진단 시 의무 체크리스트:

#### 2-2-1. step 1 — 에러 thread 이름으로 caller 식별

ERROR 로그의 thread 이름 prefix 확인:

| thread prefix | caller 종류 | 가드 필요 |
|---------------|------------|----------|
| `reactor-http-nio-*` | WebClient 응답 callback / WS callback | ★★★ 의무 |
| `boundedElastic-*` | 이미 worker thread | 0 (이미 안전) |
| `scheduler-*` | @Scheduled cron | 0 (Spring TaskScheduler는 blocking OK) |
| `parallel-*` | CPU worker | ★ caller 의도 확인 |
| `kafka-*` | Kafka listener | 0 (Spring Kafka standard) |
| `http-nio-*` | Tomcat servlet | 0 |

#### 2-2-2. step 2 — `publishOn` vs `subscribeOn` 의미 차이 명확화

- `publishOn(Scheduler)` — downstream operator만 위임 (subscription·request·`.block()` caller thread)
- `subscribeOn(Scheduler)` — subscription chain 전체 위임 (모두 worker thread)

→ `.block()` 호출 thread를 worker로 위임하려면 `subscribeOn` 의무. `publishOn`은 응답 수신 후 처리 부담만 위임 (캡슐화).

#### 2-2-3. step 3 — callee + caller 2단 방어

- callee (WebClient + `.block()`) → `subscribeOn(boundedElastic)` 부착
- caller (async re-entry 가능 path) → `isInNonBlockingThread()` 가드 + `Schedulers.boundedElastic().schedule { ... }`

#### 2-2-4. step 4 — BlockHound 호환성 통합 테스트 의무

신규 WebClient + `.block()` 코드 머지 전 통합 테스트에서 BlockHound install + 5 시나리오 (정상·timeout·circuit break·WS reconnect·@Scheduled 동시) 검증 의무. consumer 모듈 우선 (KIS WS callback caller 영역).

#### 2-2-5. step 5 — async re-entry path 일괄 점검

신규 코드 머지 전 다음 5종 path 점검 의무:

1. **WS callback** — KIS WS message·disconnect·reconnect handler
2. **Kafka listener** — `@KafkaListener` (Spring Kafka는 worker thread지만 inner async re-entry 점검)
3. **@Scheduled cron** — Spring TaskScheduler (대부분 안전이나 inner Mono callback chain 점검)
4. **CompletableFuture.thenApply** — non-blocking thread 잔류 가능 callback
5. **Mono callback chain** — `doOnNext`·`doOnSuccess`·`flatMap` 등 callback 내부 `.block()` 호출

### 2-3. 결정 3 — 컨벤션 등재 의무

`docs/architecture/code-convention.md` §reactor 신규 섹션 — 본 ADR 5단 체크리스트 본문 인용 + Before/After 예시 + async re-entry path 5종 + BlockHound 통합 테스트 의무. 본 PR-2에서 동시 등재.

---

## 3. 트레이드오프 및 대안

### 3-1. 검토한 4 옵션

| 옵션 | 본문 | 평가 | 채택 |
|------|------|------|------|
| **A. 본 ADR (callee subscribeOn + caller isInNonBlockingThread 2단 방어)** | 양 path 합성 defense in depth | ★★★ root cause LOCK + race 0 | **채택** |
| B. callee subscribeOn만 + caller 가드 0 | subscription 전체 위임 | caller가 이미 non-blocking thread면 subscribeOn caller에서 호출 thread 자체 차단됨 (정정: subscribeOn은 source 시점 thread 변경 — caller block 호출 시점은 여전히 caller. caller가 non-blocking이면 block() 자체가 차단됨) | 불충분 |
| C. caller 가드만 + callee subscribeOn 0 | re-schedule 후 worker에서 호출 | 단일 path는 OK이나 worker thread 전염 시 또 다른 caller에서 미가드 risk | 불충분 |
| D. 전면 reactive 전환 (block() 0) | 모든 caller가 Mono/Flux 반환 | spring batch / @Scheduled 등 imperative 영역 비현실 | post-MVP |

옵션 A 채택. 단점: 코드 패턴 2건 의무 (callee + caller) — 컨벤션 등재로 cover.

### 3-2. 거부 검토 옵션

- **BlockHound 비활성화** — 결함 자체 은폐 risk + 운영 단계 진입 부적합 → 거부
- **boundedElastic worker pool 크기 증대** — race 본질 cover 못함 → 거부
- **`.block(Duration)` timeout 단축** — race 본질 cover 못함 + KIS API latency 고려 부적합 → 거부
- **단일 ADR vs 횡단 컨벤션 분리** — 본 ADR이 정책 권위 + 컨벤션은 본 ADR 본문 인용 (정합 1:1 LOCK) → 통합

---

## 4. 결과 및 영향

### 4-1. 직접 영향 (PR-2 본 ADR)

- 횡단 컨벤션 본문 LOCK (code-convention.md §reactor 신규 — PR-2에서 동시 등재)
- 코드 0 touch (본 ADR PR — adapter hotfix는 PR-1 market-data 사이클 흡수)
- 자본 영향 0 (본 ADR PR)

### 4-2. 후속 영향

| 항목 | 본문 | 사이클 |
|------|------|--------|
| 적용 대상 3건 | KisOrderInquireCcldHttpAdapter + KisOrderInquireDailyCcldHttpAdapter + KisExecutionBackfillService | PR-1 market-data |
| BlockHound 통합 테스트 | consumer 모듈 우선 + 5 시나리오 | R3 일괄 dispatch |
| 컨벤션 등재 | code-convention.md §reactor 본 ADR 5단 체크리스트 + Before/After + async re-entry 5종 | PR-2 (본 PR) |
| 신규 코드 review checklist | WebClient + `.block()` 코드 review 시 5단 체크리스트 강제 | architect/qa Visual 의무 |

### 4-3. 운영 ADR 양식 3 슬롯 (헌법 §9-5 정합)

| 슬롯 | 본문 |
|------|------|
| **SLO 영향** | S-1 (시그널→주문 P95<5s) 직접 영향 0 — subscribeOn switching은 micro-second 단위. 단, BlockHound 차단 ERROR 잔존 시 caller chain 전체 차단 risk → 본 ADR 적용 후 P95<5s LOCK 회복. |
| **rollback plan** | 컨벤션 등재만 revert 가능 (PR-1 adapter hotfix는 별 PR). 컨벤션 revert 시 신규 코드 자체 검증 의무 0 → 결함 재발 risk. |
| **관측 매트릭스** | (1) BlockHound ERROR log 0건 (운영 grep) (2) `reactor-http-nio-*` thread에서 `.block()` 호출 0건 (3) WebClient timeout 발생률 baseline 유지 (4) qa Visual 회귀 시나리오 "WS reconnect → token refresh → 시그널 정상" (5) integration test BlockHound install 5 시나리오 ALL GREEN |

---

## 5. ADR cross-link

| ADR | cross 사유 |
|-----|-----------|
| 0037 (KIS API Rate Limit) | WebClient + Bucket4j 호출 path 모두 본 ADR 적용 의무 |
| 0038 (KIS OAuth 24h 토큰) | `KisAuthService.fetchTokenP()` reactor block 대표 사례 — refresh callback이 WS reconnect 영역과 정합 |
| 0048 (KIS WebSocket 41건) | reconnect callback caller thread가 reactor-http-nio — caller 가드 본 ADR 적용 |
| 0084 (주문 path 안정성 P0 4건) | WS race silent skip + reactor block 정합 — P0-3 #4 정합 |
| 0068 (T-O 주문 실행) | KisOrderInquireCcldHttpAdapter caller가 본 ADR 적용 대상 |
| 0097 (ExecutionPositionUpdatePort) | KisExecutionBackfillService가 본 port 호출 caller → reactor block 정합 LOCK |

---

## 6. 변경 이력

| 일자 | 변경 |
|------|------|
| 2026-05-23 | 초안 + Accepted (architect, opus 4.7 1M) — v3.8.0 hotfix root cause 정정 + 코덱스 협업 P0 4건 일괄 fix 사이클 PR-2 + code-convention §reactor 본 ADR 5단 체크리스트 등재 LOCK |
