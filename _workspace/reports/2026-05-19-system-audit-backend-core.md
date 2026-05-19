# 시스템 점검 — backend-core 영역 + hotfix 의제

> **발행자**: backend-core (진보적 시니어 백엔드 개발자 "리팩토링맨", ADR 0087 첫 dogfooding)
> **일자**: 2026-05-19 KST
> **영역**: domain · api · consumer (backend-core 책임 범위)
> **기준 main**: 2a2225d (v3.6.16 + devlog 2026-05-19 #1)
> **운영 시점 evidence**: docker logs (magicjar-api 3h, magicjar-consumer 1h healthy)

---

## 점검 매트릭스 요약 (6 deliverables)

| # | 영역 | 결과 | 발견 |
|---|------|------|------|
| 1 | 도메인 모델 정합 | OK + 개선 여지 | 22 패키지, 19 sealed, 35 enum, DomainResult 거의 미사용(3건) |
| 2 | JPA Repository default fun 함정 (§6) | GREEN (0건) | 38 JpaRepository interface, body fun 0 (가드 LOCK 정상) |
| 3 | Controller swagger 정합 | GREEN | 28 RestController 100% @Tag + @Operation + 153 @Parameter |
| 4 | 영속 어댑터 패턴 일관성 | 양호 + DRY 위반 1건 | 26 RepositoryAdapter @Component 일관, but Strategy 2 어댑터 중복 |
| 5 | 코드 품질·기술 부채 | 중간 | `!!` 27건 + DomainResult 미보급 + JdbcTypeCode 14 site 패턴 |
| 6 | hotfix 의제 | P1 1건 + P2 2건 | OrderOutbox drain worker null skip (자본 path) |

---

## 개발자2 (진보적) 의견 — backend-core 시스템 점검

### 코드 품질 관점

- **개선 가능 포인트**:
  - `DomainResult<T>` sealed class를 도메인/공용에 잘 정의해두고도 실제 활용처가 3건 자기참조뿐. 포트 반환에 `try-catch`·nullable·exception throw가 혼재. 통일 가능.
  - `!!` non-null assertion 27 site — Kotlin idiom 측면에서 가장 큰 부채. JPA entity의 `id: Long?`이 `save` 후 반드시 non-null인 패턴이 반복됨 → 명시적 wrapper (`SavedOrder` non-null id type)로 표현 가능.
  - `runCatching`이 persistence layer 25건 vs `try-catch` 7건 — 점진적 함수형 idiom 이행 진행 중. 일관화 시 8건 정도 더 ergonomic.
  - sealed 19개 vs enum 35개 — `RejectReason`·`DenyReason`은 sealed가 더 적합한데(이유 detail 보유 가능) enum으로 남은 케이스 다수.

- **기술 부채**:
  - **★ StrategyJpaAdapter · StrategyLookupJpaAdapter DRY 위반 정점** — `toDomain`·`safeComplexityClass`·`safeDisabledReason` 3 fun이 두 어댑터에 완전 동일 코드 (총 약 50 line 중복). 한 곳 변경 시 다른 곳 sync 누락 risk. `StrategyJpaMapper` (top-level object 또는 internal fun) 추출 권장.
  - `StrategyJpaAdapter` 말미 `private val signalRef = Signal.HOLD` 미사용 ref + `EvalOutcome` companion object 미참조 → 죽은 코드 흔적.
  - `WatchlistPollingWorker.kt:78` `!!` on `maxByOrNull` — `candles.lastOrNull()` 또는 명시적 empty 가드로 대체 가능.
  - `KisExecutionWsListener.kt`·`OrderRouterKafkaListener.kt`에 `request.id!!`·`saved.id!!` 패턴 9건 집중 — 자본 path 코드라 `!!` NPE 시 자동매매 사이클 중단 risk. 가드 함수(`requireNotNull(saved.id) { "saved.id required after persist" }`) 또는 wrapper type 권장.

- **리팩토링 제안**:
  - `safeEnum<E>(value: String?, default: E): E` 같은 reified generic helper 1개로 `safeComplexityClass`/`safeDisabledReason`/그 외 enum safe valueOf 패턴 통합.
  - `extractDslText(node: DslNode?): String = node?.toString() ?: ""` — DslNode 자체에 `text` property 또는 toString contract LOCK이 더 안정. adapter 책임 영역 벗어남.
  - JdbcTypeCode JSON column 14 site (api·consumer·batch 엔티티)에 ObjectMapper 직렬화 패턴이 어댑터에 흩어짐 — `JsonColumnConverter` 또는 Jackson `@Convert` 표준 위치 도입 시 PR #530·#532 사이클 같은 패턴 정합 risk 사전 차단.

### 코드 리뷰 (도메인·api·consumer 매트릭스)

1. **💡 [개선 제안] DomainResult<T> sealed class 실보급** — `domain/common/DomainResult.kt`는 작년 정의 후 3건 자기참조만. 포트 반환에 try-catch·null·exception 혼재 (`OrderRouterKafkaListener.kt:239` null-chain 등). 점진적 — 새 포트부터 `DomainResult<Order>` 반환 LOCK + 기존은 backlog로 흡수. 진보 페르소나 좌우명 "레거시는 빚이다" 정합.

2. **🔄 [리팩토링] StrategyJpaAdapter·StrategyLookupJpaAdapter `toDomain` 중복 50줄 추출** — 두 클래스 동일 fun 3개(`toDomain`·`safeComplexityClass`·`safeDisabledReason`). `StrategyJpaMapper` object 또는 `internal fun StrategyJpaEntity.toDomain()` extension fun으로 추출. DRY 위반 — 헌법 §6 helper 위치 룰은 "interface 내부 0건"만 의미하지 "두 어댑터 중복 OK" 아님. 변경 시 두 곳 sync 누락 시 미묘한 버그 risk.

3. **✨ [모던 패턴 적용] reified generic enum safe lookup** —
   ```kotlin
   inline fun <reified E : Enum<E>> safeEnumOf(value: String?, default: E): E =
       if (value == null) default
       else runCatching { enumValueOf<E>(value) }.getOrDefault(default)
   ```
   `domain/common/EnumExt.kt`에 두면 `safeComplexityClass`·`safeDisabledReason`을 한 줄 호출로 대체 가능. Kotlin idiom 정점.

4. **💡 [개선 제안] `!!` 27건 자본 path 가드 정비** — KisExecutionWsListener·OrderRouterKafkaListener 9건은 `saved.id!!` 패턴. JPA save 후 id가 non-null임은 보장되지만 NPE 발생 시 stack trace에 컨텍스트 0 → 디버깅 어려움. `requireNotNull(saved.id) { "Order saved without id: ${saved.intentId}" }` 패턴이 가드 + 컨텍스트 양립.

5. **🔄 [리팩토링] OrderEventOutbox·NotificationOutbox·StompEvent Outbox 패턴 3 site 추상화** — outbox 패턴이 3 도메인에 분산 구현. `OutboxPort<T>` + `OutboxPublisher<T>` 추상화 시 `OrderOutboxPublisherWorker`·`NotificationOutboxPublisherWorker` 등 50%+ 코드 공유 가능. W4 진입 시 AI 사이클이 또 outbox 패턴 가산할 가능성 — 선제 추상화 가치.

6. **✨ [모던 패턴 적용] enum → sealed 전환 후보** — `RejectReason`·`DenyReason`·`StrategyDisabledReason` 같은 "이유" enum은 sealed로 가면 각 케이스에 detail 필드(예: `RejectReason.DailyLossLimit(currentLoss: BigDecimal, threshold: BigDecimal)`) 부여 가능. 현재는 `rejectDetail: String?` free-text로 별도 보존 — sealed로 type-safe하게 표현 시 frontend 직렬화 보기도 풍부.

### 추가 제안

- **`@Tag` description 한국어 정합 일괄 audit** — 28 controller 모두 `@Tag` 보유 양호. but description 한국어 + example 값 충실도는 추후 sample audit으로 정량화 가치. 백엔드-core 1차 책임 아니지만 backend-core·frontend 협업 spec으로 잠재.
- **`DomainResult<T>` 도입 시 sealed interface로 변경** — 현재 sealed class. Kotlin 1.5+ `sealed interface` 사용 시 다중 상속 가능 + value class와 결합 ergonomic. (Kotlin 2.0 K2 정합)
- **JPA entity `data class` 0건 확인** — `find domain/src/main/kotlin -name "data class.*JpaEntity"` 0 result, 모두 일반 class + no-arg 플러그인 정합. ★ 보수 페르소나(chart-curator) 1차 lens에서도 GREEN.

### 함께 개선하면 좋은 것

- **기술 부채 해소 방안**:
  - **R1 (Quick win, patch v3.6.17 후속)**: StrategyJpaAdapter DRY 위반 1건 fix (50 line 중복 제거) + `EnumExt.safeEnumOf<E>` reified helper 도입 (4 site replace). 1 PR, 1시간 추산.
  - **R2 (1 사이클)**: `!!` 27 site Kotlin idiom 정비 — `requireNotNull` 또는 wrapper type 부분 도입. 자본 path 9건 우선. W4 진입 직전 청소 의미 큼.
  - **R3 (장기)**: DomainResult sealed interface 보급 + Outbox 패턴 추상화 + enum → sealed 점진 전환. 새 포트부터 의무, 기존은 trickle 정정. W5 진입 전 LOCK.

### hotfix 의제 도출

#### P1 (자본 path risk, 다음 patch 사이클 권장)

**P1-1 — OrderOutboxPublisherWorker 영구 skip (★ 운영 evidence)**

- **현상**: `docker logs magicjar-consumer` 5초마다 `WARN OrderOutboxPublisherWorker - OrderEventOutboxRepository 미등록 — drain worker skip` 반복 (1시간 healthy 동안 720+ 회).
- **원인**: `OrderEventOutboxRepositoryAdapter`는 `api/src/main/kotlin/kr/spdaewoon/magicjar/api/persistence/order/` 단일 위치. consumer 모듈은 api 모듈을 dependency로 포함하지 않으므로 `ObjectProvider<OrderEventOutboxRepository>.getIfAvailable()`가 null → graceful skip이 영구화.
- **자본 path risk**: 4 producer(Intent·Requested·Rejected·Filled)가 Kafka publish 실패 시 outbox row만 INSERT 후 retry worker 호출 의도 → worker가 영구 skip이면 PENDING row 무한 축적 + KillSwitch auto-ON 보호도 0 → KIS API 일시 장애 시 outbox 회복 0.
- **fix path 후보**:
  1. consumer에 별도 `ConsumerOrderEventOutboxRepositoryAdapter` 발행 (기존 Consumer*JpaAdapter 패턴 정합). consumer/persistence/order/ 신규 1 file.
  2. 또는 영속 어댑터를 batch 모듈로 이관(batch가 outbox drain owner) 후 batch에 outbox worker 통합. ADR 검토 필요.
  3. 또는 worker 자체를 api 모듈로 이관 (api가 producer + drain 단일 owner). consumer ↔ api 책임 경계 ADR 정정 필요.
- **권장 path**: 1번 (즉시 fix, ADR 정정 무필요). consumer/persistence/order/ 영역에 PositionJpaAdapter·ConsumerOrderExecutionJpaRepository 등 기존 패턴 그대로 정합. PR 1건, hotfix/* 또는 agent/backend-core/order-outbox-drain-fix.
- **검증 의무 (헌법 + 헌법 §6 § feedback_verification_strict)**: fix 후 docker logs WARN line 0건 + DB `order_event_outbox` PENDING insert→published 흐름 evidence + KillSwitch engage 로직 별도 fail injection 테스트.

#### P2 (개선 의제, backlog 흡수)

**P2-1 — StrategyJpaAdapter DRY 위반 + Signal.HOLD 죽은 ref 정리**
- 즉시 patch 가능, 1시간 작업. PR 1건. EnumExt.safeEnumOf reified helper 도입과 묶어서 발행 권장.

**P2-2 — `!!` 27 site 자본 path 가드 (KisExecutionWsListener·OrderRouterKafkaListener 9건 우선)**
- 사이클 진행 patch 누적. W4 진입 전 청소 가치. backlog §에 흡수.

#### P0 (자본 path 즉시 stop) — 0건

운영 현 시점 evidence상 ERROR 0건, P0 의제 없음. (P1-1은 자본 path risk이지만 "현재 KIS 일시 장애 발생하지 않는 한" 활동 0이므로 P1 등급)

---

## evidence 인용

### docker 운영 상태 (2026-05-19 KST 17:00 부근)

```
magicjar-consumer   Up About an hour (healthy)
magicjar-frontend   Up About an hour (healthy)
magicjar-api        Up 3 hours (healthy)
magicjar-batch      Up 4 hours (healthy)
magicjar-postgres   Up 40 hours (healthy)
```

### P1-1 reproduce 로그 sample

```
16:51:05.350 [-] [scheduling-1] WARN  k.s.m.c.o.OrderOutboxPublisherWorker - OrderEventOutboxRepository 미등록 — drain worker skip
16:51:00.347 [-] [scheduling-1] WARN  k.s.m.c.o.OrderOutboxPublisherWorker - OrderEventOutboxRepository 미등록 — drain worker skip
... (5초 주기 무한 반복, 1시간 720+ 회)
```

### DRY 위반 site (50 line 동일)

```kotlin
// StrategyJpaAdapter.kt:104-145
private fun toDomain(entity: StrategyJpaEntity): Strategy { ... }
private fun safeComplexityClass(value: String): ComplexityClass = ...
private fun safeDisabledReason(value: String?): StrategyDisabledReason = ...

// StrategyLookupJpaAdapter.kt:74-125 (위와 동일)
private fun toDomain(entity: StrategyJpaEntity): Strategy { ... }
private fun safeComplexityClass(value: String): ComplexityClass = ...
private fun safeDisabledReason(value: String?): StrategyDisabledReason = ...
```

### Repository default fun 함정 가드 GREEN

- 38 JpaRepository interface 정밀 awk scan: body 가진 fun 0건.
- 가드 LOCK 주석 7 site (CandleJpaRepository·DisclosureJpaRepository·NewsArticleJpaRepository·ScreeningRunJpaRepository·PnlDailyJpaRepository·PerformanceSummaryJpaRepository·ConsumerCandleJpaRepository) — ADR 0051·헌법 §6 정합.

---

## 작업 영역 경계

- **backend-core 영역만**: domain · api · consumer 일부 (Spring Boot 뼈대·도메인·인프라).
- **0 touch**: chart-curator(LWC·캔들 SLA) · market-data(KIS 어댑터 호출 흐름) · strategy-engine(DSL·BT) · frontend(React) · architect(ADR) · ai-engineer(AI provider) · qa(테스트 자체 영역).
- **운영 코드 0 변경**: 본 PR은 보고서만. P1-1 hotfix는 별도 사이클에서 backend-core 또는 consumer-주관 sub-agent 디스패치 권장.

---

## 진보 페르소나 dogfooding 평가 (셀프)

- ★ 출력 형식(💡/🔄/✨) LOCK 정상 작동: 6건 lens 적용 (1, 4 = 💡 / 2, 5 = 🔄 / 3, 6 = ✨).
- "DRY · 책임 분리 · 확장성 · 보일러플레이트 · 최신 패턴" 5 lens 의무 평가 진행 + 코드 리뷰 6건에 반영.
- Kotlin idiom(scope 함수·sealed class·확장 함수·함수형 체이닝) 활용 제안 4건 — `?.let`·`runCatching`·sealed interface·reified generic.
- 좌우명("레거시는 빚이다. 이자가 붙기 전에 갚아라") 정합 — P2-1 DRY 위반·DomainResult 미보급·`!!` 27건을 "이자 붙기 전 갚을 빚"으로 lens 사용.
- 짝 페르소나 매트릭스 — chart-curator 보수 lens(stale jar·default fun 함정 가드)와 backend-core 진보 lens(DRY·sealed·reified) 균형 정상 작동.
