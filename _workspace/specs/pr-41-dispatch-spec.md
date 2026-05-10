# PR-41 dispatch-spec: sub-agent 디스패치 명세

- 작성: architect
- 날짜: 2026-05-10 (KST)
- 짝 spec: `pr-41-pattern-engine-design.md`, `pr-41-pattern-engine-plan.md`
- 충돌 행렬: `pr-41-conflict-matrix.md`

## 1. sub-agent 영역 분배 (LOCK)

| sub-agent | 모듈 | LOC 추정 | branch | PR# (예정) |
|---|---|---|---|---|
| backend-core | settings.gradle.kts + pattern-engine/build.gradle.kts + api/build.gradle.kts + consumer/build.gradle.kts + api PatternController·Test + RedisPatternCacheAdapter 삭제 | ~80 | `agent/backend-core/pr-43-pattern-engine-graph` | PR-43 |
| strategy-engine | pattern-engine/src/{main,test} 67파일 (이동) + consumer JvmStrategyEvaluator·KisYamlDslParser import | ~200 import + 67 git mv | `agent/strategy-engine/pr-42-pattern-engine-move` | PR-42 |

영역 침범 절대 금지:
- backend-core가 62파일 본문 수정 → reject
- strategy-engine이 build.gradle.kts·settings.gradle.kts 수정 → reject
- 둘 다 ADR·master-architecture·CHANGELOG 본문 수정 금지 (architect 영역, 본 PR-41에서 일괄 처리)

## 2. 머지 정책

- 브랜치 base: `main`
- 머지 방식: `--rebase` (헌법 §5 + CLAUDE.md 정합 — squash 금지, author 보존)
- PR 직전 의무: `git fetch origin main && git rebase origin/main`
- author email: `bot+<agent>@magicjar.local` (pre-commit whitelist — `bot+backend-core@magicjar.local` / `bot+strategy@magicjar.local`)

## 3. 디스패치 시점

orchestrator는 본 PR-41(architect spec/plan/ADR/CHANGELOG 슬롯) 머지 직후 두 sub-agent를 background로 동시 발사:

```bash
# 두 sub-agent를 동시 background dispatch (orchestrator 영역)
# 시나리오 A 채택 — plan §2 정합
```

각 sub-agent는 본인 spec 인용:
- backend-core → `pr-41-pattern-engine-design.md` §4-1·§4-4·§7 + `pr-41-pattern-engine-plan.md` §1-1
- strategy-engine → `pr-41-pattern-engine-design.md` §5·§6 + `pr-41-pattern-engine-plan.md` §1-2

## 4. CHANGELOG 슬롯 (헌법 §4 ADR-0050 정합)

architect가 본 PR-41에서 사전 prepend:

```markdown
| 2026-05-10 | **arch: PR-41 — pattern-engine 라이브러리 모듈 분리 spec/plan/ADR (v3.2.0 W2 T-PAT 후속)** — design + plan + dispatch-spec + conflict-matrix + ADR-0004 갱신 + ADR-0054 신규 + master-architecture §5·§6 5모듈 정합 + CHANGELOG 슬롯 3건 발행. (architect) | <!-- pr-41-slot-architect -->
| 2026-05-10 | **PR-43: backend-core — pattern-engine 빌드 그래프 + 게이트 제거 — TBD (작성: backend-core)** | <!-- pr-41-slot-backend-core -->
| 2026-05-10 | **PR-42: strategy-engine — 62파일 + 5 test pattern-engine 모듈 이동 — TBD (작성: strategy-engine)** | <!-- pr-41-slot-strategy-engine -->
| | | <!-- pr-41-slot-end -->
```

sub-agent 의무:
- `(작성: <agent>)` 마커로 본인 row 식별
- `TBD` 자리표시자만 실제 사유로 교체
- 다른 row·다른 anchor 절대 수정 금지 (영역 침범)

## 5. KIS-first 의무 (헌법 §3)

본 사이클은 코드 이동만 — 새 KIS endpoint·새 외부 데이터 규격 결정 0건. raw fetch 발동 비대상.

단, sub-agent에 전파:
- strategy-engine PR-42에서 옮기는 62파일 KDoc 인용 보존 의무 (`Source: https://github.com/koreainvestment/...`)
- 본문 코드 변경 0 (이동 + 패키지/import 갱신만)
- 자체 추론 0 — architect spec §6 LOCK 시그니처 정합

## 6. fitness gap 검증 의무 (사용자 메모리 `feedback_v3_skepticism_no_regression.md`)

각 sub-agent PR 본문에:

```
## fitness gap
- v1/v2 잔재: 0건 (W2 T-PAT v3 신규 트랙 코드 이동만)
- 자동매매 적합성: 패턴 인식 = W3 T-SIG 시그널 룰 입력 + W2 카탈로그 본체 → 적합 ✓
- 모듈 경계: ADR 0004 §의존 방향 정합 보존 (api·consumer 직접 의존 0, pattern-engine 공유)
```

## 7. 룰 5 검증

| 룰 | 검증 |
|---|---|
| 룰 1 (QA 게이트) | 본 사이클 발동 0 — spec/plan + 이동 + 빌드 그래프 |
| 룰 2 (단일 책임 PR) | PR-42 = 코드 이동 단일, PR-43 = 빌드 그래프 + api 정합 단일 |
| 룰 3 (라운드 분할) | 본 사이클 R0 모듈 경계 정합 — 새 layer 추가 0 |
| 룰 4 (코드 먼저 테스트 나중) | 테스트 5파일 이동만 (신규 0) |
| 룰 5 (AI 호출 자제) | LLM provider import 0건 — pattern-engine은 W2 |

## 8. fail-fast 분기 (헌법 §7)

- sub-agent 0 tool uses 종료 → orchestrator 재디스패치
- sub-agent 영역 침범 (예: backend-core가 62파일 본문 수정) → orchestrator reject + 재디스패치
- 통합 빌드 fail (`./gradlew check`) → orchestrator 자율 hotfix 또는 sub-agent 재호출 (둘 중 하나만)
- 통합 부팅 fail (`api` 컨테이너 `No bean PatternRecognizer`) → backend-core 재호출 (빌드 그래프 누락)

## 9. 회신 보고 의무 (sub-agent → orchestrator)

각 sub-agent PR 본문에 명시:

- 본 spec(`pr-41-pattern-engine-design.md`·`pr-41-pattern-engine-plan.md`) 정합 인용 (§ 번호)
- 영역 침범 0 검증 (modify-shared 행렬 §4 정합)
- CHANGELOG 슬롯 row in-place edit 영역 명시 (다른 row 미수정 검증)
- LOC + 변경 파일 수
- `./gradlew :<module>:check` 결과
- KIS-first 의무 정합 (KDoc 인용 보존 — strategy-engine PR-42)

## 10. Links

- `pr-41-pattern-engine-design.md`
- `pr-41-pattern-engine-plan.md`
- `pr-41-conflict-matrix.md`
- ADR-0004 (모듈 매트릭스 갱신)
- ADR-0054 (pattern-engine 신설)
- ADR-0050 (CHANGELOG 슬롯 채번)
- 헌법 §3·§4·§5·§7
