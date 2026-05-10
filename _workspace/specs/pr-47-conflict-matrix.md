# PR-47 conflict matrix — modify-shared 행렬 + add-only 영역 + LOCK 분배

> **상태**: conflict matrix LOCK (architect 발행 — PR-47)
> **작성일**: 2026-05-11
> **연관 spec/plan/dispatch**: `pr-47-w3-t-bt-design.md` · `pr-47-w3-t-bt-plan.md` · `pr-47-dispatch-spec.md`
> **연관 ADR**: 0050(CHANGELOG 슬롯) · 0034(rebase 머지)

---

## §1. modify-shared 후보 점검 (전체 매트릭스)

### 1-1. 빌드 파일

| 파일 | architect PR-47 | backend-core PR-48 | 결론 |
|---|---|---|---|
| `settings.gradle.kts` | 변경 0 | 변경 0 (T-BT는 신규 모듈 0 — 기존 domain·api에 add-only) | **충돌 0** |
| `domain/build.gradle.kts` | 변경 0 | 변경 0 (도메인 sealed/data only — 의존 추가 0) | **충돌 0** |
| `api/build.gradle.kts` | 변경 0 | 변경 0 (기존 spring-boot-starter-web + jpa + spring-ai 의존만 사용) | **충돌 0** |
| `consumer/build.gradle.kts` | 변경 0 | 변경 0 (consumer는 본 PR 미진입 — 기존 `JvmStrategyEvaluator` 호출만, api 모듈이 consumer 의존 — 이미 ADR 0004 의존 그래프 정합) | **충돌 0** |
| `batch/build.gradle.kts` | 변경 0 | 변경 0 (batch는 본 PR 미진입 — 1차는 stateless REST run) | **충돌 0** |
| `frontend/package.json` | 변경 0 | 변경 0 (frontend는 backlog 합류) | **충돌 0** |

### 1-2. 문서·메타 파일

| 파일 | architect PR-47 | backend-core PR-48 | LOCK 분배 |
|---|---|---|---|
| `CHANGELOG.md` | prepend 4 row (architect slot + backend-core TBD slot anchor + start/end anchor) | in-place edit `<!-- pr-47-slot-backend-core -->` row 1건만 | **architect 단독 prepend → backend-core in-place edit** (ADR 0050 정합) — 충돌 0 |
| `docs/architecture/master-architecture.md` | §10 ADR 인덱스 row 1건 추가 (ADR 0056) + §12 후속 예정 시프트 | 변경 0 | **architect 단독 LOCK** — 충돌 0 |
| `_workspace/arch/adr/README.md` | §10-5 또는 §6 카테고리 row 1건 추가 (ADR 0056) + §12 후속 예정 시프트 | 변경 0 | **architect 단독 LOCK** — 충돌 0 |
| `docs/architecture/tracks-roadmap.md` | T-BT 트랙 진입 상태 갱신 row | 변경 0 | **architect 단독 LOCK** (선택 — 본 PR 사이클에서 1줄 갱신만 — 후속 PR에서 본격 갱신) — 충돌 0 |
| `_workspace/backlog.md` | §27 backlog 신규 row 합류 (frontend·결과 영속·동적 슬리피지 등) | 변경 0 | **architect 단독 LOCK** (선택) — 충돌 0 |

### 1-3. 코드 파일

| 영역 | architect PR-47 | backend-core PR-48 | 결론 |
|---|---|---|---|
| `domain/backtest/*` | add-only 0 (architect는 spec만 — 코드 0) | add-only 11 파일 (5 데이터 + 4 port + 1 exception + 1 LookaheadGuard) | **add-only 충돌 0** |
| `api/.../controller/BacktestController.kt` | add-only 0 | add-only 1 파일 (Swagger 4단 + DTO 7건) | **add-only 충돌 0** |
| `api/.../service/backtest/*` | add-only 0 | add-only 7 파일 | **add-only 충돌 0** |
| 기존 `api/.../controller/StrategyController.kt` 등 | 변경 0 | 변경 0 (참조만, 수정 0) | **충돌 0** |
| 기존 `consumer/.../strategy/JvmStrategyEvaluator.kt` | 변경 0 | 변경 0 (호출만 — `evaluateBacktest()` public API 이미 존재) | **충돌 0** |

### 1-4. SQL/Flyway

| 파일 | 변경 | 결론 |
|---|---|---|
| `api/src/main/resources/db/migration/V*.sql` | **변경 0건** (1차는 결과 영속 X — stateless REST run) | **충돌 0** |
| (post-MVP) `backtest_run`·`backtest_trade` 테이블 | backlog §27-2 합류 | 본 PR 비범위 |

### 1-5. 환경변수 (.env.example)

| 파일 | 변경 | 결론 |
|---|---|---|
| `.env.example` | 변경 0건 (기존 `MAGICJAR_DSL_BACKTEST_TIMEOUT_MS=1000` 이미 ADR 0047 정합 — 신규 변수 0건. 추후 backlog §27에서 모델별 환경변수 추가 시점 검토) | **충돌 0** |

---

## §2. add-only 영역 (충돌 자연 해소)

### 2-1. 신규 디렉토리

| 디렉토리 | 소유 sub-agent | 비고 |
|---|---|---|
| `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/backtest/` | backend-core | 11 파일 add-only |
| `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/backtest/port/` | backend-core | 4 port 파일 |
| `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/backtest/` | backend-core | 7 파일 add-only |
| `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/backtest/tax/` | backend-core | 1 파일 |
| `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/backtest/commission/` | backend-core | 1 파일 |
| `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/backtest/slippage/` | backend-core | 1 파일 |
| `api/src/test/kotlin/kr/spdaewoon/magicjar/api/service/backtest/` | backend-core | 7 테스트 파일 |

### 2-2. 신규 파일 (architect)

- `_workspace/specs/pr-47-w3-t-bt-design.md` (이미 작성)
- `_workspace/specs/pr-47-w3-t-bt-plan.md` (이미 작성)
- `_workspace/specs/pr-47-dispatch-spec.md` (본 파일과 동일 디렉토리)
- `_workspace/specs/pr-47-conflict-matrix.md` (본 파일)
- `_workspace/arch/adr/0056-backtest-engine-architecture.md` (이어서 작성)

---

## §3. LOCK 분배 (modify-shared 단독 LOCK 매트릭스)

| modify-shared 파일 | 단독 LOCK sub-agent | 변경 한도 |
|---|---|---|
| `CHANGELOG.md` (prepend slot anchor) | architect | 4 row prepend (architect 본인 row + 3 anchor: architect slot 마커·backend-core TBD slot·start/end anchor) |
| `CHANGELOG.md` (in-place edit slot anchor 내부) | backend-core | `<!-- pr-47-slot-backend-core -->` anchor 내부 row 1건만 (anchor 라인 자체 수정 금지) |
| `docs/architecture/master-architecture.md` | architect | §10 ADR 인덱스 row 1건 추가 (ADR 0056) + §12 후속 예정 시프트 |
| `_workspace/arch/adr/README.md` | architect | §6 (또는 §10-5 신규 카테고리) row 1건 추가 + §12 후속 예정 시프트 |
| 그 외 | (없음) | (변경 0) |

---

## §4. 시나리오 A 적극 병렬 가능 여부 결론

### 4-1. 결론: **시나리오 A 단일 sub-agent backend-core 직렬** 채택

**사유**:
1. **단일 sub-agent**: backend-core 단독 dispatch — 분리 가치 없음 (plan §2-3)
2. **add-only 충돌 0**: §1·§2 매트릭스 검증 결과 modify-shared 충돌 0
3. **머지 단순**: PR-47 (architect) → PR-48 (backend-core) 직렬 — 빌드 그래프 변경 0이라 동시 머지 불요
4. **CI 영향 0**: ADR 0049 정합 — main push 시 CI 트리거 0

### 4-2. 적극 병렬 미적용 사유

본 PR-47은 단일 sub-agent이므로 "병렬" 개념 자체가 비적용. **시나리오 B 다중 sub-agent (strategy-engine + frontend 병렬)는 plan §2-3에서 scope-out** — 다음 사유:

- **strategy-engine**: 작업 분량 ~300 LOC로 작고 자연 분리 불요 (이미 `evaluateBacktest()` API 존재)
- **frontend**: backend-core endpoint 동결 후 자연 직렬 — backlog §27-1 합류

### 4-3. 적극 병렬 재검토 트리거

다음 트리거 발생 시 architect 재검토:
- backend-core PR-48 분량이 ~1500 LOC를 초과해 부담 누적
- 사용자가 frontend 즉시 출시 요구
- 결과 영속(`backtest_run` 테이블) 의제 본 사이클 합류

위 발생 시 본 conflict matrix 갱신 + 추가 sub-agent dispatch.

---

## §5. 빌드 안전 매트릭스

| 단계 | 검증 | 예상 결과 |
|---|---|---|
| **PR-47 architect 머지 후 main** | `./gradlew check` (pre-push hook 자동) | GREEN — docs/spec/adr/README/CHANGELOG/master-architecture만 변경, 코드 0건 |
| **PR-48 backend-core 머지 후 main** | `./gradlew check`, 특히 `:domain:check :api:check` | GREEN — add-only 코드, 기존 코드 수정 0 |
| **두 PR 간 의존성** | 없음 (직렬, 빌드 그래프 변경 0) | 충돌 0 |
