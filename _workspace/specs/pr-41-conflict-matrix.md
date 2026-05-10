# PR-41 conflict-matrix: 충돌 행렬

- 작성: architect
- 날짜: 2026-05-10 (KST)
- 짝 spec: `pr-41-pattern-engine-design.md`, `pr-41-pattern-engine-plan.md`, `pr-41-dispatch-spec.md`

## 1. 결론 요약

**시나리오 A (완전 병렬) 채택 권장** — 두 sub-agent(backend-core / strategy-engine) 영역 깔끔히 분리됨. modify-shared 후보는 `CHANGELOG.md` 1건만 발생 + 헌법 §4 슬롯 prepend로 hunk 분리하면 자동 해결. 단, 두 PR 모두 머지된 후에야 main이 컴파일 통과 → orchestrator는 "둘 다 ready 후 동시 머지" 룰 LOCK.

## 2. modify-shared 행렬 (강한 LOCK — sub-agent별 단독 영역)

| 파일 | backend-core PR-43 | strategy-engine PR-42 | architect PR-41 | LOCK |
|---|---|---|---|---|
| `settings.gradle.kts` | ✓ (include 1줄) | – | – | backend-core 단독 |
| `pattern-engine/build.gradle.kts` | ✓ (신설) | – | – | backend-core 단독 |
| `api/build.gradle.kts` | ✓ (1줄) | – | – | backend-core 단독 |
| `consumer/build.gradle.kts` | ✓ (1줄) | – | – | backend-core 단독 |
| `batch/build.gradle.kts` | – | – | – | 미수정 (pattern-engine 미의존) |
| `domain/build.gradle.kts` | – | – | – | 미수정 |
| `api/.../adapter/pattern/RedisPatternCacheAdapter.kt` | ✓ (삭제) | – | – | backend-core 단독 |
| `api/.../controller/PatternController.kt` | ✓ (import + 게이트 제거) | – | – | backend-core 단독 |
| `api/src/test/.../PatternControllerTest.kt` | ✓ (import + TestPropertySource 제거) | – | – | backend-core 단독 |
| `consumer/.../application/strategy/JvmStrategyEvaluator.kt` | – | ✓ (import 갱신) | – | strategy-engine 단독 |
| `consumer/.../application/strategy/KisYamlDslParser.kt` | – | ✓ (import 갱신, 필요 시) | – | strategy-engine 단독 |
| `_workspace/arch/adr/0004-msa-module-restructure.md` | – | – | ✓ (갱신) | architect 단독 (본 PR-41) |
| `_workspace/arch/adr/0054-pattern-engine-module.md` | – | – | ✓ (신규) | architect 단독 |
| `_workspace/arch/adr/README.md` | – | – | ✓ (0054 row 추가) | architect 단독 |
| `docs/architecture/master-architecture.md` | – | – | ✓ (§5·§6 갱신) | architect 단독 |

→ **modify-shared 충돌 가능성: 0건** (영역 깔끔히 분리됨).

## 3. CHANGELOG.md 정합 (헌법 §4)

architect가 PR-41에서 슬롯 row 3건 + anchor 4건 사전 prepend:

```markdown
| 2026-05-10 | **arch: PR-41 — pattern-engine 라이브러리 모듈 분리 spec/plan/ADR (v3.2.0 W2 T-PAT 후속)** — design + plan + dispatch-spec + conflict-matrix + ADR-0004 갱신 + ADR-0054 신규. (architect) | <!-- pr-41-slot-architect -->
| 2026-05-10 | **PR-43: backend-core — pattern-engine 빌드 그래프 + 게이트 제거 — TBD (작성: backend-core)** | <!-- pr-41-slot-backend-core -->
| 2026-05-10 | **PR-42: strategy-engine — 62파일 + 5 test pattern-engine 모듈 이동 — TBD (작성: strategy-engine)** | <!-- pr-41-slot-strategy-engine -->
| | | <!-- pr-41-slot-end -->
```

sub-agent는 본인 row만 in-place edit (`(작성: <agent>)` 마커로 식별). 두 sub-agent가 다른 row 영역에 손대면 modify-shared 충돌 발생 risk.

→ **CHANGELOG 충돌 가능성: 0건** (anchor + (작성:) 마커 정합 시).

## 4. add-only 행렬

| 파일 패턴 | sub-agent | 영역 |
|---|---|---|
| `pattern-engine/build.gradle.kts` | backend-core | 신설 |
| `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/JvmPatternRecognizer.kt` | strategy-engine | git mv 결과 |
| `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/cache/RedisPatternCacheAdapter.kt` | strategy-engine | git mv 결과 |
| `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/util/CandleMath.kt` | strategy-engine | git mv 결과 |
| `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/helper/*.kt` (2) | strategy-engine | git mv |
| `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/detector/single/*.kt` (14) | strategy-engine | git mv |
| `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/detector/triple/*.kt` (27) | strategy-engine | git mv |
| `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/detector/pair/*.kt` (16) | strategy-engine | git mv |
| `pattern-engine/src/test/kotlin/kr/spdaewoon/magicjar/pattern/*.kt` (5) | strategy-engine | git mv |

→ **add-only 충돌 가능성: 0건** (서로 다른 디렉토리).

## 5. delete 행렬

| 파일 | sub-agent | 비고 |
|---|---|---|
| `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/application/pattern/**` (62파일) | strategy-engine | git mv 결과 |
| `consumer/src/test/kotlin/kr/spdaewoon/magicjar/consumer/application/pattern/**` (5파일) | strategy-engine | git mv 결과 |
| `api/src/main/kotlin/kr/spdaewoon/magicjar/api/adapter/pattern/RedisPatternCacheAdapter.kt` | backend-core | unify 후 |

→ **delete 충돌 가능성: 0건** (서로 다른 모듈).

## 6. 빌드 안전 (시나리오 A 머지 순서)

| 시점 | 빌드 상태 | 검증 |
|---|---|---|
| PR-43 머지 직후 (PR-42 미머지) | api·consumer 모두 fail (`pattern-engine` 모듈에 0파일) | × |
| PR-42 머지 직후 (PR-43 미머지) | api fail (RedisPatternCacheAdapter 중복 + import 미갱신), consumer fail (build.gradle.kts implementation 누락 → 빈 등록 0) | × |
| **둘 다 머지 후** | `./gradlew check` 통과 + api·batch·consumer 부팅 정상 | ✓ |

→ orchestrator 머지 룰: **두 PR 모두 ready 후 2분 이내 동시 머지**. 통합 fail 시 즉시 hotfix 또는 둘 중 하나 revert 후 재진입.

대안 (더 안전):
- PR-43 머지 직전 PR-42 rebase 강제 → PR-42를 main에 미리 동기화 → 둘 다 충돌 없는 상태에서 PR-43 → PR-42 순서 머지 (1분 간격)
- 또는 architect가 두 PR을 통합한 단일 통합 PR 발행 (시나리오 C — 룰 2 위반이라 거부)

## 7. 영향 비대상 (확인 검증)

다음은 본 사이클 미영향 — sub-agent가 손대면 reject:

- `domain/**` 전체 (Spring 의존 0 정책 보존)
- `batch/**` 전체 (pattern 미사용)
- `frontend/**` 전체 (UI 변경 0)
- `docker/**` 전체 (컨테이너 수 불변)
- `_workspace/specs/pr-38-w2-t-pat-*` (PR-38 spec은 권위 인용 출처, 본 사이클 갱신 0)
- `application-*.yml` (`magicjar.pattern.enabled` 변수는 게이트 제거 후 자연 무효 — 후속 cleanup)

## 8. fail-fast 시그널

orchestrator가 다음 발견 시 즉시 fail-fast + 사용자 보고:

| 시그널 | 의미 | 분기 |
|---|---|---|
| backend-core가 62파일 본문 수정 | 영역 침범 | reject + 재디스패치 |
| strategy-engine이 build.gradle.kts·settings.gradle.kts 수정 | 영역 침범 | reject + 재디스패치 |
| sub-agent가 ADR·master-architecture·CHANGELOG 본문(슬롯 row 외) 수정 | architect 영역 침범 | reject |
| sub-agent가 0 tool uses 종료 | 환경 결함 | 재디스패치 또는 자율 진행 (둘 중 하나만, 헌법 §7) |
| 통합 후 `./gradlew :api:bootJar` fail | 빌드 그래프 누락 | backend-core hotfix |
| `init { require(detectors.size == 57) }` fail | detector 카운트 깨짐 | strategy-engine hotfix |

## 9. Links

- `pr-41-pattern-engine-design.md`
- `pr-41-pattern-engine-plan.md`
- `pr-41-dispatch-spec.md`
- ADR-0004 (모듈 매트릭스)
- ADR-0050 (CHANGELOG 슬롯 채번 — 헌법 §4)
- 헌법 §4·§5·§7
