# magicJar legacy 청소 계획 v2

> **상태**: DRAFT — 사용자 검토 대기
> **작성일**: 2026-05-03
> **작성자**: architect (opus 4.7 1M)
> **트리거**: 사용자 명시 — "이젠 실수 용납안된다. 깃에 있는것도 release말곤 싹지울 예정이야". v2 비전 정정 후 클린 베이스라인 진입을 위한 청소.
> **선행**: master-architecture.md v2, tracks-roadmap.md v2, REORGANIZATION.md
> **실행자**: 메인 세션이 본 문서를 보고 일괄 실행. architect는 분류·사유만 제공.

본 문서는 magicJar 프로젝트 디렉토리 전체를 4개 카테고리로 분류해 처리 방안을 제시한다.

| 카테고리 | 대상 | 처리 |
|---|---|---|
| **A. 즉시 삭제** | 사용처 0 + 비전 어긋남 (사용자 "release외 싹 지울" 정합) | `git rm` |
| **B. `_workspace/_archive/` 이동** | 과거 retrospective·QA 카드·dogfooding 기록 | `git mv` |
| **C. 보존** (소프트, 본 위치 유지) | 1번 트랙 운영 가이드·살아있는 task | 변경 0 |
| **D. 트랙2 spec/plan 재활용** | AI 자동 매매 가정 부분만 제거 후 본체 흡수 | 본문 일부 수정 |

---

## §1. A. 즉시 삭제 (사용처 0 + 비전 위반)

### A-1. 코드 — 사용처 0 어댑터

**목표**: 1번 트랙 commit 메시지에 명시된 "batch 측 BrokerGateway·KisBrokerGateway·FakeBrokerGateway는 deprecate (사용처 0)" 정합 처리. 현재 위치 검색 결과:

| 절대 경로 | 사유 | 처리 |
|---|---|---|
| `/Users/ak-song/magic_ch1/domain/src/main/kotlin/com/mj/domain/order/port/BrokerGateway.kt` | OrderDispatcherListener는 OrderSender로 전환 완료. BrokerGateway port 사용처 0 | `git rm` |
| `/Users/ak-song/magic_ch1/consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/FakeBrokerGateway.kt` | BrokerGateway 구현체. 사용처 0 | `git rm` |
| `/Users/ak-song/magic_ch1/consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisBrokerGateway.kt` | BrokerGateway 구현체. 사용처 0 | `git rm` |

**검증 방법**: 메인 세션이 `git rm` 후 `./gradlew :domain:compileKotlin :consumer:compileKotlin` 통과 확인. Bean 레퍼런스(application.yml·@ComponentScan exclude 등)도 grep해서 잔여 0 확인.

**부수 정리**: OrderDispatcherListener.kt 주석 갱신 — "BrokerGateway는 deprecate. KisBrokerGateway·FakeBrokerGateway 빈은 호출처 0이나 일단 잔존" → "BrokerGateway 폐기 완료" 로 변경.

### A-2. 1차안 architect 산출물 (v2로 풀 폐기)

**목표**: v1 1차안 폐기 — v2가 같은 위치에 풀 재작성됨. 단 1차안의 ADR 0036 단독 파일 위치는 정식 ADR 디렉토리로 이전.

| 절대 경로 | 사유 | 처리 |
|---|---|---|
| `/Users/ak-song/magic_ch1/docs/architecture/adr-0036-kis-strategy-builder-integration.md` | 1차안 — `_workspace/arch/adr/0036-*.md`로 v2 재작성 후 본 위치 폐기 | `git rm` |
| `/Users/ak-song/magic_ch1/docs/architecture/track2-relocation.md` | 1차안 — track2 통합 가이드. v2의 master §10·tracks-roadmap §3에 흡수됨 | `git rm` |

**참고**: master-architecture.md·tracks-roadmap.md는 같은 위치에 v2로 덮어쓰기 (이미 본 세션에서 완료).

### A-3. 1차안 인라인 ADR draft

**목표**: 1차안 master §8·트랙2 spec §13에 인라인된 0031~0045 14건은 정식 발행 전 폐기.

처리: master-architecture.md v2가 §10에서 0031~0047 17건으로 재정의 — 인라인 draft 자동 폐기. 별도 파일 없음.

### A-4. 사용자 명시 정합 — 사용처 없는 mock·실험 코드

사용자 ADR 0029 인용: "Mock 발행기는 보존 (코드 삭제 안 함) — 향후 offline dev 가치". 단 v2 비전에서 mock은 default off + offline 데모 한정. **현재 보존**. (post-W3에서 재검토)

---

## §2. B. `_workspace/_archive/` 이동 (과거 산출물 보존, 가시성 줄임)

### B-1. 디렉토리 신설

```
_workspace/_archive/
├── README.md                # 본 archive 이용 가이드
├── retrospective/           # 과거 회고
├── qa-issues/               # 과거 QA 카드 (ISSUE-001~007)
├── dogfooding/              # 과거 dogfooding 기록
├── tasks-done/              # 과거 task 파일
└── legacy-code/             # 기존 _workspace/_legacy/ 통합
```

### B-2. 이동 대상

| 절대 경로 (현재) | 이동 후 | 사유 |
|---|---|---|
| `/Users/ak-song/magic_ch1/_workspace/_legacy/` 전체 | `_workspace/_archive/legacy-code/` | Phase D-P1에서 백업한 코드 (ai-app·market-kis·strategy-* 등 19 디렉토리). 일부는 _legacy/_legacy/_legacy 응축 — archive로 일괄 |
| `/Users/ak-song/magic_ch1/_workspace/_retrospective-phase-b.md` | `_workspace/_archive/retrospective/phase-b.md` | 과거 회고 |
| `/Users/ak-song/magic_ch1/_workspace/dogfooding-2026-05-02.md` | `_workspace/_archive/dogfooding/2026-05-02.md` | 1번 트랙 dogfooding 기록 — W1 진입 후 참조 가치 (중단 사유·발견 사항) |
| `/Users/ak-song/magic_ch1/_workspace/qa/issues/ISSUE-008/` 디렉토리 | `_workspace/_archive/qa-issues/ISSUE-008/` | 5번 트랙 QA 디펙 1차 결과 (ISSUE-008은 PR #63에서 FIXED 처리됨) |
| `/Users/ak-song/magic_ch1/_workspace/qa/report-20260501-1042.md` | `_workspace/_archive/qa-issues/report-20260501-1042.md` | Phase F R2 통합 QA 보고서 (PR #38·#39·#40·#41로 종결) |
| `/Users/ak-song/magic_ch1/_workspace/qa/issues.md` | `_workspace/_archive/qa-issues/issues.md` | AUTO-INDEX 누적 |
| `/Users/ak-song/magic_ch1/_workspace/tasks/done/T-20260425-2213-harness-v2-six-pack.md` | `_workspace/_archive/tasks-done/` | 과거 task 9건 일괄 |
| `/Users/ak-song/magic_ch1/_workspace/tasks/done/T-20260430-*.md` (3건) | 동상 | 동상 |
| `/Users/ak-song/magic_ch1/_workspace/tasks/done/T-20260501-*.md` (5건) | 동상 | 동상 |
| `/Users/ak-song/magic_ch1/_workspace/dashboard.html` | `_workspace/_archive/dashboard.html` | render-status.mjs가 사용하나 v2 진입 시점에 신규 task 파일로 채울 예정. 과거 dashboard html은 archive |
| `/Users/ak-song/magic_ch1/_workspace/backend/`·`market-data/`·`strategy/`·`frontend/`·`ai/` 디렉토리 | `_workspace/_archive/{영역}/` | Phase A 도메인 모델 draft 등 — 마스터 v2가 source of truth라 참조 가치는 archive로 |

총 이동 ~30 디렉토리·파일.

### B-3. archive 운영 규칙

- archive는 **읽기 전용**. 새 트랙은 archive 참조 시 명시 ("archive/qa-issues/ISSUE-008 참고" 식)
- archive 내용은 git에 보존되어 history 추적 가능
- 사용자 명시 "release외 싹 지울" 정합 시점이 오면 archive 자체도 일괄 삭제 후보 (W5 종료 후)

---

## §3. C. 보존 (소프트, 본 위치 유지)

| 절대 경로 | 사유 |
|---|---|
| `/Users/ak-song/magic_ch1/_workspace/operations/order-policy.md` | 1번 트랙 운영 가이드 (KillSwitch 절차·DailyLimit 단계적 조정·trading_mode 전환·master key rotation). 살아있는 운영 문서 |
| `/Users/ak-song/magic_ch1/_workspace/arch/adr/` (0001~0030 + REORGANIZATION.md + 새 0031~0047) | ADR 정식 위치. 상태 라벨로 관리 |
| `/Users/ak-song/magic_ch1/_workspace/arch/branching-and-qa-gates.md` | 운영 문서 (브랜치·QA 정책) — v2 진입 시 보강 필요하나 위치 유지 |
| `/Users/ak-song/magic_ch1/_workspace/_harness-state.json` | 하네스 상태 파일. 메인 세션이 매 phase 갱신 |
| `/Users/ak-song/magic_ch1/_workspace/tasks/README.md` | task 파일 시스템 가이드 |
| `/Users/ak-song/magic_ch1/_workspace/qa/README.md`·`issues/README.md` | 디펙 카드 양식 가이드 |
| `/Users/ak-song/magic_ch1/docs/architecture/master-architecture.md` (v2) | 마스터 아키텍처 — 본 세션 v2로 풀 재작성 완료 |
| `/Users/ak-song/magic_ch1/docs/architecture/tracks-roadmap.md` (v2) | 트랙 로드맵 — 본 세션 v2로 풀 재작성 완료 |
| `/Users/ak-song/magic_ch1/docs/architecture/cleanup-plan.md` | 본 문서 |
| `/Users/ak-song/magic_ch1/docs/architecture/development-rules.md` | 개발 가이드 (본 세션 신설) |
| `/Users/ak-song/magic_ch1/docs/superpowers/specs/2026-05-02-track1-kis-order-design.md` | 1번 트랙 spec (완료) — 회고 자료로 보존 |
| `/Users/ak-song/magic_ch1/docs/superpowers/plans/2026-05-02-track1-kis-order.md` | 1번 트랙 plan (완료) — 동상 |
| `/Users/ak-song/magic_ch1/docs/superpowers/specs/2026-04-27-msa-restructure-design.md` | MSA 재구성 spec (완료) — Phase D 회고 자료 |
| `/Users/ak-song/magic_ch1/docs/superpowers/plans/2026-04-27-msa-restructure*.md` (4건) | 동상 |
| `/Users/ak-song/magic_ch1/docs/superpowers/plans/2026-04-28-phase-e*.md` (2건) | Phase E 회고 자료 |
| `/Users/ak-song/magic_ch1/docs/superpowers/{specs,plans}/2026-05-01-qa-defect-loop*.md` | 5번 트랙 spec/plan (완료) — 회고 |
| `/Users/ak-song/magic_ch1/.claude/`·`.husky/`·`.github/` | 운영 인프라 |
| `/Users/ak-song/magic_ch1/scripts/` | 운영 스크립트 |
| `/Users/ak-song/magic_ch1/domain/`·`api/`·`batch/`·`consumer/`·`pyworker/`·`frontend/`·`docker/` | 본체 코드 — 트랙별 진입 시 변경 |

---

## §4. D. 트랙2 spec/plan 재활용 (AI 자동 매매 가정 제거 후 본체 흡수)

### D-1. 대상 파일

| 절대 경로 | 처리 |
|---|---|
| `/Users/ak-song/magic_ch1/docs/superpowers/specs/2026-05-02-track2-market-session-and-data-mgmt-design.md` (969줄) | **본체에 그대로 살림** + 일부 본문 수정 |
| `/Users/ak-song/magic_ch1/docs/superpowers/plans/2026-05-02-track2-market-session-and-data-mgmt.md` (573줄) | **본체에 그대로 살림** + 일부 본문 수정 |

### D-2. 본문 수정 가이드 (architect의 W1 진입 시 1회 수정)

트랙2 spec/plan은 v2 마스터의 T2 트랙 그 자체. 다음만 수정 후 진행:

| 위치 | 1차안 | v2 수정 |
|---|---|---|
| spec §"AI Decision Layer 인터페이스 의무" | "T12 AiDecisionMaker가 시그널 + 컨텍스트 → 매매 결정" | **삭제** — v2엔 AI Decision Layer 자체 없음. 시그널 → 주문은 룰 평가 직접 |
| spec §3.5 / track2-relocation §3.3 "AI 의사결정에 세션 컨텍스트 주입" | "T12 prompt에 MarketSessionState 포함" | **AI 옵션 코멘트 prompt에 세션 컨텍스트 포함**으로 수정. AI는 매매 의사결정 X, 코멘트만 |
| spec §"비범위" | "AI Decision Layer는 별도 트랙" | **"AI는 W4 옵션 — 본 트랙에서 매매 흐름 영향 0"**으로 명시 |
| plan T20·T21·T22 (ADR 발행·dogfooding) | 1차안 ADR 0031~0035 정식 발행 | **v2 ADR 0032~0035 정식 발행** (master §10 매핑) |

본 수정은 W1 진입 직전(T2 R1 시작 전) architect가 1회 수정 + commit. 사용자 검토는 master v2 검토와 동시.

### D-3. master-architecture.md v2 §10 매핑

- 1차안 트랙2 ADR 0031 → v2 ADR 0032 (Market Session FSM)
- 1차안 트랙2 ADR 0032 → v2 ADR 0033 (Fallback 3-tier)
- 1차안 트랙2 ADR 0033 → v2 ADR 0034 (REJECTED Audit Trail)
- 1차안 트랙2 ADR 0034 → v2 ADR (Profile 매트릭스는 master §7로 흡수, 별도 ADR 폐기)
- 1차안 트랙2 ADR 0035 → v2 ADR 0035 (KIS HTTP 정밀화)

번호 1개씩 우측 시프트. master-architecture.md §10에 이미 반영.

---

## §5. 실행 순서 (메인 세션이 본 문서 보고 일괄 실행)

권장 순서:

1. **A-1 즉시 삭제 (3 코드 파일)** — `git rm`. 빌드 검증.
2. **A-2 1차안 산출물 삭제 (2 문서 파일)** — `git rm`. 다른 곳에 참조 없는지 grep 검증.
3. **B-1 archive 디렉토리 생성** — `mkdir -p _workspace/_archive/{retrospective,qa-issues,dogfooding,tasks-done,legacy-code}`.
4. **B-2 30개 파일·디렉토리 이동** — `git mv` 일괄. README.md 갱신.
5. **D-2 트랙2 spec/plan 본문 수정** — W1 진입 직전 architect가 별도 commit.

각 단계 사이 commit 분리 권장 — 사용자 검토·되돌리기 용이.

### §5-1. 삭제 vs archive 판단 기준

- **삭제 가능**: 사용처 0 + git history에만 남아도 충분 + 사용자 "싹 지울" 명시
- **archive**: 미래 회고·디버깅 가치 있음 + git history만으로 추적 어려움 + W5 종료 후 일괄 삭제 가능

본 문서는 보수적으로 분류 — 사용자 결정 시 archive 일부를 삭제로 전환 가능.

---

## §6. 사용자 결정 필요 사항

본 청소 계획 검토 시 사용자가 확정:

1. **archive 신설 OK?** 또는 즉시 삭제 일괄 (release/v0 외 다 삭제) 전환?
2. **release/v1 처리** (ADR 0028) — 보존 vs 삭제? 사용자 "release말곤 싹 지울" 인용 정합 시 release/v0만 보존?
3. **트랙2 spec/plan 살림 OK?** v2 비전 정정 후에도 트랙2 본체는 그대로 진입 가능한가?
4. **본 청소 실행 시점** — master/tracks v2 승인 직후 일괄? W1 진입 시점?
5. **`_workspace/_legacy/` 거대 — 19 디렉토리** archive 이동 vs 즉시 삭제 (Phase D-P1 백업 한정 가치 vs git history)?

---

## §7. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-03 | 초안 — 삭제 5건 / archive 30건 / 보존 다수 / 트랙2 재활용 1건 (architect, opus 4.7 1M) |
