# P0-A: persistence-shared 모듈 신설 — Outbox Repository wiring 결함 fix spec

- **발행일**: 2026-05-19 KST
- **작성자**: architect (opus 4.7 1M)
- **권위 ADR**: 0088 (Proposed)
- **dispatch**: Phase 1 backend-core 단일 PR 위임 (~10 파일 + Gradle 4 파일, ~200 LOC)
- **자본 영향**: 본 spec PR 0 (architect docs only) / Phase 1 구현 PR 100% (운영 흐름 작동률 0% → 100% 회복)
- **v3.x cut 후보**: v3.7.0 minor (사용자 명시 minor 진행 — `feedback_release_policy_patch_first` 정합)

---

## §1. 결함 진단 (qa cross-validation evidence)

### 1-1. 증상

consumer 컨테이너 docker logs에서 다음 WARN 반복:

```
WARN  drain worker skip — OrderEventOutboxRepository 미등록
WARN  retry worker skip — NotificationOutboxRepository 미등록
```

매 schedule cycle마다 발생 (`OrderOutboxPublisherWorker.drain()` + `NotificationOutboxRetryWorker.retry()`).

### 1-2. 자본 흐름 영향

운영 흐름 작동률 **0%** (backend-core 자가 진단 PR #524·#523 evidence):

1. 사용자가 주문 issue → `api/OrderService` → `OrderEventOutboxJpaRepository`에 `order_event_outbox` row insert GREEN.
2. consumer `OrderOutboxPublisherWorker.drain()` schedule 시 `outboxRepositoryProvider.getIfAvailable()` 호출 → **always null**.
3. `log.warn("OrderEventOutboxRepository 미등록 — drain worker skip")` → return → drain 실행 **0건**.
4. Kafka `order.requested` 토픽 발행 **0건** → 주문 path 운영 흐름 차단.

### 1-3. Root cause

(8 sub-agent system audit 보고서 + backend-core 자가 진단 evidence 통합)

- **port 발행 위치 GREEN**: `OrderEventOutboxRepository`·`NotificationOutboxRepository`·`StompEventRepository` port는 `domain/.../port/`에 발행됨 (Hexagonal 정합).
- **adapter 발행 위치 FAIL**: 어댑터 구현체 `OrderEventOutboxRepositoryAdapter`·`NotificationOutboxRepositoryAdapter`·`StompEventRepositoryAdapter` 는 **`api/persistence/` 모듈 단독 발행**.
- **모듈 의존 LOCK**: `consumer/build.gradle.kts` 정독 GREEN — `implementation(project(":api"))` 부재 (ADR 0004 §의존 방향 LOCK 정합).
- 따라서 consumer 컨테이너 `@SpringBootApplication` ComponentScan에 api 패키지가 포함되지 않음 + `@EnableJpaRepositories` scan도 api 미포함 → ObjectProvider always empty.

`ObjectProvider<>` 패턴은 "선택적 모듈 활성화" 의도였으나, 실제로는 **모듈 경계 결함을 graceful skip로 은폐**하는 결함을 만들어냄 → 자본 흐름 작동률 0% 미감지 위험.

---

## §2. 현행 코드 매트릭스 (정독 evidence)

### 2-1. domain port (3 파일 — 위치 GREEN, 변경 0)

- `domain/.../order/event/port/OrderEventOutboxRepository.kt`
- `domain/.../notification/port/NotificationOutboxRepository.kt`
- `domain/.../stomp/port/StompEventRepository.kt`

### 2-2. api 어댑터 (9 파일 — 이관 대상)

```
api/src/main/kotlin/kr/spdaewoon/magicjar/api/persistence/
├─ order/
│  ├─ OrderEventOutboxRepositoryAdapter.kt
│  ├─ OrderEventOutboxJpaRepository.kt
│  └─ OrderEventOutboxJpaEntity.kt
├─ notification/
│  ├─ NotificationOutboxRepositoryAdapter.kt
│  ├─ NotificationOutboxJpaRepository.kt
│  └─ NotificationOutboxJpaEntity.kt
│  └─ SystemConfigSlackAdapter.kt  ← 잔류 (api 단독)
└─ stomp/
   ├─ StompEventRepositoryAdapter.kt
   ├─ StompEventJpaRepository.kt
   └─ StompEventJpaEntity.kt
```

### 2-3. consumer ObjectProvider 결함 위치 (2 파일 — wiring 갱신)

- `consumer/.../order/OrderOutboxPublisherWorker.kt:45·48` — `ObjectProvider<OrderEventOutboxRepository>` + `ObjectProvider<NotificationOutboxRepository>`
- `consumer/.../notification/NotificationOutboxRetryWorker.kt:32` — `ObjectProvider<NotificationOutboxRepository>`

### 2-4. consumer Gradle 의존 (현 상태)

```kotlin
dependencies {
    implementation(project(":domain"))
    implementation(project(":pattern-engine"))
    implementation(project(":indicator-impl"))
    // implementation(project(":api"))   ← 부재 (ADR 0004 §의존 방향 LOCK)
    ...
}
```

### 2-5. settings.gradle.kts (현 상태)

```kotlin
include(
    "domain",
    "pattern-engine",
    "indicator-impl",
    "api",
    "batch",
    "consumer",
)
```

---

## §3. 신규 모듈 구조 (ADR 0083 패턴 1:1)

### 3-1. `persistence-shared/build.gradle.kts` (신설)

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("io.spring.dependency-management")
    // Spring Boot main 0 — 라이브러리 jar (ADR 0083 indicator-impl 패턴 정합)
    // 단, BOM/dependency-management 정합을 위해 spring-boot plugin은 미적용 또는
    // managementOnly 적용 (backend-core 자율 — ADR 0083 indicator-impl 정독 후 결정)
}

dependencies {
    implementation(project(":domain"))

    // JPA + Redis (어댑터 구현 필요 의존)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // PostgreSQL driver (테스트·런타임)
    runtimeOnly("org.postgresql:postgresql")

    // 라이브러리 jar — Boot main 0
    // Flyway 의존 0 (api 단독 baseline 유지 — ADR 0006 §마이그레이션 LOCK)
}

// 라이브러리 jar로 발행 (Boot bootJar 차단)
tasks.named<Jar>("jar") {
    archiveClassifier.set("")
}
```

backend-core 자율 결정 영역:
- `kotlin("plugin.spring")` + `kotlin("plugin.jpa")` 적용 여부 (ADR 0083 indicator-impl 패턴 정독 후)
- BOM dependency-management 적용 방식
- 테스트 영역 의존 (테스트 이관 시)

### 3-2. 패키지 layout

```
persistence-shared/
└─ src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/
   ├─ order/outbox/
   │  ├─ OrderEventOutboxRepositoryAdapter.kt   ← @Repository, domain port impl
   │  ├─ OrderEventOutboxJpaRepository.kt       ← Spring Data JPA interface
   │  └─ OrderEventOutboxJpaEntity.kt           ← @Entity, order_event_outbox table
   ├─ notification/outbox/
   │  ├─ NotificationOutboxRepositoryAdapter.kt
   │  ├─ NotificationOutboxJpaRepository.kt
   │  └─ NotificationOutboxJpaEntity.kt
   └─ stomp/
      ├─ StompEventRepositoryAdapter.kt
      ├─ StompEventJpaRepository.kt
      └─ StompEventJpaEntity.kt
```

### 3-3. Spring 설정 (component scan)

`api`·`consumer` 양쪽 `@SpringBootApplication` 또는 별도 `@Configuration`에:

```kotlin
@SpringBootApplication
@ComponentScan(basePackages = [
    "kr.spdaewoon.magicjar.api",                  // (또는 consumer)
    "kr.spdaewoon.magicjar.persistence.shared",   // ← 신규
])
@EnableJpaRepositories(basePackages = [
    "kr.spdaewoon.magicjar.api.persistence",      // (잔류 어댑터)
    "kr.spdaewoon.magicjar.persistence.shared",   // ← 신규
])
@EntityScan(basePackages = [
    "kr.spdaewoon.magicjar.api.persistence",
    "kr.spdaewoon.magicjar.persistence.shared",
])
class ApiApplication
```

backend-core 자율 결정 — 현행 `@SpringBootApplication` 기본 scan 활용 가능 시 명시 row 최소화 (ADR 0083 indicator-impl 패턴 정독 후 결정).

---

## §4. 이관 매트릭스 (9 파일 + Gradle 4 파일)

### 4-1. 어댑터 git mv + 패키지 rename

| 현재 위치 | 이관 후 위치 | 패키지 rename |
|---|---|---|
| `api/persistence/order/OrderEventOutboxRepositoryAdapter.kt` | `persistence-shared/order/outbox/OrderEventOutboxRepositoryAdapter.kt` | `kr.spdaewoon.magicjar.api.persistence.order` → `kr.spdaewoon.magicjar.persistence.shared.order.outbox` |
| `api/persistence/order/OrderEventOutboxJpaRepository.kt` | `persistence-shared/order/outbox/OrderEventOutboxJpaRepository.kt` | 동일 |
| `api/persistence/order/OrderEventOutboxJpaEntity.kt` | `persistence-shared/order/outbox/OrderEventOutboxJpaEntity.kt` | 동일 |
| `api/persistence/notification/NotificationOutboxRepositoryAdapter.kt` | `persistence-shared/notification/outbox/NotificationOutboxRepositoryAdapter.kt` | `kr.spdaewoon.magicjar.api.persistence.notification` → `kr.spdaewoon.magicjar.persistence.shared.notification.outbox` |
| `api/persistence/notification/NotificationOutboxJpaRepository.kt` | `persistence-shared/notification/outbox/NotificationOutboxJpaRepository.kt` | 동일 |
| `api/persistence/notification/NotificationOutboxJpaEntity.kt` | `persistence-shared/notification/outbox/NotificationOutboxJpaEntity.kt` | 동일 |
| `api/persistence/stomp/StompEventRepositoryAdapter.kt` | `persistence-shared/stomp/StompEventRepositoryAdapter.kt` | `kr.spdaewoon.magicjar.api.persistence.stomp` → `kr.spdaewoon.magicjar.persistence.shared.stomp` |
| `api/persistence/stomp/StompEventJpaRepository.kt` | `persistence-shared/stomp/StompEventJpaRepository.kt` | 동일 |
| `api/persistence/stomp/StompEventJpaEntity.kt` | `persistence-shared/stomp/StompEventJpaEntity.kt` | 동일 |

### 4-2. 잔류 (api 모듈)

- `api/persistence/notification/SystemConfigSlackAdapter.kt` — api Settings 모달 종속 → 잔류
- `api/messaging/StompEventPersister.kt` — api STOMP `@MessageMapping` 정합 → 잔류
- `api/controller/stomp/StompEventBackfillController.kt` — api REST endpoint → 잔류

### 4-3. 테스트 이관 (선택 — backend-core 자율)

권장 옵션 A (테스트도 함께 이관):
- `api/test/.../NotificationOutboxRepositoryAdapterTest.kt` → `persistence-shared/test/`
- `api/test/.../StompEventRepositoryAdapterTest.kt` → `persistence-shared/test/`
- `api/test/.../StompEventJpaEntityJsonCastTest.kt` → `persistence-shared/test/`

옵션 B (api 잔류 — 의존 그래프 정합 GREEN):
- 테스트는 api에 그대로 유지 (의존 그래프 정합 — api → persistence-shared로 어댑터 참조 가능).
- backend-core 자율 결정.

### 4-4. import 갱신 매트릭스

이관 후 다음 호출 지점에서 import 갱신 필요:

- `api/OrderService.kt` 또는 유사 — `OrderEventOutboxJpaRepository` import 갱신
- `api/messaging/StompEventPersister.kt` — `StompEventJpaRepository` import 갱신
- `api/controller/stomp/StompEventBackfillController.kt` — `StompEventJpaRepository` import 갱신
- `api/notification/...` — `NotificationOutboxJpaRepository` import 갱신
- `consumer/.../OrderOutboxPublisherWorker.kt` — `ObjectProvider<OrderEventOutboxRepository>` 직접 빈 주입으로 변환 가능 (ObjectProvider 제거 OR 유지 — backend-core 자율)
- `consumer/.../NotificationOutboxRetryWorker.kt` — 동일

### 4-5. Gradle 변경 (4 파일)

| 파일 | 변경 |
|---|---|
| `settings.gradle.kts` | `include("persistence-shared")` row 추가 (alphabetical 정렬 또는 ADR 0083 패턴 따라 `indicator-impl` 직후) |
| `persistence-shared/build.gradle.kts` | **신설** (§3-1 본문) |
| `api/build.gradle.kts` | `implementation(project(":persistence-shared"))` row 추가 (`indicator-impl` 직후 권장) |
| `consumer/build.gradle.kts` | `implementation(project(":persistence-shared"))` row 추가 (`indicator-impl` 직후 권장) |

### 4-6. ObjectProvider 패턴 처리 (선택 — backend-core 자율)

본 spec 완료 후 `ObjectProvider<>` 사용은 더 이상 필요 없음 (어댑터가 항상 wiring GREEN).

옵션 A (graceful 패턴 제거 — 권장):
```kotlin
class OrderOutboxPublisherWorker(
    private val outboxRepository: OrderEventOutboxRepository,         // 직접 주입
    private val notificationOutboxRepository: NotificationOutboxRepository,
    ...
)
```

옵션 B (ObjectProvider 유지 — 결함 차단 lens 보존):
- 패턴 유지 시 `log.warn`은 fail-fast로 격상 권장 (silent skip은 본 결함 재발 risk).

backend-core 자율 결정 — 옵션 A 권장 (단순화 + 영구 결함 차단).

---

## §5. sub-agent dispatch plan

### 5-1. Phase 1 — backend-core 단일 PR

| 항목 | 값 |
|---|---|
| 담당 | backend-core (opus 4.7) |
| 파일 수 | ~10 (어댑터 9 + Gradle 4 + Spring config 1~2 + ObjectProvider 갱신 2) |
| LOC | ~200 |
| 영역 | persistence-shared 신규 + api·consumer 어댑터 이관·wiring |
| 자본 path 영향 | 100% (운영 흐름 작동률 0% → 100% 회복) |
| ADR 0084 위치 | P0-A 해결 (주문 path 안정성) |
| 우선순위 | **★ 최우선** (자본 path 차단 해소) |

### 5-2. cross-review (ADR 0085·0087 페르소나 매트릭스)

- 자본 path 직결 (`OrderEventOutboxJpaEntity` `order_event_outbox` table 정합) → **chart-curator 1차 lens** (보수, 🔴 require/check/try-catch)
- 모듈 경계 정합 + Spring Context 패턴 → **backend-core 1차 lens** (진보, 💡 모듈 분리·DRY·sealed)
- ADR 0087 cross-review 운영 룰 정합

### 5-3. Phase 2~3 (선택, post-MVP)

- Phase 2: `IndicatorEngineConfig` consumer 잔류 → persistence-shared 이관 검토 (backlog §66 후속)
- Phase 3: 기타 cross-module shared adapter (예: 향후 신규 Outbox 패턴) → persistence-shared 자연 합류

---

## §6. 검증 절차

### 6-1. 빌드·테스트

```bash
./gradlew :persistence-shared:check    # 신규 모듈 단독 검증
./gradlew :api:check                   # api 모듈 회귀 검증
./gradlew :consumer:check              # consumer 모듈 회귀 검증
./gradlew check                        # 전체 검증
```

### 6-2. Docker 검증

```bash
scripts/build-and-deploy.sh            # 전체 재배포
docker logs magicjar-consumer 2>&1 | grep -i "drain worker skip\|retry worker skip" | wc -l   # = 0 LOCK
docker logs magicjar-consumer 2>&1 | grep -i "OrderEventOutboxRepository\|NotificationOutboxRepository"
```

기대 결과:
- `WARN drain worker skip` 발생 **0건** LOCK
- `WARN retry worker skip` 발생 **0건** LOCK
- consumer 부팅 로그에 `OrderEventOutboxRepositoryAdapter`·`NotificationOutboxRepositoryAdapter`·`StompEventRepositoryAdapter` 3건 `@Repository` 등록 INFO 발생

### 6-3. 운영 흐름 작동률 검증 (qa cross-validation)

1. UI에서 매수 시그널 1건 issue → `order_event_outbox` row insert GREEN
2. consumer drain 1 schedule cycle (≤30s 권장) 대기
3. `outbox.status = SENT` 갱신 + Kafka `order.requested` 토픽 메시지 발행 GREEN
4. consumer `OrderRequestedKafkaProducer` 호출 + `OrderFilledKafkaProducer` 도착 GREEN

운영 흐름 작동률 **0% → 100%** 회복 evidence.

### 6-4. ADR 0004 §의존 방향 LOCK 정합 검증

```bash
grep "implementation(project(\":api\"))" consumer/build.gradle.kts   # 부재 LOCK
grep "implementation(project(\":consumer\"))" api/build.gradle.kts   # 부재 LOCK
```

api·consumer 서로 직접 의존 0 LOCK 보존.

---

## §7. v3.x cut 시점

- **v3.7.0 minor cut 후보** — 사용자 명시 `feedback_release_policy_patch_first` 정합 (v3.7=W4 마감)
- 본 P0-A spec은 W3 운영 흐름 차단 결함 fix 영역 → v3.7.0 minor 이전 patch (예: v3.6.17~v3.6.20) 누적 후 W4(T-AI) Phase 1·2 완료 시 v3.7.0 minor cut

대안 (사용자 결정 의제):
- **옵션 X (patch 누적)**: v3.6.17 patch cut + W4 진행 → v3.7.0 minor (W4 마감) ★ 권장
- 옵션 Y (P0-A 단독 minor): v3.7.0 cut 후 W4는 v3.8.0 → release 빈도 폭증 risk (reject)

---

## §8. rollback

비상 시 rollback 절차:

1. `git revert` — 본 spec Phase 1 PR 1건 (단일 머지 정합)
2. Gradle 재빌드 → 어댑터가 다시 api 모듈에 위치
3. consumer는 다시 `ObjectProvider<>` always null → graceful skip → **WARN 재발생 + 운영 흐름 0% 회귀**
4. 따라서 rollback은 자본 path 차단 회귀 → **권장 0** (forward fix만)

만약 ADR 0088 채택 후 다른 결함 발생 시 forward fix 권장:
- 신규 어댑터 graceful skip 패턴 추가 또는 `@ConditionalOnBean` wiring 등

---

## §9. 모듈 경계 정합 evidence (ADR 0004 + ADR 0083 패턴 정합)

### 9-1. ADR 0004 §의존 방향 LOCK 정합

| 원칙 | 본 ADR 0088 정합 |
|---|---|
| domain ← 모든 모듈 의존 | persistence-shared → domain (정합 GREEN) |
| api·batch·consumer 서로 직접 의존 0 | persistence-shared는 라이브러리 jar — 컨테이너 0 (정합 GREEN) |
| 라이브러리 모듈 → domain만 의존 | persistence-shared → domain + Spring DataJPA·Redis (정합 GREEN — ADR 0083 indicator-impl 동일 패턴) |

### 9-2. ADR 0083 indicator-impl 패턴 1:1 비교

| 항목 | indicator-impl (ADR 0083) | persistence-shared (ADR 0088) |
|---|---|---|
| 위치 | root 직속 | root 직속 |
| 종류 | Kotlin + Spring Context (Boot main 0) | Kotlin + Spring Context + JPA (Boot main 0) |
| 패키지 root | `kr.spdaewoon.magicjar.indicator.impl` | `kr.spdaewoon.magicjar.persistence.shared` |
| 의존 | domain only | domain + Data JPA + Redis |
| 사용 모듈 | api·consumer | api·consumer |
| 컨테이너 영향 | 0 | 0 |
| 신규 모듈 수 | +1 (5→6) | +1 (6→7) |

ADR 0083 indicator-impl 운영 evidence(v3.6.15 cut Accepted)로 본 패턴 검증 완료. 운영 인지 비용 0 (sub-agent + 사용자 학습 완료).

### 9-3. Hexagonal Architecture 정합

- **port** (`domain/.../port/`) — 추상 GREEN
- **adapter** (`persistence-shared/`) — 구현 GREEN (재배치)
- **use cases** (`api/service/`, `consumer/worker/`) — 호출 GREEN

port·adapter·use case 3계층 분리 LOCK 정합.

---

## §10. 운영 코드 0 touch LOCK (본 spec PR)

본 architect spec PR은 다음만 변경:

- `_workspace/arch/agent-charter.md` — §1-A-extended 신설 (P0-C)
- `scripts/setup-worktree-config.sh` — 헬퍼 스크립트 신설 (P0-C)
- `_workspace/arch/adr/0088-persistence-shared-module-outbox-wiring.md` — 본 ADR 신설 (P0-A)
- `_workspace/specs/2026-05-19-persistence-shared-module-outbox-wiring-spec.md` — 본 spec 신설 (P0-A)
- `_workspace/arch/adr/README.md` — ADR 0088 인덱스 갱신
- `docs/architecture/master-architecture.md` — §10 모듈 표 + §13 변경 이력 갱신
- `_workspace/backlog.md` — P0-A 의제 row
- `CHANGELOG.md` — row 추가
- `_workspace/tasks/T-20260519-1753-*.md` — task 파일

운영 코드 0 변경 (Kotlin/TS/SQL **0**). 구현은 Phase 1 backend-core 후속 PR.

---

## 영역 경계 검증

- ✓ architect 영역만 (spec/plan + ADR + 정합 docs + 헬퍼 스크립트)
- ✓ 운영 코드 0 touch
- ✓ Phase 1 backend-core dispatch 위임 (architect는 spec 발행까지)
- ✓ 자본 path 영향 0 (본 PR)
- ✓ KIS endpoint 호출 0
- ✓ AI 호출 0
- ✓ mock 합성 0

---

## 변경 이력

| 일시 | 변경 | 담당 |
|------|------|------|
| 2026-05-19 | 초기 발행 — P0-A persistence-shared 모듈 spec 10 § 풀세트 (옵션 B 채택 — ADR 0083 패턴 1:1) | architect (opus 4.7 1M) |
