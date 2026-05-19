# ADR 0088: persistence-shared 모듈 신설 — Outbox Repository wiring 결함 fix (모듈 경계 정합)

- 상태: Proposed (Phase 1 backend-core 구현 후 Accepted 격상 예정)
- 작성일: 2026-05-19
- 결정자: 사용자 sp-daewoon (최고 관리자, 옵션 B 명시 예정) + architect 합의
- 관계: ADR 0004(MSA 모듈 재구성)를 **갱신**(supersede 아님 — 6 모듈 → 7 모듈, 의존 방향·도메인 순수성 정책 보존). ADR 0054(pattern-engine 모듈) + ADR 0083(indicator-impl 모듈) 패턴 1:1 정합. ADR 0084(주문 path 안정성 P0-A) 후속.

## 맥락

qa cross-validation evidence (2026-05-19 system audit 8 sub-agent 보고서 통합) + backend-core 자가 진단 (PR #524·#523 운영 흐름 작동률 0% root cause 분석):

```
log: WARN  drain worker skip — OrderEventOutboxRepository 미등록
log: WARN  retry worker skip — NotificationOutboxRepository 미등록
```

consumer 모듈의 `OrderOutboxPublisherWorker`·`NotificationOutboxRetryWorker`가 다음 패턴으로 wiring:

```kotlin
class OrderOutboxPublisherWorker(
    private val outboxRepositoryProvider: ObjectProvider<OrderEventOutboxRepository>,
    private val notificationOutboxRepositoryProvider: ObjectProvider<NotificationOutboxRepository>,
    ...
) {
    fun drain() {
        val repo = outboxRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.warn("OrderEventOutboxRepository 미등록 — drain worker skip")
            return
        }
        ...
    }
}
```

근본 원인:

- `OrderEventOutboxRepository`·`NotificationOutboxRepository`·`StompEventRepository` port는 `domain` 모듈에 발행됨 (port 추상 GREEN).
- 어댑터 구현체 `OrderEventOutboxRepositoryAdapter`·`NotificationOutboxRepositoryAdapter`·`StompEventRepositoryAdapter` 는 **`api/persistence/` 모듈에만 단독 발행**됨.
- ADR 0004 §의존 방향 LOCK 정합으로 consumer 모듈 → api 모듈 의존 **0** (`consumer/build.gradle.kts` 정독 GREEN — `implementation(project(":api"))` 부재).
- 결과: consumer 컨테이너 부팅 시 `ObjectProvider<OrderEventOutboxRepository>` 는 **항상 empty** → drain·retry worker가 매 schedule cycle마다 `getIfAvailable() == null` → `log.warn` + skip → **outbox 영구 미발행**.
- 자본 흐름 영향: 사용자가 주문 issue → api `OrderEventOutboxJpaRepository`에 `order_event_outbox` row insert는 됨 → 그러나 consumer drain worker가 작동 0 → Kafka 발행 0 → 주문 path 운영 흐름 작동률 **0%**.

본 결함은 **ADR 0004 모듈 경계 + ObjectProvider graceful skip 패턴의 의도와 실제 wiring 불일치** — graceful skip은 "선택적 모듈 활성화" 의도였으나, 실제로는 **모듈 경계 결함 차단** 기능을 하지 못함.

ADR 0054(pattern-engine) + ADR 0083(indicator-impl) 패턴이 동일 결함을 이미 해결한 evidence — domain 의존 spring context 모듈 신설로 api·consumer 양쪽 빈 공유.

## 검토한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| A. consumer에 어댑터 중복 발행 | Gradle 변경 0 | **80지표 ADR 0083 alt D와 동일 결함** — DRY 위반 + sync 부담 + 알고리즘 drift risk + 어댑터·Entity·JpaRepository × 3 모듈 (×3 = 9 파일) 중복 |
| **B. `persistence-shared` 라이브러리 모듈 신설** (본 결정, 사용자 명시 옵션 B) | ADR 0004 §의존 방향 정합 보존 + api·consumer 동일 빈 공유 + 컨테이너 수 불변 (api·batch·consumer 3개 유지) + ADR 0054(pattern-engine) · ADR 0083(indicator-impl) 패턴 1:1 재사용 (운영 인지 비용 0) + Hexagonal port·adapter 분리 정석 | 모듈 1개 신설 비용 + ~10 파일 git mv + 패키지 rename + Flyway 권위 결정 (api 단독 유지 — V 시퀀스 충돌 차단) |
| C. domain에 어댑터 흡수 | 모듈 추가 0 | **ADR 0004 "domain Spring 의존 0" 정책 LOCK 위반** + Hexagonal port 순수성 위반 (port·adapter 동일 모듈) |
| D. api↔consumer cross-call REST | 모듈 추가 0 | **운영 부담 폭증** + 컨테이너 1개 down 시 outbox 차단 + 추가 회로 차단기 필요 + ADR 0004 §의존 방향 위반 가능 |
| E. consumer를 api 모듈로 흡수 | 어댑터 공유 즉시 | **ADR 0004 §컨테이너 격리 LOCK 위반** + KafkaListener·KIS WS connect 이중화 + 자본 path 위협 |

## 결정

**`persistence-shared` 라이브러리 모듈을 신설한다.**

- 위치: `persistence-shared/` (root 직속, `domain`·`pattern-engine`·`indicator-impl`과 동일 레벨)
- 종류: Kotlin + Spring Context + JPA (Spring Boot main 0, 라이브러리 jar — ADR 0083 indicator-impl 패턴 정합)
- 패키지 루트: `kr.spdaewoon.magicjar.persistence.shared.*`
- 의존: `persistence-shared` → `domain` + `spring-boot-starter-data-jpa` + `spring-boot-starter-data-redis`. `api`·`consumer` → `persistence-shared` 신규 의존 추가
- 컨테이너: 0 (라이브러리 jar — api·batch·consumer 컨테이너 수 불변)

### 모듈 매트릭스 (7 모듈 = ADR 0004 + ADR 0083 갱신)

```
.
├─ domain/             # Kotlin java-library (Spring 0 — 도메인 모델·port·DSL·정책)
├─ pattern-engine/     # Kotlin + Spring Context (Boot main 0) — 57 pattern detector + Redis (ADR 0054)
├─ indicator-impl/     # Kotlin + Spring Context (Boot main 0) — 80 KIS + 5 Native source 구현 (ADR 0083)
├─ persistence-shared/ # ← 신설 — Kotlin + Spring Context + JPA (Boot main 0) — cross-module shared adapter
├─ api/                # Spring Boot — implementation(":persistence-shared")
├─ batch/              # Spring Boot — indicator·outbox 미사용 (의존 추가 0)
└─ consumer/           # Spring Boot — implementation(":persistence-shared")
```

### 의존 방향 (단방향, ADR 0004 정합 보존)

- `domain` ← 모든 모듈 의존 (이전과 동일).
- `persistence-shared` → `domain`만 의존 (Spring Context + JPA 추가). 다른 응용 모듈 미의존 LOCK.
- `pattern-engine`·`indicator-impl` → `domain`만 의존 (이전과 동일).
- `api`, `consumer` → `persistence-shared`·`pattern-engine`·`indicator-impl` 의존 (라이브러리 jar).
- `api`, `batch`, `consumer`는 **여전히 서로 직접 의존하지 않는다** (ADR 0004 §의존 방향 LOCK 보존).
- `batch`는 `persistence-shared` 의존 추가 0 (outbox·stomp 미사용).
- `frontend`는 Gradle 그래프 밖.

cycle 검증: domain(0) → persistence-shared(domain) → api/consumer(domain + persistence-shared + pattern-engine + indicator-impl) — cycle 0건.

### 책임

`persistence-shared`는 다음 자원을 보유:

- **Order Outbox 어댑터** (3 파일)
  - `OrderEventOutboxRepositoryAdapter` (`@Repository`, domain port impl)
  - `OrderEventOutboxJpaRepository` (`@Repository`, Spring Data JPA interface)
  - `OrderEventOutboxJpaEntity` (`@Entity`, `order_event_outbox` table)

- **Notification Outbox 어댑터** (3 파일)
  - `NotificationOutboxRepositoryAdapter` (`@Repository`)
  - `NotificationOutboxJpaRepository`
  - `NotificationOutboxJpaEntity`

- **STOMP Event 어댑터** (3 파일)
  - `StompEventRepositoryAdapter` (`@Repository`)
  - `StompEventJpaRepository`
  - `StompEventJpaEntity`

- 패키지 layout (3 카테고리, 패키지 rename — ADR 0083 패턴):
  - `kr.spdaewoon.magicjar.persistence.shared.order.outbox` (3 파일 — Order Outbox)
  - `kr.spdaewoon.magicjar.persistence.shared.notification.outbox` (3 파일 — Notification Outbox)
  - `kr.spdaewoon.magicjar.persistence.shared.stomp` (3 파일 — STOMP Event)

### 잔류 자원 (api 모듈)

- `SystemConfigSlackAdapter` (api 단독 노출 endpoint 정합 — Settings 모달 종속 → api 잔류)
- `StompEventPersister` (api `@MessageMapping` 정합 — STOMP broker 직결 → api 잔류)
- `StompEventBackfillController` (api REST endpoint — api 잔류)
- `OrderEventOutboxJpaRepository` 호출 service (api `OrderService` 정합 — write side는 api에서 호출 → 의존 추가 후 그대로)

### Flyway 권위 (LOCK)

- `api` 모듈이 **단독으로** `db/migration/` 보유 (ADR 0006 §마이그레이션 정합 LOCK 보존).
- `persistence-shared`는 `@Entity`·`JpaRepository`만 보유 — Flyway script 0건.
- 이유: V 시퀀스 충돌 차단 + Flyway baseline single source LOCK.

### Spring 설정 (자동 wire)

- `persistence-shared/src/main/resources/META-INF/spring.factories` 또는 `@AutoConfiguration` 미사용 (ADR 0083 패턴 — `@Component` 기반 component-scan).
- `api`·`consumer` 양쪽 `@SpringBootApplication`의 component scan base package에 `kr.spdaewoon.magicjar.persistence.shared` 포함 또는 `@ComponentScan(basePackages = [...])` 명시.
- `@EnableJpaRepositories(basePackages = [...])` 양쪽 모두 `kr.spdaewoon.magicjar.persistence.shared` 포함.

### 마이그레이션 매트릭스 (~10 파일 + Gradle 4 파일)

- 패키지 rename + 파일 이관:
  - `api/persistence/order/Outbox*` (3 파일) → `persistence-shared/order/outbox/`
  - `api/persistence/notification/NotificationOutbox*` (3 파일) → `persistence-shared/notification/outbox/`
  - `api/persistence/stomp/StompEvent*` (3 파일) → `persistence-shared/stomp/`
- Gradle 변경:
  - `settings.gradle.kts` — `include("persistence-shared")` row 추가
  - `persistence-shared/build.gradle.kts` 신설
  - `api/build.gradle.kts` — `implementation(project(":persistence-shared"))` row 추가
  - `consumer/build.gradle.kts` — `implementation(project(":persistence-shared"))` row 추가
- 테스트 이관 (선택):
  - `api/test/.../OrderEventOutbox*Test.kt`·`NotificationOutbox*Test.kt`·`StompEvent*Test.kt` (각각 1~2 파일) — 위치 유지 가능 (의존 그래프 정합 GREEN — api → persistence-shared로 참조 가능). 또는 `persistence-shared/src/test/`로 이관 (선택, backend-core 자율).
- 파일 매트릭스 풀세트: `_workspace/specs/2026-05-19-persistence-shared-module-outbox-wiring-spec.md` §4 정독.

## 결과

긍정:
- qa cross-validation evidence + backend-core 자가 진단 PR #524·#523 운영 흐름 작동률 **0% → 100% 회복** — consumer 컨테이너 `ObjectProvider<OrderEventOutboxRepository>` GREEN 등록 → drain·retry worker GREEN 동작 → outbox Kafka 발행 GREEN → 주문 path 자본 흐름 GREEN.
- ADR 0004 §의존 방향 LOCK 정합 보존 — api·batch·consumer 서로 직접 의존 0 유지.
- ADR 0054(pattern-engine) + ADR 0083(indicator-impl) 패턴 1:1 재사용 — 운영 인지 비용 0 (sub-agent + 사용자 모두 동일 패턴 학습 완료).
- Hexagonal port·adapter 분리 정석 — port(domain) + adapter(persistence-shared) + write/read use cases(api·consumer).
- Flyway 권위 LOCK 보존 — api 단독 baseline GREEN.

부정:
- 모듈 1개 신설 비용 — `persistence-shared/build.gradle.kts` + Gradle 4 파일 변경.
- 9 파일 git mv + 패키지 rename — backend-core Phase 1 1 PR ~10 파일 + ~200 LOC.
- 컴포넌트 scan path 변경 — api·consumer `@SpringBootApplication` base package 또는 `@EnableJpaRepositories` 명시 row 1~2건 추가.

중립:
- 컨테이너 수 불변 (api·batch·consumer 3개) — docker compose 변경 0.
- DB 스키마 변경 0 — `@Entity` 위치만 이동, 테이블·컬럼 변경 0 + Flyway script 신규 0.
- 자본 path 영향 — 본 spec PR은 architect docs only (운영 코드 0 touch). Phase 1 backend-core 구현 PR에서 자본 path 어댑터 이관 → 회귀 가능성 0 (rename + 빈 위치 변경만).

## 사용자 명시 인용

(2026-05-19 KST orchestrator 디스패치 의제 통합 — 본 ADR 발행은 사용자 명시 옵션 B 채택 후 Accepted 격상 예정)

> "qa는 위 핫픽스 다 처리하고 보고내용 다 처리 끝내고 테스트 진행할래. 지금 당장은 각 개발영역과 아키테처를 믿고 있어"
>
> → qa는 마지막. 핫픽스 + 보고 처리 우선. 본 사이클은 architect 영역 자율 진행 (신뢰 기반).

옵션 B 권장 사유 (architect 명시):
- 옵션 A (consumer 어댑터 중복) → DRY 위반 + sync 부담 + 알고리즘 drift risk (3 어댑터 × 9 파일 중복) → reject
- 옵션 B (persistence-shared 모듈) → ADR 0054·0083 패턴 1:1 + 운영 인지 비용 0 + 모듈 경계 정합 영구 LOCK → **★ 채택**
- 옵션 C (domain 흡수) → ADR 0004 §domain Spring 의존 0 LOCK 위반 → reject
- 옵션 D (cross-call REST) → 운영 부담 폭증 + 추가 차단기 필요 → reject

## 관련 결정

- ADR 0004 갱신 — 6 → 7 모듈 (persistence-shared 추가, 의존 방향 LOCK 보존)
- ADR 0054(pattern-engine) — 라이브러리 모듈 패턴 baseline
- ADR 0083(indicator-impl) — 본 ADR 패턴 1:1 정합 (sub-agent + 사용자 학습 완료)
- ADR 0006(PostgreSQL 단일) — Flyway 권위 api 단독 유지 LOCK 보존
- ADR 0084(주문 path 안정성 P0-A) — 본 ADR이 운영 흐름 작동률 0% 결함 fix의 후속 해결

## 변경 이력

| 일시 | 변경 | 담당 |
|------|------|------|
| 2026-05-19 | 초기 발행 (Proposed) — qa cross-validation evidence + backend-core 자가 진단 PR #524·#523 + 8 sub-agent system audit 통합 evidence. ADR 0054·0083 패턴 1:1 재사용 (architect, opus 4.7 1M) | architect |
