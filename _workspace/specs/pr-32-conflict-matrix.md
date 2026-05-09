# PR-32 충돌 행렬 (T-IND R1+R2 — 완전 병렬 권장 시나리오 A)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-09
- 정합 SoT: `_workspace/specs/pr-32-w2-t-ind-design.md` v1 + `pr-32-dispatch-spec.md` v1
- 본 문서: PR-33a (strategy-engine) + PR-33b (backend-core) 병렬 디스패치 시 modify-shared·add-add 충돌 사전 점검 매트릭스.
- 헌법 §4 (CHANGELOG 슬롯 채번룰) + 사용자 메모리 `feedback_orchestrator_active_parallel.md` (적극 병렬 디스패치) 정합.

---

## §1. 결론 (TL;DR)

**시나리오 A — 완전 병렬 권장**. PR-33a·PR-33b 동시 background 발사 안전.

- modify-shared 0건 (기존 파일 동시 수정 0)
- add-add 0건 (둘 다 신규 생성하는 동일 파일 0)
- CHANGELOG add-add는 ADR 0050 in-place edit으로 충돌 회피 (anchor + slot row 사전 발행 정합)
- 인터페이스 동결: PR-33b가 domain port 신규 add-only → PR-33a는 read-only import만

orchestrator는 본 spec PR-32 머지 즉시 PR-33a·PR-33b 동시 background 발사 가능. 직렬 권장 답습 금지 (사용자 메모리 정합).

---

## §2. modify-shared 매트릭스 (기존 파일 동시 수정)

| 파일 | PR-33a strategy-engine | PR-33b backend-core | 충돌 위험 | 해결 |
|---|---|---|---|---|
| `domain/build.gradle.kts` | (touch 0) | (touch 0 — kotlinx-datetime·java.math 기존 dep 충분) | 0 | - |
| `consumer/build.gradle.kts` | (touch 0 — Redis·Spring 기존 dep 충분) | (touch 0) | 0 | - |
| `api/build.gradle.kts` | **수정 가능 — `:consumer` dep 추가** | (touch 0) | 0 | strategy-engine 단독 LOCK |
| `consumer/src/main/resources/application.yml` | (touch 0 — Redis URL 기존) | (touch 0) | 0 | - |
| `api/src/main/resources/application.yml` | (touch 0) | (touch 0) | 0 | - |
| `settings.gradle.kts` | (touch 0 — 기존 :api·:consumer·:domain·:batch 그대로) | (touch 0) | 0 | - |
| `CHANGELOG.md` | **본인 row in-place edit** | **본인 row in-place edit** | 0 | ADR 0050 — `(작성: ...)` 마커 row 1줄만 수정. anchor `<!-- pr-32-slot-* -->` hunk 분리. git diff line 다름 인식. |

**총 modify-shared 1건 (api/build.gradle.kts)** — strategy-engine 단독 LOCK으로 충돌 0.
**CHANGELOG 1건** — ADR 0050 in-place edit으로 충돌 0.

---

## §3. add-add 매트릭스 (둘 다 신규 생성 동일 파일)

| 파일 | PR-33a strategy-engine | PR-33b backend-core | 충돌 위험 |
|---|---|---|---|
| `domain/.../indicator/IndicatorSpec.kt` | (touch 0) | **신규** | 0 |
| `domain/.../indicator/IndicatorResult.kt` | (touch 0) | **신규** | 0 |
| `domain/.../indicator/IndicatorPoint.kt` | (touch 0) | **신규** | 0 |
| `domain/.../indicator/IndicatorEngine.kt` | (touch 0) | **신규** | 0 |
| `domain/.../indicator/IndicatorException.kt` | (touch 0) | **신규** | 0 |
| `domain/.../indicator/IndicatorCatalog.kt` | (touch 0) | **신규** | 0 |
| `domain/.../indicator/port/IndicatorSource.kt` | (touch 0) | **신규** | 0 |
| `consumer/.../application/indicator/source/KisStrategyBuilderSource.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../application/indicator/source/kis/IndicatorTrend.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../application/indicator/source/kis/IndicatorMomentum.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../application/indicator/source/kis/IndicatorVolume.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../application/indicator/source/kis/IndicatorVolatility.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../application/indicator/source/kis/IndicatorOscillator.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../application/indicator/source/kis/IndicatorOther.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../application/indicator/source/NativeIndicatorSource.kt` | (touch 0) | **신규** | 0 |
| `consumer/.../application/indicator/source/native/NativeMa.kt` | (touch 0) | **신규** | 0 |
| `consumer/.../application/indicator/source/native/NativeEma.kt` | (touch 0) | **신규** | 0 |
| `consumer/.../application/indicator/source/native/NativeRsi.kt` | (touch 0) | **신규** | 0 |
| `consumer/.../application/indicator/source/native/NativeMacd.kt` | (touch 0) | **신규** | 0 |
| `consumer/.../application/indicator/source/native/NativeBollingerBands.kt` | (touch 0) | **신규** | 0 |
| `consumer/.../application/indicator/IndicatorEngineConfig.kt` | **신규 (LOCK)** | (touch 0) | 0 |
| `consumer/.../application/indicator/CrossCheckEngine.kt` | (touch 0) | **신규 (옵션)** | 0 |
| `consumer/.../infrastructure/cache/RedisIndicatorCacheAdapter.kt` | **신규** | (touch 0) | 0 |
| `api/.../controller/IndicatorController.kt` | **신규** | (touch 0) | 0 |
| `api/.../controller/IndicatorResultDto.kt` | **신규** | (touch 0) | 0 |
| `api/.../controller/config/IndicatorEngineConfig.kt` | **신규 (LOCK)** | (touch 0) | 0 |

**총 add-add 0건** — 양쪽 영역 add-only로 완전 분리.

**테스트 add-add**: 양쪽 단위 테스트 5종씩 신규 — 파일명 prefix 다름 (`KisStrategyBuilderSource*Test` vs `Native*Test`) → 충돌 0.

---

## §4. CHANGELOG hunk 분리 (ADR 0050 정합)

본 spec PR-32 머지 시점에 sub-agent별 anchor + slot row 사전 발행:

```markdown
| 2026-05-09 | architect spec row | docs/spec | ... |
<!-- pr-32-slot-architect -->
| 2026-05-09 | TBD ... (작성: strategy-engine) — TBD | consumer/api | TBD |
<!-- pr-32-slot-strategy-engine -->
| 2026-05-09 | TBD ... (작성: backend-core) — TBD | domain/consumer | TBD |
<!-- pr-32-slot-backend-core -->
<!-- pr-32-slot-end -->
```

### 4-1. 머지 순서별 충돌 시나리오

#### 시나리오 1 — strategy-engine 먼저 머지

1. strategy-engine PR-33a 머지 → CHANGELOG `(작성: strategy-engine)` row 채워짐 (Line N)
2. backend-core PR-33b는 main rebase → 본인 row `(작성: backend-core)` 위치 그대로 (Line N+1, anchor `<!-- pr-32-slot-backend-core -->` 변경 0)
3. backend-core fillout 후 push → CHANGELOG diff = backend-core row만 수정 → 충돌 0

#### 시나리오 2 — backend-core 먼저 머지

대칭. 충돌 0.

#### 시나리오 3 — 동시 push (race condition)

git PR 머지는 직렬 (origin main HEAD 1개) — race 자체 발생 안 함. 한 쪽이 먼저 머지되면 다른 쪽은 rebase 의무.

### 4-2. anchor 위치 LOCK

본 spec PR-32 머지 후 CHANGELOG.md 정확 위치:

```
Line 5: | 2026-05-09 | docs(specs): PR-32 ... (architect) | docs/spec | ... |
Line 6: <!-- pr-32-slot-architect -->
Line 7: | 2026-05-09 | TBD ... (작성: strategy-engine) — TBD | consumer/api | TBD |
Line 8: <!-- pr-32-slot-strategy-engine -->
Line 9: | 2026-05-09 | TBD ... (작성: backend-core) — TBD | domain/consumer | TBD |
Line 10: <!-- pr-32-slot-backend-core -->
Line 11: <!-- pr-32-slot-end -->
Line 12+: 기존 v3.1.0 마감 row 등 ...
```

sub-agent는 anchor 마커 grep으로 본인 row 위치 자동 식별 → in-place edit. 다른 row·다른 anchor 절대 수정 금지.

---

## §5. 빌드 의존성 검증

### 5-1. domain 모듈 — Spring·JPA 의존 0 유지

| 추가 import 후보 | 허용? | 사유 |
|---|---|---|
| `kotlinx.datetime.Instant` | ✅ | 기존 `domain/market/event/TickEvent.kt` 등에서 사용 중 |
| `java.math.BigDecimal` | ✅ | JDK 표준 — 기존 사용 중 |
| `org.springframework.*` | ❌ | domain 순수성 위반 (master §5-1) |
| `jakarta.persistence.*` | ❌ | JPA 의존 위반 |
| `com.fasterxml.jackson.*` | ❌ | 1차 보류 — IndicatorResult JSON 직렬화는 api/consumer adapter에서 |

### 5-2. consumer 모듈 — 기존 dep 재사용

| dep | 기존 보유? | 본 PR 추가? |
|---|---|---|
| Spring Web | ✅ (KIS REST) | - |
| Spring Data Redis | ✅ (RedisQuoteCacheAdapter) | - |
| Jackson | ✅ | - |

### 5-3. api 모듈 — `:consumer` dep 추가 필요

**현재 검증 필요 항목**: `api/build.gradle.kts`에 `:consumer` dep 존재 여부.

```kotlin
// api/build.gradle.kts
dependencies {
    implementation(project(":domain"))
    // implementation(project(":consumer"))  // 본 PR 추가 후보
}
```

**circular dep risk**: `consumer/build.gradle.kts`에 `:api` dep 존재 검증 의무.
- 만약 consumer가 api dep 가지면 → circular → architect 호출 → spec fallback (api 모듈 코드 복사 OR domain 모듈 source 이동)
- 현재 알기로는 api·consumer 둘 다 domain만 의존 (master §5-1 정합) — circular 0

**sub-agent 책임**: PR-33a strategy-engine이 빌드 검증 (`./gradlew :api:bootJar`). circular fail 시 즉시 architect 호출.

---

## §6. 사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합

> "architect '직렬 권장' 답습 금지. 사용자 의도+실제 충돌 행렬 재검토 후 적극 병렬화. add-only 충돌은 진짜 병렬 가능"

본 PR-32에서:
- modify-shared = 1건 (api/build.gradle.kts) → strategy-engine 단독 LOCK으로 0
- add-add = 0건 (영역 분리 완전)
- CHANGELOG = ADR 0050 in-place edit으로 충돌 0
- 인터페이스 동결 = domain port 신규 add-only → race 0

**결론**: PR-33a·PR-33b **완전 병렬 가능**. orchestrator는 직렬 권장 답습 금지.

병렬 디스패치 시 예상 wallclock:
- 직렬 (PR-33b → PR-33a): ~6시간 (domain port 1.5h + KIS 80지표 4h + REST 0.5h)
- **병렬 (PR-33a ⊕ PR-33b)**: ~4-5시간 (max(domain port 1.5h, KIS 80지표 4h) + REST 0.5h)

병렬화 이득 ~25% wallclock 단축.

---

## §7. 운영 가드 (orchestrator 디스패치 시점 사전 점검)

### 7-1. 디스패치 직전 점검 (orchestrator 의무)

- [ ] PR-32 spec PR 머지 완료 (`git log origin/main --oneline -3`에 PR-32 commit hash 존재)
- [ ] CHANGELOG sub-agent slot row 3건 + anchor 4건 main에 존재 (`grep "pr-32-slot-" CHANGELOG.md` → 4건)
- [ ] task 파일 archive 완료 (architect T-20260509-0947)
- [ ] worktree·로컬 브랜치 정리 (heritage §2)
- [ ] sub-agent 프롬프트에 본 spec 3 파일 + ADR 0021/0020/0050 + KIS-first 정책 인용 의무 명시
- [ ] orchestrator 본인은 main 위 — task `--agent orchestrator` start

### 7-2. 머지 단계 점검 (orchestrator 의무)

먼저 머지된 PR — 본인 row 채워짐 + 다른 row TBD 그대로:
- [ ] CHANGELOG diff에 anchor 변경 0
- [ ] CHANGELOG diff에 본인 row 1줄만 수정 (`(작성: ...)` 마커 정합)
- [ ] 다른 sub-agent slot row TBD 그대로

두 번째 머지 PR — main rebase 후:
- [ ] CHANGELOG conflict 0 (있다면 즉시 rebase 자체 해결 — 사용자 메모리 `feedback_conflict_resolution_autonomy.md` 정합)

### 7-3. 머지 직후 정리 (heritage §2)

- [ ] main pull (root worktree)
- [ ] task archive (각 sub-agent task)
- [ ] worktree remove (각 sub-agent worktree)
- [ ] agent/* 로컬 브랜치 삭제

---

## §8. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-09 | PR-32 충돌 행렬 v1 발행. modify-shared 1건(api/build.gradle.kts — strategy-engine 단독 LOCK)·add-add 0건·CHANGELOG ADR 0050 정합. 시나리오 A 적극 병렬 권장 결론. 사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합. (architect, opus 4.7 1M) |
