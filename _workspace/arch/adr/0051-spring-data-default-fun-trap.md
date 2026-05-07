# ADR 0051 — Spring Data Repository default fun 함정 (회피 패턴)

> **상태**: Accepted (2026-05-08)
> **사이클**: PR-26 운영 헌법 통합
> **연관 헌법**: `_workspace/arch/agent-charter.md` §6
> **연관 사례**: PR-23·PR-25 (BatchInstrumentJpaRepository.upsert wrapper → InstrumentSeedRunner.upsertSeed 이동)

## 문제

Spring Data JPA `JpaRepository<T, ID>` interface 안에 **body 가진 fun**(default 키워드 유무 무관)을 발행하면 **Spring Data가 query inference로 SQL 생성 시도** → `No property 'X' found for type` 부팅 실패.

### 근본 원인

Spring Data Repository proxy 생성 시점:

1. interface 내 모든 abstract fun + 기본 구현 fun에 대해 method name parsing 수행
2. `find...By...` · `count...By...` · `delete...By...` 등 prefix 매칭 + property navigation
3. body 가진 fun도 abstract와 구분 없이 query inference 시도 (Kotlin interface fun 기본은 default 동작 — JVM bytecode level에서 abstract와 default 구분 모호)
4. 매칭 실패 시 부팅 단계에서 `PropertyReferenceException` throw → 컨테이너 부팅 실패

### Kotlin 특수성

Java interface는 `default` 키워드 명시로 abstract와 구분 가능. Kotlin interface는 body 유무로 자동 결정 — Spring Data가 body 가진 fun을 default로 인식하지 못하고 abstract로 query inference 시도 risk.

## 사례 (PR-23 → PR-25)

### PR-23 — wrapper 추가 (실패 패턴)

`BatchInstrumentJpaRepository`에 시드 INSERT용 wrapper 추가:

```kotlin
// ❌ 금지 — Spring Data가 'upsert' 메서드명을 query로 해석 시도
interface BatchInstrumentJpaRepository : JpaRepository<BatchInstrumentJpaEntity, Long> {

    fun upsertFull(code: String, isin: String?, name: String, /* 70+ 파라미터 */) { /* native query */ }

    fun upsert(code: String, name: String, market: String, type: String, sector: String?, listedAt: LocalDate?, securitiesGroup: String?) {
        upsertFull(code, null, name, null, market, type, sector, listedAt, securitiesGroup, /* 60+ default */)
    }
}
```

### 부팅 실패 (e37e02d 시점)

```
PropertyReferenceException: No property 'upsert' found for type 'BatchInstrumentJpaEntity'
```

batch 컨테이너 부팅 실패 → InstrumentSeedRunner @PostConstruct 호출 차단 → 시드 데이터 INSERT 0.

### PR-25 — caller helper로 이동 (성공 패턴)

```kotlin
// ✅ 허용 — Repository interface 안 abstract fun만
interface BatchInstrumentJpaRepository : JpaRepository<BatchInstrumentJpaEntity, Long> {
    @Modifying
    @Query(value = "INSERT INTO instrument (...) VALUES (...) ON CONFLICT (code) DO UPDATE SET ...", nativeQuery = true)
    fun upsertFull(code: String, /* 70+ 파라미터 */): Int
}

// ✅ caller class 내부 private fun helper
@Component
class InstrumentSeedRunner(
    private val instrumentRepo: BatchInstrumentJpaRepository,
) {
    private fun upsertSeed(code: String, name: String, market: String, type: String, sector: String?) {
        instrumentRepo.upsertFull(
            code = code,
            isin = null,
            name = name,
            // ... 60+ default null/false
        )
    }
}
```

## 결정

magicJar 전 영역에서 Spring Data Repository interface 안에 **body 가진 fun 발행 금지**. 회피 패턴은 다음 3가지 중 택1.

### 허용 패턴

1. **caller class 내부 private fun helper** (★ 권장 — PR-25 정정 패턴)
   - Repository는 abstract fun + `@Query` native만
   - 시드용 default 파라미터 채움 같은 wrapper 로직은 caller class 안 private fun

2. **extension fun (Repository 외부)**
   - `fun BatchInstrumentJpaRepository.upsertSeed(code: String, ...) { upsertFull(...) }`
   - 별도 파일에 두면 Spring Data proxy가 인식하지 못함 (extension은 static method로 컴파일)

3. **Repository interface 내부 abstract fun만**
   - body 0 + `@Query` annotation으로 SQL 명시
   - 또는 method name으로 query derivation (`findByCode`, `countByMarket` 등)

### 금지 패턴

- ❌ Repository interface 안 body 가진 fun (Kotlin default 동작)
- ❌ `default fun` 키워드 명시 (Kotlin은 default 키워드 자체가 syntax error)
- ❌ Repository interface 안 private fun (Kotlin은 interface private 가능하지만 Spring Data 인식 위험)

## 검증 절차

architect spec/plan + sub-agent 코드 발행 시:

1. **interface 검증** — `grep -r "fun.*{" <module>/src/main/kotlin/**/*Repository.kt` 0건 (body 가진 fun 부재)
2. **부팅 smoke** — `./gradlew :batch:bootJar :api:bootJar :consumer:bootJar` GREEN
3. **통합 빌드** — `./gradlew check` 또는 `./gradlew :module:bootRun --args='--spring.profiles.active=test'` 부팅 검증
4. **의심 시 본 ADR 정독** — 헌법 §6 + 본 ADR cross-ref

## 트레이드오프

- **wrapper 분산** — Repository에 wrapper 두면 caller 1곳에서 simplicity. helper로 이동 시 caller마다 helper 작성 필요. → 부팅 안정성이 우선.
- **default 파라미터 활용** — Kotlin default 파라미터로 wrapper 효과 가능 (e.g. `upsertFull(code, isin = null, ...)`). 단, Repository interface는 named argument 호출만 지원하므로 caller 가독성 ↓.
- **Repository 분할** — wrapper 메서드별 별도 Repository interface 분할은 과도한 추상화. → caller helper로 충분.

## 영향

- batch·api·consumer 3 모듈의 모든 Repository interface 검증 의무 (사이클 시작 시 architect 점검 권장)
- 신규 sub-agent SKILL 영역 침범 없이 본 ADR 정합 가능 (backend-core·market-data 영역 핵심 risk)
- 헌법 §6에 본 ADR 본문 인용 cross-ref

## 후속

- 향후 Spring Data Kotlin extension 또는 동작 변경 시 본 ADR superseded 검토
- 자체 추론 검증 시점에 발견 시 즉시 본 ADR 본문 인용 + 정정 PR
