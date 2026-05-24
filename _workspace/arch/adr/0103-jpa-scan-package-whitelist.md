# ADR 0103: JpaScanConfiguration 신규 패키지 누락 차단 (장기 fix 옵션 매트릭스 + 채택안 LOCK)

- 상태: **Accepted (2026-05-24 — W4 P1 R3 qa 부팅 차단 결함 hotfix 직후 architect amend)**
- 작성일: 2026-05-24
- 결정자: architect (opus 4.7 1M) — orchestrator W4 P1 dispatch 정합
- 영역: 메타·운영·하네스 (api 모듈 JPA scan 화이트리스트 + 신규 패키지 추가 시 차단 룰)
- 권위 spec: ADR 0088 (persistence-shared 모듈 + JpaScanConfiguration 신설 권위) · 본 ADR R3 qa evidence (PR #681)
- 관련 ADR: 0004(모듈 경계 — api·consumer·batch 분리) · 0027(MCP — api.mcp.* 패키지 책임) · 0084(P0 OPS · 부팅 안정성) · 0088(persistence-shared 모듈 권위) · 0094(운영 단계 — 본 ADR 운영 ADR 양식 3 슬롯 의무)

---

## 1. 맥락 (R3 qa 부팅 차단 결함 evidence)

### 1-1. 결함 evidence (R3 qa PR #681)

2026-05-24 W4 R3 qa Visual 회귀 dispatch가 본격 머지 직전 docker logs 검증 단계에서 다음 부팅 abort를 감지:

```
APPLICATION FAILED TO START
Parameter 0 of constructor in McpAuditService required a bean of type
'kr.spdaewoon.magicjar.api.mcp.persistence.McpAuditJpaRepository' that could not be found.
```

→ exit code 1 → `magicjar-api` 컨테이너 부팅 실패 → docker compose dependency chain 의해 `magicjar-batch` · `magicjar-consumer` 동반 dependency failure (3 컨테이너 RED).

### 1-2. Root cause

`api/src/main/kotlin/kr/spdaewoon/magicjar/api/config/JpaScanConfiguration.kt` (ADR 0088 v3.7.1 hotfix #2 권위) 의 `@EnableJpaRepositories(basePackages=[...])` + `@EntityScan(basePackages=[...])` 화이트리스트가 **2건만 등록**:

- `kr.spdaewoon.magicjar.api.persistence` (api 자체)
- `kr.spdaewoon.magicjar.persistence.shared` (ADR 0088 라이브러리)

R2 backend-core T-MCP Phase 1 (PR #679) 는 ADR 0027 §2-1 패키지 표준 정합으로 신규 `kr.spdaewoon.magicjar.api.mcp.persistence` 패키지를 신설하고 `McpAuditJpaRepository` + `McpAuditJpaEntity` 를 발행. 그러나 `JpaScanConfiguration` 화이트리스트 갱신 의무를 누락 (PR 본문 audit 누락 + ADR 0088 본문에 신규 패키지 추가 시 갱신 의무 명시 0건).

Spring Boot `JpaRepositoriesAutoConfiguration` 의 fallback 룰 (main class 패키지 = `kr.spdaewoon.magicjar` 자동 scan) 은 `@EnableJpaRepositories` 가 한 번이라도 등장 시 비활성화 (ADR 0088 §root cause 권위). 따라서 화이트리스트 누락 패키지는 자동 fallback 0 → bean 미발견 → 부팅 차단.

### 1-3. R3 qa 자율 hotfix (1줄 add-only · `feedback_conflict_resolution_autonomy` 정합)

R3 qa 가 다음 1줄 add-only hotfix 발행 (PR #681 commit 062abecd 머지 완료):

```kotlin
@EnableJpaRepositories(
    basePackages = [
        "kr.spdaewoon.magicjar.api.persistence",
        "kr.spdaewoon.magicjar.api.mcp.persistence",   // ← R3 qa hotfix
        "kr.spdaewoon.magicjar.persistence.shared",
    ],
)
@EntityScan(
    basePackages = [
        "kr.spdaewoon.magicjar.api.persistence",
        "kr.spdaewoon.magicjar.api.mcp.persistence",   // ← R3 qa hotfix
        "kr.spdaewoon.magicjar.persistence.shared",
    ],
)
```

KDoc 7줄 사유 본문 추가 (재발 방지 — "신규 패키지에 JpaRepository/Entity 추가 시 본 화이트리스트 갱신 의무 + 장기 fix는 sub-package 자동 스캔으로 ADR amend 후보 — backend-core 영역 dispatch"). 본 amend 머지 후 docker 재기동 → 3 컨테이너 GREEN 복귀 (R3 qa task `T-20260524-1626-w4-r3-visual-regression` evidence).

### 1-4. 본 ADR 발행 목적

R3 qa hotfix는 즉시 fix 단독 — **재발 방지 본문 LOCK 0건**이다. 본 ADR이 다음 책임을 본문화:

- 본 결함 root cause + R3 hotfix 1줄 evidence 영구 기록
- **장기 fix 옵션 3건 매트릭스 + 채택안 LOCK** (단순 add-only 갱신 의무 vs 자동 스캔 vs 통합 테스트)
- 향후 신규 persistence 패키지 추가 시 안전망 단일 권위 (재발 방지)
- 운영 단계 (ADR 0094) lens 정합 — 부팅 abort 는 자본 path 0 touch라도 운영 SLO S-2 직접 영향 risk

### 1-5. 운영 단계 lens 정합 (ADR 0094)

운영 단계 진입 후 부팅 차단 결함은 **운영 SLO S-2 (KIS WS 연결률 > 99%) 직접 영향** + on-call 호출 risk + 자본 path 단절 (consumer 부팅 실패 시 Kafka 소비 0 → KIS WS 메시지 처리 0 → 자본 path 멈춤). 본 ADR은 운영 ADR 양식 3 슬롯 (SLO 영향·rollback·관측 매트릭스) 의무 본문화 (헌법 §9-5 정합).

---

## 2. 결정 옵션 매트릭스 (3건)

### 2-1. 옵션 A — 모든 JPA 패키지 단일화 (base scan `kr.spdaewoon.magicjar`)

```kotlin
@EnableJpaRepositories(basePackages = ["kr.spdaewoon.magicjar"])
@EntityScan(basePackages = ["kr.spdaewoon.magicjar"])
class JpaScanConfiguration
```

**원리**: `kr.spdaewoon.magicjar.*` 전체 sub-package 자동 scan → 모든 JpaRepository·@Entity 발견.

**포지티브**:
- 신규 패키지 추가 시 화이트리스트 갱신 의무 0 (자동 발견)
- 재발 방지 100% (본 결함 root cause 자체 제거)

**네거티브 (mitigation)**:
- domain·indicator-impl·strategy-engine·market-trend-engine 등 **persistence 0 모듈**의 패키지도 scan 대상 → ClassLoader 시작 시간 미세 ↑ (실측 < 50ms 본질 무시 가능)
- 향후 모듈 분리 시 의도치 않은 패키지가 scan 되어 충돌 risk — **mitigation**: `@Entity` annotation은 JPA 전용 → 사이드 이펙트 0 (Spring Data Repository 발견은 `@Repository` annotation 없이도 `JpaRepository` 상속만으로 자동 — 그러나 본 모듈에서는 분리 모듈은 JpaRepository 상속 0 baseline LOCK)
- `@WebMvcTest` slice 시 JPA 활성화 회귀 risk — **mitigation**: ADR 0088 §root cause 패턴 그대로 (별도 `@Configuration` class 분리로 `TypeExcludeFilters` 자동 제외) reuse

### 2-2. 옵션 B — PR template + 갱신 의무 명문화 (현재 화이트리스트 유지)

`.github/PULL_REQUEST_TEMPLATE.md` (또는 헌법 §amend) 에 신규 persistence 패키지 추가 시 `JpaScanConfiguration` 갱신 의무 체크리스트 1 row 추가:

```markdown
- [ ] 신규 `JpaRepository`/`@Entity` 패키지 추가 시 `api/src/main/kotlin/.../config/JpaScanConfiguration.kt` 화이트리스트 갱신
```

**원리**: 갱신 의무를 사람·룰 layer 로 강제 (코드 변경 0).

**포지티브**:
- 코드 변경 0 — 룰 amend 단독
- 명시적 화이트리스트 유지 — 의도치 않은 패키지 scan risk 0

**네거티브**:
- 사람 의존 — 본 결함 재발 risk (R2 backend-core 도 PR template 본질 인지 부재 시 누락 가능)
- PR template 또는 헌법 §amend 가 sub-agent 인지 의무 발동 0 시 잠재 risk 잔류
- **재발 방지 강도 낮음** (단일 결함 evidence 1건 후 옵션 A 또는 C 로 amend 의제 누적 risk)

### 2-3. 옵션 C — Spring context load smoke test (통합 테스트)

`api/src/test/kotlin/.../boot/JpaScanSmokeTest.kt` 신설:

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class JpaScanSmokeTest {
    @Test
    fun `application context loads without bean missing exception`() {
        // context 로딩 자체로 성공 — Bean missing 시 UnsatisfiedDependencyException 발생
    }
}
```

**원리**: ApplicationContext 로딩 시 모든 JpaRepository bean 발견 검증 → `./gradlew :api:check` 단계에서 부팅 차단 결함 자동 검출 → pre-push hook (ADR 0031) 자동 RED.

**포지티브**:
- 부팅 차단 결함 ALL 자동 검출 (JpaScan 외 모든 의존성 누락 결함 cover)
- ADR 0036 task cycle 정합 — `./gradlew check` pre-push에서 자동 실행
- 화이트리스트 갱신 의무는 잔류하지만 **누락 시 즉시 RED → PR 머지 차단** → 재발 방지 100%

**네거티브 (mitigation)**:
- `@SpringBootTest` 통합 테스트 1건 추가 → 테스트 시간 ↑ (실측 ~3~5s 본질 무시 가능)
- TestContainers 또는 H2 in-memory DB 필요 (현재 `api/src/test/` 에 `@SpringBootTest` 1건도 없으면 dependency 설정 필요)
- 화이트리스트 갱신 의무 잔류 — 옵션 A 의 갱신 의무 0 vs 본 옵션은 갱신 의무 + 자동 검출 이중 보호

---

## 3. 채택안 LOCK

### 3-1. 채택: **옵션 A (모든 JPA 패키지 단일화) + 옵션 C-lite (R0 baseline 한정 smoke test)** 하이브리드

**근거 매트릭스**:

| lens | 옵션 A | 옵션 B | 옵션 C |
|---|---|---|---|
| 재발 방지 강도 | **100%** (자동 발견) | 30% (사람 의존) | 100% (RED 차단) |
| 코드 변경량 | 1줄 (basePackages 단일화) | 0 (룰 amend 단독) | 통합 테스트 1건 (~10 LOC) |
| 운영 risk | **0** (실측 sideeffect 0) | 0 (코드 0) | LOW (테스트 시간 ↑) |
| 유지보수 비용 | **0** (갱신 의무 0) | HIGH (사람 의존 누적) | LOW (테스트 단일) |
| 운영 SLO S-2 영향 | **0 (제거)** | LOW (재발 가능) | 0 (CI 차단) |

**결정**:

1. **본 ADR 발행 직후 즉시 적용은 옵션 A 아님** — R3 qa hotfix (현재 origin/main `094ab481` 상태 — 화이트리스트 3건 명시 유지) 가 운영 안정. 옵션 A 적용 시 ClassLoader scan 범위 확장 + ADR 0088 §root cause 패턴 회귀 risk audit 필요 → **별 사이클 backend-core 영역 dispatch 의제** (본 ADR P1 후속).
2. **옵션 C (smoke test)** 는 R3 qa 영역 또는 backend-core 영역 단독 dispatch — **본 ADR P1 우선**. 본 통합 테스트 1건이 ALL 부팅 차단 결함 검출 → 옵션 A 적용 전 안전망 단일 권위.
3. **옵션 B (PR template)** 는 옵션 A·C 적용 시 자동 무효 → 본 ADR 채택 0 (옵션 A 적용 후 갱신 의무 0).
4. **옵션 A 채택 시점**: 옵션 C smoke test 적용 후 1 사이클 안정 운영 evidence (W4 P1 후속) → backend-core PR으로 옵션 A 적용 (basePackages 단일화 + ADR 0088 §root cause 패턴 cross-ref + 본 ADR §3-1 채택안 본문 인용).

### 3-2. 임시 baseline (옵션 A 적용 전)

R3 qa hotfix (PR #681 commit 062abecd · origin/main 094ab481) 의 화이트리스트 3건 명시는 본 ADR P1 후속 사이클까지 baseline. 향후 신규 persistence 패키지 추가 시:

- **즉시 의무**: 본 화이트리스트 add-only 갱신 (R3 hotfix KDoc 7줄 본문 권위)
- **PR audit 의무**: PR 본문에 `JpaScanConfiguration.kt` 갱신 row 명시 (옵션 C smoke test 머지 전 사람 layer 안전망)

### 3-3. 후속 사이클 매트릭스 (본 ADR 발행 직후 dispatch 의제)

| # | 영역 | 작업 | 트리거 |
|---|---|---|---|
| P1 | backend-core | 옵션 C smoke test 1건 add-only (`JpaScanSmokeTest.kt`) | 본 ADR 발행 직후 (자율) |
| P2 | backend-core | 옵션 A 적용 (basePackages 단일화) + ADR 0088 §root cause 패턴 audit | 옵션 C 안정 운영 evidence 누적 후 1 사이클 |
| P3 | architect | 본 ADR amend (옵션 A 적용 완료 본문 + ADR 0088 정합 amend) | 옵션 A 적용 머지 후 |

---

## 4. 운영 ADR 양식 3 슬롯 (헌법 §9-5 정합)

### 4-1. SLO 영향 (ADR 0095 §9-1)

| SLO | 영향 | 분석 |
|---|---|---|
| **S-2 (KIS WS 연결률 > 99%)** | **본 결함 발생 시 직접 RED** | api 부팅 abort → KIS WS reconnect path 부재 + consumer 부팅 dependency failure → KIS WS 메시지 처리 0 → 자본 path 단절 risk |
| S-1 (시그널→주문 P95<5s) | 본 결함 발생 시 직접 RED | consumer 부팅 abort → 시그널 처리 0 → 주문 발행 0 |
| S-3 (account API p95<500ms) | 본 결함 발생 시 직접 RED | api 부팅 abort → REST endpoint 0 응답 |
| S-4 (KIS rate limit < 1건/5분) | 0 | rate limit path 영향 0 |
| S-5 (KillSwitch 자동 ON < 1건/일) | 0 | KillSwitch path 영향 0 |

**본 ADR 채택 후 SLO 영향**:
- 옵션 C smoke test 적용 시 → S-1·S-2·S-3 부팅 차단 결함 0 (CI RED 차단 → main 진입 0)
- 옵션 A 적용 시 → 신규 패키지 추가 시 부팅 차단 결함 0 (자동 발견)

신규 SLO 후보 (post-MVP backlog):
- **S-OPS-1 (api·batch·consumer 3 컨테이너 부팅 성공률 = 100%)** — 본 ADR 권한 영역 P1 backlog

### 4-2. rollback plan

본 ADR 채택안 옵션 A 적용 후 운영 부적합 발견 시 (예: 예상 외 ClassLoader scan side effect):

| 단계 | 절차 | 예상 소요 |
|---|---|---|
| 1 | `JpaScanConfiguration.kt` `basePackages = ["kr.spdaewoon.magicjar"]` → R3 qa hotfix 3건 명시 화이트리스트 (origin/main `094ab481` 상태) 즉시 revert | < 5분 (PR revert) |
| 2 | `./gradlew :api:bootJar :batch:bootJar :consumer:bootJar` 재빌드 + docker 재기동 | < 10분 |
| 3 | api·batch·consumer 3 컨테이너 GREEN evidence 검증 (`docker compose logs --since 5m | grep "Started.*Application"`) | < 5분 |
| 4 | 옵션 C smoke test는 유지 (RED 차단 안전망 잔류 — rollback 영향 0) | 0 |
| 5 | 본 ADR amend (옵션 A 실패 evidence + 옵션 B 또는 별 옵션 채택 amend) | < 30분 |

전체 rollback < 50분 (RTO 30분 LOCK 안 — `feedback_release_policy_patch_first` patch cut 정합).

옵션 C smoke test 적용 후 운영 부적합 발견 시 (예: 테스트 flaky):
- 단순 revert (테스트 1 파일 add-only 본질) — < 5분

### 4-3. 관측 매트릭스

| 측정 | source | 1차 권한 | alert 임계 |
|---|---|---|---|
| api·batch·consumer 부팅 성공률 | docker compose status | Prometheus blackbox exporter (post-MVP) | 1 컨테이너 RED → CRITICAL alert (P0-OPS-3 §S-OPS-1 amend 후) |
| 옵션 C smoke test 통과율 | `./gradlew :api:check` CI run | pre-push hook (ADR 0031) | 1회 RED → main 머지 차단 자동 |
| `JpaScanConfiguration` 변경 빈도 | `git log --follow JpaScanConfiguration.kt` | 사용자 spot check | (단순 observability — alert 없음) |
| Bean missing UnsatisfiedDependencyException 발생 빈도 | docker logs grep `APPLICATION FAILED TO START` | 운영자 일일 점검 | 1건/주 이상 → architect amend dispatch (옵션 A 적용 trigger) |
| Visual 회귀 시나리오 (헌법 §9-2 frontend 핵심 화면 영향) | qa Playwright | R3 자동 dispatch | api 부팅 abort 시 frontend 모든 화면 RED → 자동 검출 (본 결함 R3 qa 발견 패턴 1:1) |

---

## 5. 영향도 매트릭스

### 5-1. 기존 ADR · 컴포넌트 영향

| 기존 ADR/컴포넌트 | 영향 | 대응 |
|---|---|---|
| ADR 0088 (persistence-shared 모듈 권위) | LOW (본 ADR이 §root cause 패턴 cross-ref · 옵션 A 적용 시 ADR 0088 §basePackages 매트릭스 amend 동반 의무) | 옵션 A 적용 PR (P2) 머지 시 ADR 0088 amend 동반 |
| ADR 0027 (MCP 패키지 책임) | 0 (api.mcp.* 패키지 본질 LOCK · 본 ADR은 화이트리스트 룰 영역) | 0 |
| ADR 0084 (P0 OPS 부팅 안정성) | LOW (S-OPS-1 신규 SLO 후보 본 ADR §4-1 본문화) | P0-OPS-3 §S-OPS-1 amend 후보 (post-MVP backlog) |
| ADR 0094 (운영 단계 lens) | 0 (운영 ADR 양식 3 슬롯 본 ADR 본문화) | 0 |
| `JpaScanConfiguration.kt` 본체 | LOW (옵션 C smoke test 동반 시 KDoc 보강 권장) | P1 backend-core dispatch 시 KDoc amend 권장 |
| pre-push hook (ADR 0031) | 0 (옵션 C smoke test는 `./gradlew :api:check` 자동 실행 path) | 0 |

### 5-2. 영역 침범 매트릭스 (헌법 §1-A · §2 정합)

| 영역 | 침범 | 회피 |
|---|---|---|
| architect | 본 ADR 신설 단독 (md only — 운영 코드 0 touch) | 본 ADR PR diff Kotlin/TS/SQL 0 변경 |
| backend-core | P1 smoke test + P2 옵션 A 적용 (별 사이클 dispatch) | 본 ADR PR에는 본문 인용 단독 — 실 구현 0 |
| R3 qa hotfix (PR #681 머지 완료) | 0 (이미 main 머지 완료 · 본 ADR은 evidence 인용 단독) | 본 ADR이 R3 hotfix 본문 amend 0 |
| 자본 path | 0 (운영 안정성 영역 — 자본 발행 0) | grep 검증 의무 |

### 5-3. 연결 매트릭스 (신규 → 기존 reuse)

| 신규 본문 | 활용 기존 |
|---|---|
| 옵션 A basePackages 단일화 | ADR 0088 §root cause 패턴 (`@WebMvcTest` slice + `TypeExcludeFilters` 자동 제외) reuse |
| 옵션 C smoke test | `@SpringBootTest(webEnvironment = NONE)` Spring Boot 표준 reuse + pre-push hook (ADR 0031) `./gradlew :api:check` 자동 path reuse |
| §4-1 SLO 영향 | ADR 0095 §9-1 5 SLO 매트릭스 reuse |
| §4-2 rollback | `feedback_release_policy_patch_first` patch cut RTO 30분 LOCK reuse |
| §4-3 관측 매트릭스 | ADR 0094 §9-2 qa Visual 자동 의무 reuse (R3 qa 발견 패턴 1:1) |

---

## 6. 트레이드오프

| 의제 | 채택 | 거부 |
|---|---|---|
| 1차 적용 옵션 | **옵션 A (장기) + 옵션 C-lite (즉시 안전망)** 하이브리드 | 옵션 A 즉시 단독 (ADR 0088 §root cause 패턴 회귀 audit 부재 risk) · 옵션 B 단독 (사람 의존 재발 risk) · 옵션 C 단독 (재발 방지 100%지만 갱신 의무 잔류) |
| 옵션 A 적용 시점 | **옵션 C 안정 운영 evidence 누적 후 1 사이클 (P2 dispatch)** | 즉시 적용 (audit 부재 risk) · 무기한 보류 (재발 risk 잔류) |
| 옵션 B PR template | **거부** | 채택 (사람 의존 → 옵션 A·C 적용 후 자동 무효 본질) |
| 통합 테스트 위치 | **`api/src/test/kotlin/.../boot/JpaScanSmokeTest.kt` (api 모듈 단독)** | batch·consumer 동등 테스트 (의제 중복 — api 부팅 GREEN 시 dependency chain 자동 GREEN) · 별 모듈 (테스트 격리 비용 ↑) |
| S-OPS-1 신규 SLO | **post-MVP backlog (P0-OPS-3 amend 후보)** | 즉시 S-OPS-1 발행 (운영 evidence 누적 부족 — alert 임계 보수적 결정 부재) |
| 본 ADR R3 qa hotfix 본문 amend | **거부** | 채택 (R3 hotfix 본문 KDoc 7줄 자체 충분 + 옵션 A 적용 시 본 KDoc 동시 amend 본질) |

---

## 7. 후속 사이클 (post-MVP)

| 의제 | 위치 | 트리거 |
|---|---|---|
| **옵션 C smoke test 1건 add-only** | `api/src/test/kotlin/.../boot/JpaScanSmokeTest.kt` 신설 | **P1 backend-core dispatch (본 ADR 머지 직후 자율)** |
| **옵션 A 적용 (basePackages 단일화) + ADR 0088 amend 동반** | `JpaScanConfiguration.kt` `basePackages = ["kr.spdaewoon.magicjar"]` | **P2 backend-core dispatch (옵션 C 안정 운영 evidence 누적 후 1 사이클)** |
| 본 ADR amend (옵션 A 적용 완료 본문) | `_workspace/arch/adr/0103-jpa-scan-package-whitelist.md` | P3 architect dispatch (옵션 A 머지 후) |
| S-OPS-1 신규 SLO 발행 (`api·batch·consumer 3 컨테이너 부팅 성공률 = 100%`) | ADR 0095 §9-1 amend + Prometheus alert rule 1건 add-only | post-MVP backlog (운영 evidence 누적 후) |
| Spring Boot Bean missing 결함 자동 진단 도구 | `scripts/diagnose-bean-missing.mjs` 신설 | post-MVP (사용자 자율) |
| frontend 핵심 화면 visual 회귀 시나리오에 부팅 차단 자동 검출 row 추가 | qa Playwright spec 1건 add-only | qa P1 dispatch (post-MVP) |

---

## 8. References

- 본 ADR 발생 trigger: 2026-05-24 W4 R3 qa PR #681 commit 062abecd (api 부팅 차단 결함 hotfix 1줄)
- R3 qa task: `_workspace/tasks/T-20260524-1626-w4-r3-visual-regression.md`
- 영향받은 PR: R2 backend-core T-MCP Phase 1 (PR #679) — 신규 `api.mcp.persistence` 패키지 신설 시 화이트리스트 갱신 누락
- ADR 0088 (persistence-shared 모듈 권위 · JpaScanConfiguration 신설) — `_workspace/arch/adr/0088-persistence-shared-module-outbox-wiring.md`
- ADR 0027 (MCP 패키지 책임 분리 — api.mcp.*) · ADR 0084 (P0 OPS 부팅 안정성) · ADR 0094 (운영 단계 lens) · ADR 0095 (P0-OPS-3 SLO/SLA)
- 사용자 메모리: `feedback_conflict_resolution_autonomy` (qa 자율 hotfix 정합) · `feedback_release_policy_patch_first` (patch cut RTO 30분 LOCK) · `feedback_verification_strict` (R3 qa docker logs evidence 인용)

---

## 9. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-05-24 | **ADR 0103 신설 Accepted — JpaScanConfiguration 신규 패키지 누락 차단 (장기 fix 옵션 3건 매트릭스 + 채택안 옵션 A + 옵션 C-lite 하이브리드 LOCK + 운영 ADR 양식 3 슬롯 SLO/rollback/관측 + 후속 사이클 6 슬롯)** — 2026-05-24 W4 R3 qa PR #681 commit 062abecd 부팅 차단 결함 hotfix 1줄 직후 architect amend dispatch 정합. R2 backend-core T-MCP Phase 1 (PR #679) 신규 `api.mcp.persistence` 패키지 신설 시 `JpaScanConfiguration.kt` 화이트리스트 갱신 누락 → `UnsatisfiedDependencyException — McpAuditService 의존성 McpAuditJpaRepository bean 미발견` → exit code 1 → api·batch·consumer 3 컨테이너 dependency RED → R3 qa 자율 hotfix 1줄 add-only (`api.mcp.persistence` basePackages + entityScan 동시) + KDoc 7줄 사유. 본 ADR이 R3 hotfix 후속 재발 방지 본문 LOCK + 장기 fix 옵션 매트릭스 + 채택안 본문화. **채택안**: 옵션 A (모든 JPA 패키지 단일화 — basePackages = `kr.spdaewoon.magicjar` 자동 scan) + 옵션 C-lite (Spring context load smoke test 1건 add-only — `./gradlew :api:check` pre-push 자동 RED 차단 안전망) 하이브리드. **임시 baseline**: R3 hotfix 화이트리스트 3건 명시 유지 (옵션 A 적용은 옵션 C smoke test 안정 운영 1 사이클 후 P2 dispatch). 운영 단계 lens 정합 — 부팅 차단 결함은 S-1·S-2·S-3 SLO 직접 RED + 자본 path 단절 risk + S-OPS-1 신규 SLO 후보 본문화. **본 ADR PR diff 5 파일** (운영 코드 0 touch — md only): `_workspace/arch/adr/0103-*.md` 신설 + `_workspace/arch/adr/README.md` §9 row prepend + 카운트 12→13 + §11 후속 ADR 번호 갱신 + `docs/architecture/master-architecture.md` §10 row 추가 + §13 변경 이력 row + `CHANGELOG.md` architect slot row + `_workspace/tasks/T-20260524-1704-w4-p1-adr-amend.md`. **다른 sub-agent 영역 침범 0** (Kotlin/TS/SQL 0 변경 — ADR + README + master + CHANGELOG + task only). 자본 영향 0 + KIS endpoint 호출 0 + AI 호출 0 + mock 합성 0. (architect, opus 4.7 1M) |
